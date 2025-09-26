(ns my.repro
  (:gen-class))

(defn -main [& _args]
  (prn "λ⚙️中文")
  (alter-var-root #'*out* (constantly (java.io.OutputStreamWriter. System/out)))
  (prn "λ⚙️中文"))
