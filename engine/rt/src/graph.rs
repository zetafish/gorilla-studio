//! The song graph the audio thread plays: channels (a generator and its voice)
//! and patterns (rows of steps that trigger channels).
//!
//! A graph is built on the control thread and handed to the audio thread
//! whole, which swaps it in at a buffer boundary. Channels and rows that exist
//! in both the old and the new graph carry their state across, so ringing
//! voices and queued bars survive a swap.

use crate::voice::{Ctx, Generator, Voice};

pub const STEPS: usize = 16;

/// On/off steps: the shape of a drum row, and how tests write rows.
pub type Steps = [bool; STEPS];

/// One note starting on a step.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Hit {
    pub pitch: u8,
    /// 0..1.
    pub vel: f32,
    /// Steps the note is held for; one-shot generators ignore it.
    pub len: u8,
}

/// What a step row plays: middle C, full velocity, one step.
pub const STEP_HIT: Hit = Hit {
    pitch: 60,
    vel: 1.0,
    len: 1,
};

/// A row's bar: at most one note starting on each step.
pub type Hits = [Option<Hit>; STEPS];

pub fn hits_from_steps(steps: &Steps) -> Hits {
    steps.map(|on| on.then_some(STEP_HIT))
}

#[cfg(test)]
pub fn steps_of(hits: &Hits) -> Steps {
    hits.map(|h| h.is_some())
}

/// Upcoming bars a row can hold. More than the brain's lookahead needs.
pub const QUEUED_BARS: usize = 4;

#[derive(Debug, PartialEq)]
pub struct Channel {
    pub generator: Generator,
    pub voice: Voice,
    /// Steps until the held note is released; 0 when nothing is held.
    pub gate: u16,
}

#[derive(Debug, PartialEq)]
pub struct Row {
    /// Index into `Graph::channels`.
    pub channel: usize,
    pub hits: Hits,
    pub queue: BarQueue,
}

#[derive(Debug, PartialEq)]
pub struct Pattern {
    pub rows: Vec<Row>,
    /// Whether the playlist has this pattern playing in the current bar.
    pub active: bool,
}

/// A pattern placed in the arrangement, in bars.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Clip {
    pub pattern: usize,
    pub start: u64,
    pub len: u64,
}

#[derive(Debug, Default, PartialEq)]
pub struct Graph {
    pub channels: Vec<Channel>,
    pub patterns: Vec<Pattern>,
    /// For each channel, its index in the graph this one replaces, when its
    /// voice should keep playing.
    pub channel_carry: Vec<Option<usize>>,
    /// For each pattern and row, its (pattern, row) index in the graph this
    /// one replaces.
    pub row_carry: Vec<Vec<Option<(usize, usize)>>>,
    /// The arrangement. Empty: every pattern loops.
    pub clips: Vec<Clip>,
    /// Bars up to the end of the last clip.
    pub song_bars: u64,
    pub looping: bool,
}

impl Graph {
    /// Takes ringing voices, steps and queued bars from `old`.
    pub fn carry_from(&mut self, old: &Graph) {
        for (channel, carry) in self.channels.iter_mut().zip(&self.channel_carry) {
            if let Some(old_channel) = carry.and_then(|i| old.channels.get(i)) {
                channel.voice = old_channel.voice;
                channel.gate = old_channel.gate;
            }
        }
        for (pattern, carries) in self.patterns.iter_mut().zip(&self.row_carry) {
            for (row, carry) in pattern.rows.iter_mut().zip(carries) {
                if let Some(old_row) = carry.and_then(|(p, r)| old.row(p, r)) {
                    row.hits = old_row.hits;
                    row.queue = old_row.queue;
                }
            }
        }
    }

    pub fn row(&self, pattern: usize, row: usize) -> Option<&Row> {
        self.patterns.get(pattern)?.rows.get(row)
    }

    pub fn row_mut(&mut self, pattern: usize, row: usize) -> Option<&mut Row> {
        self.patterns.get_mut(pattern)?.rows.get_mut(row)
    }

    /// True when the arrangement has run out at `bar` and doesn't loop.
    pub fn ended(&self, bar: u64) -> bool {
        !self.clips.is_empty() && !self.looping && bar >= self.song_bars
    }

    /// Marks which patterns the arrangement plays in `bar`.
    pub fn update_active(&mut self, bar: u64) {
        if self.clips.is_empty() {
            for pattern in &mut self.patterns {
                pattern.active = true;
            }
            return;
        }
        let pos = if self.looping && self.song_bars > 0 {
            bar % self.song_bars
        } else {
            bar
        };
        for (i, pattern) in self.patterns.iter_mut().enumerate() {
            pattern.active = self
                .clips
                .iter()
                .any(|c| c.pattern == i && c.start <= pos && pos < c.start.saturating_add(c.len));
        }
    }

    pub fn clear_queues(&mut self) {
        for row in self.patterns.iter_mut().flat_map(|p| p.rows.iter_mut()) {
            row.queue.clear();
        }
    }

    /// Releases notes whose length has run out, then triggers every channel
    /// with a hit on `step`. At a bar line, queued bars for `bar` replace
    /// their rows first.
    pub fn trigger_step(&mut self, step: usize, bar: Option<u64>) {
        let Graph {
            channels, patterns, ..
        } = self;
        for channel in channels.iter_mut() {
            if channel.gate > 0 {
                channel.gate -= 1;
                if channel.gate == 0 {
                    channel.generator.release(&mut channel.voice);
                }
            }
        }
        for pattern in patterns.iter_mut() {
            let active = pattern.active;
            for row in pattern.rows.iter_mut() {
                // Queued bars are taken even when the pattern is silent, so
                // a live row stays in step with the bar count.
                if let Some(hits) = bar.and_then(|bar| row.queue.take(bar)) {
                    row.hits = hits;
                }
                if !active {
                    continue;
                }
                if let Some(Some(hit)) = row.hits.get(step)
                    && let Some(channel) = channels.get_mut(row.channel)
                {
                    channel
                        .generator
                        .trigger(&mut channel.voice, hit.pitch, hit.vel);
                    channel.gate = u16::from(hit.len.max(1));
                }
            }
        }
    }

    /// Sum of all channels for one sample.
    pub fn next_sample(&mut self, ctx: &mut Ctx) -> f32 {
        self.channels
            .iter_mut()
            .map(|c| c.generator.next(&mut c.voice, ctx))
            .sum()
    }
}

/// Fixed-size queue of upcoming bars for one row.
#[derive(Debug, Clone, Copy, Default, PartialEq)]
pub struct BarQueue([Option<(u64, Hits)>; QUEUED_BARS]);

impl BarQueue {
    /// Queues `steps` for `bar`, replacing any entry for the same bar and
    /// dropping entries before `earliest`. When full, the furthest bar gives
    /// way to a nearer one.
    pub fn insert(&mut self, bar: u64, hits: Hits, earliest: u64) {
        if bar < earliest {
            return;
        }
        for slot in &mut self.0 {
            if slot.is_some_and(|(b, _)| b < earliest) {
                *slot = None;
            }
        }
        if let Some(slot) = self.0.iter_mut().find(|s| s.is_some_and(|(b, _)| b == bar)) {
            *slot = Some((bar, hits));
        } else if let Some(slot) = self.0.iter_mut().find(|s| s.is_none()) {
            *slot = Some((bar, hits));
        } else if let Some(slot) = self
            .0
            .iter_mut()
            .max_by_key(|s| s.map_or(0, |(b, _)| b))
            .filter(|s| s.is_some_and(|(b, _)| b > bar))
        {
            *slot = Some((bar, hits));
        }
    }

    /// Removes and returns the steps for `bar`, dropping anything older.
    pub fn take(&mut self, bar: u64) -> Option<Hits> {
        let mut found = None;
        for slot in &mut self.0 {
            match *slot {
                Some((b, hits)) if b == bar => {
                    found = Some(hits);
                    *slot = None;
                }
                Some((b, _)) if b < bar => *slot = None,
                _ => {}
            }
        }
        found
    }

    pub fn clear(&mut self) {
        self.0 = [None; QUEUED_BARS];
    }

    #[cfg(test)]
    pub fn bars(&self) -> Vec<u64> {
        let mut bars: Vec<u64> = self.0.iter().flatten().map(|(b, _)| *b).collect();
        bars.sort_unstable();
        bars
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const ALL: Hits = [Some(STEP_HIT); STEPS];

    #[test]
    fn a_full_queue_keeps_the_nearest_bars() {
        let mut queue = BarQueue::default();
        for bar in [10, 9, 8, 7, 6, 5] {
            queue.insert(bar, ALL, 0);
        }
        assert_eq!(queue.bars(), [5, 6, 7, 8]);
        assert_eq!(queue.take(7), Some(ALL));
        assert_eq!(queue.bars(), [8], "5 and 6 were stale");
    }

    #[test]
    fn inserting_drops_bars_before_earliest() {
        let mut queue = BarQueue::default();
        queue.insert(3, ALL, 0);
        queue.insert(5, ALL, 4);
        queue.insert(2, ALL, 4);
        assert_eq!(queue.bars(), [5]);
    }
}
