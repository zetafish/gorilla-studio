(ns user
  "REPL helpers. Start the engine (`cargo run -p gorilla-rt` in engine/), then:

    (connect!)          ; connect and send the song
    (toggle! :hat 3)    ; toggle a step; the engine hears it on the next buffer
    (undo!) (redo!)
    (stop!) (play!)     ; transport; play restarts from the top
    (disconnect!)       ; the engine keeps playing what it has
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

(defn connect! [] (engine/connect! studio/song))

(defn disconnect! [] (engine/disconnect!))

(defn play! [] (engine/set-playing! true) :playing)

(defn stop! [] (engine/set-playing! false) :stopped)

(defn toggle!
  ([row step] (toggle! :drums row step))
  ([pattern row step]
   (studio/dispatch! {:cmd :pattern/toggle-step :pattern pattern :row row :step step})
   (get-in @studio/song [:patterns pattern :steps row])))

(defn undo! [] (studio/undo!) :ok)

(defn redo! [] (studio/redo!) :ok)
