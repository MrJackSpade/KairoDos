// SPDX-License-Identifier: GPL-2.0-or-later
#pragma once
#include <array>
#include <cstdint>
#include <cstring>
#include <vector>

// Remember indexed input only. The renderer owns the authoritative RGB cache.
// Reuse requires this exact row to have been rendered in the preceding frame,
// plus identical indices and palette. Resets/failed updates invalidate history.
class KairoPaletteCache {
    size_t width_ = 0, rows_ = 0;
    uint64_t epoch_ = 1;
    std::array<uint32_t, 256> palette_{};
    std::vector<uint8_t> indices_;
    std::vector<uint64_t> row_epochs_, row_frames_;
public:
    void Invalidate() {
        if (++epoch_ == 0) { epoch_ = 1; row_epochs_.assign(rows_, 0); }
    }
    bool Remember(const uint8_t* indices, const void* palette, size_t width,
                  size_t rows, size_t row, uint64_t frame) {
        constexpr size_t MaxPixels = 2 * 1024 * 1024;
        if (!width || !rows || row >= rows || width > MaxPixels / rows) {
            Invalidate(); return false;
        }
        if (width_ != width || rows_ != rows) {
            width_ = width; rows_ = rows;
            indices_.resize(width * rows);
            row_epochs_.assign(rows, 0); row_frames_.assign(rows, 0);
        }
        if (std::memcmp(palette_.data(), palette, sizeof(palette_)) != 0) {
            std::memcpy(palette_.data(), palette, sizeof(palette_));
            Invalidate();
        }
        auto* old = indices_.data() + row * width;
        const bool same = row_epochs_[row] == epoch_ &&
                          row_frames_[row] + 1 == frame &&
                          std::memcmp(old, indices, width) == 0;
        if (!same) std::memcpy(old, indices, width);
        row_epochs_[row] = epoch_; row_frames_[row] = frame;
        return same;
    }
};

bool KairoTryReusePaletteLine(const uint8_t* indices, const void* palette,
                             size_t width, size_t rows, size_t row);
void KairoInvalidatePaletteLines();
