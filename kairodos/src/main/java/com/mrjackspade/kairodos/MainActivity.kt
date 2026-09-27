package com.mrjackspade.kairodos

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import com.mrjackspade.kairo.frontend.PixelTextView
import com.mrjackspade.kairo.frontend.Ui

/** Development shell used to verify that this app consumes the pinned Kairo frontend. */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            setPadding(Ui.dp(this@MainActivity, 24), Ui.dp(this@MainActivity, 32),
                Ui.dp(this@MainActivity, 24), Ui.dp(this@MainActivity, 24))
        }
        content.addView(PixelTextView(this).apply {
            text = "KAIRODOS"
            color = Ui.ACCENT
            scale = 2
        })
        content.addView(Ui.sectionLabel(this, "DOS app prototype"))
        content.addView(Ui.text(this,
            "The shared Kairo frontend is loaded. DOSBox Pure integration and the game library are in progress.",
            Ui.BODY, Ui.TEXT_BODY).apply { gravity = Gravity.START })
        content.addView(Ui.text(this,
            "This development build does not run DOS games yet.",
            Ui.SECONDARY, Ui.TEXT_MUTED).apply {
            setPadding(0, Ui.dp(this@MainActivity, 20), 0, 0)
        })
        setContentView(ScrollView(this).apply { addView(content) })
    }
}
