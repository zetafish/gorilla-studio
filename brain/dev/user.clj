(ns user
  "REPL helpers.

    (connect!)          ; stay connected to the engine; starts it with
                        ; `cargo run` if nothing is listening, restarts it if it dies
    (toggle! :hat 3)    ; toggle a step; the engine hears it on the next buffer
    (undo!) (redo!)
    (stop!) (play!)     ; transport; play restarts from the top
    (playhead)          ; where the engine is: {:bar 12 :step 3 :playing true ...}
    (disconnect!)       ; stop reconnecting; the engine keeps playing what it has
    (stop-engine!)      ; end an engine that connect! started
    (reload!)           ; reload every changed namespace, in dependency order

  Live patterns (generators from gorilla.pattern are referred here):

    (defpattern :hat (fn [bar] (euclid (+ 3 (mod bar 4)) 16)))
    (defpattern :kick (fn [bar] (every 4 bar \"x..x..x...x.x...\" \"x...x...x...x...\")))
    (unpattern :hat)    ; back to the song's row

  The song drives the engine:

    (bpm! 96)
    (channel! :pop \"/System/Library/Sounds/Pop.aiff\")   ; a sampler
    (channel! :kick2 :kick)                               ; a built-in drum
    (row! :pop \"....x.......x..x\")                       ; row :pop in :drums
    (row! :fills :pop \"xxxx............\")               ; another pattern
    (remove-row! :pop) (remove-channel! :pop)
    (new-song!)         ; empty song, live patterns cleared (undo restores the song)

  Melody (notes are {:t step :len steps :pitch midi :vel 0..1}):

    (channel! :bass :bass)                ; built-in synths: :bass and :lead
    (notes! :low :bass (rhythm [1/4 1/8 1/8 1/2] [:a1 :a1 :c2 :e2]))
    (defpattern :low :bass
      (fn [bar] (rhythm [1/8] (markov-walk {:seed bar :len 8 :scale (scale :a1 :minor)}))))

  Songs on disk (a folder: song.edn plus assets/):

    (save! \"songs/dnb\")  ; later just (save!)
    (open! \"songs/dnb\")  ; live patterns come back frozen; nothing is evaluated
    (activate!)          ; run their saved source again, in a sandbox"
  (:require [clojure.tools.namespace.dir :as dir]
            [clojure.tools.namespace.track :as track]
            [gorilla.engine :as engine]
            [gorilla.file :as file]
            [gorilla.live :as live :refer [defpattern]]
            [gorilla.pattern :as pattern :refer [steps euclid rotate invert overlay mask chance every
                                                 note scale degree chord markov-walk rhythm
                                                 transpose]]
            [gorilla.song]
            [gorilla.studio :as studio]))

(defn reload!
  "Reloads every project namespace, dependencies first, so a regenerated
  schema reaches everything built from it. Nothing is unloaded, so `defonce`
  state (the song, history, engine link) survives."
  []
  (let [order (::track/load (dir/scan-dirs (track/tracker) ["src" "dev" "test"]))]
    (doseq [ns-sym order]
      (require ns-sym :reload))
    order))

(defn connect! [] (engine/auto-connect! studio/song {:launch true}))

(defn disconnect! [] (engine/stop-auto-connect!))

(defn stop-engine! [] (engine/stop-engine!))

(defn play! [] (live/play!))

(defn stop! [] (engine/set-playing! false) :stopped)

(defn playhead [] @engine/playhead)

(defn unpattern
  ([row] (live/remove-pattern! row))
  ([pattern row] (live/remove-pattern! pattern row)))

(defn patterns
  "Live patterns and their source."
  []
  (into (sorted-map) (map (fn [[k v]] [k (:source v)])) @live/patterns))

(def ^:private steps* pattern/steps)

(defn- dispatch! [cmd] (studio/dispatch! cmd) :ok)

(defn new-song!
  "Replaces the song with an empty one (or `song`) and clears live patterns.
  Undo brings the previous song back, but not its live patterns."
  ([] (new-song! gorilla.song/empty-song))
  ([song]
   (doseq [[pattern row] (keys @live/patterns)]
     (live/remove-pattern! pattern row))
   (dispatch! {:cmd :song/replace :song song})))

(defn save!
  ([] (file/save!))
  ([dir] (file/save! dir)))

(defn open! [dir] (file/open! dir))

(defn activate! [] (live/activate!))

(defn bpm! [bpm] (dispatch! {:cmd :song/set-bpm :bpm bpm}))

(defn channel!
  "A sampler for a path string, or a built-in drum for :kick, :snare or :hat."
  [channel source]
  (when (string? source)
    (let [f (java.io.File. (engine/sample-path source))]
      (when-not (.isFile f)
        (throw (ex-info (str "No such sample: " f) {:sample source})))))
  (dispatch! {:cmd :channel/set :channel channel
              :gen (if (string? source)
                     [:sampler {:sample source}]
                     [:builtin {:voice source}])}))

(defn row!
  "Adds a row playing `channel` (same id) to a pattern, :drums by default."
  ([channel] (row! :drums channel nil))
  ([channel-or-pattern steps-or-channel]
   (if (keyword? steps-or-channel)
     (row! channel-or-pattern steps-or-channel nil)
     (row! :drums channel-or-pattern steps-or-channel)))
  ([pattern channel steps]
   (dispatch! (cond-> {:cmd :pattern/add-row :pattern pattern :row channel}
                steps (assoc :steps (steps* steps))))))

(defn notes!
  "Sets a notes row (creating it, and its pattern, if needed). `notes` are
  normalized: (notes! :low :bass [{:t 0 :len 4 :pitch :a1}])."
  [pattern channel notes]
  (dispatch! {:cmd :pattern/set-notes :pattern pattern :row channel
              :notes (pattern/notes notes)}))

(defn remove-row!
  ([channel] (remove-row! :drums channel))
  ([pattern channel] (dispatch! {:cmd :pattern/remove-row :pattern pattern :row channel})))

(defn remove-channel! [channel] (dispatch! {:cmd :channel/remove :channel channel}))

(defn rejection
  "The engine's last refusal, if any."
  []
  @engine/last-rejection)

(defn toggle!
  ([row step] (toggle! :drums row step))
  ([pattern row step]
   (studio/dispatch! {:cmd :pattern/toggle-step :pattern pattern :row row :step step})
   (get-in @studio/song [:patterns pattern :steps row])))

(defn undo! [] (studio/undo!) :ok)

(defn redo! [] (studio/redo!) :ok)

(def my-song
  {:song/bpm 110
   :song/time-sig [4 4]
   :channels {:kick {:gen [:builtin {:voice :kick}]}
              :pop  {:gen [:sampler {:sample "/System/Library/Sounds/Pop.aiff"}]}}
   :patterns {:beat {:steps {:kick (steps "x..x..x...x.....")
                             :pop  (steps "....x.......x...")}}}
   :playlist []
   :mixer {}})
