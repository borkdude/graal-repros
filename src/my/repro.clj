(ns my.repro
  (:gen-class))

(set! *warn-on-reflection* true)

(defn -main [& args]
  (let [expr (or (first args) "(assoc {} :foo :bar)")]
    (prn (clojure.lang.Compiler/eval (read-string expr)))))
