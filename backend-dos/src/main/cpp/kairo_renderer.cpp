// SPDX-License-Identifier: GPL-2.0-or-later
#include "staging_bridge.h"
#include "gui/render/render_backend.h"
#include "SDL.h"
#include <stdexcept>
#include <vector>

namespace {
class KairoRenderer final : public RenderBackend {
    SDL_Window* window = nullptr;
    std::vector<uint32_t> pixels;
    int width = 0, height = 0;
    bool dirty = false;
    double aspect = 4.0 / 3.0;
public:
    KairoRenderer(int w, int h) {
        window = SDL_CreateWindow("KairoDos", 0, 0, w, h, SDL_WINDOW_RESIZABLE);
        if (!window) throw std::runtime_error(SDL_GetError());
    }
    ~KairoRenderer() override { SDL_DestroyWindow(window); }
    SDL_Window* GetWindow() override { return window; }
    DosBox::Rect GetCanvasSizeInPixels() override {
        int w = 0, h = 0;
        SDL_GetWindowSize(window, &w, &h);
        return {0, 0, w, h};
    }
    void NotifyViewportSizeChanged(DosBox::Rect) override {}
    void NotifyRenderSizeChanged(int w, int h) override {
        if (w <= 0 || h <= 0 || w > 8192 || h > 8192)
            throw std::runtime_error("Invalid DOS framebuffer dimensions");
        width = w; height = h;
        pixels.assign(static_cast<size_t>(w) * h, 0);
        dirty = true;
    }
    void NotifyVideoModeChanged(const VideoMode& mode) override {
        if (mode.height > 0 && mode.pixel_aspect_ratio.Num() > 0)
            aspect = static_cast<double>(mode.width) / mode.height * mode.pixel_aspect_ratio.ToDouble();
    }
    SetShaderResult SetShader(const std::string&) override { return SetShaderResult::Ok; }
    void ForceReloadCurrentShader() override {}
    ShaderInfo GetCurrentShaderInfo() override { return {}; }
    ShaderPreset GetCurrentShaderPreset() override { return {}; }
    std::string GetCurrentSymbolicShaderDescriptor() override { return {}; }
    ShaderDescriptor GetCurrentShaderDescriptor() override { return {}; }
    void StartFrame(uint32_t*& out, int& pitch) override {
        out = pixels.data(); pitch = width * sizeof(uint32_t);
    }
    void EndFrame() override { dirty = true; }
    void PrepareFrame() override {}
    void PresentFrame() override {
        if (!dirty || pixels.empty()) return;
        // VGA updates only changed pixels. Keep this buffer intact between
        // frames; the frontend copies it synchronously into its latest slot.
        KairoStagingVideo(pixels.data(), width, height, width * 4, aspect);
        dirty = false;
    }
    void SetVsync(bool) override {}
    void SetColorSpace(ColorSpace) override {}
    void EnableImageAdjustments(bool) override {}
    void SetImageAdjustmentSettings(const ImageAdjustmentSettings&) override {}
    void SetDeditheringStrength(float) override {}
    RenderedImage ReadPixelsPostShader(DosBox::Rect) override { return {}; }
    uint32_t MakePixel(uint8_t r, uint8_t g, uint8_t b) override {
        return 0xff000000u | (uint32_t(r) << 16) | (uint32_t(g) << 8) | b;
    }
};
}

RenderBackend* KairoCreateRenderer(int width, int height) {
    return new KairoRenderer(width, height);
}
