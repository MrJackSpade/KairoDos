// SPDX-License-Identifier: GPL-2.0-or-later
// Standalone worker/Nuked ordering test, not the Staging port/mixer integration.
#include "kairo/ordered_worker.h"
extern "C" {
#include "opl3.h"
}
#include <array>
#include <chrono>
#include <cstdio>
#include <memory>
#include <stdexcept>
#include <vector>

struct Command { uint64_t sequence = 0; uint16_t reg = 0, frames = 0; uint8_t value = 0; };
struct Result { uint64_t sequence = 0; uint16_t frames = 0; std::array<int16_t, 128> pcm{}; };
struct Synth {
    opl3_chip chip{};
    explicit Synth() { OPL3_Reset(&chip, 49716); }
    Result process(const Command& command) {
        Result result{};
        result.sequence = command.sequence;
        result.frames = command.frames;
        if (command.frames) OPL3_GenerateStream(&chip, result.pcm.data(), command.frames);
        else OPL3_WriteRegBuffered(&chip, command.reg, command.value);
        return result;
    }
};

template<size_t Capacity>
static void trial(int mode, bool delayed) {
    Synth reference, actual;
    uint64_t issued = 0, consumed = 0, compared = 0, nonzero = 0;
    std::vector<Result> expected;
    kairo::OrderedWorker<Command, Result, Capacity, true> worker([&](const Command& c) {
        if (delayed && c.sequence % 127 == 0)
            std::this_thread::sleep_for(std::chrono::microseconds(50));
        return actual.process(c);
    });
    auto consume = [&](Result r) {
        if (consumed >= expected.size()) throw std::runtime_error("extra result");
        const auto& e = expected[consumed++];
        if (r.sequence != e.sequence || r.frames != e.frames)
            throw std::runtime_error("operation order mismatch");
        for (size_t i = 0; i < r.frames * 2u; ++i) {
            ++compared;
            nonzero += e.pcm[i] != 0;
            if (r.pcm[i] != e.pcm[i]) throw std::runtime_error("sample mismatch");
        }
    };
    auto submit = [&](uint16_t reg, uint8_t value, uint16_t frames = 0) {
        Command c{issued++, reg, frames, value};
        expected.push_back(reference.process(c));
        worker.submit(c, consume);
    };
    submit(0x105, mode ? 1 : 0);
    for (int bank = 0; bank < (mode ? 2 : 1); ++bank) {
        const int base = bank * 256;
        for (int slot : {0, 3}) {
            submit(base + 0x20 + slot, 1);
            submit(base + 0x40 + slot, slot ? 0 : 16);
            submit(base + 0x60 + slot, 0xf2);
            submit(base + 0x80 + slot, 0x24);
        }
        submit(base + 0xc0, bank ? 0x20 : 0x10);
        submit(base + 0xa0, 0x98);
        submit(base + 0xb0, 0x31);
    }
    // More than Nuked's buffered-write capacity, with repeated writes before
    // generation. Preserve its own overflow/delay behavior exactly.
    for (int i = 0; i < OPL_WRITEBUF_SIZE * 3; ++i) submit(0x40, i & 63);
    for (unsigned i = 0; i < 3000; ++i) {
        submit(0, 0, 1 + i % 64);
        const unsigned bank = mode && (i & 1) ? 256 : 0;
        submit(bank + 0xa0, (i * 17) & 255);
        if (i % 11 == 0) submit(bank + 0xb0, i % 22 ? 0x31 : 0x11);
        if (i % 31 == 0) submit(0xbd, i & 63); // rhythm, depth and key changes
        if (mode == 2 && i % 43 == 0) submit(0x104, i & 63); // four-op modes
        if (i % 47 == 0) worker.drain(consume); // drain then issue more work
    }
    submit(0, 0, 64);
    worker.drain(consume);
    if (consumed != issued || !nonzero) throw std::runtime_error("incomplete/silent test");
    const auto stats = worker.statistics();
    if (!stats.maxCommands || !stats.maxResults || stats.maxCommands > Capacity ||
        stats.maxResults > Capacity || (Capacity == 1 && !stats.submitDrains))
        throw std::runtime_error("queue bounds/pressure not exercised");
    std::printf("{\"capacity\":%zu,\"mode\":%d,\"delayed\":%s,\"operations\":%llu,\"comparedSamples\":%llu,\"nonzeroSamples\":%llu,\"mismatches\":0,\"maxCommands\":%zu,\"maxResults\":%zu,\"submitDrains\":%llu,\"submitWaits\":%llu}\n",
        Capacity, mode, delayed ? "true" : "false", (unsigned long long)issued,
        (unsigned long long)compared, (unsigned long long)nonzero,
        stats.maxCommands, stats.maxResults, (unsigned long long)stats.submitDrains,
        (unsigned long long)stats.submitWaits);
}

int main() {
    try {
        for (int mode = 0; mode < 3; ++mode) {
            trial<1>(mode, true);
            trial<4>(mode, true);
            trial<64>(mode, false);
        }
        // A worker exception must wake a waiting caller, not leave it blocked.
        bool caught = false;
        {
            kairo::OrderedWorker<int, int, 1> worker([](const int&) -> int {
                throw std::runtime_error("expected processor failure");
            });
            try { worker.submit(1, [](int) {}); worker.drain([](int) {}); }
            catch (const std::runtime_error&) { caught = true; }
        }
        if (!caught) throw std::runtime_error("worker error was lost");
        // Cancellation while output is full must join without requiring a consumer.
        for (int i = 0; i < 20; ++i) {
            kairo::OrderedWorker<int, int, 1> worker([](const int& c) { return c; });
            worker.submit(1, [](int) {});
            worker.submit(2, [](int) {});
            const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(5);
            while (worker.statistics().currentResults != 1) {
                if (std::chrono::steady_clock::now() > deadline)
                    throw std::runtime_error("output never filled before cancellation");
                std::this_thread::yield();
            }
        }
        puts("{\"exceptionPropagation\":true,\"cancellationTrials\":20}");
        return 0;
    } catch (const std::exception& e) {
        std::fprintf(stderr, "FAIL: %s\n", e.what());
        return 1;
    }
}
