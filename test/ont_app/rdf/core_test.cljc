(ns ont-app.rdf.core-test
  (:require
   #?(:cljs [cljs.test :refer-macros [async deftest is testing]]
      :clj [clojure.test :refer :all])
   [ont-app.rdf.core :as rdf-app]
   ))

(deftest language-tagged-strings
  (testing "langstr disptach"
    (let [x #lstr "asdf@en"]
      (is (= (str x) "asdf"))
      (is (= (rdf-app/lang x) "en"))
      (is (= (rdf-app/render-literal-dispatch x)
             ::rdf-app/LangStr)))))

(deftest transit
  (testing "transit encoding/decoding"
    (let [v [1 2 3]
          s (set v)
          f `(fn [x] "yowsa")
          round-trip (fn [x]
                       (rdf-app/read-transit-json
                        ((re-matches rdf-app/transit-re
                                     (rdf-app/render-literal x)) 1)))
          ]
      (is (parents (rdf-app/render-literal-dispatch v))
          :rdf-app/TransitData)
      (is (= (rdf-app/render-literal v)
             "\"[1,2,3]\"^^transit:json"))
      (is (= ((re-matches rdf-app/transit-re (rdf-app/render-literal v)) 1)
             "[1,2,3]"))
      (is (= (round-trip v)
             v))
      (is (parents (rdf-app/render-literal-dispatch s))
          :rdf-app/TransitData)
      (is (= (rdf-app/render-literal s)
             "\"[&quot;~#set&quot;,[1,3,2]]\"^^transit:json"))
      (is (= (round-trip s)
             s))
      (is (parents (rdf-app/render-literal-dispatch f))
          :rdf-app/TransitData)
      (is (= (rdf-app/render-literal f)
             "\"[&quot;~#list&quot;,[&quot;~$clojure.core/fn&quot;,[&quot;~$ont-app.rdf.core-test/x&quot;],&quot;yowsa&quot;]]\"^^transit:json"))
      (is (= (round-trip f)
             f))
      )))

