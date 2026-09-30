// SPDX-License-Identifier: GPL-2.0-or-later
// Device-only debug fixture: real shared contexts, fenced snapshots, two
// ImageReader windows, surface removal/recreation, and changing FBO sizes.
#include <jni.h>
#include <android/native_window_jni.h>
#include "kairo/gpu_frame_queue.h"
#include <thread>

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mrjackspade_kairodos_GpuRenderingFixture_nativeVerify(
        JNIEnv* env, jclass, jobject firstSurface, jobject secondSurface, jboolean bottomLeft) {
    ANativeWindow* first = ANativeWindow_fromSurface(env, firstSurface);
    ANativeWindow* second = ANativeWindow_fromSurface(env, secondSurface);
    kairo::GpuFrameQueue queue;
    // Unsupported context creation must roll back cleanly before retry/fallback.
    if (queue.create(99, 0) || queue.ready()) {
        queue.destroy(); ANativeWindow_release(first); ANativeWindow_release(second); return false;
    }
    if (!queue.create(3, 1)) {
        ANativeWindow_release(first); ANativeWindow_release(second); return false;
    }
    queue.releaseCurrent();
    std::atomic<int> selected{0};
    std::thread renderer([&] { queue.render([&] {
        ANativeWindow* result = selected == 0 ? first : selected == 1 ? second : nullptr;
        if (result) ANativeWindow_acquire(result);
        return result;
    }); });
    bool ok = true;
    ok = !queue.resize(0xffffffffu, 1024);
    for (int frame = 0; frame < 90 && ok; ++frame) {
        ok = queue.makeCurrent();
        if (!ok) break;
        if (frame == 30) selected = 1;
        if (frame == 50) selected = 2;
        if (frame == 65) selected = 0;
        if (frame == 45) ok = queue.resize(2048, 1024);
        glBindFramebuffer(GL_FRAMEBUFFER, queue.framebuffer());
        glViewport(0, 0, 64, 64);
        glDisable(GL_SCISSOR_TEST); glClearColor(1, 0, 0, 1); glClear(GL_COLOR_BUFFER_BIT);
        glEnable(GL_SCISSOR_TEST); glScissor(0, 32, 64, 32);
        glClearColor(0, 0, 1, 1); glClear(GL_COLOR_BUFFER_BIT);
        glDisable(GL_SCISSOR_TEST);
        queue.publish(64, 64, bottomLeft);
        ok = ok && !queue.failed() && glGetError() == GL_NO_ERROR;
        queue.releaseCurrent();
        std::this_thread::sleep_for(std::chrono::milliseconds(15));
    }
    queue.stop(); renderer.join();
    ok = ok && !queue.failed();
    queue.destroy();
    ANativeWindow_release(first); ANativeWindow_release(second);
    return ok;
}
