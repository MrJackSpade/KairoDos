// SPDX-License-Identifier: GPL-2.0-or-later
#pragma once
#ifdef KAIRO_GUEST_PROFILE
#include <cstdio>
#include <cstdint>
#include <ctime>
#include <string>
#include <vector>

// Measurement-only translation metadata. Never instruments executed guest blocks.
// All access is on Staging's emulation thread. Timestamps use CLOCK_MONOTONIC,
// matching simpleperf --clockid monotonic. Guest bytes/addresses stay local.
namespace kairo_guest_profile {
inline FILE* output = nullptr;
inline uint64_t last_flush = 0;
inline uint64_t serial = 0;
inline uint64_t now() {
    timespec t{}; clock_gettime(CLOCK_MONOTONIC, &t);
    return uint64_t(t.tv_sec) * 1000000000ull + t.tv_nsec;
}
inline void close() {
    if (output) { std::fclose(output); output = nullptr; }
}
inline void open(const std::string& directory) {
    close(); serial = 0;
    const auto marker = directory + "/kairo-guest-profile.enable";
    FILE* enabled = std::fopen(marker.c_str(), "rb");
    if (!enabled) return;
    std::fclose(enabled);
    output = std::fopen((directory + "/kairo-guest-profile.jsonl").c_str(), "wb");
    if (output) {
        std::setvbuf(output, nullptr, _IOFBF, 65536);
        std::fprintf(output, "{\"event\":\"begin\",\"time\":%llu,\"version\":1}\n",
                     static_cast<unsigned long long>(now()));
    }
}
inline void flush() {
    if (!output) return;
    const auto time = now();
    if (time - last_flush >= 1000000000ull) { std::fflush(output); last_flush = time; }
}
inline void clear(const void* block) {
    if (output) std::fprintf(output,
        "{\"event\":\"clear\",\"time\":%llu,\"block\":%llu}\n",
        static_cast<unsigned long long>(now()),
        static_cast<unsigned long long>(reinterpret_cast<uintptr_t>(block)));
}
struct Instruction { uintptr_t host; uint32_t guest; unsigned opcode; };
struct Translation {
    std::vector<Instruction> instructions;
    void instruction(const void* host, uint32_t guest, unsigned opcode) {
        if (output) instructions.push_back({reinterpret_cast<uintptr_t>(host), guest, opcode});
    }
    void publish(const void* block, const void* host, size_t size,
                 uint32_t guest, uint32_t end, uint32_t cs_base, bool code32) {
        if (!output) return;
        std::fprintf(output,
            "{\"event\":\"block\",\"time\":%llu,\"serial\":%llu,\"block\":%llu,"
            "\"host\":%llu,\"size\":%zu,\"guest\":%u,\"end\":%u,\"csBase\":%u,"
            "\"code32\":%s,\"instructions\":[",
            static_cast<unsigned long long>(now()), static_cast<unsigned long long>(++serial),
            static_cast<unsigned long long>(reinterpret_cast<uintptr_t>(block)),
            static_cast<unsigned long long>(reinterpret_cast<uintptr_t>(host)), size,
            guest, end, cs_base, code32 ? "true" : "false");
        bool first = true;
        for (const auto& i : instructions) {
            std::fprintf(output, "%s[%llu,%u,%u]", first ? "" : ",",
                static_cast<unsigned long long>(i.host), i.guest, i.opcode);
            first = false;
        }
        std::fputs("]}\n", output);
    }
};
} // namespace kairo_guest_profile
#endif
