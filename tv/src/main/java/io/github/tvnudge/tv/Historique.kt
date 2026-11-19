package io.github.tvnudge.tv

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.PowerManager
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.concurrent.thread

// Une séance : une app restée à l'écran, éventuellement en plusieurs morceaux rapprochés.
class Seance(val paquet: String, val debut: Long, var fin: Long, var duree: Long, var ouverte: Boolean)

// Séances reconstituées d'après le journal d'usage d'Android (premier plan / arrière-plan des activités).
object Seances {
    // Au-delà, une reprise de la même app compte comme une nouvelle séance (la veille met l'app en arrière-plan).
    private const val PAUSE_MAX_MS = 5 * 60_000L

    // Séances entre debut et fin. Une app encore à l'écran à fin compte jusqu'à fin si jusquAFin
    // (jour passé, ou écran allumé) ; sinon l'écran s'est éteint sans que le journal le dise, elle s'arrête là.
    fun lire(c: Context, debut: Long, fin: Long, jusquAFin: Boolean): List<Seance> {
        val journal = (c.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager)
            .queryEvents(debut - 12 * 3600_000L, fin)
        val seances = ArrayList<Seance>()
        var courante: Seance? = null
        var debutMorceau = 0L
        fun fermer(t: Long) {
            val s = courante ?: return
            val d = minOf(t, fin) - maxOf(debutMorceau, debut)
            if (d > 0) s.duree += d
            s.fin = maxOf(s.fin, minOf(t, fin))
            s.ouverte = false
            courante = null
        }

        val e = UsageEvents.Event()
        while (journal.hasNextEvent()) {
            journal.getNextEvent(e)
            when (e.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                    fermer(e.timeStamp)
                    val derniere = seances.lastOrNull()
                    courante = if (derniere != null && derniere.paquet == e.packageName && e.timeStamp - derniere.fin <= PAUSE_MAX_MS) derniere
                               else Seance(e.packageName, e.timeStamp, e.timeStamp, 0L, true).also { seances.add(it) }
                    courante!!.ouverte = true
                    debutMorceau = e.timeStamp
                }
                UsageEvents.Event.MOVE_TO_BACKGROUND -> if (courante?.paquet == e.packageName) fermer(e.timeStamp)
            }
        }
        courante?.let { s -> if (jusquAFin) { fermer(fin); s.ouverte = true } else fermer(debutMorceau) }
        return seances.filter { it.fin > debut || it.ouverte }
    }
}

// Historique jour par jour, gardé sur le Fire TV dans files/historique/AAAA-MM-JJ.json.
// Android ne garde son journal d'usage que quelques jours : toutes les 5 minutes, on recopie les séances du jour,
// avec ce que ce journal ne contient pas (temps de lecture et titres, messages envoyés).
object Historique {
    private const val TAG = "MessageTele"
    private const val ECHANTILLON_MS = 30_000L
    private const val COPIE_MS = 5 * 60_000L
    private const val JOURS_RATTRAPES = 10
    private const val SEANCE_MIN_MS = 5_000L   // en dessous : écrans de passage (lancement d'une app…)
    const val PIN = "com.amazon.tv.parentalcontrols"
    val IGNOREES = setOf("com.amazon.tv.launcher", "io.github.tvnudge.tv", "com.android.systemui")

    private lateinit var dossier: File
    private val formatJour = SimpleDateFormat("yyyy-MM-dd", Locale.FRANCE)
    // Temps de lecture du jour en cours, par app et titre (le titre est vide quand l'app ne le donne pas).
    private var jourLectures = ""
    private val lectures = LinkedHashMap<Pair<String, String>, Long>()

    fun demarrer(c: Context) {
        dossier = File(c.filesDir, "historique").apply { mkdirs() }
        thread(name = "historique", isDaemon = true) {
            synchronized(this) {
                chargerLectures(jour(System.currentTimeMillis()))
                for (i in JOURS_RATTRAPES downTo 1) {
                    val debut = debutDuJour(System.currentTimeMillis(), -i)
                    if (!fichier(jour(debut)).exists()) copier(c, debut)
                }
            }
            var depuisCopie = 0L
            while (true) {
                try {
                    Thread.sleep(ECHANTILLON_MS)
                    synchronized(this) {
                        echantillonner(c)
                        depuisCopie += ECHANTILLON_MS
                        if (depuisCopie >= COPIE_MS) { copier(c, debutDuJour(System.currentTimeMillis(), 0)); depuisCopie = 0 }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "historique : ${e.message}")
                }
            }
        }
    }

    // Les derniers jours, du plus récent au plus ancien, avec aujourd'hui à jour.
    @Synchronized
    fun jours(c: Context, nombre: Int): JSONArray {
        copier(c, debutDuJour(System.currentTimeMillis(), 0))
        val r = JSONArray()
        (dossier.listFiles() ?: emptyArray()).filter { it.name.endsWith(".json") }
            .sortedByDescending { it.name }.take(nombre)
            .forEach { f -> lire(f)?.let { r.put(it) } }
        return r
    }

    private fun echantillonner(c: Context) {
        val maintenant = System.currentTimeMillis()
        val aujourdhui = jour(maintenant)
        if (aujourdhui != jourLectures) {
            // Minuit est passé : on termine la veille avant de repartir de zéro.
            copier(c, debutDuJour(maintenant, -1))
            chargerLectures(aujourdhui)
        }
        if (!(c.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive) return
        val l = Statut.lecture(c) ?: return
        if (l.optString("etat") != "lecture") return
        val cle = l.optString("paquet") to l.optString("titre")
        lectures[cle] = (lectures[cle] ?: 0L) + ECHANTILLON_MS
    }

    // Écrit le fichier du jour qui commence à debut. Les séances déjà copiées sont gardées
    // quand Android a effacé son journal ; les messages sont fusionnés d'après leur heure d'envoi.
    private fun copier(c: Context, debut: Long) {
        val maintenant = System.currentTimeMillis()
        val nom = jour(debut)
        val fin = minOf(debutDuJour(debut, 1), maintenant)
        val aujourdhui = nom == jour(maintenant)
        val ancien = lire(fichier(nom))

        val jusquAFin = !aujourdhui || (c.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        val seances = JSONArray()
        Seances.lire(c, debut, fin, jusquAFin)
            .filter { it.paquet !in IGNOREES && (it.duree >= SEANCE_MIN_MS || it.paquet == PIN) }
            .forEach {
                seances.put(JSONObject().put("paquet", it.paquet).put("nom", Statut.nom(c, it.paquet))
                    .put("debut", maxOf(it.debut, debut)).put("fin", it.fin).put("duree", it.duree)
                    .put("pin", it.paquet == PIN))
            }

        val json = JSONObject().put("jour", nom)
        json.put("seances", if (seances.length() == 0 && ancien != null) ancien.optJSONArray("seances") ?: seances else seances)
        json.put("lectures", if (nom == jourLectures) lecturesJson(c) else ancien?.optJSONArray("lectures") ?: JSONArray())
        json.put("messages", fusionnerMessages(ancien?.optJSONArray("messages"), debut, debutDuJour(debut, 1)))
        if (json.getJSONArray("seances").length() == 0 && json.getJSONArray("messages").length() == 0 && ancien == null) return
        fichier(nom).writeText(json.toString())
    }

    private fun lecturesJson(c: Context) = JSONArray().also { a ->
        lectures.entries.sortedByDescending { it.value }.forEach { (cle, duree) ->
            a.put(JSONObject().put("paquet", cle.first).put("nom", Statut.nom(c, cle.first)).put("titre", cle.second).put("duree", duree))
        }
    }

    private fun chargerLectures(nom: String) {
        jourLectures = nom
        lectures.clear()
        val a = lire(fichier(nom))?.optJSONArray("lectures") ?: return
        for (i in 0 until a.length()) {
            val l = a.getJSONObject(i)
            lectures[l.optString("paquet") to l.optString("titre")] = l.optLong("duree")
        }
    }

    private fun fusionnerMessages(anciens: JSONArray?, debut: Long, fin: Long): JSONArray {
        val parHeure = sortedMapOf<Long, JSONObject>()
        if (anciens != null) for (i in 0 until anciens.length()) anciens.getJSONObject(i).let { parHeure[it.optLong("heure")] = it }
        ServiceMessages.envoisEntre(debut, fin).forEach { parHeure[it.optLong("heure")] = it }
        return JSONArray(parHeure.values)
    }

    private fun fichier(nom: String) = File(dossier, "$nom.json")
    private fun lire(f: File): JSONObject? = try { JSONObject(f.readText()) } catch (_: Exception) { null }
    private fun jour(ms: Long): String = formatJour.format(ms)

    // Minuit du jour de ms, décalé de decalage jours.
    private fun debutDuJour(ms: Long, decalage: Int): Long = Calendar.getInstance().apply {
        timeInMillis = ms
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_MONTH, decalage)
    }.timeInMillis
}
