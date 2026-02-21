(ns my.repro
  (:gen-class)
  (:require [clojure.data]
            [clojure.edn]
            [clojure.java.io]
            [clojure.java.shell]
            [clojure.main]
            [clojure.math]
            [clojure.pprint]
            [clojure.reflect]
            [clojure.repl]
            [clojure.set]
            [clojure.stacktrace]
            [clojure.string]
            [clojure.template]
            [clojure.test]
            [clojure.walk]
            [clojure.xml]
            [clojure.zip])
  (:import [my JarClassLoader]))

(set! *warn-on-reflection* true)


(defn- parse-args
  "Parse -cp <paths> from args. Returns [cp-string remaining-args]."
  [args]
  (loop [args args
         cp nil]
    (if (seq args)
      (let [[flag & rest-args] args]
        (if (= "-cp" flag)
          (recur (rest rest-args) (first rest-args))
          [cp args]))
      [cp args])))

(defn -main [& args]
  (let [[cp-str remaining] (parse-args args)
        _ (when cp-str
            (let [paths (.split ^String cp-str ":")
                  cl (JarClassLoader. paths (.getContextClassLoader (Thread/currentThread)))]
              (.setContextClassLoader (Thread/currentThread) cl)
))
        expr (or (first remaining) "(assoc {} :foo :bar)")]
    (binding [*ns* *ns*
              *warn-on-reflection* *warn-on-reflection*
              *data-readers* *data-readers*
              *default-data-reader-fn* *default-data-reader-fn*
              *repl* true]
      (prn (clojure.lang.Compiler/eval (read-string expr))))))
