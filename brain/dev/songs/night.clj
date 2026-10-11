(ns songs.night
  "A melodic, arranged song: A minor, 124 BPM, 40 bars that loop.

    (require 'songs.night :reload)
    (songs.night/start!)       ; replace the song and play from bar 0

  Four patterns, placed by the playlist:

    bars  0-8    intro    hats, then the bass groove from bar 4
    bars  8-24   beat     drums come in; the tune joins at 16
    bars 24-28   intro    a break: hats and bass only
    bars 28-40   beat     drums and tune to the end, then it loops

  The bass and the tune are live patterns: markov walks through the A minor
  pentatonic scale, a new phrase every 2 (bass) or 4 (tune) bars."
  (:require [gorilla.engine :as engine]
            [gorilla.live :as live :refer [defpattern]]
            [gorilla.pattern :refer [markov-walk rhythm scale steps]]
            [gorilla.song :as song]
            [gorilla.studio :as studio]))

(def song
  {:song/bpm 124
   :song/time-sig [4 4]
   :song/loop true
   :channels {:kick {:gen [:builtin {:voice :kick}]}
              :snare {:gen [:builtin {:voice :snare}]}
              :hat {:gen [:builtin {:voice :hat}]}
              :bass {:gen [:builtin {:voice :bass}]}
              :lead {:gen [:builtin {:voice :lead}]}}
   :patterns {:intro {:steps {:hat (steps "..x...x...x...x.")}}
              :beat {:steps {:kick (steps "x...x...x...x...")
                             :snare (steps "....x.......x...")
                             :hat (steps "..x.xx.x..x.x.xx")}}
              ;; Starting points; the live patterns below take over.
              :groove {:notes {:bass [{:t 0 :len 6 :pitch 33 :vel 0.9}
                                      {:t 8 :len 6 :pitch 36 :vel 0.8}]}}
              :tune {:notes {:lead [{:t 0 :len 8 :pitch 69 :vel 0.6}]}}}
   :playlist [{:pattern :intro :start 0 :len 8 :track 0}
              {:pattern :groove :start 4 :len 36 :track 1}
              {:pattern :beat :start 8 :len 16 :track 0}
              {:pattern :tune :start 16 :len 8 :track 2}
              {:pattern :intro :start 24 :len 4 :track 0}
              {:pattern :beat :start 28 :len 12 :track 0}
              {:pattern :tune :start 28 :len 12 :track 2}]
   :mixer {}})

(defn- live! []
  ;; Each pattern uses only clojure.core and gorilla.pattern, so its saved
  ;; source runs again in the sandbox after (gorilla.live/activate!).

  ;; Low, bouncy bass: six notes, a new walk every two bars.
  (defpattern :groove :bass
    (fn [bar]
      (rhythm [1/8 1/8 1/4 1/8 1/8 1/4]
              (markov-walk {:seed (quot bar 2) :len 6 :max-step 2
                            :scale (scale :a1 :minor-pentatonic)}))))

  ;; The tune: a five-note phrase with a rest, changing every four bars.
  (defpattern :tune :lead
    (fn [bar]
      (rhythm [3/16 3/16 1/8 1/4 1/8 1/8]
              (conj (markov-walk {:seed (+ 100 (quot bar 4)) :len 5 :start 2
                                  :scale (scale :a4 :minor-pentatonic)})
                    nil)
              {:vel 0.6}))))

(defn start!
  "Replaces the song with this one (undo brings the old one back) and, if the
  engine is connected, plays it from bar 0."
  []
  (when-let [errors (song/explain song)]
    (throw (ex-info "songs.night/song is invalid" {:errors errors})))
  (studio/dispatch! {:cmd :song/replace :song song})
  (live/clear!)
  (live!)
  (if (engine/connected?)
    (live/play!)
    :not-connected))
