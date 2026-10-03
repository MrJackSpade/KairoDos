# On-screen controls across game sessions

The DOS library-return path cleared `onScreenControls` together with session-owned views. That controller is created once in `onCreate` and its overlay belongs to `appRoot`, so it must survive replacing `gameRoot`. Clearing the reference left subsequent games without controls. Opening the touch settings from the controller editor then suspended the parent page but could not open the child settings, leaving the game paused behind an invisible editor.

Retain the activity-owned object. Existing `leaveGame` cleanup still closes its editor, releases input, unbinds the old keyboard and hides the overlay. A subsequent game binds its new keyboard and restores visibility. PC-98 already retains its controls this way.

The shared `EndSessionFixture` now accepts an optional second game URI and verifies control-object identity through teardown, overlay visibility in both games, opening/closing touch settings, restoration of the parent controller editor, and native game resumption. The regression failed on the unfixed DOS app with `Activity controls discarded on return to library`.

Both debug builds and RGDS regression runs passed: Commander Keen 4 to DOOM in DOS, and Rusty to Touhou 2 in PC-98. The DOS fixture also verified cancellation during an early third launch.
