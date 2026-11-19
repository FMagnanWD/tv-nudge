package io.github.tvnudge.phone

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.tvnudge.phone.Couleurs.CHAMP
import io.github.tvnudge.phone.Couleurs.DOUX
import io.github.tvnudge.phone.Couleurs.FOND
import io.github.tvnudge.phone.Couleurs.JAUNE
import io.github.tvnudge.phone.Couleurs.ROUGE
import io.github.tvnudge.phone.Couleurs.TEXTE
import io.github.tvnudge.phone.Couleurs.VERT
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

// Envoie un message à l'app TV Nudge du Fire TV et suit son état. Montre aussi ce qui se passe
// sur le Fire TV (écran, app à l'écran, lecture en cours, temps passé par app aujourd'hui, derniers messages),
// relu toutes les 2 secondes tant que l'app est à l'écran.
class Envoi : Activity() {

    private val messagesPrets = listOf("À table !", "Douche", "Éteins dans 5 minutes", "Au lit", "Viens me voir", "Range ta chambre")
    private val INTERVALLE = 2000L

    private lateinit var champTexte: EditText
    private lateinit var champTitre: EditText
    private lateinit var champAdresse: EditText
    private lateinit var etat: TextView

    private lateinit var pastille: TextView
    private lateinit var imageApp: ImageView
    private lateinit var nomApp: TextView
    private lateinit var detailApp: TextView
    private lateinit var blocLecture: LinearLayout
    private lateinit var etatLecture: TextView
    private lateinit var titreLecture: TextView
    private lateinit var barreLecture: Barre
    private lateinit var tempsLecture: TextView
    private lateinit var totalJour: TextView
    private lateinit var listeJour: LinearLayout
    private lateinit var listeMessages: LinearLayout
    private lateinit var miseAJour: TextView

    private var suivi = 0         // le message dont on suit l'état ; un nouvel envoi arrête le suivi du précédent
    private var tour = 0          // change à chaque retour sur l'app : une seule boucle d'interrogation à la fois
    private var derniereReponse = 0L

    private val principal = Handler(Looper.getMainLooper())
    private val reglages by lazy { getSharedPreferences("reglages", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = FOND
        window.navigationBarColor = FOND

        val colonne = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(28))
        }

        val entete = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        entete.addView(texte("TV Nudge", 26f, TEXTE, gras = true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        pastille = texte("Recherche…", 13f, DOUX, gras = true).apply { setPadding(dp(12), dp(6), dp(12), dp(6)) }
        entete.addView(pastille)
        colonne.addView(entete, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { leftMargin = dp(4) })

        colonne.addView(carteEcran())
        colonne.addView(carteEnvoi())
        colonne.addView(carteJour())
        colonne.addView(carteMessages())

        champAdresse = champ("Adresse du Fire TV", 14f).apply {
            setText(reglages.getString("adresse", ""))
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        colonne.addView(texte("ADRESSE DU FIRE TV", 12f, DOUX, gras = true).apply { letterSpacing = 0.08f },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(24); leftMargin = dp(4) })
        colonne.addView(champAdresse, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })
        miseAJour = texte("", 12f, DOUX).apply { gravity = Gravity.CENTER }
        colonne.addView(miseAJour, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(14) })

        setContentView(ScrollView(this).apply { setBackgroundColor(FOND); addView(colonne) })
    }

    override fun onResume() {
        super.onResume()
        interroger(++tour)
    }

    override fun onPause() {
        tour++
        super.onPause()
    }

    /* ---------- Construction de l'écran ---------- */

    private fun carteEcran() = carte(null).apply {
        val ligne = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        imageApp = image(56)
        ligne.addView(imageApp, LinearLayout.LayoutParams(dp(56), dp(56)))
        val textes = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0) }
        nomApp = texte("Fire TV", 22f, TEXTE, gras = true).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END }
        detailApp = texte("Connexion…", 14f, DOUX)
        textes.addView(nomApp)
        textes.addView(detailApp)
        ligne.addView(textes, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(ligne)

        blocLecture = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(14))
            background = arrondi(CHAMP, dp(14).toFloat())
            visibility = View.GONE
        }
        etatLecture = texte("", 15f, VERT, gras = true)
        titreLecture = texte("", 15f, TEXTE).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        barreLecture = Barre(context, JAUNE)
        tempsLecture = texte("", 13f, DOUX)
        blocLecture.addView(etatLecture)
        blocLecture.addView(titreLecture, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) })
        blocLecture.addView(barreLecture, LinearLayout.LayoutParams(MATCH_PARENT, dp(6)).apply { topMargin = dp(10) })
        blocLecture.addView(tempsLecture, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })
        addView(blocLecture, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(14) })
    }

    private fun carteEnvoi() = carte("ENVOYER UN MESSAGE").apply {
        champTexte = champ("Ton message", 18f).apply {
            minLines = 2
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        addView(champTexte, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) })

        champTitre = champ("Titre (Message)", 15f).apply {
            setText(reglages.getString("titre", "Message"))
            setSingleLine()
        }
        addView(champTitre, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })

        addView(texte("Envoyer", 17f, Couleurs.BLEU_NUIT, gras = true).apply {
            gravity = Gravity.CENTER
            background = arrondi(JAUNE, dp(14).toFloat())
            isClickable = true
            setOnClickListener { envoyer(champTexte.text.toString()) }
        }, LinearLayout.LayoutParams(MATCH_PARENT, dp(52)).apply { topMargin = dp(12) })

        etat = texte("", 15f, TEXTE).apply { gravity = Gravity.CENTER; visibility = View.GONE }
        addView(etat, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })

        addView(texte("Tout prêts", 13f, DOUX), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(16) })
        val grille = GridLayout(context).apply { columnCount = 2 }
        messagesPrets.forEach { m ->
            grille.addView(texte(m, 15f, TEXTE).apply {
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(12), dp(8), dp(12))
                background = arrondi(CHAMP, dp(12).toFloat())
                isClickable = true
                setOnClickListener { envoyer(m) }
            }, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply {
                width = 0
                setMargins(dp(3), dp(3), dp(3), dp(3))
            })
        }
        addView(grille, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })
    }

    private fun carteJour() = carte("AUJOURD'HUI").apply {
        totalJour = texte("", 15f, TEXTE)
        addView(totalJour, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })
        listeJour = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        addView(listeJour)
        addView(texte("Historique jour par jour  →", 15f, JAUNE, gras = true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
            background = arrondi(CHAMP, dp(12).toFloat())
            setOnClickListener {
                startActivity(Intent(this@Envoi, Historique::class.java).putExtra(Historique.ADRESSE, champAdresse.text.toString()))
            }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(16) })
    }

    private fun carteMessages() = carte("DERNIERS MESSAGES").apply {
        listeMessages = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        addView(listeMessages)
    }

    /* ---------- État du Fire TV ---------- */

    private fun interroger(t: Int) {
        if (t != tour) return
        val base = adresse()
        thread {
            val statut = try { FireTv.requete("$base/statut", null) } catch (_: Exception) { null }
            runOnUiThread {
                if (t != tour) return@runOnUiThread
                afficherStatut(statut)
                principal.postDelayed({ interroger(t) }, INTERVALLE)
            }
        }
    }

    private fun afficherStatut(s: JSONObject?) {
        if (s == null) {
            marquer(if (derniereReponse == 0L) "Recherche…" else "Hors ligne", if (derniereReponse == 0L) DOUX else ROUGE)
            if (champAdresse.text.isBlank()) montrerApp(null, "Adresse du Fire TV ?", "Saisis-la en bas de l'écran (Fire TV : Paramètres → Ma Fire TV → À propos → Réseau)")
            else montrerApp(null, "Fire TV injoignable", "Éteint au bouton, débranché ou pas sur le Wi-Fi de la maison")
            blocLecture.visibility = View.GONE
            miseAJour.text = if (derniereReponse > 0) "Dernière réponse à ${Format.heureSecondes(derniereReponse)}" else ""
            return
        }
        derniereReponse = System.currentTimeMillis()
        val heure = s.optLong("heure", derniereReponse)
        val autorisations = s.optJSONObject("autorisations") ?: JSONObject()
        val app = s.optJSONObject("app")

        when {
            !s.optBoolean("allume") -> {
                marquer("En veille", DOUX)
                montrerApp(null, "Écran en veille", "La télé est éteinte ou le Fire TV s'est endormi")
            }
            app != null -> {
                marquer("Allumé", VERT)
                val depuis = app.optLong("depuis")
                montrerApp(app.optString("paquet"), app.optString("nom"),
                    "À l'écran depuis ${Format.heure(depuis)} · ${Format.duree(heure - depuis)}")
            }
            else -> {
                marquer("Allumé", VERT)
                montrerApp(null, "Fire TV allumé",
                    if (autorisations.optBoolean("usage")) "" else "Autorisation « usage » à donner sur le Fire TV (README)")
            }
        }

        afficherLecture(if (s.optBoolean("allume")) s.optJSONObject("lecture") else null, app?.optString("paquet"))
        afficherJour(s.optJSONArray("aujourdhui"), autorisations.optBoolean("usage"))
        afficherMessages(s.optJSONArray("messages") ?: JSONArray())
        miseAJour.text = "Mis à jour à ${Format.heureSecondes(derniereReponse)}"
    }

    private fun marquer(t: String, couleur: Int) {
        pastille.text = "●  $t"
        pastille.setTextColor(couleur)
        pastille.background = arrondi(Couleurs.voile(couleur, 40), dp(20).toFloat())
    }

    private fun montrerApp(paquet: String?, nom: String, detail: String) {
        nomApp.text = nom
        detailApp.text = detail
        detailApp.visibility = if (detail.isEmpty()) View.GONE else View.VISIBLE
        imageApp.tag = paquet
        if (paquet == null) imageApp.setImageResource(R.drawable.icone) else FireTv.icone(adresse(), paquet, imageApp)
    }

    private fun afficherLecture(l: JSONObject?, paquetEcran: String?) {
        if (l == null) { blocLecture.visibility = View.GONE; return }
        blocLecture.visibility = View.VISIBLE
        val (libelle, couleur) = when (l.optString("etat")) {
            "lecture" -> "▶  En lecture" to VERT
            "pause" -> "❚❚  En pause" to JAUNE
            else -> "…  Chargement" to DOUX
        }
        etatLecture.text = if (l.optString("paquet") == paquetEcran) libelle else "$libelle · ${l.optString("nom")}"
        etatLecture.setTextColor(couleur)

        val titre = listOf(l.optString("titre"), l.optString("sousTitre")).filter { it.isNotEmpty() }.joinToString(" — ")
        titreLecture.text = titre.ifEmpty { "Titre non partagé par ${l.optString("nom")}" }
        titreLecture.setTextColor(if (titre.isEmpty()) DOUX else TEXTE)

        val position = l.optLong("position")
        val duree = l.optLong("duree")
        if (duree > 0) {
            barreLecture.visibility = View.VISIBLE
            barreLecture.part = position.toFloat() / duree
            tempsLecture.text = "${Format.chrono(position)} / ${Format.chrono(duree)} · fin dans ${Format.duree(duree - position)}"
        } else {
            barreLecture.visibility = View.GONE
            tempsLecture.text = "Position ${Format.chrono(position)} · durée non partagée"
        }
    }

    private fun afficherJour(jour: JSONArray?, autorise: Boolean) {
        listeJour.removeAllViews()
        if (!autorise || jour == null) {
            totalJour.text = "Autorisation « usage » à donner sur le Fire TV (README)"
            totalJour.setTextColor(DOUX)
            return
        }
        val apps = (0 until jour.length()).map { jour.getJSONObject(it) }
        val total = apps.sumOf { it.optLong("duree") }
        val max = apps.maxOfOrNull { it.optLong("duree") } ?: 1L
        totalJour.text = if (apps.isEmpty()) "Pas encore d'écran aujourd'hui" else "${Format.duree(total)} d'écran"
        totalJour.setTextColor(if (apps.isEmpty()) DOUX else TEXTE)
        apps.forEach { a ->
            val ligne = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            val vignette = image(32)
            FireTv.icone(adresse(), a.optString("paquet"), vignette)
            ligne.addView(vignette, LinearLayout.LayoutParams(dp(32), dp(32)))
            val milieu = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(12), 0) }
            milieu.addView(texte(a.optString("nom"), 15f, TEXTE).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END })
            milieu.addView(Barre(this, JAUNE).apply { part = a.optLong("duree").toFloat() / max },
                LinearLayout.LayoutParams(MATCH_PARENT, dp(5)).apply { topMargin = dp(5) })
            ligne.addView(milieu, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            ligne.addView(texte(Format.duree(a.optLong("duree")), 14f, DOUX).apply { gravity = Gravity.END },
                LinearLayout.LayoutParams(dp(64), WRAP_CONTENT))
            listeJour.addView(ligne, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })
        }
    }

    private fun afficherMessages(messages: JSONArray) {
        listeMessages.removeAllViews()
        if (messages.length() == 0) {
            listeMessages.addView(texte("Aucun message depuis le dernier redémarrage du Fire TV", 14f, DOUX),
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
            return
        }
        for (i in 0 until messages.length()) {
            val m = messages.getJSONObject(i)
            val ligne = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            ligne.addView(texte(Format.heure(m.optLong("heure")), 13f, DOUX), LinearLayout.LayoutParams(dp(48), WRAP_CONTENT))
            val textes = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, dp(10), 0) }
            textes.addView(texte(m.optString("texte"), 15f, TEXTE).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
            if (m.optString("titre") != "Message") textes.addView(texte(m.optString("titre"), 12f, DOUX))
            ligne.addView(textes, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            val (libelle, couleur) = libelleEtat(m.optString("etat"))
            ligne.addView(badge(libelle, couleur))
            listeMessages.addView(ligne, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })
        }
    }

    private fun libelleEtat(e: String) = when (e) {
        "affiché" -> "À l'écran" to JAUNE
        "vu" -> "Vu ✓" to VERT
        "fermé" -> "Fermé" to DOUX
        "expiré" -> "Pas vu" to ROUGE
        "remplacé" -> "Remplacé" to DOUX
        else -> "Erreur" to ROUGE
    }

    /* ---------- Envoi ---------- */

    private fun adresse() = FireTv.base(champAdresse.text.toString())

    private fun envoyer(texte: String) {
        if (texte.isBlank()) { afficher("Écris un message d'abord."); return }
        val titre = champTitre.text.toString().trim().ifEmpty { "Message" }
        val base = adresse()
        reglages.edit().putString("titre", titre).putString("adresse", champAdresse.text.toString().trim()).apply()
        afficher("Envoi…")

        thread {
            try {
                val id = FireTv.requete("$base/message", JSONObject().put("texte", texte.trim()).put("titre", titre)).getInt("id")
                suivi = id
                runOnUiThread { champTexte.setText(""); afficher("📺 Affiché sur la télé, en attente…") }
                while (suivi == id) {
                    Thread.sleep(2000)
                    val e = FireTv.requete("$base/etat?id=$id", null).getString("etat")
                    if (e == "affiché") continue
                    if (suivi == id) afficher(when (e) {
                        "vu" -> "✅ Vu !"
                        "fermé" -> "Fermé avec Retour"
                        "expiré" -> "⏱ Pas vu : le message a disparu au bout d'une minute"
                        "remplacé" -> "Remplacé par un autre message"
                        else -> e
                    })
                    break
                }
            } catch (e: Exception) {
                afficher("❌ Le Fire TV ne répond pas (éteint, ou pas sur le Wi-Fi de la maison ?)")
            }
        }
    }

    private fun afficher(m: String) = runOnUiThread { etat.text = m; etat.visibility = View.VISIBLE }
}
