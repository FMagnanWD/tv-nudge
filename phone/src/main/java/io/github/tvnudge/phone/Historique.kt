package io.github.tvnudge.phone

import android.app.Activity
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import io.github.tvnudge.phone.Couleurs.CHAMP
import io.github.tvnudge.phone.Couleurs.DOUX
import io.github.tvnudge.phone.Couleurs.FOND
import io.github.tvnudge.phone.Couleurs.JAUNE
import io.github.tvnudge.phone.Couleurs.ROUGE
import io.github.tvnudge.phone.Couleurs.TEXTE
import io.github.tvnudge.phone.Couleurs.VERT
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.concurrent.thread

// Historique jour par jour, gardé par le Fire TV : temps d'écran, apps ouvertes, ce qui a été lu,
// demandes de code PIN (contrôle parental) et messages envoyés.
class Historique : Activity() {

    companion object {
        const val ADRESSE = "adresse"
        private const val JOURS = 30
    }

    private lateinit var base: String
    private lateinit var colonne: LinearLayout
    private val formatJour = SimpleDateFormat("yyyy-MM-dd", Locale.FRANCE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = FOND
        window.navigationBarColor = FOND
        base = FireTv.base(intent.getStringExtra(ADRESSE) ?: "")

        colonne = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(28))
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(FOND); addView(colonne) })
        charger()
    }

    private fun charger() {
        colonne.removeAllViews()
        colonne.addView(entete())
        colonne.addView(texte("Chargement…", 15f, DOUX).apply { setPadding(dp(4), dp(20), 0, 0) })
        thread {
            val jours = try { FireTv.requete("$base/historique?jours=$JOURS", null, 20000).getJSONArray("jours") } catch (_: Exception) { null }
            runOnUiThread { afficher(jours) }
        }
    }

    private fun entete() = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(texte("‹", 34f, TEXTE).apply {
            setPadding(dp(4), 0, dp(14), dp(4))
            setOnClickListener { finish() }
        })
        addView(texte("Historique", 26f, TEXTE, gras = true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(texte("↻", 24f, DOUX).apply {
            setPadding(dp(14), 0, dp(4), 0)
            setOnClickListener { charger() }
        })
    }

    private fun afficher(jours: JSONArray?) {
        colonne.removeAllViews()
        colonne.addView(entete())
        if (jours == null) {
            colonne.addView(carte(null).apply {
                addView(texte("Fire TV injoignable", 18f, TEXTE, gras = true))
                addView(texte("L'historique est gardé sur le Fire TV : il faut qu'il soit allumé et sur le Wi-Fi de la maison.", 14f, DOUX))
            })
            return
        }
        val liste = (0 until jours.length()).map { jours.getJSONObject(it) }
        colonne.addView(carteSemaine(liste))
        liste.forEach { colonne.addView(carteJour(it)) }
        val premier = liste.lastOrNull()?.optString("jour")
        if (premier != null && liste.size < JOURS) colonne.addView(texte(
            "Rien avant le ${nomDuJour(premier, court = true)} : Android n'en gardait pas plus. L'historique se complète désormais chaque jour.",
            12f, DOUX).apply { gravity = Gravity.CENTER; setPadding(dp(12), dp(18), dp(12), 0) })
    }

    /* ---------- Les 7 derniers jours ---------- */

    private fun carteSemaine(jours: List<JSONObject>) = carte("7 DERNIERS JOURS").apply {
        val parJour = jours.associate { it.optString("jour") to tempsEcran(it) }
        val initiales = SimpleDateFormat("EEE", Locale.FRANCE)
        val colonnes = (6 downTo 0).map { i ->
            val c = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -i) }
            val valeur = parJour[formatJour.format(c.time)] ?: 0L
            Histogramme.Colonne(initiales.format(c.time).take(3).replaceFirstChar { it.uppercase() }, valeur,
                Format.dureeCourte(valeur), enAvant = i == 0)
        }
        addView(Histogramme(context).apply { this.colonnes = colonnes },
            LinearLayout.LayoutParams(MATCH_PARENT, dp(170)).apply { topMargin = dp(12) })
        val avecEcran = colonnes.filter { it.valeur > 0 }
        if (avecEcran.isNotEmpty()) addView(texte(
            "Moyenne : ${Format.duree(avecEcran.sumOf { it.valeur } / avecEcran.size)} par jour · total ${Format.duree(colonnes.sumOf { it.valeur })}",
            14f, DOUX).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) })
    }

    /* ---------- Un jour ---------- */

    private fun carteJour(jour: JSONObject) = carte(null).apply {
        val seances = tableau(jour, "seances")
        val apps = seances.filter { !it.optBoolean("pin") }
        val pins = seances.filter { it.optBoolean("pin") }
        val lectures = tableau(jour, "lectures")
        val messages = tableau(jour, "messages")

        addView(texte(nomDuJour(jour.optString("jour")), 19f, TEXTE, gras = true))
        val resume = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        resume.addView(texte(if (apps.isEmpty()) "Pas d'écran" else "${Format.duree(tempsEcran(jour))} d'écran", 15f, DOUX),
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        if (pins.isNotEmpty()) resume.addView(badge("🔒 ${pins.size} code PIN", JAUNE))
        if (messages.isNotEmpty()) resume.addView(badge("✉ ${messages.size}", VERT),
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(6) })
        addView(resume, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(2) })

        // Apps, de la plus regardée à la moins regardée.
        val parApp = apps.groupBy { it.optString("paquet") }
        val enLecture = lectures.groupBy { it.optString("paquet") }.mapValues { (_, l) -> l.sumOf { it.optLong("duree") } }
        val max = parApp.values.maxOfOrNull { s -> s.sumOf { it.optLong("duree") } } ?: 1L
        parApp.entries.sortedByDescending { (_, s) -> s.sumOf { it.optLong("duree") } }.forEach { (paquet, s) ->
            val duree = s.sumOf { it.optLong("duree") }
            val details = mutableListOf(if (s.size == 1) "1 ouverture" else "${s.size} ouvertures")
            enLecture[paquet]?.let { if (it >= 60_000L) details.add("${Format.duree(it)} en lecture") }
            addView(ligneApp(paquet, s[0].optString("nom"), duree, duree.toFloat() / max, details.joinToString(" · ")),
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })
        }

        // Titres lus, quand l'app les donne (Netflix ne le fait pas).
        val titres = lectures.filter { it.optString("titre").isNotEmpty() }
        if (titres.isNotEmpty()) {
            addView(texte("REGARDÉ", 12f, DOUX, gras = true).apply { letterSpacing = 0.08f },
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(16) })
            titres.forEach {
                addView(texte("${it.optString("titre")} · ${it.optString("nom")} · ${Format.duree(it.optLong("duree"))}", 14f, TEXTE),
                    LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })
            }
        }

        if (seances.isEmpty() && messages.isEmpty()) return@apply
        val detail = chronologie(seances, messages).apply { visibility = View.GONE }
        val bouton = texte("Voir la journée  ▾", 14f, JAUNE, gras = true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(10))
            background = arrondi(CHAMP, dp(12).toFloat())
        }
        bouton.setOnClickListener {
            val ouvert = detail.visibility == View.VISIBLE
            detail.visibility = if (ouvert) View.GONE else View.VISIBLE
            bouton.text = if (ouvert) "Voir la journée  ▾" else "Masquer la journée  ▴"
        }
        addView(bouton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(16) })
        addView(detail)
    }

    private fun ligneApp(paquet: String, nom: String, duree: Long, part: Float, details: String) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        val vignette = image(36)
        FireTv.icone(base, paquet, vignette)
        addView(vignette, LinearLayout.LayoutParams(dp(36), dp(36)))
        val milieu = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(12), 0) }
        milieu.addView(texte(nom, 15f, TEXTE).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END })
        milieu.addView(Barre(context, JAUNE).apply { this.part = part }, LinearLayout.LayoutParams(MATCH_PARENT, dp(5)).apply { topMargin = dp(5) })
        milieu.addView(texte(details, 12f, DOUX), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) })
        addView(milieu, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(texte(Format.duree(duree), 14f, DOUX).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(dp(72), WRAP_CONTENT))
    }

    // Tout ce qui s'est passé dans la journée, dans l'ordre : apps, codes PIN, messages.
    private fun chronologie(seances: List<JSONObject>, messages: List<JSONObject>) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val evenements = (seances.map { it.optLong("debut") to it } + messages.map { it.optLong("heure") to it }).sortedBy { it.first }
        evenements.forEach { (heure, e) ->
            val ligne = when {
                e.has("texte") -> ligneMessage(heure, e)
                e.optBoolean("pin") -> lignePin(heure, e, seances)
                else -> ligneSeance(heure, e)
            }
            addView(ligne, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) })
        }
    }

    private fun ligneSeance(heure: Long, s: JSONObject) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(texte("${Format.heure(heure)}\n${Format.heure(s.optLong("fin"))}", 12f, DOUX), LinearLayout.LayoutParams(dp(50), WRAP_CONTENT))
        val vignette = image(28)
        FireTv.icone(base, s.optString("paquet"), vignette)
        addView(vignette, LinearLayout.LayoutParams(dp(28), dp(28)))
        addView(texte(s.optString("nom"), 15f, TEXTE).apply { setPadding(dp(10), 0, dp(8), 0); setSingleLine(); ellipsize = TextUtils.TruncateAt.END },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(texte(Format.duree(s.optLong("duree")), 13f, DOUX))
    }

    // Le Fire TV ne dit pas si le code était bon : on montre ce qui s'est ouvert juste après.
    private fun lignePin(heure: Long, pin: JSONObject, seances: List<JSONObject>) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = arrondi(Couleurs.voile(JAUNE, 30), dp(12).toFloat())
        addView(texte(Format.heure(heure), 12f, JAUNE), LinearLayout.LayoutParams(dp(40), WRAP_CONTENT))
        val suite = seances.firstOrNull { !it.optBoolean("pin") && it.optLong("debut") >= pin.optLong("fin") && it.optLong("debut") - pin.optLong("fin") < 5 * 60_000L }
        val textes = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        textes.addView(texte("🔒 Code PIN demandé · ${pin.optLong("duree") / 1000} s", 15f, JAUNE, gras = true))
        textes.addView(texte(if (suite != null) "Ensuite : ${suite.optString("nom")}" else "Ensuite : retour au menu", 13f, DOUX))
        addView(textes)
    }

    private fun ligneMessage(heure: Long, m: JSONObject) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(texte(Format.heure(heure), 12f, DOUX), LinearLayout.LayoutParams(dp(50), WRAP_CONTENT))
        addView(texte("✉  ${m.optString("texte")}", 15f, TEXTE).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(0, 0, dp(8), 0) },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val (libelle, couleur) = when (m.optString("etat")) {
            "vu" -> "Vu ✓" to VERT
            "expiré" -> "Pas vu" to ROUGE
            "affiché" -> "À l'écran" to JAUNE
            else -> m.optString("etat").replaceFirstChar { it.uppercase() } to DOUX
        }
        addView(badge(libelle, couleur))
    }

    /* ---------- Outils ---------- */

    private fun tableau(jour: JSONObject, cle: String): List<JSONObject> {
        val a = jour.optJSONArray(cle) ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it) }
    }

    private fun tempsEcran(jour: JSONObject) = tableau(jour, "seances").filter { !it.optBoolean("pin") }.sumOf { it.optLong("duree") }

    // « Aujourd'hui », « Hier », « Lundi 17 novembre » (ou « lundi 17 novembre » au fil d'une phrase).
    private fun nomDuJour(jour: String, court: Boolean = false): String {
        val date = formatJour.parse(jour) ?: return jour
        val aujourdhui = formatJour.format(Calendar.getInstance().time)
        val hier = formatJour.format(Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -1) }.time)
        val nom = when (jour) {
            aujourdhui -> "Aujourd'hui"
            hier -> "Hier"
            else -> SimpleDateFormat("EEEE d MMMM", Locale.FRANCE).format(date)
        }
        return if (court) nom.replaceFirstChar { it.lowercase() } else nom.replaceFirstChar { it.uppercase() }
    }
}
