//! The engine's description of the song, kept on the control thread. Patches
//! edit it; edits that change its shape rebuild the graph from it.
//!
//! A row plays the channel with the same id, as in a channel rack: row
//! `"kick"` of every pattern triggers channel `"kick"`.

use std::path::PathBuf;

use crate::graph::{
    BarQueue, Channel, Graph, Hit, Hits, Pattern, Row, STEPS, Steps, hits_from_steps,
};
use crate::samples::SampleCache;
use crate::voice::{Builtin, Generator, Voice};

#[derive(Debug, Clone, PartialEq)]
pub enum GenSpec {
    Builtin(Builtin),
    Sampler(PathBuf),
}

impl GenSpec {
    pub fn from_wire(generator: &gorilla_proto::Gen) -> Result<Self, String> {
        match generator {
            gorilla_proto::Gen::Builtin { voice } => Builtin::from_name(voice)
                .map(GenSpec::Builtin)
                .ok_or_else(|| format!("unknown builtin voice {voice:?}")),
            gorilla_proto::Gen::Sampler { sample } => Ok(GenSpec::Sampler(sample.into())),
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct ChannelSpec {
    pub id: String,
    pub generator: GenSpec,
}

#[derive(Debug, Clone, PartialEq)]
pub struct RowSpec {
    pub id: String,
    pub hits: Hits,
}

#[derive(Debug, Clone, PartialEq)]
pub struct PatternSpec {
    pub id: String,
    pub rows: Vec<RowSpec>,
}

#[derive(Debug, Clone, Default, PartialEq)]
pub struct Model {
    pub channels: Vec<ChannelSpec>,
    pub patterns: Vec<PatternSpec>,
}

const X: bool = true;
const O: bool = false;

/// What the engine plays before any brain connects.
pub const DEFAULT_KICK: Steps = [X, O, O, O, X, O, O, O, X, O, O, O, X, O, O, O];
pub const DEFAULT_SNARE: Steps = [O, O, O, O, X, O, O, O, O, O, O, O, X, O, O, O];
pub const DEFAULT_HAT: Steps = [X, O, X, O, X, O, X, O, X, O, X, O, X, O, X, O];

impl Model {
    pub fn default_drums() -> Self {
        let builtins = [
            ("kick", Builtin::Kick, DEFAULT_KICK),
            ("snare", Builtin::Snare, DEFAULT_SNARE),
            ("hat", Builtin::Hat, DEFAULT_HAT),
        ];
        Model {
            channels: builtins
                .iter()
                .map(|(id, kind, _)| ChannelSpec {
                    id: (*id).into(),
                    generator: GenSpec::Builtin(*kind),
                })
                .collect(),
            patterns: vec![PatternSpec {
                id: "drums".into(),
                rows: builtins
                    .iter()
                    .map(|(id, _, steps)| RowSpec {
                        id: (*id).into(),
                        hits: hits_from_steps(steps),
                    })
                    .collect(),
            }],
        }
    }

    pub fn channel_index(&self, id: &str) -> Option<usize> {
        self.channels.iter().position(|c| c.id == id)
    }

    pub fn row_index(&self, pattern: &str, row: &str) -> Option<(usize, usize)> {
        let p = self.patterns.iter().position(|p| p.id == pattern)?;
        let r = self
            .patterns
            .get(p)?
            .rows
            .iter()
            .position(|r| r.id == row)?;
        Some((p, r))
    }

    /// Sets a row's bar, creating the pattern and row if needed. Returns
    /// true when that changed the model's shape.
    pub fn set_hits(&mut self, pattern: &str, row: &str, hits: Hits) -> bool {
        let p = match self.patterns.iter().position(|p| p.id == pattern) {
            Some(p) => p,
            None => {
                self.patterns.push(PatternSpec {
                    id: pattern.into(),
                    rows: Vec::new(),
                });
                self.patterns.len() - 1
            }
        };
        let Some(spec) = self.patterns.get_mut(p) else {
            return false;
        };
        match spec.rows.iter_mut().find(|r| r.id == row) {
            Some(existing) => {
                existing.hits = hits;
                false
            }
            None => {
                spec.rows.push(RowSpec {
                    id: row.into(),
                    hits,
                });
                true
            }
        }
    }

    /// Creates or changes a channel. Returns true when that changed anything.
    pub fn set_channel(&mut self, id: &str, generator: GenSpec) -> bool {
        match self.channels.iter_mut().find(|c| c.id == id) {
            Some(existing) if existing.generator == generator => false,
            Some(existing) => {
                existing.generator = generator;
                true
            }
            None => {
                self.channels.push(ChannelSpec {
                    id: id.into(),
                    generator,
                });
                true
            }
        }
    }

    /// Removes a channel and every row that plays it.
    pub fn remove_channel(&mut self, id: &str) -> bool {
        let before = self.channels.len();
        self.channels.retain(|c| c.id != id);
        let mut changed = self.channels.len() != before;
        for pattern in &mut self.patterns {
            let rows = pattern.rows.len();
            pattern.rows.retain(|r| r.id != id);
            changed |= pattern.rows.len() != rows;
        }
        changed
    }

    pub fn remove_row(&mut self, pattern: &str, row: &str) -> bool {
        self.patterns
            .iter_mut()
            .find(|p| p.id == pattern)
            .is_some_and(|p| {
                let before = p.rows.len();
                p.rows.retain(|r| r.id != row);
                p.rows.len() != before
            })
    }

    pub fn remove_pattern(&mut self, pattern: &str) -> bool {
        let before = self.patterns.len();
        self.patterns.retain(|p| p.id != pattern);
        self.patterns.len() != before
    }

    /// Builds a graph for this model. `prev` is the model the current graph
    /// was built from; channels and rows found in both carry their state.
    pub fn build(
        &self,
        prev: &Model,
        sample_rate: f32,
        samples: &mut SampleCache,
    ) -> Result<Graph, String> {
        let channels = self
            .channels
            .iter()
            .map(|spec| {
                Ok(Channel {
                    generator: match &spec.generator {
                        GenSpec::Builtin(kind) => Generator::builtin(*kind, sample_rate),
                        GenSpec::Sampler(path) => {
                            Generator::sampler(samples.get(path)?, sample_rate)
                        }
                    },
                    voice: Voice::default(),
                    gate: 0,
                })
            })
            .collect::<Result<_, String>>()?;
        let channel_carry = self
            .channels
            .iter()
            .map(|spec| {
                prev.channel_index(&spec.id).filter(|&i| {
                    prev.channels.get(i).map(|c| &c.generator) == Some(&spec.generator)
                })
            })
            .collect();

        let mut patterns = Vec::with_capacity(self.patterns.len());
        let mut row_carry = Vec::with_capacity(self.patterns.len());
        for pattern in &self.patterns {
            let mut rows = Vec::with_capacity(pattern.rows.len());
            let mut carries = Vec::with_capacity(pattern.rows.len());
            for row in &pattern.rows {
                let channel = self.channel_index(&row.id).ok_or_else(|| {
                    format!(
                        "row {:?} of pattern {:?} has no channel",
                        row.id, pattern.id
                    )
                })?;
                rows.push(Row {
                    channel,
                    hits: row.hits,
                    queue: BarQueue::default(),
                });
                carries.push(prev.row_index(&pattern.id, &row.id));
            }
            patterns.push(Pattern { rows });
            row_carry.push(carries);
        }

        Ok(Graph {
            channels,
            patterns,
            channel_carry,
            row_carry,
        })
    }
}

/// Converts wire steps to a bar of hits.
pub fn hits_from_wire_steps(wire: &[gorilla_proto::Step; STEPS]) -> Hits {
    hits_from_steps(&wire.map(|s| s.0))
}

/// Converts wire notes to a bar of hits. Two notes on one step: the later
/// one wins, since a channel plays one note at a time.
pub fn hits_from_notes(notes: &[gorilla_proto::Note]) -> Result<Hits, String> {
    if notes.len() > gorilla_proto::MAX_NOTES {
        return Err(format!("{} notes in one bar", notes.len()));
    }
    let mut hits: Hits = [None; STEPS];
    for note in notes {
        if note.len == 0 || note.len > gorilla_proto::MAX_NOTE_LEN {
            return Err(format!("note length {} out of range", note.len));
        }
        if note.pitch > 127 {
            return Err(format!("pitch {} out of range", note.pitch));
        }
        if !(0.0..=1.0).contains(&note.vel) {
            return Err(format!("velocity {} out of range", note.vel));
        }
        let slot = hits
            .get_mut(usize::from(note.t))
            .ok_or_else(|| format!("note at step {} is outside the bar", note.t))?;
        *slot = Some(Hit {
            pitch: note.pitch,
            vel: note.vel as f32,
            len: note.len,
        });
    }
    Ok(hits)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn default_drums_build() {
        let model = Model::default_drums();
        let graph = model.build(&Model::default(), 48_000.0, &mut SampleCache::default());
        assert!(graph.is_ok_and(|g| g.channels.len() == 3 && g.patterns.len() == 1));
    }

    #[test]
    fn removing_a_channel_removes_its_rows() {
        let mut model = Model::default_drums();
        assert!(model.remove_channel("hat"));
        assert_eq!(model.row_index("drums", "hat"), None);
        assert!(!model.remove_channel("hat"));
        assert!(
            model
                .build(&Model::default(), 48_000.0, &mut SampleCache::default())
                .is_ok()
        );
    }

    #[test]
    fn a_missing_sample_fails_the_build() {
        let mut model = Model::default_drums();
        model.set_channel("clap", GenSpec::Sampler("/no/such/clap.wav".into()));
        assert!(
            model
                .build(&Model::default(), 48_000.0, &mut SampleCache::default())
                .is_err()
        );
    }

    fn note(t: u8, len: u8, pitch: u8, vel: f64) -> gorilla_proto::Note {
        gorilla_proto::Note { t, len, pitch, vel }
    }

    #[test]
    fn notes_become_hits_and_bad_notes_are_refused() {
        let hits = hits_from_notes(&[note(0, 4, 36, 0.5), note(3, 1, 40, 1.0)]);
        assert!(hits.is_ok());
        let Ok(hits) = hits else { return };
        assert_eq!(
            hits.first().copied().flatten(),
            Some(Hit {
                pitch: 36,
                vel: 0.5,
                len: 4
            })
        );
        assert_eq!(hits.iter().flatten().count(), 2);
        for bad in [
            note(16, 1, 60, 1.0),
            note(0, 0, 60, 1.0),
            note(0, 65, 60, 1.0),
            note(0, 1, 128, 1.0),
            note(0, 1, 60, 1.5),
            note(0, 1, 60, f64::NAN),
        ] {
            assert!(
                hits_from_notes(std::slice::from_ref(&bad)).is_err(),
                "{bad:?}"
            );
        }
        assert!(hits_from_notes(&vec![note(0, 1, 60, 1.0); 17]).is_err());
    }

    #[test]
    fn rows_need_a_channel() {
        let mut model = Model::default_drums();
        assert!(model.set_hits("drums", "cowbell", hits_from_steps(&[true; STEPS])));
        assert!(
            model
                .build(&Model::default(), 48_000.0, &mut SampleCache::default())
                .is_err()
        );
    }

    #[test]
    fn carry_maps_follow_ids() {
        let prev = Model::default_drums();
        let mut next = prev.clone();
        next.channels.reverse();
        next.set_hits("fills", "hat", hits_from_steps(&[true; STEPS]));
        let graph = next.build(&prev, 48_000.0, &mut SampleCache::default());
        assert!(graph.is_ok());
        let Ok(graph) = graph else { return };
        assert_eq!(graph.channel_carry, [Some(2), Some(1), Some(0)]);
        assert_eq!(
            graph.row_carry,
            [vec![Some((0, 0)), Some((0, 1)), Some((0, 2))], vec![None]]
        );
    }
}
