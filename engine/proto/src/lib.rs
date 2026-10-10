//! Patch and telemetry types: the single source of truth for the brain/engine boundary.
//!
//! Wire format: each message is a 4-byte big-endian length followed by a
//! MessagePack map. Clojure keywords travel as strings holding their full name
//! (`:pattern/set-steps` becomes `"pattern/set-steps"`).
//!
//! `malli_schema` renders the matching malli schemas for the brain. It is
//! written next to the types it mirrors, and a test checks the generated
//! file in the brain is current, so the two sides can't drift silently.

use std::fmt;

use serde::{Deserialize, Serialize};

/// Steps in one pattern row.
pub const PATTERN_STEPS: usize = 16;

/// Largest payload either side accepts, in bytes. Bigger frames are rejected
/// before anything is allocated for them.
pub const MAX_FRAME_BYTES: usize = 64 * 1024;

/// Bytes in the length prefix.
pub const HEADER_BYTES: usize = 4;

/// A batch of patches the brain sends after one song change.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Envelope {
    /// Song version these patches lead to. Lets the engine report what it plays
    /// and the brain resync from a full snapshot.
    pub epoch: u64,
    pub ops: Vec<Patch>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "op", deny_unknown_fields)]
pub enum Patch {
    /// Replace one row of a step pattern.
    #[serde(rename = "pattern/set-steps")]
    SetSteps {
        pattern: String,
        row: String,
        steps: [Step; PATTERN_STEPS],
    },
    /// Start or stop playback. Transport is engine state, not song data: it
    /// isn't saved or undone, and it doesn't advance the epoch.
    #[serde(rename = "transport/set")]
    SetTransport { playing: bool },
}

/// Messages from the engine to the brain, framed like patches.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "op", deny_unknown_fields)]
pub enum Telemetry {
    /// First message on every connection. `engine-id` is random per engine
    /// process, so the brain can tell a restarted engine from the one it left.
    #[serde(rename = "engine/hello")]
    Hello {
        #[serde(rename = "engine-id")]
        engine_id: u32,
        epoch: u64,
    },
    /// Where playback is, about 60 times a second. `epoch` is the last song
    /// version the engine applied.
    #[serde(rename = "engine/playhead")]
    Playhead {
        epoch: u64,
        playing: bool,
        bar: u64,
        step: u8,
    },
}

/// One step: 0 (off) or 1 (on) on the wire.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(try_from = "u8", into = "u8")]
pub struct Step(pub bool);

impl TryFrom<u8> for Step {
    type Error = &'static str;

    fn try_from(value: u8) -> Result<Self, Self::Error> {
        match value {
            0 => Ok(Step(false)),
            1 => Ok(Step(true)),
            _ => Err("a step must be 0 or 1"),
        }
    }
}

impl From<Step> for u8 {
    fn from(step: Step) -> u8 {
        u8::from(step.0)
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ProtoError {
    FrameTooLarge(usize),
    Decode(String),
    Encode(String),
}

impl fmt::Display for ProtoError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            ProtoError::FrameTooLarge(len) => {
                write!(f, "frame of {len} bytes exceeds {MAX_FRAME_BYTES}")
            }
            ProtoError::Decode(msg) => write!(f, "invalid patch: {msg}"),
            ProtoError::Encode(msg) => write!(f, "cannot encode patch: {msg}"),
        }
    }
}

impl std::error::Error for ProtoError {}

/// Payload length announced by a frame header.
pub fn payload_len(header: [u8; HEADER_BYTES]) -> Result<usize, ProtoError> {
    let len = usize::try_from(u32::from_be_bytes(header)).unwrap_or(usize::MAX);
    if len > MAX_FRAME_BYTES {
        return Err(ProtoError::FrameTooLarge(len));
    }
    Ok(len)
}

/// Decodes a patch frame payload (without its header).
pub fn decode(payload: &[u8]) -> Result<Envelope, ProtoError> {
    decode_as(payload)
}

/// Decodes a telemetry frame payload (without its header).
pub fn decode_telemetry(payload: &[u8]) -> Result<Telemetry, ProtoError> {
    decode_as(payload)
}

fn decode_as<T: serde::de::DeserializeOwned>(payload: &[u8]) -> Result<T, ProtoError> {
    if payload.len() > MAX_FRAME_BYTES {
        return Err(ProtoError::FrameTooLarge(payload.len()));
    }
    rmp_serde::from_slice(payload).map_err(|e| ProtoError::Decode(e.to_string()))
}

/// Encodes an envelope or telemetry message as a complete frame, header included.
pub fn encode_frame<T: Serialize>(message: &T) -> Result<Vec<u8>, ProtoError> {
    let payload =
        rmp_serde::to_vec_named(message).map_err(|e| ProtoError::Encode(e.to_string()))?;
    if payload.len() > MAX_FRAME_BYTES {
        return Err(ProtoError::FrameTooLarge(payload.len()));
    }
    let len = u32::try_from(payload.len()).map_err(|e| ProtoError::Encode(e.to_string()))?;
    let mut frame = Vec::with_capacity(HEADER_BYTES + payload.len());
    frame.extend_from_slice(&len.to_be_bytes());
    frame.extend_from_slice(&payload);
    Ok(frame)
}

/// The malli schemas for everything in this crate, as a Clojure source file.
/// Brain-side values use keywords where the wire has strings.
pub fn malli_schema() -> String {
    format!(
        r#";; GENERATED from engine/proto by `cargo run -p gorilla-proto --bin gen-malli`.
;; Do not edit. Change the Rust types and regenerate.
(ns gorilla.proto-schema)

(def max-frame-bytes {MAX_FRAME_BYTES})

(def pattern-steps {PATTERN_STEPS})

(def Patch
  [:multi {{:dispatch :op}}
   [:pattern/set-steps
    [:map {{:closed true}}
     [:op [:= :pattern/set-steps]]
     [:pattern :keyword]
     [:row :keyword]
     [:steps [:vector {{:min {PATTERN_STEPS} :max {PATTERN_STEPS}}} [:enum 0 1]]]]]
   [:transport/set
    [:map {{:closed true}}
     [:op [:= :transport/set]]
     [:playing :boolean]]]])

(def Envelope
  [:map {{:closed true}}
   [:epoch [:int {{:min 0}}]]
   [:ops [:vector Patch]]])

(def Telemetry
  [:multi {{:dispatch :op}}
   [:engine/hello
    [:map {{:closed true}}
     [:op [:= :engine/hello]]
     [:engine-id [:int {{:min 0 :max {u32_max}}}]]
     [:epoch [:int {{:min 0}}]]]]
   [:engine/playhead
    [:map {{:closed true}}
     [:op [:= :engine/playhead]]
     [:epoch [:int {{:min 0}}]]
     [:playing :boolean]
     [:bar [:int {{:min 0}}]]
     [:step [:int {{:min 0 :max {last_step}}}]]]]])
"#,
        u32_max = u32::MAX,
        last_step = PATTERN_STEPS - 1,
    )
}

#[cfg(test)]
mod tests {
    use std::collections::BTreeMap;

    use super::*;

    fn hat_steps() -> [Step; PATTERN_STEPS] {
        std::array::from_fn(|i| Step(i % 2 == 0))
    }

    fn envelope() -> Envelope {
        Envelope {
            epoch: 7,
            ops: vec![Patch::SetSteps {
                pattern: "drums".into(),
                row: "hat".into(),
                steps: hat_steps(),
            }],
        }
    }

    fn payload(frame: &[u8]) -> &[u8] {
        frame.get(HEADER_BYTES..).unwrap_or_default()
    }

    #[test]
    fn frames_round_trip() {
        let frame = encode_frame(&envelope()).unwrap_or_default();
        let header: [u8; HEADER_BYTES] = frame
            .get(..HEADER_BYTES)
            .and_then(|h| h.try_into().ok())
            .unwrap_or_default();
        assert_eq!(payload_len(header), Ok(frame.len() - HEADER_BYTES));
        assert_eq!(decode(payload(&frame)), Ok(envelope()));
    }

    /// Dynamic MessagePack values, standing in for what the brain's encoder sends.
    #[derive(Serialize)]
    #[serde(untagged)]
    enum Wire {
        Str(&'static str),
        Int(u64),
        Bool(bool),
        Ints(Vec<u8>),
        Maps(Vec<BTreeMap<&'static str, Wire>>),
    }

    fn brain_message(op: &'static str, steps: Vec<u8>) -> Vec<u8> {
        let op = BTreeMap::from([
            ("op", Wire::Str(op)),
            ("pattern", Wire::Str("drums")),
            ("row", Wire::Str("hat")),
            ("steps", Wire::Ints(steps)),
        ]);
        let msg = BTreeMap::from([("epoch", Wire::Int(7)), ("ops", Wire::Maps(vec![op]))]);
        rmp_serde::to_vec(&msg).unwrap_or_default()
    }

    fn hat_ints() -> Vec<u8> {
        hat_steps().iter().map(|s| u8::from(*s)).collect()
    }

    #[test]
    fn decodes_what_the_brain_sends() {
        let bytes = brain_message("pattern/set-steps", hat_ints());
        assert_eq!(decode(&bytes), Ok(envelope()));
    }

    #[test]
    fn decodes_transport() {
        let op = BTreeMap::from([
            ("op", Wire::Str("transport/set")),
            ("playing", Wire::Bool(false)),
        ]);
        let msg = BTreeMap::from([("epoch", Wire::Int(7)), ("ops", Wire::Maps(vec![op]))]);
        let bytes = rmp_serde::to_vec(&msg).unwrap_or_default();
        assert_eq!(
            decode(&bytes),
            Ok(Envelope {
                epoch: 7,
                ops: vec![Patch::SetTransport { playing: false }],
            })
        );
    }

    #[test]
    fn telemetry_round_trips() {
        for msg in [
            Telemetry::Hello {
                engine_id: u32::MAX,
                epoch: 3,
            },
            Telemetry::Playhead {
                epoch: 3,
                playing: false,
                bar: 12,
                step: 15,
            },
        ] {
            let frame = encode_frame(&msg).unwrap_or_default();
            assert_eq!(decode_telemetry(payload(&frame)), Ok(msg));
        }
    }

    #[test]
    fn rejects_bad_input() {
        let mut fifteen = hat_ints();
        fifteen.pop();
        let mut bad_step = hat_ints();
        bad_step.push(2);
        bad_step.remove(0);

        for bytes in [
            brain_message("pattern/set-stepz", hat_ints()),
            brain_message("pattern/set-steps", fifteen),
            brain_message("pattern/set-steps", bad_step),
            vec![],
            vec![0xc1],
            br#"{"epoch":1,"ops":[]}"#.to_vec(),
            vec![0; MAX_FRAME_BYTES + 1],
        ] {
            assert!(decode(&bytes).is_err(), "accepted {bytes:?}");
        }
        assert_eq!(
            payload_len([0xff; HEADER_BYTES]),
            Err(ProtoError::FrameTooLarge(u32::MAX as usize))
        );
    }

    #[test]
    fn generated_brain_schema_is_current() {
        let checked_in = include_str!("../../../brain/src/gorilla/proto_schema.cljc");
        assert_eq!(
            checked_in,
            malli_schema(),
            "run `cargo run -p gorilla-proto --bin gen-malli > ../brain/src/gorilla/proto_schema.cljc`"
        );
    }
}
