//! The engine's description of the song, kept on the control thread. Patches
//! edit it; edits that change its shape rebuild the graph from it.
//!
//! A row plays the channel with the same id, as in a channel rack: row
//! `"kick"` of every pattern triggers channel `"kick"`.

use crate::graph::{BarQueue, Channel, Graph, Pattern, Row, STEPS, Steps};
use crate::voice::{Builtin, Generator, Voice};

#[derive(Debug, Clone, PartialEq)]
pub enum GenSpec {
    Builtin(Builtin),
}

#[derive(Debug, Clone, PartialEq)]
pub struct ChannelSpec {
    pub id: String,
    pub generator: GenSpec,
}

#[derive(Debug, Clone, PartialEq)]
pub struct RowSpec {
    pub id: String,
    pub steps: Steps,
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
                        steps: *steps,
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

    /// Sets a row's steps, creating the pattern and row if needed. Returns
    /// true when that changed the model's shape.
    pub fn set_steps(&mut self, pattern: &str, row: &str, steps: Steps) -> bool {
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
                existing.steps = steps;
                false
            }
            None => {
                spec.rows.push(RowSpec {
                    id: row.into(),
                    steps,
                });
                true
            }
        }
    }

    /// Builds a graph for this model. `prev` is the model the current graph
    /// was built from; channels and rows found in both carry their state.
    pub fn build(&self, prev: &Model, sample_rate: f32) -> Result<Graph, String> {
        let channels = self
            .channels
            .iter()
            .map(|spec| Channel {
                generator: match &spec.generator {
                    GenSpec::Builtin(kind) => Generator::builtin(*kind, sample_rate),
                },
                voice: Voice::default(),
            })
            .collect();
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
                    steps: row.steps,
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

/// Converts wire steps to engine steps.
pub fn steps_from(wire: &[gorilla_proto::Step; STEPS]) -> Steps {
    wire.map(|s| s.0)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn default_drums_build() {
        let model = Model::default_drums();
        let graph = model.build(&Model::default(), 48_000.0);
        assert!(graph.is_ok_and(|g| g.channels.len() == 3 && g.patterns.len() == 1));
    }

    #[test]
    fn rows_need_a_channel() {
        let mut model = Model::default_drums();
        assert!(model.set_steps("drums", "cowbell", [true; STEPS]));
        assert!(model.build(&Model::default(), 48_000.0).is_err());
    }

    #[test]
    fn carry_maps_follow_ids() {
        let prev = Model::default_drums();
        let mut next = prev.clone();
        next.channels.reverse();
        next.set_steps("fills", "hat", [true; STEPS]);
        let graph = next.build(&prev, 48_000.0);
        assert!(graph.is_ok());
        let Ok(graph) = graph else { return };
        assert_eq!(graph.channel_carry, [Some(2), Some(1), Some(0)]);
        assert_eq!(
            graph.row_carry,
            [vec![Some((0, 0)), Some((0, 1)), Some((0, 2))], vec![None]]
        );
    }
}
