(ns my.repro
  (:gen-class))

(set! *warn-on-reflection* true)

(alter-var-root #'*out* (constantly (java.io.OutputStreamWriter. System/out)))

(defn -main [& _args]
  (prn (System/getProperty "java.file.encoding"))
  (prn (.getEncoding ^java.io.OutputStreamWriter *out*))
  (prn "λ⚙️中文")
  (alter-var-root #'*out* (constantly (java.io.OutputStreamWriter. System/out)))
  (prn "λ⚙️中文"))
