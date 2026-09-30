// SPDX-License-Identifier: GPL-2.0-or-later
// Android platform services for the embedded SDL utility library. Kairo owns
// the Activity, Surface and input lifecycle; there is no SDLActivity/window.
#include "SDL.h"
#include <stdlib.h>
#include <sys/system_properties.h>

int SDL_GetAndroidSDKVersion(void) {
    char value[PROP_VALUE_MAX] = {0};
    __system_property_get("ro.build.version.sdk", value);
    return atoi(value);
}
const char* SDL_AndroidGetInternalStoragePath(void) { return getenv("XDG_CONFIG_HOME"); }
SDL_bool SDL_IsAndroidTablet(void) { return SDL_FALSE; }
void Android_JNI_GetManifestEnvironmentVariables(void) {}
void Android_ActivityMutex_Lock_Running(void) {}
void Android_ActivityMutex_Unlock(void) {}
SDL_bool Android_JNI_ShouldMinimizeOnFocusLoss(void) { return SDL_FALSE; }
