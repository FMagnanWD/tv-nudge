package io.github.tvnudge.tv

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

// La bulle affichée par-dessus ce qui passe à l'écran. Elle prend la télécommande tant qu'elle est là :
// OK la ferme (« vu »), Retour aussi (« fermé ») ; sinon elle disparaît seule au bout de sa durée (« expiré »).
class Bulle(private val contexte: Context) {

    private val fenetres = contexte.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val minuteur = Handler(Looper.getMainLooper())
    private var vue: LinearLayout? = null
    private var idCourant = 0

    fun montrer(id: Int, titre: String, texte: String, dureeSecondes: Int, son: Boolean) {
        fermer("remplacé")
        idCourant = id

        val densite = contexte.resources.displayMetrics.density
        fun dp(v: Int) = (v * densite).toInt()

        val cadre = object : LinearLayout(contexte) {
            override fun dispatchKeyEvent(e: KeyEvent): Boolean {
                if (e.action == KeyEvent.ACTION_UP) when (e.keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A -> fermer("vu")
                    KeyEvent.KEYCODE_BACK -> fermer("fermé")
                }
                return true
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32), dp(24), dp(32), dp(24))
            background = GradientDrawable().apply {
                setColor(Color.argb(235, 20, 28, 45))
                cornerRadius = dp(18).toFloat()
                setStroke(dp(3), Color.rgb(255, 196, 0))
            }
            isFocusable = true
            isFocusableInTouchMode = true
        }

        cadre.addView(TextView(contexte).apply {
            text = titre
            setTextColor(Color.rgb(255, 196, 0))
            textSize = 26f
            paint.isFakeBoldText = true
        })
        cadre.addView(TextView(contexte).apply {
            text = texte
            setTextColor(Color.WHITE)
            textSize = 34f
            setPadding(0, dp(10), 0, dp(14))
        })
        cadre.addView(TextView(contexte).apply {
            text = "Appuie sur OK pour dire que tu as vu"
            setTextColor(Color.argb(170, 255, 255, 255))
            textSize = 16f
        })

        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                   else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val largeur = (contexte.resources.displayMetrics.widthPixels * 0.6).toInt()
        val parametres = WindowManager.LayoutParams(
            largeur, WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(40)
        }

        try {
            fenetres.addView(cadre, parametres)
        } catch (e: Exception) {
            // Sans la permission d'affichage par-dessus les autres apps (adb shell appops set … SYSTEM_ALERT_WINDOW allow).
            ServiceMessages.etats[id] = "non affiché : ${e.message}"
            return
        }
        vue = cadre
        cadre.requestFocus()
        if (son) bip()
        minuteur.postDelayed({ if (idCourant == id) fermer("expiré") }, dureeSecondes * 1000L)
    }

    private fun fermer(etat: String) {
        val v = vue ?: return
        vue = null
        minuteur.removeCallbacksAndMessages(null)
        try { fenetres.removeView(v) } catch (_: Exception) {}
        ServiceMessages.etats[idCourant] = etat
    }

    private fun bip() {
        try {
            val t = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
            t.startTone(ToneGenerator.TONE_PROP_BEEP2, 400)
            minuteur.postDelayed({ t.release() }, 1000)
        } catch (_: Exception) {}
    }
}
