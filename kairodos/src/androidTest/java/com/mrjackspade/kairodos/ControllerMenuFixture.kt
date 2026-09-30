package com.mrjackspade.kairodos

import android.app.AlertDialog
import android.app.Instrumentation
import android.content.Intent
import android.view.KeyEvent
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.TextView
import com.mrjackspade.kairo.frontend.*

/** Exercises actual focus traversal and activation, not just binding serialization. */
internal object ControllerMenuFixture {
    fun verify(instrumentation: Instrumentation) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        lateinit var root: FrameLayout
        lateinit var editor: ControllerEditor<String>
        var bindings = DosControllerBindings.duke3d()
        var physical = PhysicalControllerBindings.defaults()
        fun ui(action: () -> Unit) { instrumentation.runOnMainSync { action() }; instrumentation.waitForIdleSync() }
        fun key(code: Int) = ui {
            editor.handleKey(KeyEvent(KeyEvent.ACTION_DOWN, code))
            editor.handleKey(KeyEvent(KeyEvent.ACTION_UP, code))
        }
        fun hat(value: Float, send: (MotionEvent) -> Unit) = ui {
            val properties = MotionEvent.PointerProperties().apply { id = 0 }
            val coords = MotionEvent.PointerCoords().apply { setAxisValue(MotionEvent.AXIS_HAT_Y, value) }
            MotionEvent.obtain(0, android.os.SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE,
                1, arrayOf(properties), arrayOf(coords), 0, 0, 1f, 1f, 0, 0,
                InputDevice.SOURCE_JOYSTICK, 0).also { send(it); it.recycle() }
        }
        fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
            (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
        fun select(label: String) {
            var target: View? = null
            ui { target = descendants(root).firstOrNull {
                it.isFocusable && (it.contentDescription?.toString()?.startsWith("$label.") == true ||
                    it is TextView && it.text.toString() == label)
            } }
            check(target != null) { "Missing menu control: $label" }
            repeat(180) {
                var reached = false
                ui { reached = target!!.hasFocus() }
                if (reached) { key(KeyEvent.KEYCODE_BUTTON_A); return }
                key(KeyEvent.KEYCODE_DPAD_DOWN)
            }
            error("D-pad could not reach $label; focus=${root.findFocus()?.contentDescription}; root=${root.width}x${root.height}; target=${target!!.width}x${target!!.height}")
        }
        try {
            for (spec in listOf(DosControllerBindings.spec,
                DosControllerBindings.spec.copy(name = "PC-98", keyCodes = listOf(1, 2, 3),
                    keyLabel = { "Key $it" }, modifierCodes = emptySet()))) {
                ui {
                    physical = PhysicalControllerBindings.defaults()
                    bindings = bindings.filterNot { it.input == "virtual:a" }
                    root = FrameLayout(activity)
                    activity.setContentView(root)
                    editor = ControllerEditor(activity, root, { bindings }, { _, value -> bindings = value }, {},
                        { physical }, { physical = it }, {}, { .35f }, {}, {}, {}, { false }, {},
                        { it }, spec, {})
                    editor.show(null)
                }
                key(KeyEvent.KEYCODE_DPAD_RIGHT)
                key(KeyEvent.KEYCODE_BUTTON_A)
                select("A")
                select("${spec.name} key or key combination")
                select(spec.keyLabel(spec.keyCodes.first()))
                select("Save 1 key")
                check(bindings.single { it.input == "virtual:a" }.keys == listOf(spec.keyCodes.first()))
                // A different input type must also be editable entirely with the D-pad.
                ui { editor.close(); editor.show(null) }
                key(KeyEvent.KEYCODE_DPAD_RIGHT); key(KeyEvent.KEYCODE_BUTTON_A)
                select("Right stick left")
                select("${spec.name} mouse")
                select("Move left")
                var before = 0
                ui { before = (root.findFocus() as SeekBar).progress }
                key(KeyEvent.KEYCODE_DPAD_RIGHT)
                ui { check((root.findFocus() as SeekBar).progress == before + 1) }
                select("Save mouse mapping")
                check(bindings.single { it.input == "virtual:rsleft" }.mouseSpeed == (before + 2) / 10f)
                key(KeyEvent.KEYCODE_BUTTON_B)
                check(!editor.isOpen)
                ui { editor.show(null, startPhysical = true) }
                select("A")
                select("Clear assignment")
                check(physical.none { it.control == "a" })
                key(KeyEvent.KEYCODE_BUTTON_B)
                check(!editor.isOpen)
            }
            lateinit var dialog: AlertDialog
            var chosen = -1
            ui {
                dialog = AlertDialog.Builder(activity).setTitle("Controller selector test")
                    .setSingleChoiceItems(arrayOf("First", "Second"), 0) { current, which ->
                        chosen = which; current.dismiss()
                    }.setNegativeButton("Cancel", null).create()
                dialog.show(); Ui.styleDialog(dialog)
                dialog.listView.requestFocusFromTouch()
                dialog.listView.setSelection(0)
            }
            fun dialogKey(code: Int) = ui {
                dialog.window!!.callback.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                dialog.window!!.callback.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            }
            hat(1f) { dialog.window!!.callback.dispatchGenericMotionEvent(it) }
            hat(0f) { dialog.window!!.callback.dispatchGenericMotionEvent(it) }
            dialogKey(KeyEvent.KEYCODE_BUTTON_A)
            check(chosen == 1 && !dialog.isShowing) { "Dialog selection failed: $chosen" }
            ui { dialog.show(); Ui.styleDialog(dialog) }
            dialogKey(KeyEvent.KEYCODE_BUTTON_B)
            check(!dialog.isShowing)
        } finally { ui { runCatching { editor.close() }; activity.finish() } }
    }
}
