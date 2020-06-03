(ns ont-app.rdf.core-test
  (:require
   #?(:cljs [cljs.test :refer-macros [async deftest is testing]]
      :clj [clojure.test :refer :all])
   [clojure.string :as str]
   [cljstache.core :as stache]
   [ont-app.vocabulary.lstr :as lstr]
   [ont-app.rdf.core :as rdf-app]
   ))



(deftest language-tagged-strings
  (testing "langstr dispatch"
    (let [x #lstr "asdf@en"]
      (is (= (str x) "asdf"))
      (is (= (lstr/lang x) "en"))
      (is (= (rdf-app/render-literal-dispatch x)
             :rdf-app/LangStr))
      )))


(deftest transit
  (testing "transit encoding/decoding"
    (let [v [1 2 3]
          s (set v)
          f `(fn [x] "yowsa")
          round-trip (fn [x]
                       (rdf-app/read-transit-json
                        ((re-matches rdf-app/transit-re
                                     (rdf-app/render-literal x)) 1)))
          order-neutral (fn [s] (str/replace s #"[0-9]" "<number>"))
          cljs-ns->clojure-ns (fn [s] (str/replace s #"cljs" "clojure"))
          ]
      (is (= (parents (rdf-app/render-literal-dispatch v))
             :rdf-app/TransitData))
      (is (= (rdf-app/render-literal v)
             "\"[1,2,3]\"^^transit:json"))
      (is (= ((re-matches rdf-app/transit-re (rdf-app/render-literal v)) 1)
             "[1,2,3]"))
      (is (= (round-trip v)
             v))
      (is (= (parents (rdf-app/render-literal-dispatch s))
             :rdf-app/TransitData))
      (is (= (order-neutral (str (rdf-app/render-literal s)))
             (str "\"[&quot;~#set&quot;,[<number>,<number>,<number>]]\"^^transit:json")))
      (is (= (round-trip s)
             s))
      (is (= (parents (rdf-app/render-literal-dispatch f))
             :rdf-app/TransitData))
      (is (= (cljs-ns->clojure-ns (rdf-app/render-literal f))
             "\"[&quot;~#list&quot;,[&quot;~$clojure.core/fn&quot;,[&quot;~$ont-app.rdf.core-test/x&quot;],&quot;yowsa&quot;]]\"^^transit:json"))
      (is (= (round-trip f)
             f))
      )))

(deftest selmer-to-cljstache
  (testing "Using cljstache (instead of selmer) should work on clj(s)."
    (is (= (stache/render rdf-app/subjects-query-template
                          {:graph-name-open "GRAPH <http://example.com> {"
                           :graph-name-close "}"
                           })
           "\n  Select Distinct ?s Where\n  {\n    GRAPH <http://example.com> { \n    ?s ?p ?o.\n    }\n  }\n  "))
    (is (= (stache/render
            rdf-app/subjects-query-template
            {:graph-name-open ""
             :graph-name-close ""
             })
           "\n  Select Distinct ?s Where\n  {\n     \n    ?s ?p ?o.\n    \n  }\n  "))))

