(ns my.repro
  (:gen-class))

(set! *warn-on-reflection* true)

(defn -main [& _args]
  (prn (eval '(+ 1 2 3))))
