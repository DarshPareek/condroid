use byteorder::{ByteOrder, LittleEndian};
use thiserror::Error;

/// Magic identifier for Condroid packets ("CD" -> 0x4344)
pub const PROTOCOL_MAGIC: u16 = 0x4344;

/// Current protocol wire version
pub const PROTOCOL_VERSION: u8 = 1;

/// Size of the ControllerState binary packet in bytes
pub const STATE_PACKET_SIZE: usize = 31;

/// Size of the Ping binary packet in bytes
pub const PING_PACKET_SIZE: usize = 12;

/// Size of the Pong binary packet in bytes
pub const PONG_PACKET_SIZE: usize = 16;

/// Size of the Rumble binary packet in bytes
pub const RUMBLE_PACKET_SIZE: usize = 8;

/// Size of the SlotInfo binary packet in bytes
pub const SLOT_INFO_PACKET_SIZE: usize = 6;

#[derive(Debug, Error, PartialEq, Eq)]
pub enum ProtocolError {
    #[error("Packet is too short: expected at least {expected} bytes, got {actual}")]
    PacketTooShort { expected: usize, actual: usize },

    #[error("Invalid magic byte sequence: expected 0x{expected:04x}, got 0x{actual:04x}")]
    InvalidMagic { expected: u16, actual: u16 },

    #[error("Unsupported protocol version: expected {expected}, got {actual}")]
    UnsupportedVersion { expected: u8, actual: u8 },

    #[error("Unknown or invalid packet type: 0x{0:02x}")]
    UnknownPacketType(u8),

    #[error("Checksum mismatch: expected 0x{expected:02x}, got 0x{actual:02x}")]
    ChecksumMismatch { expected: u8, actual: u8 },

    #[error("Buffer too small for serialization: provided {provided}, need {required}")]
    BufferTooSmall { provided: usize, required: usize },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum PacketType {
    State = 0x01,
    Ping = 0x02,
    Pong = 0x03,
    Rumble = 0x04,
    Disconnect = 0x05,
    SlotInfo = 0x06,
}

impl TryFrom<u8> for PacketType {
    type Error = ProtocolError;

    fn try_from(val: u8) -> Result<Self, Self::Error> {
        match val {
            0x01 => Ok(PacketType::State),
            0x02 => Ok(PacketType::Ping),
            0x03 => Ok(PacketType::Pong),
            0x04 => Ok(PacketType::Rumble),
            0x05 => Ok(PacketType::Disconnect),
            0x06 => Ok(PacketType::SlotInfo),
            unknown => Err(ProtocolError::UnknownPacketType(unknown)),
        }
    }
}

/// Bitmask representing button states on a standard game controller
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct Buttons(pub u16);

impl Buttons {
    pub const BTN_A: u16 = 1 << 0;
    pub const BTN_B: u16 = 1 << 1;
    pub const BTN_X: u16 = 1 << 2;
    pub const BTN_Y: u16 = 1 << 3;
    pub const BTN_LB: u16 = 1 << 4;
    pub const BTN_RB: u16 = 1 << 5;
    pub const BTN_SELECT: u16 = 1 << 6;
    pub const BTN_START: u16 = 1 << 7;
    pub const BTN_GUIDE: u16 = 1 << 8;
    pub const BTN_THUMB_L: u16 = 1 << 9;
    pub const BTN_THUMB_R: u16 = 1 << 10;
    pub const DPAD_UP: u16 = 1 << 11;
    pub const DPAD_DOWN: u16 = 1 << 12;
    pub const DPAD_LEFT: u16 = 1 << 13;
    pub const DPAD_RIGHT: u16 = 1 << 14;

    #[inline(always)]
    pub const fn empty() -> Self {
        Self(0)
    }

    #[inline(always)]
    pub fn is_set(&self, mask: u16) -> bool {
        (self.0 & mask) != 0
    }

    #[inline(always)]
    pub fn set(&mut self, mask: u16, pressed: bool) {
        if pressed {
            self.0 |= mask;
        } else {
            self.0 &= !mask;
        }
    }
}

/// 20-30 byte packed controller state update sent at high frequency (120 - 1000 Hz)
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct ControllerState {
    pub seq: u32,
    pub timestamp_ms: u32,
    pub buttons: Buttons,
    pub left_trigger: u8,
    pub right_trigger: u8,
    pub left_stick_x: i16,
    pub left_stick_y: i16,
    pub right_stick_x: i16,
    pub right_stick_y: i16,
    pub gyro_x: i16,
    pub gyro_y: i16,
    pub gyro_z: i16,
}

impl ControllerState {
    /// Compute simple CRC8 checksum over bytes 0..30
    fn compute_checksum(buf: &[u8]) -> u8 {
        let mut crc: u8 = 0xFF;
        for &byte in buf {
            crc ^= byte;
            for _ in 0..8 {
                if (crc & 0x80) != 0 {
                    crc = (crc << 1) ^ 0x07;
                } else {
                    crc <<= 1;
                }
            }
        }
        crc
    }

    /// Serializes this controller state into a fixed 31-byte slice
    pub fn serialize(&self, out: &mut [u8]) -> Result<usize, ProtocolError> {
        if out.len() < STATE_PACKET_SIZE {
            return Err(ProtocolError::BufferTooSmall {
                provided: out.len(),
                required: STATE_PACKET_SIZE,
            });
        }

        LittleEndian::write_u16(&mut out[0..2], PROTOCOL_MAGIC);
        out[2] = PROTOCOL_VERSION;
        out[3] = PacketType::State as u8;
        LittleEndian::write_u32(&mut out[4..8], self.seq);
        LittleEndian::write_u32(&mut out[8..12], self.timestamp_ms);
        LittleEndian::write_u16(&mut out[12..14], self.buttons.0);
        out[14] = self.left_trigger;
        out[15] = self.right_trigger;
        LittleEndian::write_i16(&mut out[16..18], self.left_stick_x);
        LittleEndian::write_i16(&mut out[18..20], self.left_stick_y);
        LittleEndian::write_i16(&mut out[20..22], self.right_stick_x);
        LittleEndian::write_i16(&mut out[22..24], self.right_stick_y);
        LittleEndian::write_i16(&mut out[24..26], self.gyro_x);
        LittleEndian::write_i16(&mut out[26..28], self.gyro_y);
        LittleEndian::write_i16(&mut out[28..30], self.gyro_z);

        out[30] = Self::compute_checksum(&out[0..30]);
        Ok(STATE_PACKET_SIZE)
    }

    /// Deserializes a controller state from a slice
    pub fn deserialize(buf: &[u8]) -> Result<Self, ProtocolError> {
        if buf.len() < STATE_PACKET_SIZE {
            return Err(ProtocolError::PacketTooShort {
                expected: STATE_PACKET_SIZE,
                actual: buf.len(),
            });
        }

        let magic = LittleEndian::read_u16(&buf[0..2]);
        if magic != PROTOCOL_MAGIC {
            return Err(ProtocolError::InvalidMagic {
                expected: PROTOCOL_MAGIC,
                actual: magic,
            });
        }

        let version = buf[2];
        if version != PROTOCOL_VERSION {
            return Err(ProtocolError::UnsupportedVersion {
                expected: PROTOCOL_VERSION,
                actual: version,
            });
        }

        let pkt_type = PacketType::try_from(buf[3])?;
        if pkt_type != PacketType::State {
            return Err(ProtocolError::UnknownPacketType(buf[3]));
        }

        let expected_checksum = Self::compute_checksum(&buf[0..30]);
        let actual_checksum = buf[30];
        if expected_checksum != actual_checksum {
            return Err(ProtocolError::ChecksumMismatch {
                expected: expected_checksum,
                actual: actual_checksum,
            });
        }

        let seq = LittleEndian::read_u32(&buf[4..8]);
        let timestamp_ms = LittleEndian::read_u32(&buf[8..12]);
        let buttons = Buttons(LittleEndian::read_u16(&buf[12..14]));
        let left_trigger = buf[14];
        let right_trigger = buf[15];
        let left_stick_x = LittleEndian::read_i16(&buf[16..18]);
        let left_stick_y = LittleEndian::read_i16(&buf[18..20]);
        let right_stick_x = LittleEndian::read_i16(&buf[20..22]);
        let right_stick_y = LittleEndian::read_i16(&buf[22..24]);
        let gyro_x = LittleEndian::read_i16(&buf[24..26]);
        let gyro_y = LittleEndian::read_i16(&buf[26..28]);
        let gyro_z = LittleEndian::read_i16(&buf[28..30]);

        Ok(Self {
            seq,
            timestamp_ms,
            buttons,
            left_trigger,
            right_trigger,
            left_stick_x,
            left_stick_y,
            right_stick_x,
            right_stick_y,
            gyro_x,
            gyro_y,
            gyro_z,
        })
    }
}

/// Ping packet for RTT measurement
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PingPacket {
    pub seq: u32,
    pub timestamp_ms: u32,
}

impl PingPacket {
    pub fn serialize(&self, out: &mut [u8]) -> Result<usize, ProtocolError> {
        if out.len() < PING_PACKET_SIZE {
            return Err(ProtocolError::BufferTooSmall {
                provided: out.len(),
                required: PING_PACKET_SIZE,
            });
        }
        LittleEndian::write_u16(&mut out[0..2], PROTOCOL_MAGIC);
        out[2] = PROTOCOL_VERSION;
        out[3] = PacketType::Ping as u8;
        LittleEndian::write_u32(&mut out[4..8], self.seq);
        LittleEndian::write_u32(&mut out[8..12], self.timestamp_ms);
        Ok(PING_PACKET_SIZE)
    }

    pub fn deserialize(buf: &[u8]) -> Result<Self, ProtocolError> {
        if buf.len() < PING_PACKET_SIZE {
            return Err(ProtocolError::PacketTooShort {
                expected: PING_PACKET_SIZE,
                actual: buf.len(),
            });
        }
        let magic = LittleEndian::read_u16(&buf[0..2]);
        if magic != PROTOCOL_MAGIC {
            return Err(ProtocolError::InvalidMagic {
                expected: PROTOCOL_MAGIC,
                actual: magic,
            });
        }
        if buf[2] != PROTOCOL_VERSION {
            return Err(ProtocolError::UnsupportedVersion {
                expected: PROTOCOL_VERSION,
                actual: buf[2],
            });
        }
        if PacketType::try_from(buf[3])? != PacketType::Ping {
            return Err(ProtocolError::UnknownPacketType(buf[3]));
        }
        let seq = LittleEndian::read_u32(&buf[4..8]);
        let timestamp_ms = LittleEndian::read_u32(&buf[8..12]);
        Ok(Self { seq, timestamp_ms })
    }
}

/// Pong packet responding to Ping
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PongPacket {
    pub seq: u32,
    pub client_timestamp_ms: u32,
    pub server_timestamp_ms: u32,
}

impl PongPacket {
    pub fn serialize(&self, out: &mut [u8]) -> Result<usize, ProtocolError> {
        if out.len() < PONG_PACKET_SIZE {
            return Err(ProtocolError::BufferTooSmall {
                provided: out.len(),
                required: PONG_PACKET_SIZE,
            });
        }
        LittleEndian::write_u16(&mut out[0..2], PROTOCOL_MAGIC);
        out[2] = PROTOCOL_VERSION;
        out[3] = PacketType::Pong as u8;
        LittleEndian::write_u32(&mut out[4..8], self.seq);
        LittleEndian::write_u32(&mut out[8..12], self.client_timestamp_ms);
        LittleEndian::write_u32(&mut out[12..16], self.server_timestamp_ms);
        Ok(PONG_PACKET_SIZE)
    }

    pub fn deserialize(buf: &[u8]) -> Result<Self, ProtocolError> {
        if buf.len() < PONG_PACKET_SIZE {
            return Err(ProtocolError::PacketTooShort {
                expected: PONG_PACKET_SIZE,
                actual: buf.len(),
            });
        }
        let magic = LittleEndian::read_u16(&buf[0..2]);
        if magic != PROTOCOL_MAGIC {
            return Err(ProtocolError::InvalidMagic {
                expected: PROTOCOL_MAGIC,
                actual: magic,
            });
        }
        if buf[2] != PROTOCOL_VERSION {
            return Err(ProtocolError::UnsupportedVersion {
                expected: PROTOCOL_VERSION,
                actual: buf[2],
            });
        }
        if PacketType::try_from(buf[3])? != PacketType::Pong {
            return Err(ProtocolError::UnknownPacketType(buf[3]));
        }
        let seq = LittleEndian::read_u32(&buf[4..8]);
        let client_timestamp_ms = LittleEndian::read_u32(&buf[8..12]);
        let server_timestamp_ms = LittleEndian::read_u32(&buf[12..16]);
        Ok(Self {
            seq,
            client_timestamp_ms,
            server_timestamp_ms,
        })
    }
}

/// Rumble / Haptic Feedback packet (Host -> Client)
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct RumblePacket {
    pub weak_magnitude: u8,   // High-frequency rumble motor (0-255)
    pub strong_magnitude: u8, // Low-frequency rumble motor (0-255)
    pub duration_ms: u16,     // Duration of vibration
}

impl RumblePacket {
    pub fn serialize(&self, out: &mut [u8]) -> Result<usize, ProtocolError> {
        if out.len() < RUMBLE_PACKET_SIZE {
            return Err(ProtocolError::BufferTooSmall {
                provided: out.len(),
                required: RUMBLE_PACKET_SIZE,
            });
        }
        LittleEndian::write_u16(&mut out[0..2], PROTOCOL_MAGIC);
        out[2] = PROTOCOL_VERSION;
        out[3] = PacketType::Rumble as u8;
        out[4] = self.weak_magnitude;
        out[5] = self.strong_magnitude;
        LittleEndian::write_u16(&mut out[6..8], self.duration_ms);
        Ok(RUMBLE_PACKET_SIZE)
    }

    pub fn deserialize(buf: &[u8]) -> Result<Self, ProtocolError> {
        if buf.len() < RUMBLE_PACKET_SIZE {
            return Err(ProtocolError::PacketTooShort {
                expected: RUMBLE_PACKET_SIZE,
                actual: buf.len(),
            });
        }
        let magic = LittleEndian::read_u16(&buf[0..2]);
        if magic != PROTOCOL_MAGIC {
            return Err(ProtocolError::InvalidMagic {
                expected: PROTOCOL_MAGIC,
                actual: magic,
            });
        }
        if buf[2] != PROTOCOL_VERSION {
            return Err(ProtocolError::UnsupportedVersion {
                expected: PROTOCOL_VERSION,
                actual: buf[2],
            });
        }
        if PacketType::try_from(buf[3])? != PacketType::Rumble {
            return Err(ProtocolError::UnknownPacketType(buf[3]));
        }
        let weak_magnitude = buf[4];
        let strong_magnitude = buf[5];
        let duration_ms = LittleEndian::read_u16(&buf[6..8]);
        Ok(Self {
            weak_magnitude,
            strong_magnitude,
            duration_ms,
        })
    }
}

/// Slot Information packet (Host -> Client)
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct SlotInfoPacket {
    pub player_slot: u8, // 1-indexed (e.g. 1 for Player 1, 2 for Player 2)
    pub total_slots: u8, // Maximum available slots (e.g. 4)
}

impl SlotInfoPacket {
    pub fn serialize(&self, out: &mut [u8]) -> Result<usize, ProtocolError> {
        if out.len() < SLOT_INFO_PACKET_SIZE {
            return Err(ProtocolError::BufferTooSmall {
                provided: out.len(),
                required: SLOT_INFO_PACKET_SIZE,
            });
        }
        LittleEndian::write_u16(&mut out[0..2], PROTOCOL_MAGIC);
        out[2] = PROTOCOL_VERSION;
        out[3] = PacketType::SlotInfo as u8;
        out[4] = self.player_slot;
        out[5] = self.total_slots;
        Ok(SLOT_INFO_PACKET_SIZE)
    }

    pub fn deserialize(buf: &[u8]) -> Result<Self, ProtocolError> {
        if buf.len() < SLOT_INFO_PACKET_SIZE {
            return Err(ProtocolError::PacketTooShort {
                expected: SLOT_INFO_PACKET_SIZE,
                actual: buf.len(),
            });
        }
        let magic = LittleEndian::read_u16(&buf[0..2]);
        if magic != PROTOCOL_MAGIC {
            return Err(ProtocolError::InvalidMagic {
                expected: PROTOCOL_MAGIC,
                actual: magic,
            });
        }
        if buf[2] != PROTOCOL_VERSION {
            return Err(ProtocolError::UnsupportedVersion {
                expected: PROTOCOL_VERSION,
                actual: buf[2],
            });
        }
        if PacketType::try_from(buf[3])? != PacketType::SlotInfo {
            return Err(ProtocolError::UnknownPacketType(buf[3]));
        }
        Ok(Self {
            player_slot: buf[4],
            total_slots: buf[5],
        })
    }
}

/// Generic high-level packet enumeration
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Packet {
    State(ControllerState),
    Ping(PingPacket),
    Pong(PongPacket),
    Rumble(RumblePacket),
    Disconnect,
    SlotInfo(SlotInfoPacket),
}

impl Packet {
    pub fn parse(buf: &[u8]) -> Result<Self, ProtocolError> {
        if buf.len() < 4 {
            return Err(ProtocolError::PacketTooShort {
                expected: 4,
                actual: buf.len(),
            });
        }
        let magic = LittleEndian::read_u16(&buf[0..2]);
        if magic != PROTOCOL_MAGIC {
            return Err(ProtocolError::InvalidMagic {
                expected: PROTOCOL_MAGIC,
                actual: magic,
            });
        }
        let version = buf[2];
        if version != PROTOCOL_VERSION {
            return Err(ProtocolError::UnsupportedVersion {
                expected: PROTOCOL_VERSION,
                actual: version,
            });
        }
        match PacketType::try_from(buf[3])? {
            PacketType::State => Ok(Packet::State(ControllerState::deserialize(buf)?)),
            PacketType::Ping => Ok(Packet::Ping(PingPacket::deserialize(buf)?)),
            PacketType::Pong => Ok(Packet::Pong(PongPacket::deserialize(buf)?)),
            PacketType::Rumble => Ok(Packet::Rumble(RumblePacket::deserialize(buf)?)),
            PacketType::Disconnect => Ok(Packet::Disconnect),
            PacketType::SlotInfo => Ok(Packet::SlotInfo(SlotInfoPacket::deserialize(buf)?)),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_buttons_bitmask() {
        let mut buttons = Buttons::empty();
        assert!(!buttons.is_set(Buttons::BTN_A));
        assert!(!buttons.is_set(Buttons::DPAD_UP));

        buttons.set(Buttons::BTN_A, true);
        buttons.set(Buttons::DPAD_UP, true);
        assert!(buttons.is_set(Buttons::BTN_A));
        assert!(buttons.is_set(Buttons::DPAD_UP));
        assert!(!buttons.is_set(Buttons::BTN_B));

        buttons.set(Buttons::BTN_A, false);
        assert!(!buttons.is_set(Buttons::BTN_A));
        assert!(buttons.is_set(Buttons::DPAD_UP));
    }

    #[test]
    fn test_controller_state_roundtrip() {
        let mut buttons = Buttons::empty();
        buttons.set(Buttons::BTN_A, true);
        buttons.set(Buttons::BTN_RB, true);
        buttons.set(Buttons::DPAD_LEFT, true);

        let original = ControllerState {
            seq: 42,
            timestamp_ms: 123456,
            buttons,
            left_trigger: 128,
            right_trigger: 255,
            left_stick_x: -16000,
            left_stick_y: 20000,
            right_stick_x: 32767,
            right_stick_y: -32768,
            gyro_x: 10,
            gyro_y: -20,
            gyro_z: 30,
        };

        let mut buf = [0u8; 64];
        let bytes_written = original.serialize(&mut buf).expect("serialize should succeed");
        assert_eq!(bytes_written, STATE_PACKET_SIZE);

        let deserialized = ControllerState::deserialize(&buf[..bytes_written])
            .expect("deserialize should succeed");
        assert_eq!(original, deserialized);
    }

    #[test]
    fn test_checksum_corruption_detection() {
        let original = ControllerState {
            seq: 10,
            timestamp_ms: 500,
            ..Default::default()
        };
        let mut buf = [0u8; STATE_PACKET_SIZE];
        original.serialize(&mut buf).unwrap();

        // Corrupt a single byte in the payload
        buf[5] ^= 0x01;
        let res = ControllerState::deserialize(&buf);
        assert!(matches!(res, Err(ProtocolError::ChecksumMismatch { .. })));
    }

    #[test]
    fn test_ping_pong_roundtrip() {
        let ping = PingPacket {
            seq: 101,
            timestamp_ms: 9999,
        };
        let mut buf = [0u8; 32];
        let n = ping.serialize(&mut buf).unwrap();
        assert_eq!(n, PING_PACKET_SIZE);

        let parsed = PingPacket::deserialize(&buf[..n]).unwrap();
        assert_eq!(ping, parsed);

        let pong = PongPacket {
            seq: 101,
            client_timestamp_ms: 9999,
            server_timestamp_ms: 10002,
        };
        let n2 = pong.serialize(&mut buf).unwrap();
        assert_eq!(n2, PONG_PACKET_SIZE);

        let parsed_pong = PongPacket::deserialize(&buf[..n2]).unwrap();
        assert_eq!(pong, parsed_pong);
    }

    #[test]
    fn test_rumble_roundtrip() {
        let rumble = RumblePacket {
            weak_magnitude: 200,
            strong_magnitude: 100,
            duration_ms: 350,
        };
        let mut buf = [0u8; 32];
        let n = rumble.serialize(&mut buf).unwrap();
        assert_eq!(n, RUMBLE_PACKET_SIZE);

        let parsed = RumblePacket::deserialize(&buf[..n]).unwrap();
        assert_eq!(rumble, parsed);
    }

    #[test]
    fn test_slot_info_roundtrip() {
        let slot_info = SlotInfoPacket {
            player_slot: 2,
            total_slots: 4,
        };
        let mut buf = [0u8; 16];
        let n = slot_info.serialize(&mut buf).unwrap();
        assert_eq!(n, SLOT_INFO_PACKET_SIZE);

        let parsed = SlotInfoPacket::deserialize(&buf[..n]).unwrap();
        assert_eq!(slot_info, parsed);

        let pkt = Packet::parse(&buf[..n]).unwrap();
        assert_eq!(pkt, Packet::SlotInfo(slot_info));
    }
}
