package io.github.tvnudge.tv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

// Petit serveur HTTP sur le port 7979 :
//   POST /message   {"texte": "...", "titre": "...", "duree": 60, "son": true}  -> {"id": 3}
//   GET  /message?texte=...&titre=...                                          (même chose, pour un raccourci iOS simple)
//   GET  /etat?id=3  -> {"id": 3, "etat": "affiché" | "vu" | "fermé" | "expiré" | "remplacé" | "inconnu"}
//   GET  /statut     -> écran, app à l'écran, lecture, temps par app aujourd'hui (voir Statut) et derniers messages
//   GET  /historique?jours=14 -> les derniers jours (voir Historique) : séances, temps de lecture, messages
//   GET  /icone?paquet=com.netflix.ninja -> icône PNG de l'app
//   GET  /ping       -> {"ok": true}
class ServiceMessages : Service() {

    companion object {
        const val PORT = 7979
        private const val TAG = "MessageTele"
        val etats = ConcurrentHashMap<Int, String>()
        private val compteur = AtomicInteger(0)
        private const val GARDES = 50

        private class Envoi(val id: Int, val titre: String, val texte: String, val heure: Long)
        private val envois = ArrayDeque<Envoi>()   // les plus récents d'abord

        private fun json(e: Envoi) = JSONObject().put("id", e.id).put("titre", e.titre).put("texte", e.texte)
            .put("heure", e.heure).put("etat", etats[e.id] ?: "inconnu")

        fun derniersEnvois(n: Int) = synchronized(envois) { envois.take(n).map { json(it) } }
        fun envoisEntre(debut: Long, fin: Long) = synchronized(envois) { envois.filter { it.heure in debut until fin }.map { json(it) } }
    }

    private class Reponse(val code: Int, val type: String, val octets: ByteArray)
    private fun json(code: Int, o: JSONObject) = Reponse(code, "application/json; charset=utf-8", o.toString().toByteArray(Charsets.UTF_8))

    private val principal = Handler(Looper.getMainLooper())
    private var serveur: ServerSocket? = null
    private lateinit var bulle: Bulle

    override fun onCreate() {
        super.onCreate()
        bulle = Bulle(this)
        demarrerAuPremierPlan()
        Historique.demarrer(this)
        thread(name = "serveur") { ecouter() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        try { serveur?.close() } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // Au premier plan, Fire OS ne coupe pas le service quand la mémoire manque.
    private fun demarrerAuPremierPlan() {
        val constructeur = if (Build.VERSION.SDK_INT >= 26) {
            val canal = NotificationChannel("service", "Service", NotificationManager.IMPORTANCE_MIN)
            getSystemService(NotificationManager::class.java).createNotificationChannel(canal)
            Notification.Builder(this, "service")
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this).setPriority(Notification.PRIORITY_MIN)
        }
        val notification = constructeur
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Prêt à recevoir des messages")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        startForeground(1, notification)
    }

    private fun ecouter() {
        while (true) {
            try {
                ServerSocket(PORT).use { s ->
                    serveur = s
                    while (true) {
                        val client = s.accept()
                        thread { repondre(client) }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "serveur : ${e.message}")
                Thread.sleep(3000)
            }
        }
    }

    private fun repondre(client: Socket) {
        client.use { c ->
            c.soTimeout = 10000
            val r = try {
                traiter(c.getInputStream())
            } catch (e: Exception) {
                json(400, JSONObject().put("erreur", e.message ?: "requête invalide"))
            }
            val statut = if (r.code == 200) "200 OK" else if (r.code == 404) "404 Not Found" else "400 Bad Request"
            val entete = "HTTP/1.1 $statut\r\nContent-Type: ${r.type}\r\n" +
                "Content-Length: ${r.octets.size}\r\nConnection: close\r\n\r\n"
            c.getOutputStream().apply { write(entete.toByteArray(Charsets.ISO_8859_1)); write(r.octets); flush() }
        }
    }

    private fun traiter(entree: InputStream): Reponse {
        val premiere = lireLigne(entree).split(" ")
        if (premiere.size < 2) return json(400, JSONObject().put("erreur", "requête invalide"))
        val methode = premiere[0]
        val chemin = premiere[1].substringBefore("?")
        val requete = parametres(premiere[1].substringAfter("?", ""))

        var longueur = 0
        while (true) {
            val ligne = lireLigne(entree)
            if (ligne.isEmpty()) break
            if (ligne.lowercase().startsWith("content-length:")) longueur = ligne.substringAfter(":").trim().toInt()
        }
        val corps = String(lireOctets(entree, longueur), Charsets.UTF_8)

        return when (chemin) {
            "/ping" -> json(200, JSONObject().put("ok", true))
            "/etat" -> {
                val id = requete["id"]?.toIntOrNull() ?: -1
                json(200, JSONObject().put("id", id).put("etat", etats[id] ?: "inconnu"))
            }
            "/statut" -> json(200, Statut.lire(this).put("messages", JSONArray(derniersEnvois(10))))
            "/historique" -> {
                val jours = (requete["jours"]?.toIntOrNull() ?: 14).coerceIn(1, 365)
                json(200, JSONObject().put("jours", Historique.jours(this, jours)))
            }
            "/icone" -> Statut.icone(this, requete["paquet"] ?: "")?.let { Reponse(200, "image/png", it) }
                ?: json(404, JSONObject().put("erreur", "pas d'icône"))
            "/message" -> {
                val json = if (methode == "POST" && corps.isNotBlank()) JSONObject(corps) else JSONObject(requete as Map<*, *>)
                val texte = json.optString("texte", "").trim()
                if (texte.isEmpty()) return json(400, JSONObject().put("erreur", "texte manquant"))
                val titre = json.optString("titre", "Message").trim()
                val duree = json.optInt("duree", 60).coerceIn(5, 600)
                val son = json.optBoolean("son", true)
                val id = compteur.incrementAndGet()
                etats[id] = "affiché"
                synchronized(envois) {
                    envois.addFirst(Envoi(id, titre, texte, System.currentTimeMillis()))
                    while (envois.size > GARDES) envois.removeLast()
                }
                principal.post { bulle.montrer(id, titre, texte, duree, son) }
                json(200, JSONObject().put("id", id))
            }
            else -> json(404, JSONObject().put("erreur", "inconnu : $chemin"))
        }
    }

    private fun parametres(q: String): Map<String, String> =
        q.split("&").filter { it.contains("=") }.associate {
            URLDecoder.decode(it.substringBefore("="), "UTF-8") to URLDecoder.decode(it.substringAfter("="), "UTF-8")
        }

    private fun lireLigne(entree: InputStream): String {
        val b = StringBuilder()
        while (true) {
            val c = entree.read()
            if (c == -1 || c == '\n'.code) break
            if (c != '\r'.code) b.append(c.toChar())
            if (b.length > 8192) throw IllegalArgumentException("ligne trop longue")
        }
        return b.toString()
    }

    private fun lireOctets(entree: InputStream, n: Int): ByteArray {
        if (n > 65536) throw IllegalArgumentException("message trop long")
        val tampon = ByteArray(n)
        var lu = 0
        while (lu < n) {
            val r = entree.read(tampon, lu, n - lu)
            if (r == -1) break
            lu += r
        }
        return tampon.copyOf(lu)
    }
}
