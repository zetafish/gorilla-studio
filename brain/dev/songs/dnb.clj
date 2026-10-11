(ns songs.dnb
  "A two-step drum & bass loop at 174 BPM, with an 8-bar intro.

    (require 'songs.dnb :reload)
    (songs.dnb/start!)       ; replace the song and play from bar 0

  The built-in drums carry the beat; macOS system sounds stand in for the
  rest. All patterns loop at once (there's no playlist yet), so the
  arrangement lives in the live patterns: each is a function of the bar
  number, counted from `start!`.

    bars 0-3   kick, hats and the atmosphere
    bars 4-7   snare and ghost notes come in; a fill on bar 7
    bar 8 on   the drop: bass and stab join; rolls every 4 bars, fills every 8"
  (:require [gorilla.engine :as engine]
            [gorilla.live :as live :refer [defpattern]]
            [gorilla.pattern :refer [chance every overlay steps]]
            [gorilla.song :as song]
            [gorilla.studio :as studio]))

(defn- system-sound [name]
  [:sampler {:sample (str "/System/Library/Sounds/" name ".aiff")}])

;; The static rows are what the engine plays before the live patterns take
;; over, and what keeps looping if the brain goes away: the drop.
(def song
  {:song/bpm 174
   :song/time-sig [4 4]
   :channels {:kick  {:gen [:builtin {:voice :kick}]}
              :snare {:gen [:builtin {:voice :snare}]}
              :hat   {:gen [:builtin {:voice :hat}]}
              :ghost {:gen (system-sound "Tink")}
              :bass  {:gen (system-sound "Basso")}
              :stab  {:gen (system-sound "Funk")}
              :atmos {:gen (system-sound "Submarine")}}
   :patterns {:drums {:steps {:kick  (steps "x.........x.....")
                              :snare (steps "....x.......x...")
                              :hat   (steps "..x...x...x...x.")}}
              :perc  {:steps {:ghost (steps ".......x.x.....x")}}
              :bass  {:steps {:bass  (steps "x.........x..x..")}}
              :fx    {:steps {:stab  (steps "......x.........")
                              :atmos (steps "x...............")}}}
   :playlist []
   :mixer {}})

(defn- live! []
  ;; Each pattern uses only clojure.core and gorilla.pattern, so its saved
  ;; source runs again in the sandbox after (gorilla.live/activate!).

  ;; Two-step kick; a busier fill on the last bar of every 8.
  (defpattern :drums :kick
    (fn [bar]
      (if (= 7 (mod bar 8))
        "x.x.......x.x.x."
        "x.........x.....")))

  ;; Backbeat on 2 and 4 from bar 4; a roll into every 4th bar, a bigger
  ;; one into the 8th.
  (defpattern :drums :snare
    (fn [bar]
      (cond
        (< bar 4)            ""
        (= 7 (mod bar 8))    "....x...x.x.xxxx"
        (= 3 (mod bar 4))    "....x.......x.xx"
        :else                "....x.......x...")))

  ;; Offbeat hats with a few extra sixteenths that change every bar.
  (defpattern :drums :hat
    (fn [bar]
      (overlay "..x...x...x...x." (chance 0.2 bar))))

  ;; Ghost notes shuffle around the snare once it's in.
  (defpattern :perc :ghost
    (fn [bar]
      (if (< bar 4)
        ""
        (overlay ".......x.x.....x" (chance 0.1 (+ 1000 bar))))))

  ;; Bass from the drop, following the kick, with an alternating pickup.
  (defpattern :bass :bass
    (fn [bar]
      (cond
        (< bar 8)   ""
        (even? bar) "x.........x..x.."
        :else       "x.........x...x.")))

  ;; A stab every other bar after the drop.
  (defpattern :fx :stab
    (fn [bar]
      (if (< bar 8) "" (every 2 bar "......x........." ""))))

  ;; The atmosphere swells once every 8 bars.
  (defpattern :fx :atmos
    (fn [bar]
      (every 8 bar "x..............." ""))))

(defn start!
  "Replaces the song with this one (undo brings the old one back) and, if the
  engine is connected, plays it from bar 0. Clears other live patterns first."
  []
  (when-let [errors (song/explain song)]
    (throw (ex-info "songs.dnb/song is invalid" {:errors errors})))
  (studio/dispatch! {:cmd :song/replace :song song})
  (live/clear!)
  (live!)
  (if (engine/connected?)
    (live/play!)
    :not-connected))
