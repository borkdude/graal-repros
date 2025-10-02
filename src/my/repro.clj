(ns my.repro
  (:gen-class))

(set! *warn-on-reflection* true)

(defn -main [& _args]
  (prn (System/getProperty "java.file.encoding"))
  (prn (System/getProperty "stdout.encoding"))
  (prn (.getEncoding ^java.io.OutputStreamWriter *out*))
  (prn "λ⚙️中文")
  (alter-var-root #'*out* (constantly (java.io.OutputStreamWriter. System/out)))
  (prn "λ⚙️中文"))
