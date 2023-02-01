(ns ont-app.rdf.core-test
  (:require
   #?(:cljs [cljs.test :refer-macros [async deftest is testing]]
      :clj [clojure.test :refer :all])
   [cljstache.core :as stache]
   [clojure.java.io :as io]
   [clojure.spec.alpha :as spec]
   [clojure.string :as str]
   [clj-http.client :as http]
   [ont-app.graph-log.core :as glog]
   [ont-app.igraph.core :as igraph :refer [unique]]
   [ont-app.igraph.graph :as native-normal :refer [make-graph]]
   [ont-app.rdf.core :as rdf-app]
   [ont-app.rdf.test-support :as test-support]
   [ont-app.vocabulary.core :as voc]
   [ont-app.vocabulary.lstr :as lstr]
   #?(:clj [ont-app.graph-log.levels :as levels
            :refer [warn debug trace value-trace value-debug]]
      :cljs [ont-app.graph-log.levels :as levels
            :refer-macros [warn debug trace value-trace value-debug]])
   ))

(def core-test-ttl
  "A local ttl file containing a single triple."
  (let [temp (java.io.File/createTempFile "rdf-core-test" ".ttl")
        ]
    (spit temp (str (reduce str "" (map (comp #(str "<" % ">")
                                              voc/uri-for)
                                        [:rdf-app/A
                                         :rdf-app/B
                                         :rdf-app/C]))
                    "."))
    temp))

(def dulce-web-resource
  "No-fuss GET-able OWL file on the web"
  (java.net.URL. "http://www.ontologydesignpatterns.org/ont/dul/DUL.owl"))



(deftest test-specs
  (let [java-resource (io/resource "test_support/bnode-test.ttl")
        web-resource (java.net.URL. "http://www.ontologydesignpatterns.org/ont/dul/DUL.owl")
        ]
    (is (spec/valid? ::rdf-app/bnode-kwi :_/<_b123>))
    (is (not (spec/valid? ::rdf-app/bnode-kwi :<_b123>)))
    
    (is (spec/valid? ::rdf-app/file-resource java-resource))
    (is (not (spec/valid? ::rdf-app/file-resource web-resource)))
    
    (is (spec/valid? ::rdf-app/web-resource web-resource))
    (is (not (spec/valid? ::rdf-app/web-resource java-resource)))
    ))


(deftest test-io-dispatch
  (let [context (-> @rdf-app/default-context
                    (igraph/add [[#'rdf-app/load-rdf
                                  :rdf-app/hasGraphDispatch ::TestGraphDispatch]
                                 [#'rdf-app/read-rdf
                                  :rdf-app/hasGraphDispatch ::TestGraphDispatch]
                                 ]))
        ]

    ;; recognized classes of input...
    (is (= [::TestGraphDispatch :rdf-app/LocalFile]
           (rdf-app/load-rdf-dispatch context (str core-test-ttl))))
    (is (= [::TestGraphDispatch :rdf-app/LocalFile]
           (rdf-app/read-rdf-dispatch context nil (str core-test-ttl))))

    (is (= [::TestGraphDispatch :rdf-app/LocalFile]
           (rdf-app/load-rdf-dispatch context core-test-ttl)))
    (is (= [::TestGraphDispatch :rdf-app/LocalFile]
           (rdf-app/read-rdf-dispatch context nil core-test-ttl)))

    (is (= [::TestGraphDispatch :rdf-app/FileResource]
           (rdf-app/load-rdf-dispatch context test-support/bnode-test-data)))
    (is (= [::TestGraphDispatch :rdf-app/FileResource]
           (rdf-app/read-rdf-dispatch context nil test-support/bnode-test-data)))

    (is (= [::TestGraphDispatch :rdf-app/WebResource]
           (rdf-app/load-rdf-dispatch context dulce-web-resource)))
    (is (= [::TestGraphDispatch :rdf-app/WebResource]
           (rdf-app/read-rdf-dispatch context nil dulce-web-resource)))

    ;; otherwise return the type of to-import
    (is (= [::TestGraphDispatch ont_app.igraph.graph.Graph]
           (rdf-app/load-rdf-dispatch context context)))
    
    (is (= [::TestGraphDispatch ont_app.igraph.graph.Graph]
           (rdf-app/read-rdf-dispatch context nil context)))
    
    ;; to-import Dispatch provided in the context...
    (let [context' (igraph/add
                    context
                    [[#'rdf-app/load-rdf
                      :rdf-app/toImportDispatchFn (fn [to-load] ::TestImportDispatch)
                      ]
                     [#'rdf-app/read-rdf
                      :rdf-app/toImportDispatchFn (fn [to-read] ::TestImportDispatch)
                      ]])
          ]
      (is (= [::TestGraphDispatch ::TestImportDispatch]
           (rdf-app/load-rdf-dispatch context' nil)))
      
      (is (= [::TestGraphDispatch ::TestImportDispatch]
             (rdf-app/read-rdf-dispatch context' nil nil)))
      )
    ))

(deftest test-dummy-imports
  (let [context @rdf-app/default-context
        import-fn-state (atom nil)
        cache-dir (io/file (unique (context :rdf-app/UrlCache :rdf-app/directory)))
        dummy-import (fn [_ temp-file-path]
                       (reset! import-fn-state
                               {:temp-file-path temp-file-path
                                }))
        ]

    (let [url test-support/bnode-test-data
          ]
      (rdf-app/import-url-via-temp-file context dummy-import url)

      (is (= cache-dir
             (.getParentFile (io/file (:temp-file-path @import-fn-state)))))
      (is (re-find #"bnode-test"
                   (:temp-file-path @import-fn-state))))

    (let [url dulce-web-resource
          ]
      (rdf-app/import-url-via-temp-file context dummy-import url)
      (is (= cache-dir (.getParentFile (io/file (:temp-file-path @import-fn-state)))))
      (is (re-find #"DUL"
                   (:temp-file-path @import-fn-state))))
    
    ))

(deftest test-language-tagged-strings
  (testing "langstr dispatch"
    (let [x #?(:clj #voc/lstr "asdf@en"
               :cljs (read-string "#voc/lstr \"asdf@en\""))
          ]
      (is (= (str x) "asdf"))
      (is (= (lstr/lang x) "en"))
      (is (= (rdf-app/render-literal-dispatch x)
             ;; :rdf-app/LangStr
             ont_app.vocabulary.lstr.LangStr
             ))
      )))

(deftest test-transit
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
      (is (spec/valid? ::rdf-app/transit-tag "\"[1 2 3]\"^^transit:json"))
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
  (is (= (str (rdf-app/render-literal #?(:clj #voc/lstr "dog@en"
                                         :cljs (read-string "#voc/lstr \"dog@en\"")
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



(deftest test-selmer-to-cljstache
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




(comment
  (def g
    (->> (voc/prefix-to-ns)
         (reduce-kv collect-ns-catalog-metadata (make-graph))))
  (def dc-url "http://purl.org/dc/elements/1.1/")
  (def m-type  (unique (igraph/get-o @rdf-app/resource-catalog dc-url :dcat/mediaType)))
  (def ext (let [
                 ]
             (-> (igraph/query rdf-app/ontology
                               [[:?media-url :formats/media_type m-type]
                                [:?media-url :formats/preferred_suffix :?suffix]])
                 (unique)
                 (:?suffix)
                 )))
  (def dc-file (http/get dc-url {:accept m-type}))
  (spit (format "/tmp/test%s" ext) (:body dc-file))

  (def u (igraph/union @rdf-app/resource-catalog
                       rdf-app/ontology))
  (igraph/query u
                [[dc-url :dcat/mediaType :?media-type]
                 [:?media-url :formats/media_type :?media-type]
                 [:?media-url :formats/preferred_suffix :?suffix]])
  )
