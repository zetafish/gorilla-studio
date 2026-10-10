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
    (unpattern :hat)    ; back to the song's row"
  (:require [clojure.tools.namespace.dir :as dir]
            [clojure.tools.namespace.track :as track]
            [gorilla.engine :as engine]
            [gorilla.live :as live :refer [defpattern]]
            [gorilla.pattern :refer [steps euclid rotate invert overlay mask chance every]]
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

(defn toggle!
  ([row step] (toggle! :drums row step))
  ([pattern row step]
   (studio/dispatch! {:cmd :pattern/toggle-step :pattern pattern :row row :step step})
   (get-in @studio/song [:patterns pattern :steps row])))

(defn undo! [] (studio/undo!) :ok)

(defn redo! [] (studio/redo!) :ok)
