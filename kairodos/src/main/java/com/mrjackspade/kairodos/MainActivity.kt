package com.mrjackspade.kairodos

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.mrjackspade.kairo.frontend.GuestKeyboardPanel
import com.mrjackspade.kairo.frontend.InputRouter
import com.mrjackspade.kairo.frontend.PixelTextView
import com.mrjackspade.kairo.frontend.TouchInputSettingsDialog
import com.mrjackspade.kairo.frontend.Ui

/** Development shell used to verify that this app consumes the pinned Kairo frontend. */
class MainActivity : Activity() {
    private val keyboardInput = InputRouter({ _, _ -> }, 341)

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
        val preferences = getSharedPreferences("development_settings", MODE_PRIVATE)
        val modeNames = listOf("Auto", "Keyboard", "Mouse")
        val modeValue = { modeNames[preferences.getInt("touch_mode", 0).coerceIn(0, 2)] +
            if (preferences.getBoolean("direct_touch", false)) " · direct tap" else " · touchpad" }
        lateinit var touchRow: Ui.ActionRow
        touchRow = Ui.actionRow(this, "Touch input preview", modeValue) {
            val selected = preferences.getInt("touch_mode", 0).coerceIn(0, 2)
            val dialog = TouchInputSettingsDialog.builder(this, TouchInputSettingsDialog.Options(
                title = "Touch input preview",
                modeLabels = listOf("Auto", "Keyboard", "Mouse"),
                modeIndex = selected,
                directTouch = preferences.getBoolean("direct_touch", false),
                directTouchExplanation = "These development settings are stored locally. " +
                    "They do not control a DOS emulator yet.",
                onSave = { mode, direct, _ ->
                    preferences.edit().putInt("touch_mode", mode)
                        .putBoolean("direct_touch", direct).apply()
                    touchRow.detail.text = modeValue()
                }
            )).create()
            dialog.show()
            Ui.styleDialog(dialog)
        }
        content.addView(touchRow.view)
        lateinit var keyboard: GuestKeyboardPanel
        keyboard = GuestKeyboardPanel(this, keyboardInput, DosKeyboardLayout.value,
            { keyboard.close() })
        content.addView(Ui.secondaryButton(this, "Preview shared keyboard") {
            keyboard.visibility = View.VISIBLE
        }, LinearLayout.LayoutParams(-1, Ui.dp(this, 48)).apply {
            topMargin = Ui.dp(this@MainActivity, 24)
        })
        val root = FrameLayout(this)
        root.addView(ScrollView(this).apply { addView(content) })
        root.addView(keyboard, FrameLayout.LayoutParams(-1, Ui.dp(this, 260), Gravity.BOTTOM))
        setContentView(root)
    }

    override fun onStop() {
        keyboardInput.releaseAll()
        super.onStop()
    }
}
