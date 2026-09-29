package com.mrjackspade.kairodos

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.mrjackspade.kairo.frontend.*

/** Exercises ownership boundaries and real shared input routers without launching a core. */
object InputDispatchFixture {
    fun verify() {
        val sent = mutableListOf<Pair<Int, Boolean>>()
        val joy = mutableListOf<Pair<Int, Boolean>>()
        val clicks = mutableListOf<Pair<Int, Boolean>>()
        val keys = InputRouter({ key, down -> sent.add(key to down) })
        val mouse = MouseInputRouter({ _, _ -> }, { button, down -> clicks.add(button to down) })
        val mapper = GamepadMapper(keys, JoystickInputRouter({ key, down -> joy.add(key to down) }),
            mouse, {}, {}, listOf(ControllerBinding("virtual:a", keys = listOf(42)),
                ControllerBinding("virtual:lsright", keys = listOf(43)),
                ControllerBinding("virtual:b", joystick = "button1"),
                ControllerBinding("virtual:x", mouse = "leftButton")))
        var screen = InputDispatchCoordinator.Screen.GUEST
        var handled = true
        var frontendCalls = 0
        val frontend = object : InputDispatchCoordinator.Frontend {
            override fun screen() = screen
            override fun key(screen: InputDispatchCoordinator.Screen, control: String?, event: KeyEvent): Boolean {
                frontendCalls++
                return handled
            }
            override fun motion(screen: InputDispatchCoordinator.Screen, event: MotionEvent): Boolean {
                frontendCalls++
                return handled
            }
        }
        var backs = 0
        var menus = 0
        var cancelled = 0
        val dispatch = InputDispatchCoordinator(frontend, mapper, keys, { 21 },
            { backs++ }, { menus++ }, {}, { mouse.releasePrefix("touch") },
            { cancelled++ }, setOf("automation"))
        fun event(code: Int, action: Int = KeyEvent.ACTION_DOWN, device: Int = 7, repeat: Int = 0,
                  source: Int = InputDevice.SOURCE_KEYBOARD) =
            KeyEvent(0, 0, action, code, repeat, 0, device, 0, 0, source)
        fun key(event: KeyEvent) = dispatch.dispatchKey(event, dispatch::physicalKey)

        for (target in InputDispatchCoordinator.Screen.entries.filter {
                it != InputDispatchCoordinator.Screen.GUEST }) {
            screen = target
            check(key(event(KeyEvent.KEYCODE_BUTTON_A, source = InputDevice.SOURCE_GAMEPAD)))
            check(keys.pressedKeys().isEmpty()) { "Frontend controller input leaked from $target" }
            handled = false
            key(event(KeyEvent.KEYCODE_A))
            check(keys.pressedKeys().isEmpty()) { "Frontend keyboard input leaked from $target" }
            handled = true
        }
        screen = InputDispatchCoordinator.Screen.GUEST
        key(event(KeyEvent.KEYCODE_BACK))
        key(event(KeyEvent.KEYCODE_BACK, repeat = 1))
        key(event(KeyEvent.KEYCODE_BACK, KeyEvent.ACTION_UP))
        key(event(KeyEvent.KEYCODE_MENU))
        key(event(KeyEvent.KEYCODE_MENU, repeat = 1))
        check(backs == 1 && menus == 1 && keys.pressedKeys().isEmpty())

        key(event(KeyEvent.KEYCODE_A, device = 10))
        key(event(KeyEvent.KEYCODE_A, device = 11))
        dispatch.releaseDevice(10)
        check(keys.pressedKeys() == setOf(21))
        dispatch.releaseDevice(11)
        check(keys.pressedKeys().isEmpty() && sent.takeLast(2) == listOf(21 to true, 21 to false))
        key(event(KeyEvent.KEYCODE_A, repeat = 1))
        check(keys.pressedKeys().isEmpty())

        val motion = MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE, 1f, 0f, 0)
        motion.source = InputDevice.SOURCE_JOYSTICK
        try {
            for (target in listOf(InputDispatchCoordinator.Screen.FIRST_RUN,
                    InputDispatchCoordinator.Screen.TOUCH_EDITOR,
                    InputDispatchCoordinator.Screen.CONTROLLER_EDITOR,
                    InputDispatchCoordinator.Screen.LIBRARY, InputDispatchCoordinator.Screen.SESSION)) {
                screen = target
                dispatch.dispatchMotion(motion) { false }
                check(keys.pressedKeys().isEmpty()) { "Frontend axes leaked from $target" }
            }
            screen = InputDispatchCoordinator.Screen.GUEST
            check(dispatch.dispatchMotion(motion) { false })
            check(keys.pressedKeys() == setOf(43))
            dispatch.releaseDevice(motion.deviceId)
            check(keys.pressedKeys().isEmpty())
        } finally { motion.recycle() }

        key(event(KeyEvent.KEYCODE_BUTTON_A, source = InputDevice.SOURCE_GAMEPAD))
        key(event(KeyEvent.KEYCODE_BUTTON_B, source = InputDevice.SOURCE_GAMEPAD))
        key(event(KeyEvent.KEYCODE_BUTTON_X, source = InputDevice.SOURCE_GAMEPAD))
        mouse.hold("touch", "rightButton")
        keys.hold("automation", listOf(63))
        var companion = false
        var audioPaused = 0
        var audioResumed = 0
        var resets = 0
        val lifecycle = GuestLifecycleCoordinator({ dispatch.releaseInputs() }, {},
            { audioPaused++ }, { audioResumed++ }, companionActive = { companion },
            resetGestures = { resets++ }, releaseOnFocusLoss = { dispatch.releaseInputs(true) })
        lifecycle.onWindowFocusChanged(false)
        check(keys.pressedKeys() == setOf(63) && cancelled == 0)
        check(joy.takeLast(2) == listOf(4 to true, 4 to false))
        check(clicks.containsAll(listOf(1 to true, 1 to false, 2 to true, 2 to false)))
        companion = true
        check(!lifecycle.onPause() && !lifecycle.onStop() && lifecycle.isVisible)
        check(keys.pressedKeys() == setOf(63) && audioPaused == 0 && resets == 0)
        companion = false
        check(lifecycle.onPause() && !lifecycle.isVisible && keys.pressedKeys().isEmpty())
        check(cancelled == 1 && audioPaused == 1)
        lifecycle.onResume()
        check(lifecycle.isVisible && audioResumed == 1)
        check(lifecycle.onStop() && !lifecycle.isVisible && resets == 1)
        check(frontendCalls > 0)

        val session = SessionNavigationState()
        check(session.presentation(true, false, false, false).pauseGuest)
        session.enterGame()
        check(!session.presentation(true, false, false, false).pauseGuest)
        session.userPaused = true
        val paused = session.presentation(true, false, false, false)
        check(paused.pauseGuest && paused.showGuest) { "Pause hid the guest display/keyboard" }
        session.showLibrary()
        check(!session.presentation(true, false, false, false).showGuest)
        session.enterGame()
        check(session.userPaused) { "Library resume discarded the user's pause" }
        session.userPaused = false
        for (blocked in listOf(session.presentation(false, false, false, false),
                session.presentation(true, true, false, false),
                session.presentation(true, false, true, false),
                session.presentation(true, false, false, true))) {
            check(blocked.pauseGuest && !blocked.showGuest)
        }
    }
}
