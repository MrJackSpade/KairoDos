package com.mrjackspade.kairodos

import android.app.Instrumentation
import android.content.Intent
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import com.mrjackspade.kairo.frontend.*

/** Exercises actual focus traversal and activation through the host's input dispatch, not just
 * binding serialization: Button mapping, the button target page, Controller inputs, and a choice. */
internal object ControllerMenuFixture {
    fun verify(instrumentation: Instrumentation) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var bindings = DosControllerBindings.parse(DosGameCatalog(activity).controllerBindings("duke3d-fps-v1", ControllerLayout.WITH_STICKS))
        var physical = PhysicalControllerBindings.defaults()
        fun ui(action: () -> Unit) { instrumentation.runOnMainSync { action() }; instrumentation.waitForIdleSync() }
        val pages = MainActivity::class.java.getDeclaredField("pages").apply { isAccessible = true }.get(activity) as PageStack
        fun key(code: Int) = ui {
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
        fun hat(value: Float) = ui {
            val properties = MotionEvent.PointerProperties().apply { id = 0 }
            val coords = MotionEvent.PointerCoords().apply { setAxisValue(MotionEvent.AXIS_HAT_Y, value) }
            MotionEvent.obtain(0, android.os.SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE,
                1, arrayOf(properties), arrayOf(coords), 0, 0, 1f, 1f, 0, 0,
                InputDevice.SOURCE_JOYSTICK, 0).also { activity.dispatchGenericMotionEvent(it); it.recycle() }
        }
        fun top() = pages.top!!
        fun centre(view: View) = IntArray(2).also { view.getLocationOnScreen(it) }
            .let { (it[0] + view.width / 2) to (it[1] + view.height / 2) }
        /** Move with the D-pad toward [id] by screen position, as a player would, then press A. */
        fun select(id: String, press: Boolean = true) {
            repeat(240) {
                var code = 0
                ui {
                    val page = top()
                    val target = page.itemView(id) ?: error("Missing control $id on ${page.itemIds()}")
                    val current = page.focusedItemId?.let(page::itemView)
                    code = when {
                        page.focusedItemId == id -> 0
                        current == null -> KeyEvent.KEYCODE_DPAD_DOWN
                        else -> {
                            val (tx, ty) = centre(target)
                            val (cx, cy) = centre(current)
                            when {
                                ty > cy + current.height / 2 -> KeyEvent.KEYCODE_DPAD_DOWN
                                ty < cy - current.height / 2 -> KeyEvent.KEYCODE_DPAD_UP
                                tx > cx -> KeyEvent.KEYCODE_DPAD_RIGHT
                                else -> KeyEvent.KEYCODE_DPAD_LEFT
                            }
                        }
                    }
                }
                if (code == 0) {
                    if (press) key(KeyEvent.KEYCODE_BUTTON_A)
                    return
                }
                key(code)
                Thread.sleep(30)
            }
            error("D-pad could not reach $id; focus=${top().focusedItemId}")
        }
        /** The key picker shows keys on the guest keyboard's tabs; codes it lacks are under Other. */
        fun selectKey(spec: ControllerGuestSpec, code: Int) {
            val description = spec.keyLabel(code) + " 0x" + code.toString(16).padStart(2, '0')
            var tabs = 0
            ui { tabs = top().itemIds().count { it.startsWith("sub:") } }
            for (tab in 0 until tabs) {
                select("sub:$tab")
                var id: String? = null
                ui { id = top().itemIds().firstOrNull { top().itemView(it)?.contentDescription == description } }
                if (id != null) { select(id!!); return }
            }
            error("Key $description is not on the picker")
        }
        lateinit var editor: ControllerPages<String>
        try {
            for (spec in listOf(DosControllerBindings.spec,
                DosControllerBindings.spec.copy(name = "PC-98", keyCodes = listOf(1, 2, 3),
                    keyLabel = { "Key $it" }, modifierCodes = emptySet()))) {
                ui {
                    physical = PhysicalControllerBindings.defaults()
                    bindings = bindings.filterNot { it.input == "virtual:a" }
                    val onScreen = MainActivity::class.java.getDeclaredField("onScreenControls")
                        .apply { isAccessible = true }.get(activity) as OnScreenControls
                    val touch = TouchSettingsCoordinator(object : TouchSettingsStore<String> {
                        override fun global() = TouchInputSelection(InputModeDecider.Mode.AUTO, false)
                        override fun saveGlobal(value: TouchInputSelection) = Unit
                        override fun mode(game: String) = GameValue<InputModeDecider.Mode>({ null },
                            { InputModeDecider.Mode.AUTO }, { GameSource.ALL_GAMES }, {})
                        override fun directTouch(game: String) = GameValue<Boolean>({ null }, { false },
                            { GameSource.ALL_GAMES }, {})
                    }, listOf(InputModeDecider.Mode.AUTO to ChoiceOption("auto", "Auto"),
                        InputModeDecider.Mode.KEYBOARD to ChoiceOption("keyboard", "Keyboard"),
                        InputModeDecider.Mode.MOUSE to ChoiceOption("mouse", "Mouse")), "")
                    editor = ControllerPages(activity, pages, { bindings }, { _, value -> bindings = value }, {},
                        { false }, { physical }, { physical = it }, {}, { false }, { .35f }, {},
                        spec, {}, null, {}, { null }, { it }, onScreen, touch)
                    pages.push(editor.mappingPage(null))
                }
                Thread.sleep(300)
                select("m:a")
                Thread.sleep(300)
                ui { check(top().focusedItemId == "tab:KEYS") { "Target opened on ${top().focusedItemId}" } }
                selectKey(spec, spec.keyCodes.first())
                ui { check(top().itemView("footer:save")!!.contentDescription == "Save 1 key") }
                select("footer:save")
                Thread.sleep(300)
                check(bindings.single { it.input == "virtual:a" }.keys == listOf(spec.keyCodes.first()))
                // A different input type must also be editable entirely with the D-pad.
                select("m:rsleft")
                Thread.sleep(300)
                select("tab:MOUSE")
                select("mouse:moveLeft")
                select("speed", press = false)
                var before = ""
                ui { before = (top() as RowsPage).rowValue("speed")!! }
                key(KeyEvent.KEYCODE_DPAD_RIGHT)
                // Right moves the slider by half a step of speed.
                val expected = minOf(before.removeSuffix("×").toFloat() + .5f, 20f)
                ui { check((top() as RowsPage).rowValue("speed") == "%.1f×".format(java.util.Locale.ROOT, expected)) {
                    "Speed did not step from $before" } }
                select("footer:save")
                Thread.sleep(300)
                check(bindings.single { it.input == "virtual:rsleft" }.let { it.mouse == "moveLeft" &&
                    kotlin.math.abs(it.mouseSpeed - expected) < .001f })
                key(KeyEvent.KEYCODE_BUTTON_B)
                Thread.sleep(300)
                check(!pages.isActive)
                // Controller inputs: listening captures controller buttons, and keyboard keys reach the tray.
                ui { pages.push(editor.inputsPage()) }
                Thread.sleep(300)
                select("p:a")
                ui { check(pages.capturing && (top() as RowsPage).trayButtons().contains("Clear")) }
                key(KeyEvent.KEYCODE_BUTTON_X)
                ui {
                    check(!pages.capturing && physical.single { it.control == "a" }.input == "button:${KeyEvent.KEYCODE_BUTTON_X}") {
                        "Listening did not assign the pressed button"
                    }
                }
                select("p:a")
                key(KeyEvent.KEYCODE_DPAD_RIGHT)
                ui { check((top() as RowsPage).trayFocused() == "Clear") }
                key(KeyEvent.KEYCODE_ENTER)
                check(physical.none { it.control == "a" })
                key(KeyEvent.KEYCODE_BUTTON_B)
                Thread.sleep(300)
                check(!pages.isActive)
            }
            // A choice page with hat movement, A to pick, and B to leave without choosing.
            var chosen = -1
            fun selector() = ChoicePage(ChoiceSpec("Controller selector test", { null }, {
                listOf(ChoiceGroup(null, listOf(ChoiceOption("0", "First"), ChoiceOption("1", "Second"))))
            }, { "0" }, select = { chosen = it.toInt() }))
            ui { pages.push(selector()) }
            Thread.sleep(300)
            ui { check(top().focusedItemId == "o:0") }
            hat(1f); hat(0f)
            ui { check(top().focusedItemId == "o:1") }
            key(KeyEvent.KEYCODE_BUTTON_A)
            Thread.sleep(500)
            check(chosen == 1 && !pages.isActive) { "Choice selection failed: $chosen" }
            chosen = -1
            ui { pages.push(selector()) }
            Thread.sleep(300)
            key(KeyEvent.KEYCODE_BUTTON_B)
            Thread.sleep(300)
            check(chosen == -1 && !pages.isActive)
        } finally { ui { pages.closeAll(); activity.finish() } }
    }
}
