package io.github.tvnudge.tv

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

// Ce qui se passe sur le Fire TV, pour l'app du téléphone : écran allumé ou en veille, app à l'écran,
// lecture en cours et temps passé dans chaque app aujourd'hui.
// Fire OS n'a pas d'écran pour les deux autorisations nécessaires : elles se donnent par adb (voir le README).
object Statut {

    private const val LANCEUR = "com.amazon.tv.launcher"
    // Noms plus parlants que ceux d'Amazon (l'écran du code PIN s'appelle « Arcus Android Client »).
    private val NOMS = mapOf(LANCEUR to "Menu Fire TV", "com.amazon.firebat" to "Prime Video",
        "com.amazon.tv.parentalcontrols" to "Code PIN (contrôle parental)", "com.amazon.tv.settings.v2" to "Paramètres",
        "com.amazon.tv.notificationcenter" to "Notifications")
    private val icones = ConcurrentHashMap<String, ByteArray>()

    fun lire(c: Context): JSONObject {
        val maintenant = System.currentTimeMillis()
        val allume = (c.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        val json = JSONObject().put("heure", maintenant).put("allume", allume)

        val usage = usageAutorise(c)
        if (usage) ecran(c, maintenant, allume, json)
        val lecture = lecture(c)
        if (lecture != null && lecture.length() > 0) json.put("lecture", lecture)
        json.put("autorisations", JSONObject().put("usage", usage).put("lecture", lecture != null))
        return json
    }

    fun nom(c: Context, paquet: String): String = NOMS[paquet] ?: try {
        c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(paquet, 0)).toString()
    } catch (_: Exception) { paquet }

    fun icone(c: Context, paquet: String): ByteArray? = icones[paquet] ?: try {
        val dessin = c.packageManager.getApplicationIcon(paquet)
        val image = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        dessin.setBounds(0, 0, 96, 96)
        dessin.draw(Canvas(image))
        ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            .also { icones[paquet] = it }
    } catch (_: Exception) { null }

    // App à l'écran (et depuis quand) et temps passé par app depuis minuit, d'après les séances du jour.
    private fun ecran(c: Context, maintenant: Long, allume: Boolean, json: JSONObject) {
        val minuit = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val seances = Seances.lire(c, minuit, maintenant, allume)
        val app = if (allume) seances.lastOrNull() else null
        if (app != null) json.put("app", JSONObject().put("paquet", app.paquet).put("nom", nom(c, app.paquet)).put("depuis", app.debut))

        val aujourdhui = JSONArray()
        seances.filter { it.paquet !in Historique.IGNOREES && it.paquet != Historique.PIN }
            .groupBy { it.paquet }.mapValues { (_, s) -> s.sumOf { it.duree } }
            .filterValues { it >= 60_000L }
            .entries.sortedByDescending { it.value }
            .forEach { aujourdhui.put(JSONObject().put("paquet", it.key).put("nom", nom(c, it.key)).put("duree", it.value)) }
        json.put("aujourdhui", aujourdhui)
    }

    // La lecture en cours, d'après les sessions média : vide s'il n'y en a pas, null sans l'autorisation.
    // Netflix ne donne que l'état et la position, sans titre ni durée.
    fun lecture(c: Context): JSONObject? {
        val sessions = try {
            (c.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager)
                .getActiveSessions(ComponentName(c, EcouteLecture::class.java))
        } catch (_: SecurityException) { return null }
        val enCours = listOf(PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_PAUSED)
        val session = enCours.firstNotNullOfOrNull { etat -> sessions.firstOrNull { it.playbackState?.state == etat } }
            ?: return JSONObject()
        val etat = session.playbackState!!
        val position = if (etat.state == PlaybackState.STATE_PLAYING)
            etat.position + ((SystemClock.elapsedRealtime() - etat.lastPositionUpdateTime) * etat.playbackSpeed).toLong()
        else etat.position
        val r = JSONObject()
            .put("paquet", session.packageName)
            .put("nom", nom(c, session.packageName))
            .put("etat", when (etat.state) { PlaybackState.STATE_PLAYING -> "lecture"; PlaybackState.STATE_PAUSED -> "pause"; else -> "chargement" })
            .put("position", maxOf(0L, position))
        session.metadata?.let { m ->
            val duree = m.getLong(MediaMetadata.METADATA_KEY_DURATION)
            if (duree > 0) r.put("duree", duree)
            (m.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: m.getString(MediaMetadata.METADATA_KEY_TITLE))
                ?.let { r.put("titre", it) }
            (m.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: m.getString(MediaMetadata.METADATA_KEY_ARTIST))
                ?.let { r.put("sousTitre", it) }
        }
        return r
    }

    private fun usageAutorise(c: Context) = (c.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager)
        .checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName) == AppOpsManager.MODE_ALLOWED
}

// Ne fait rien : Android n'ouvre l'accès aux sessions média qu'aux écouteurs de notifications autorisés.
class EcouteLecture : NotificationListenerService()
