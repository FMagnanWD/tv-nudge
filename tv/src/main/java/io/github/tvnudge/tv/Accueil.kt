package io.github.tvnudge.tv

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

// Écran de l'app (lancée une fois après l'installation) : démarre le service et dit qu'il est prêt.
class Accueil : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startService(Intent(this, ServiceMessages::class.java))
        setContentView(TextView(this).apply {
            text = "TV Nudge est prêt.\nLes messages s'afficheront par-dessus tes programmes."
            textSize = 28f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(31, 58, 95))
            gravity = Gravity.CENTER
        })
    }
}

// Relance le service au démarrage du Fire TV et après une mise à jour de l'app.
class Demarrage : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val i = Intent(context, ServiceMessages::class.java)
        if (android.os.Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
    }
}
