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
    (reload!)           ; reload every changed namespace, in dependency order"
  (:require [clojure.tools.namespace.dir :as dir]
            [clojure.tools.namespace.track :as track]
            [gorilla.engine :as engine]
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

(defn play! [] (engine/set-playing! true) :playing)

(defn stop! [] (engine/set-playing! false) :stopped)

(defn playhead [] @engine/playhead)

(defn toggle!
  ([row step] (toggle! :drums row step))
  ([pattern row step]
   (studio/dispatch! {:cmd :pattern/toggle-step :pattern pattern :row row :step step})
   (get-in @studio/song [:patterns pattern :steps row])))

(defn undo! [] (studio/undo!) :ok)

(defn redo! [] (studio/redo!) :ok)
