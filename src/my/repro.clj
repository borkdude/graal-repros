(ns my.repro
  (:gen-class))

(set! *warn-on-reflection* true)

(defn -main [& _args]
  (prn (clojure.lang.Compiler/eval '(assoc {} :foo :bar))))
