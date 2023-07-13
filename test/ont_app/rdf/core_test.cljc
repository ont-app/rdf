(ns ont-app.rdf.core-test
  (:require
   #?(:cljs [cljs.test :refer-macros [async deftest is testing]]
      :clj [clojure.test :refer :all])
   [cljstache.core :as stache]
   [clojure.java.io :as io]
   [clojure.reflect :refer [reflect]]
   [clojure.spec.alpha :as spec]
   [clojure.string :as str]
   [clj-http.client :as http]
   [ont-app.graph-log.core :as glog]
   [ont-app.igraph.core :as igraph :refer [assert-unique unique]]
   [ont-app.igraph.graph :as native-normal :refer [make-graph]]
   [ont-app.rdf.core :as rdf-app]
   [ont-app.rdf.test-support :as test-support]
   [ont-app.vocabulary.core :as voc]
   [ont-app.vocabulary.lstr :as lstr]
   [ont-app.vocabulary.dstr :as dstr]
   #?(:clj [clojure.repl :refer :all])
   #?(:clj [ont-app.graph-log.levels :as levels
            :refer [warn debug trace value-trace value-debug]]
      :cljs [ont-app.graph-log.levels :as levels
            :refer-macros [warn debug trace value-trace value-debug]])
   ))


(glog/log-reset!)
(glog/set-level! :glog/LogGraph :glog/OFF)

(defn log-reset!
  ([]
   (log-reset! :glog/DEBUG))
  ([level]
   (glog/log-reset!)
   (glog/set-level! level)))

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


(def rdfs-web-resource
  "The URL for the rdfs schema, which has an entry in the resource catalog, drawn from voc metadata. "
  (java.net.URL. "http://www.w3.org/2000/01/rdf-schema#"))

(deftest test-specs
  (let [java-resource (io/resource "test_support/bnode-test.ttl")
        web-resource (java.net.URL. "http://www.ontologydesignpatterns.org/ont/dul/DUL.owl")
        ]
    (is (spec/valid? ::rdf-app/bnode-kwi :rdf-app/_:b123))
    (is (not (spec/valid? ::rdf-app/bnode-kwi :_:b123)))
    
    (is (spec/valid? ::rdf-app/file-resource java-resource))
    (is (not (spec/valid? ::rdf-app/file-resource web-resource)))
    
    (is (spec/valid? ::rdf-app/web-resource web-resource))
    (is (not (spec/valid? ::rdf-app/web-resource java-resource)))
    ))


(derive ::TestGraphIO :rdf-app/IGraph)
(derive :rdf-app/FileResource :rdf-app/CachedResource)
(derive :rdf-app/WebResource :rdf-app/CachedResource)

(def test-context (-> @rdf-app/default-context
                    (igraph/add [[#'rdf-app/load-rdf
                                  :rdf-app/hasGraphDispatch ::TestGraphIO]
                                 [#'rdf-app/read-rdf
                                  :rdf-app/hasGraphDispatch ::TestGraphIO]
                                 ])))
(deftest test-io-dispatch 
  (let [context test-context]
    ;; recognized classes of input...
    (is (= [::TestGraphIO :rdf-app/LocalFile]
           (rdf-app/load-rdf-dispatch context (str core-test-ttl))))
    (is (= [::TestGraphIO :rdf-app/LocalFile]
           (rdf-app/read-rdf-dispatch context nil (str core-test-ttl))))

    (is (= [::TestGraphIO :rdf-app/LocalFile]
           (rdf-app/load-rdf-dispatch context core-test-ttl)))
    (is (= [::TestGraphIO :rdf-app/LocalFile]
           (rdf-app/read-rdf-dispatch context nil core-test-ttl)))

    (is (= [::TestGraphIO :rdf-app/FileResource]
           (rdf-app/load-rdf-dispatch context test-support/bnode-test-data)))
    (is (= [::TestGraphIO :rdf-app/FileResource]
           (rdf-app/read-rdf-dispatch context nil test-support/bnode-test-data)))

    (is (= [::TestGraphIO :rdf-app/WebResource]
           (rdf-app/load-rdf-dispatch context rdfs-web-resource)))
    (is (= [::TestGraphIO :rdf-app/WebResource]
           (rdf-app/read-rdf-dispatch context nil rdfs-web-resource)))

    ;; otherwise return the type of to-import
    (is (= [::TestGraphIO ont_app.igraph.graph.Graph]
           (rdf-app/load-rdf-dispatch context context)))
    
    (is (= [::TestGraphIO ont_app.igraph.graph.Graph]
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
      (is (= [::TestGraphIO ::TestImportDispatch]
           (rdf-app/load-rdf-dispatch context' nil)))
      
      (is (= [::TestGraphIO ::TestImportDispatch]
             (rdf-app/read-rdf-dispatch context' nil nil)))
      )
    ))


(deftest test-cache-url-as-local-file
  "URLs should be copied to locally cached, appropriately named local files"
  (let [cache-dir (io/file (unique (@rdf-app/default-context
                                    :rdf-app/UrlCache :rdf-app/directory)))
        ]

    ;; File resource (like from a jar)
    (let [url test-support/bnode-test-data
          path (rdf-app/cache-url-as-local-file
                (-> @rdf-app/default-context
                    (igraph/add [url :rdf/type :rdf-app/FileResource]))
                url)
          ]
      (is (= cache-dir (.getParentFile path)))
      
      (is (re-find #"bnode-test" (str path)))
      (is (> (.length path) 0))
      )

    ;; Web resource
    (let [url rdfs-web-resource
          path (rdf-app/cache-url-as-local-file
                (-> @rdf-app/default-context
                    (igraph/add [url :rdf/type :rdf-app/WebResource]))
                url)
          ]
      (is (= cache-dir (.getParentFile path)))
      (is (re-find #"rdfs" (str path)))
      (is (> (.length path) 0))
      )

    ;; adding an entry to the resource catalog
    (let [url (java.net.URL. "http://www.ontologydesignpatterns.org/ont/dul/DUL.owl")
          ]
      (rdf-app/add-catalog-entry! url
                                  "http://ontologydesignpatterns.org/ont/dulce.owl"
                                  "dulce"
                                  "application/owl+xml"
                                  )
      (let [path (rdf-app/cache-url-as-local-file
                                  (-> @rdf-app/default-context
                    (igraph/add [url :rdf/type :rdf-app/WebResource]))
                                  url)
            ]
        (is (= cache-dir (.getParentFile path)))
        (is (re-find #"dulce" (str path)))
        (is (> (.length path) 0))
        ))
    ))

(deftest test-load-rdf
  "Loading URLs should flag an error after it's been translated to a local file, since ther is no method defined in the RDF module to load local files.  Loading local files is the responsibility of individual implementations.
"
  (testing "file resource"
    (try (rdf-app/load-rdf (assert-unique
                            test-context
                            :rdf-app/UrlCache
                            :rdf/type
                            :rdf-app/SuppressLoadFileResourceCacheWarning)
                           ;; kills a warning we're deliberately triggering
                           test-support/bnode-test-data)
         (catch clojure.lang.ExceptionInfo e
           (let [ed (ex-data e)
                 ]
             (is (= ::rdf-app/NoMethodForLoadRdf (:type ed) ))
             (is (re-matches #".*bnode-test_hash=.*.ttl" (str (::rdf-app/file ed))))
             (is (= [::TestGraphIO :rdf-app/LocalFile] (::rdf-app/dispatch ed)))
             ))))
  (testing "web resource"
    (try (rdf-app/load-rdf test-context rdfs-web-resource)
         (catch clojure.lang.ExceptionInfo e
           (let [ed (ex-data e)
                 ]
             (is (= ::rdf-app/NoMethodForLoadRdf (:type ed) ))
             (is (re-matches #".*rdfs_hash=.*.ttl" (str (::rdf-app/file ed))))
             (is (= [::TestGraphIO :rdf-app/LocalFile] (::rdf-app/dispatch ed)))
             ))))
  )

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

(defn transit-tag-untag
  [x]
  (-> x
      (voc/tag :transit/json)
      (voc/untag)))

(defn transit-round-trip "Returns `x` after converting it to a transit literal and re-parsing it"
    [x]
    (as-> (rdf-app/render-literal x)
        it
        (re-matches rdf-app/transit-re it)
        (nth it 1)
        (rdf-app/read-transit-json it)))

(deftest test-transit
  (testing "transit encoding/decoding"
    (let [v [1 2 3]
          s (set v)
          f `(fn [x] "yowsa")
          v-of-k [::a]
          order-neutral (fn [s] (str/replace s #"[0-9]" "<number>"))
          cljs-ns->clojure-ns (fn [s] (str/replace s #"cljs" "clojure"))
          ]
      (is (spec/valid? ::rdf-app/transit-tag "\"[1 2 3]\"^^transit:json"))
      ;; applying set a boolean function...
      (is ((parents (rdf-app/render-literal-dispatch v))
           :rdf-app/TransitData))
      (is (= (rdf-app/render-literal v)
             "\"[1,2,3]\"^^transit:json"
             ))
      (is (= (transit-tag-untag v)
             v))
      (is (= (transit-round-trip v) v))
      ;; applying set a boolean function...
      (is ((parents (rdf-app/render-literal-dispatch s))
           :rdf-app/TransitData))
      (is (= (transit-tag-untag s)
             s))
      ;; applying set a boolean function...
      (is ((parents (rdf-app/render-literal-dispatch f))
           :rdf-app/TransitData))
      (is (= (transit-tag-untag f) f))
      (is (= (transit-round-trip v-of-k)
             v-of-k))
      )))


(deftest render-basic-literals-test
  "Basic render-literal implementations for numbers and language-tagged strings"
  (is (= (str (rdf-app/render-literal 1)) "1"))
  (is (= (str (rdf-app/render-literal 1.0)) "1.0"))
  (is (= (str (rdf-app/render-literal #?(:clj #voc/lstr "dog@en"
                                         :cljs (read-string "#voc/lstr \"dog@en\"")))
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

(deftest issue-11-caching
  (let [cache-rdfs (fn []
                     ;; caches the rdfs specification
                     (rdf-app/cache-url-as-local-file
                      (assert-unique test-context
                                     rdfs-web-resource :rdf/type :rdf-app/WebResource)
                      rdfs-web-resource))
        ]
    ;; with no urls...
    (let [cache (cache-rdfs)
          ]
      (is (.exists cache))
      (rdf-app/clear-url-cache! test-context)
      (is (not (.exists cache))))
    ;; with a url ...
    (let [cache (cache-rdfs)
          ]
      (is (.exists cache))
      (rdf-app/clear-url-cache! test-context rdfs-web-resource)
      (is (not (.exists cache))))
    ))

(deftest issue-12-check-qname-error
  ;; problem came from creating symbol of null ns. Should no longer throw error.
  (is (= (rdf-app/check-ns-metadata :http:%2F%2Fdatashapes.org%2Fdash#expectedResult)
         :http:%2F%2Fdatashapes.org%2Fdash#expectedResult)))

(defn do-read-ttl-from-github
  []
  (let [source (java.net.URL. "https://raw.githubusercontent.com/TopQuadrant/shacl/master/src/test/resources/sh/tests/rules/sparql/rectangle.test.ttl")
        ]
    (try
      (rdf-app/read-rdf test-context nil source)
      ;; this will fail as "No Method for Read RDF", but will cache a local file
      (catch Throwable e
        (let [e-data (ex-data e)
              ]
          e-data)))))

(deftest issue-14-read-ttl-from-github
  (let [result (do-read-ttl-from-github)]
    ;; No read method defined, but the cached file should be non-empty
    (is (= (:type result)
           :ont-app.rdf.core/NoMethodForReadRdf))
    (is (> (-> result
               :ont-app.rdf.core/file
               (.length))
           0))
    (.delete (-> result :ont-app.rdf.core/file))))


(deftest resource-types
  ;; introduced in voc version 0.4. Blank nodes have to be supported in RDF
  (is (= (voc/resource-type :rdf-app/_:yadda-yadda) :rdf-app/BnodeKwi))
  (is (= (voc/resource-type "_:yadda-yadda") :rdf-app/BnodeString))
  ;; The "URI" of a bnode is just the bnode string
  (is (= (voc/resource-type (voc/as-uri-string  :rdf-app/_:yadda-yadda))
         :rdf-app/BnodeString))
  (is (= (voc/resource-type (voc/as-kwi "_:yadda-yadda")) :rdf-app/BnodeKwi))
  ;; The "qname" of a bnode is just the bnode string
  (is (= (voc/as-qname :rdf-app/_:yadda-yadda)
         (voc/as-uri-string :rdf-app/_:yadda-yadda))))

(deftest issue-15-quoted-tagged-literals
  ;; triple-single-quotes for content including regular quotes....
  (is (= "'''[\"~:a\"]'''^^transit:json"
         (rdf-app/render-literal [:a])))
  (is (= "'''This is \"quoted\".'''"
         (rdf-app/render-literal "This is \"quoted\"."))))

(defn describe-api
  "Returns [`member`, ...] for `obj`, for public members of `obj`, sorted by :name,  possibly filtering on `name-re`
  - Where
    - `obj` is an object subject to reflection
    - `name-re` is a regular expression to match against (:name `member`)
    - `member` := m, s.t. (keys m) = #{:name, :parameter-types, :return-type}
  "
  ([obj]
   (let [collect-public-member (fn [acc member]
                                (if (not
                                     (empty?
                                      (clojure.set/intersection #{:public}
                                                                (:flags member))))
                                  (conj acc (select-keys member
                                                         [:name
                                                          :parameter-types
                                                          :return-type]))
                                  ;;else member is not public
                                  acc))]
     (sort (fn compare-names [this that] (compare (:name this) (:name that)))
           (reduce collect-public-member [] (:members (reflect obj))))))
  ([obj name-re]
   (filter (fn [member]
             (re-matches name-re (str (:name member))))
           (describe-api obj))))

(comment
  (require '[clojure.pprint :refer [pprint]])
  (require '[clojure.reflect :refer [reflect]])
  (require '[clojure.repl :refer [apropos]])
  (def g
    (->> (voc/prefix-to-ns)
         (reduce-kv rdf-app/collect-ns-catalog-metadata (make-graph))))
  (def dc-url (java.net.URL. "http://purl.org/dc/elements/1.1/"))
  (def m-type  (unique (igraph/get-o @rdf-app/resource-catalog dc-url :dcat/mediaType)))
  (def ext (let [
                 ]
             (-> (igraph/query rdf-app/ontology
                               [[:?media-url :formats/media_type m-type]
                                [:?media-url :formats/preferred_suffix :?suffix]])
                 (unique)
                 (:?suffix)
                 )))
   (rdf-app/cache-url-as-local-file (igraph/add @rdf-app/default-context
                                               [rdfs-web-resource
                                                :rdf/type :rdf-app/WebResource])
                                   rdfs-web-resource)
  ) ;; /comment
