(ns gorilla.nrepl
  "Starts an nREPL server next to an app, when nREPL is on the classpath.")

(defn start!
  "Starts nREPL on a free port and writes .nrepl-port, unless a REPL that's
  already running owns that file. Returns the port, or nil."
  []
  (when-let [start (requiring-resolve 'nrepl.server/start-server)]
    (let [server (start :port 0)
          port (:port server)
          port-file (java.io.File. ".nrepl-port")]
      (when-not (.exists port-file)
        (spit port-file port)
        (.deleteOnExit port-file))
      (println "nREPL on port" port)
      port)))
