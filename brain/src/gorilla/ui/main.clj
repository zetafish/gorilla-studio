(ns gorilla.ui.main
  "Starts Gorilla Studio as a desktop app: the window, the engine link (which
  starts the engine if it isn't running) and, when nREPL is on the classpath,
  an nREPL server so you can still live-code alongside the UI.

    clojure -M:ui"
  (:require [gorilla.engine :as engine]
            [gorilla.nrepl :as nrepl]
            [gorilla.studio :as studio]
            [gorilla.ui.app :as app]))

(defn -main [& _]
  (try (nrepl/start!)
       (catch Exception e (println "No nREPL:" (ex-message e))))
  (engine/auto-connect! studio/song {:launch true})
  (app/show! {:exit-on-close? true}))
