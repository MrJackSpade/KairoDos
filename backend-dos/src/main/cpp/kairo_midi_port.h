// SPDX-License-Identifier: GPL-2.0-or-later
// KairoDos's MIDI port (KairoDos#150): Staging's "port" MIDI device on Android, the guest's MIDI out to the
// MIDI port of the device the player picked (the app's MIDI out, kairo/midi_bytes.h through the host's
// midi_byte callback). Staging-midi-port (tools/patches.py) creates it for mididevice=port and hands it
// every byte the guest writes first, as written, so running status stays as the guest sent it.
#pragma once

#include <cstdint>
#include <memory>

class MidiDevice;

// The device: it plays nothing itself; while it is Staging's MIDI device the guest's bytes go to the port.
std::unique_ptr<MidiDevice> KAIRO_CreateMidiPort();

// One byte the guest writes to its MIDI out (MIDI_RawOutByte, ahead of Staging's own parsing): to the port
// when it is the device. While the guard lives, the messages Staging makes of that byte are not sent to
// the port again; the messages Staging sends of its own (MIDI_Reset's All Notes Off) are.
struct KairoMidiRawByte {
	explicit KairoMidiRawByte(uint8_t data);
	~KairoMidiRawByte();
	KairoMidiRawByte(const KairoMidiRawByte&)            = delete;
	KairoMidiRawByte& operator=(const KairoMidiRawByte&) = delete;
};
