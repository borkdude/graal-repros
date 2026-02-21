(println "hello")

(deftype Dude []
  clojure.lang.ILookup
  (valAt [_ _] :value))

(prn (get (->Dude) 3))

#_(time (let [x 0 j 10000000] (loop [i 0 j 10000000] (if (zero? j) i (recur (inc i) (dec j))))))
;; ~1s, much slower than babashka currently, but who knows

(definterface IDude)

(defmulti foo :foo)

(defmethod foo 1 [_]
  (prn "one"))

(foo {:foo 1})

(require '[clojure.spec.alpha :as s])

(s/def ::foo int?)

(prn (s/valid? ::foo 1))
