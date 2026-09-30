package com.mrjackspade.kairodos

import android.content.Context
import android.view.InputDevice
import android.view.MotionEvent
import com.mrjackspade.kairo.frontend.*
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** Real shared mapper, profile persistence and relative movement at controlled elapsed times. */
object ControllerMouseFixture {
    fun verify(context: Context) {
        verifyKeyboard(context)
        val duke = DosControllerBindings.duke3d()
        check(DosControllerBindings.parse(DosControllerBindings.toJson(duke).toString()) == duke)
        check(duke.none { it.joystick != null })
        check(duke.single { it.input == "virtual:l2" }.keys == listOf(59))
        check(duke.single { it.input == "virtual:r2" }.keys == listOf(39))
        val moves = mutableListOf<Pair<Int, Int>>()
        val clicks = mutableListOf<Pair<Int, Boolean>>()
        val mouse = MouseInputRouter({ x, y -> moves.add(x to y) }, { b, d -> clicks.add(b to d) })
        fun totalX() = moves.sumOf { it.first }
        fun tick() { repeat(20) { mouse.tick(50) } }
        mouse.hold("test", "moveRight")
        tick()
        check(abs(totalX() - 480) <= 1)
        mouse.release("test")
        moves.clear()
        mouse.hold("test", "moveRight", speed = 8f)
        tick()
        check(abs(totalX() - 3840) <= 1)
        mouse.release("test")
        moves.clear()
        // Pointer/touch movement never receives the controller multiplier.
        mouse.moveBy(7, -3)
        check(moves.single() == (7 to -3))
        moves.clear()
        mouse.hold("x", "moveRight", speed = 8f)
        mouse.hold("y", "moveDown", speed = 8f)
        tick()
        val x = totalX()
        val y = moves.sumOf { it.second }
        check(abs(x - 2715) <= 2 && abs(y - 2715) <= 2)
        mouse.releasePrefix("")
        moves.clear()
        mouse.hold("a", "leftButton")
        mouse.hold("b", "leftButton")
        mouse.release("a")
        check(clicks == listOf(1 to true))
        mouse.release("b")
        check(clicks == listOf(1 to true, 1 to false))
        check(runCatching { mouse.hold("bad", "moveRight", speed = Float.NaN) }.isFailure)

        val keys = mutableListOf<Pair<Int, Boolean>>()
        val mapper = GamepadMapper(InputRouter({ code, down -> keys.add(code to down) }),
            JoystickInputRouter({ _, _ -> }), mouse, {}, {}, DosControllerBindings.doom())
        fun motion(value: Float, axis: Int = MotionEvent.AXIS_Z) {
            val properties = MotionEvent.PointerProperties().apply { id = 0 }
            val coords = MotionEvent.PointerCoords().apply { setAxisValue(axis, value) }
            val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE, 1,
                arrayOf(properties), arrayOf(coords), 0, 0, 1f, 1f, 42, 0,
                InputDevice.SOURCE_JOYSTICK, 0)
            try { check(mapper.motion(event)) } finally { event.recycle() }
        }
        fun sample(value: Float): Int {
            mapper.releaseAll()
            moves.clear()
            motion(value)
            tick()
            val result = totalX()
            motion(0f)
            mouse.tick(50)
            check(totalX() == result)
            return result
        }
        check(sample(0.30f) == 0)
        check(sample(0.35f) == 0)
        check(abs(sample(0.5f) - 886) <= 2)
        check(abs(sample(0.675f) - 1920) <= 2)
        check(abs(sample(1f) - 3840) <= 1)
        check(abs(sample(-1f) + 3840) <= 1)
        moves.clear()
        motion(1f, MotionEvent.AXIS_X)
        check(keys.last() == (46 to true) && moves.isEmpty())
        motion(0f, MotionEvent.AXIS_X)
        check(keys.last() == (46 to false))
        mapper.releaseAll()

        val codec = ControllerBindingsCodec({ emptyList() }, { it in 0..340 }, emptyList(), emptyList())
        val binding = listOf(ControllerBinding("virtual:rsright", mouse = "moveRight", mouseSpeed = 4.3f))
        check(codec.parse(codec.toJson(binding).toString()) == binding)
        check(codec.parse("[{\"input\":\"virtual:rsright\",\"mouse\":\"moveRight\"}]").single().mouseSpeed == 1f)
        check(codec.valid(JSONArray("[{\"input\":\"virtual:rsright\",\"mouse\":\"moveRight\",\"mouseSpeed\":0.1}]")))
        for (bad in listOf(-1, 0, 21, "fast")) {
            val json = JSONArray().put(JSONObject().put("input", "virtual:rsright")
                .put("mouse", "moveRight").put("mouseSpeed", bad))
            check(!codec.valid(json))
        }
        check(!codec.valid(JSONArray().put(JSONObject().put("input", "virtual:a")
            .put("keys", JSONArray().put(13)).put("mouseSpeed", 2))))
        val original = DosControllerBindings.doom().map { it.copy(mouseSpeed = 1f) }
        check(DosControllerBindings.parse(DosControllerBindings.toJson(original).toString()) == DosControllerBindings.doom())
        val custom = original.map { if (it.input == "virtual:a") it.copy(keys = listOf(32)) else it }
        check(DosControllerBindings.parse(DosControllerBindings.toJson(custom).toString()) == custom)
        val tuned = DosControllerBindings.doom().map { if (it.mouse != null) it.copy(mouseSpeed = 4f) else it }
        check(DosControllerBindings.parse(DosControllerBindings.toJson(tuned).toString()) == tuned)
        val name = "controller_mouse_fixture"
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        try {
            fun store() = ControllerProfileStore(prefs, codec::parse, { codec.toJson(it).toString() })
            store().saveGlobal(binding)
            check(store().global() == binding)
            val games = DosPerGameSettings(prefs, GameSettingScope(prefs))
            val scope = GameSettingScope(prefs)
            check(games.videoHardware("fixture-game") == 0)
            prefs.edit().putInt(DosVideoHardware.setting, 2).commit()
            check(games.videoHardware("fixture-game") == 2)
            scope.setInt("fixture-game", DosVideoHardware.setting, 1)
            check(games.videoHardware("fixture-game") == 1 && games.videoHardware() == 2)
            scope.setInt("fixture-game", DosVideoHardware.setting, 0)
            check(games.videoHardware("fixture-game") == 0) // Explicit automatic overrides global card.
            games.reset("fixture-game")
            check(games.videoHardware("fixture-game") == 2)
            prefs.edit().putInt(DosVideoHardware.setting, 999).commit()
            check(games.videoHardware("fixture-game") == 0)
            games.setControllerBindings("fixture-game", DosControllerBindings.toJson(tuned).toString())
            check(DosControllerBindings.parse(DosPerGameSettings(prefs, GameSettingScope(prefs))
                .controllerBindings("fixture-game")) == tuned)
            check(store().global() == binding)
        } finally { context.deleteSharedPreferences(name) }
    }

    private fun verifyKeyboard(context: Context) {
        val router = InputRouter({ _, _ -> }, 341)
        val panel = GuestKeyboardPanel(context, router, DosKeyboardLayout.value, {})
        fun descendants(view: android.view.View): List<android.view.View> = listOf(view) +
            if (view is android.view.ViewGroup) (0 until view.childCount).flatMap {
                descendants(view.getChildAt(it))
            } else emptyList()
        for ((label, code) in listOf("Shift" to 304, "Ctrl" to 306)) {
            val key = descendants(panel).filterIsInstance<android.widget.TextView>()
                .first { it.text.toString() == label }
            fun gesture(duration: Long, cancel: Boolean = false) {
                val start = android.os.SystemClock.uptimeMillis()
                MotionEvent.obtain(start, start, MotionEvent.ACTION_DOWN, 0f, 0f, 0).also {
                    key.dispatchTouchEvent(it); it.recycle()
                }
                check(code in router.pressedKeys())
                MotionEvent.obtain(start, start + duration,
                    if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, 0f, 0f, 0).also {
                    key.dispatchTouchEvent(it); it.recycle()
                }
            }
            gesture(50)
            check(code !in router.pressedKeys()) { "$label tap stayed pressed" }
            gesture(android.view.ViewConfiguration.getLongPressTimeout().toLong() + 1)
            check(code in router.pressedKeys()) { "$label long press did not latch" }
            gesture(50)
            check(code !in router.pressedKeys()) { "$label latch did not clear" }
            gesture(1000, cancel = true)
            check(code !in router.pressedKeys()) { "$label cancel latched" }
        }
        panel.close()
        check(router.pressedKeys().isEmpty())
    }
}
