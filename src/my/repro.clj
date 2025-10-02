(ns my.repro
  (:gen-class))

(set! *warn-on-reflection* true)

(defn -main [& _args]
  (prn :java.file.encoding (System/getProperty "java.file.encoding"))
  (prn :stdout.encoding (System/getProperty "stdout.encoding"))
  (prn :out-var-encoding (.getEncoding ^java.io.OutputStreamWriter *out*))
  (prn :unicode-string "λ⚙️中文")
  (println "setting out var to new outputstreamwriter")
  (alter-var-root #'*out* (constantly (java.io.OutputStreamWriter. System/out)))
  (prn :unicode-string-with-new-out "λ⚙️中文"))
