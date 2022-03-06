(ns ont-app.rdf.core-test
  (:require
   #?(:cljs [cljs.test :refer-macros [async deftest is testing]]
      :clj [clojure.test :refer :all])
   [clojure.string :as str]
   [cljstache.core :as stache]
   [ont-app.vocabulary.lstr :as lstr]
   [ont-app.graph-log.core :as glog]
   [ont-app.rdf.core :as rdf-app]
   [ont-app.vocabulary.core :as voc]
   #?(:clj [ont-app.graph-log.levels :as levels
            :refer [warn debug trace value-trace value-debug]]
      :cljs [ont-app.graph-log.levels :as levels
            :refer-macros [warn debug trace value-trace value-debug]])
   ))



(deftest language-tagged-strings
  (testing "langstr dispatch"
    (let [x #?(:clj #lstr "asdf@en"
               :cljs (read-string "#lstr \"asdf@en\""))
          ]
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
      ;; applying set a boolean function...
      (is ((parents (rdf-app/render-literal-dispatch v))
           :rdf-app/TransitData))
      (is (= (rdf-app/render-literal v)
             "\"[1,2,3]\"^^transit:json"))
      (is (= ((re-matches rdf-app/transit-re (rdf-app/render-literal v)) 1)
             "[1,2,3]"))
      (is (= (round-trip v)
             v))
      ;; applying set a boolean function...
      (is ((parents (rdf-app/render-literal-dispatch s))
           :rdf-app/TransitData))
      (is (= (order-neutral (str (rdf-app/render-literal s)))
             (str "\"[&quot;~#set&quot;,[<number>,<number>,<number>]]\"^^transit:json")))
      (is (= (round-trip s)
             s))
      ;; applying set a boolean function...
      (is ((parents (rdf-app/render-literal-dispatch f))
           :rdf-app/TransitData))
      (is (= (cljs-ns->clojure-ns (rdf-app/render-literal f))
             "\"[&quot;~#list&quot;,[&quot;~$clojure.core/fn&quot;,[&quot;~$ont-app.rdf.core-test/x&quot;],&quot;yowsa&quot;]]\"^^transit:json"))
      (is (= (round-trip f)
             f))
      )))

(deftest render-basic-literals-test
  "Basic render-literal implementations for numbers and language-tagged strings"
  (is (= (str (rdf-app/render-literal 1)) "1"))
  (is (= (str (rdf-app/render-literal 1.0)) "1.0"))
  (is (= (str (rdf-app/render-literal #?(:clj #lstr "dog@en"
                                         :cljs (read-string "#lstr \"dog@en\"")
                                         ))
              "\"dog\"@en"))))

(def test-query-template "
  Select Distinct ?s
  {{{from-clauses}}} 
  Where
  {
    ?_s ?_p ?_o.
    Bind ({{{rebind-_s}}} as ?s)
  }
  ")

(deftest selmer-to-cljstache
  (testing "Using cljstache (instead of selmer) should work on clj(s)."
    (is (= (stache/render test-query-template
                          (merge @rdf-app/query-template-defaults
                                 {:rebind-_s "IF(isBlank(?_s), IRI(?_s), ?_s)"
                                  }
                                 ))
           "\n  Select Distinct ?s\n   \n  Where\n  {\n    ?_s ?_p ?_o.\n    Bind (IF(isBlank(?_s), IRI(?_s), ?_s) as ?s)\n  }\n  "
           ))))

(deftest issue-5-from-clauses
  (is (= "\n  Select ?s ?p ?o\n  FROM <http://www.w3.org/1999/02/22-rdf-syntax-ns#just-kidding>\nFROM <http://www.w3.org/1999/02/22-rdf-syntax-ns#also-just-kidding>\n  Where\n  {\n    ?_s ?_p ?_o.\n    Bind (?_s as ?s)\n    Bind (?_p as ?p)\n    Bind (?_o as ?o)\n  }\n  "
         (stache/render rdf-app/normal-form-query-template
                        (merge @rdf-app/query-template-defaults
                               {:from-clauses
                                (str/join "\n"
                                          (map (comp rdf-app/from-clause-for voc/iri-for)
                                               #{:rdf/just-kidding
                                                 :rdf/also-just-kidding
                                                 }))})))))

 
