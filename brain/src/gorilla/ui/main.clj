(ns gorilla.ui.main
  "Starts Gorilla Studio as a desktop app: the window, the engine link (which
  starts the engine if it isn't running) and, when nREPL is on the classpath,
  an nREPL server so you can still live-code alongside the UI.

    clojure -M:ui"
  (:require [gorilla.engine :as engine]
            [gorilla.studio :as studio]
            [gorilla.ui.app :as app]))

(defn- start-nrepl! []
  (when-let [start (requiring-resolve 'nrepl.server/start-server)]
    (let [server (start :port 0)
          port (:port server)
          port-file (java.io.File. ".nrepl-port")]
      ;; Don't take over the port file of a REPL that's already running.
      (when-not (.exists port-file)
        (spit port-file port)
        (.deleteOnExit port-file))
      (println "nREPL on port" port))))

(defn -main [& _]
  (try (start-nrepl!)
       (catch Exception e (println "No nREPL:" (ex-message e))))
  (engine/auto-connect! studio/song {:launch true})
  (app/show! {:exit-on-close? true}))
