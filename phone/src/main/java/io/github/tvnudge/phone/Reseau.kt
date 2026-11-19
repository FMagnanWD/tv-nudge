package io.github.tvnudge.phone

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread

// Échanges avec le service TV Nudge du Fire TV (port 7979).
object FireTv {

    private val principal = Handler(Looper.getMainLooper())
    private val icones = HashMap<String, Bitmap?>()
    private val iconesEnAttente = HashMap<String, MutableList<ImageView>>()

    fun base(adresse: String) = "http://" + adresse.trim() + ":7979"

    // GET, ou POST si corps est donné. À appeler hors du fil principal.
    fun requete(adresse: String, corps: JSONObject?, delaiMs: Int = 4000): JSONObject {
        val c = URL(adresse).openConnection() as HttpURLConnection
        c.connectTimeout = 4000
        c.readTimeout = delaiMs
        if (corps != null) {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(corps.toString().toByteArray()) }
        }
        return c.inputStream.use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }.also { c.disconnect() }
    }

    // Icône d'une app, servie par le Fire TV ; une seule demande par app, même si plusieurs vues l'attendent.
    // À appeler sur le fil principal.
    fun icone(base: String, paquet: String, vue: ImageView) {
        vue.tag = paquet
        if (icones.containsKey(paquet)) { vue.setImageBitmap(icones[paquet]); return }
        vue.setImageDrawable(null)
        val attente = iconesEnAttente.getOrPut(paquet) { mutableListOf() }
        attente.add(vue)
        if (attente.size > 1) return
        thread {
            val image = try {
                URL("$base/icone?paquet=" + URLEncoder.encode(paquet, "UTF-8")).openStream().use { BitmapFactory.decodeStream(it) }
            } catch (_: Exception) { null }
            principal.post {
                icones[paquet] = image
                iconesEnAttente.remove(paquet)?.forEach { if (it.tag == paquet) it.setImageBitmap(image) }
            }
        }
    }
}
