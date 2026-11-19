package io.github.tvnudge.phone

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Couleurs et petits éléments d'interface de l'app, toujours en thème sombre (aux couleurs de l'icône).
object Couleurs {
    val FOND = Color.rgb(14, 23, 38)
    val CARTE = Color.rgb(23, 35, 58)
    val CHAMP = Color.rgb(31, 46, 74)
    val TEXTE = Color.rgb(242, 245, 250)
    val DOUX = Color.rgb(138, 155, 184)
    val JAUNE = Color.rgb(255, 196, 0)
    val BLEU_NUIT = Color.rgb(31, 58, 95)
    val VERT = Color.rgb(76, 208, 138)
    val ROUGE = Color.rgb(255, 107, 107)

    fun voile(couleur: Int, alpha: Int) = Color.argb(alpha, Color.red(couleur), Color.green(couleur), Color.blue(couleur))
}

fun arrondi(couleur: Int, rayon: Float) = GradientDrawable().apply { setColor(couleur); cornerRadius = rayon }

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

fun Context.texte(t: String, taille: Float, couleur: Int, gras: Boolean = false) = TextView(this).apply {
    text = t
    textSize = taille
    setTextColor(couleur)
    if (gras) typeface = Typeface.DEFAULT_BOLD
}

// Étiquette arrondie sur fond teinté (états, compteurs).
fun Context.badge(t: String, couleur: Int) = texte(t, 12f, couleur, gras = true).apply {
    setPadding(dp(10), dp(4), dp(10), dp(4))
    background = arrondi(Couleurs.voile(couleur, 40), dp(12).toFloat())
}

fun Context.carte(titre: String?) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(18), dp(16), dp(18), dp(18))
    background = arrondi(Couleurs.CARTE, dp(20).toFloat())
    layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(14) }
    if (titre != null) addView(texte(titre, 12f, Couleurs.DOUX, gras = true).apply { letterSpacing = 0.08f })
}

fun Context.champ(indice: String, taille: Float) = EditText(this).apply {
    hint = indice
    textSize = taille
    setTextColor(Couleurs.TEXTE)
    setHintTextColor(Couleurs.DOUX)
    background = arrondi(Couleurs.CHAMP, dp(12).toFloat())
    setPadding(dp(14), dp(12), dp(14), dp(12))
}

// Vignette arrondie pour l'icône d'une app.
fun Context.image(taille: Int) = ImageView(this).apply {
    background = arrondi(Couleurs.CHAMP, dp(taille / 4).toFloat())
    clipToOutline = true
    scaleType = ImageView.ScaleType.CENTER_CROP
}

// Barre de progression arrondie.
class Barre(c: Context, private val couleur: Int) : View(c) {
    var part = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    private val pinceau = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val r = height / 2f
        pinceau.color = Couleurs.CHAMP
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, pinceau)
        if (part <= 0f) return
        pinceau.color = couleur
        canvas.drawRoundRect(0f, 0f, maxOf(height.toFloat(), width * part), height.toFloat(), r, r, pinceau)
    }
}

object Format {
    fun heure(ms: Long): String = SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(ms))
    fun heureSecondes(ms: Long): String = SimpleDateFormat("HH:mm:ss", Locale.FRANCE).format(Date(ms))

    // 1h05, compact pour les graphiques
    fun dureeCourte(ms: Long): String {
        val minutes = ms / 60_000
        return if (minutes < 60) "${minutes}min" else "${minutes / 60}h%02d".format(minutes % 60)
    }

    // 45 min, 1 h 05
    fun duree(ms: Long): String {
        val minutes = ms / 60_000
        return when {
            minutes < 1 -> "< 1 min"
            minutes < 60 -> "$minutes min"
            else -> "${minutes / 60} h %02d".format(minutes % 60)
        }
    }

    // 19:30, 1:02:15
    fun chrono(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }
}

// Histogramme du temps d'écran par jour : une barre par jour, la valeur au-dessus, le jour en dessous.
class Histogramme(c: Context) : View(c) {
    class Colonne(val jour: String, val valeur: Long, val libelle: String, val enAvant: Boolean)

    var colonnes: List<Colonne> = emptyList()
        set(v) { field = v; invalidate() }
    private val pinceau = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ecriture = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    override fun onDraw(canvas: Canvas) {
        if (colonnes.isEmpty()) return
        val d = resources.displayMetrics.density
        val hautTexte = 18 * d
        val basTexte = 22 * d
        val hauteurMax = height - hautTexte - basTexte
        val largeur = width.toFloat() / colonnes.size
        val max = colonnes.maxOf { it.valeur }.coerceAtLeast(1L)
        colonnes.forEachIndexed { i, col ->
            val centre = largeur * (i + 0.5f)
            val demi = minOf(largeur * 0.3f, 16 * d)
            val haut = hautTexte + hauteurMax * (1f - col.valeur.toFloat() / max)
            val bas = hautTexte + hauteurMax
            pinceau.color = Couleurs.CHAMP
            canvas.drawRoundRect(centre - demi, hautTexte, centre + demi, bas, 6 * d, 6 * d, pinceau)
            if (col.valeur > 0) {
                pinceau.color = if (col.enAvant) Couleurs.JAUNE else Couleurs.voile(Couleurs.JAUNE, 150)
                canvas.drawRoundRect(centre - demi, minOf(haut, bas - 4 * d), centre + demi, bas, 6 * d, 6 * d, pinceau)
            }
            ecriture.textSize = 11 * d
            ecriture.color = Couleurs.TEXTE
            if (col.valeur > 0) canvas.drawText(col.libelle, centre, minOf(haut, bas - 4 * d) - 5 * d, ecriture)
            ecriture.textSize = 12 * d
            ecriture.color = if (col.enAvant) Couleurs.TEXTE else Couleurs.DOUX
            canvas.drawText(col.jour, centre, height - 5 * d, ecriture)
        }
    }
}
