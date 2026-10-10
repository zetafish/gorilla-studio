(ns user
  "REPL helpers. Start the engine (`cargo run -p gorilla-rt` in engine/), then:

    (start!)            ; connect and send the song
    (toggle! :hat 3)    ; toggle a step; the engine hears it on the next buffer
    (undo!) (redo!)
    (stop!)             ; disconnect; the engine keeps playing"
  (:require [gorilla.engine :as engine]
            [gorilla.studio :as studio]))

(defn start! [] (engine/connect! studio/song))

(defn stop! [] (engine/disconnect!))

(defn toggle!
  ([row step] (toggle! :drums row step))
  ([pattern row step]
   (studio/dispatch! {:cmd :pattern/toggle-step :pattern pattern :row row :step step})
   (get-in @studio/song [:patterns pattern :steps row])))

(defn undo! [] (studio/undo!) :ok)

(defn redo! [] (studio/redo!) :ok)
