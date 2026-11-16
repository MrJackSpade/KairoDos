// SPDX-License-Identifier: GPL-2.0-or-later
// KairoDos's MIDI port (kairo_midi_port.h).

#include "kairo_midi_port.h"

#include <cstddef>
#include <string>

#include "midi/midi.h"
#include "midi/private/midi_device.h"
#include "staging_bridge.h"

namespace {

// Whether the port is Staging's MIDI device now; and whether a guest byte is being parsed, whose
// messages already went to the port as written. Both on the emulation thread only.
bool active = false;
int raw_bytes = 0;

void send(const uint8_t* bytes, const size_t count)
{
	for (size_t i = 0; i < count; ++i) {
		kairo_staging_midi_byte(bytes[i]);
	}
}

class MidiDeviceKairoPort final : public MidiDevice {
public:
	MidiDeviceKairoPort()
	{
		active = true;
	}

	~MidiDeviceKairoPort() override
	{
		active = false;
	}

	std::string GetName() const override
	{
		return "port";
	}

	Type GetType() const override
	{
		return MidiDevice::Type::External;
	}

	// Staging's own messages; those it parsed from the guest's bytes went as written.
	void SendMidiMessage(const MidiMessage& msg) override
	{
		if (raw_bytes == 0) {
			send(msg.data.data(), MIDI_message_len_by_status[msg.status()]);
		}
	}

	void SendSysExMessage(uint8_t* sysex, const size_t len) override
	{
		if (raw_bytes == 0) {
			send(sysex, len);
		}
	}
};

} // namespace

std::unique_ptr<MidiDevice> KAIRO_CreateMidiPort()
{
	return std::make_unique<MidiDeviceKairoPort>();
}

KairoMidiRawByte::KairoMidiRawByte(const uint8_t data)
{
	if (active) {
		kairo_staging_midi_byte(data);
	}
	++raw_bytes;
}

KairoMidiRawByte::~KairoMidiRawByte()
{
	--raw_bytes;
}
