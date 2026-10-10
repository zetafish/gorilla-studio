(ns user
  "REPL helpers. Start the engine (`cargo run -p gorilla-rt` in engine/), then:

    (connect!)          ; connect and send the song
    (toggle! :hat 3)    ; toggle a step; the engine hears it on the next buffer
    (undo!) (redo!)
    (stop!) (play!)     ; transport; play restarts from the top
    (disconnect!)       ; the engine keeps playing what it has"
  (:require [gorilla.engine :as engine]
            [gorilla.studio :as studio]))

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
