(ns ont-app.rdf.test-support
  "These are tests that should be applicable to implementations of the igraph/rdf. query-for-failures should be empty."
  {
   :voc/mapsTo 'ont-app.rdf.ont
   ;; ;; this metadata can be used by downstream libraries that rely on RDF URIs...
   ;; :vann/preferredNamespacePrefix
   ;; "rdf-test" ;; matches :: declarations directly
   ;; :vann/preferredNamespaceUri
   ;; "http://rdf.naturallexicon.org/ont-app/igraph/rdf-test#"
   :clj-kondo/config '{:linters {:redundant-let {:level :off}}}
   }
  (:require
   [clojure.set]
   [clojure.java.io :as io]
   [ont-app.vocabulary.core :as voc]
   [ont-app.igraph.core :as igraph :refer [
                                           add
                                           add!
                                           subjects
                                           unique
                                           ]]
   [ont-app.igraph.graph :as native-normal]
   [ont-app.igraph.test-support :as igts]
   [ont-app.rdf.core :as rdf]
   )
  (:import
   [java.io
    File
    ]
   ))


(def bnode-test-data
  "A small turtle file containing blank nodes"
  (io/resource "test_support/bnode-test.ttl"))

(defn prepare-report
  "Returns a graph initialized for RDFImplementationReport"
  [make-graph-fn load-file-fn]
  (-> (native-normal/make-graph)
      (add [:rdf-app/RDFImplementationReport
            :rdf-app/makeGraphFn make-graph-fn
            :rdf-app/loadFileFn load-file-fn
            ])))


(def super-sub-query
  "A query to test for sub/super relations"
  (voc/prepend-prefix-declarations
                      "
Select ?sub ?super
Where
{
  ?super rdf-app:hasSubordinate ?sub.
  ?super a rdf-app:Test.
  ?sub a rdf-app:Subordinate.
}
"
                      ))
  
(defn test-bnode-support
  "Side-effects: Updates `report` with results of tests related to blank nodes
   Returns: modified `report`
  - Where
    - `report` is an atom containing a native-normal graph
    - (keys (@`report` :rdf-app/RDFImplementationReport)) :~ #{:rdf-app/loadFileFn}
    - `loadFileFn` links to fn[file-name] -> RDF IGraph implementation
  "
  [report]
  (let [load-rdf-file (unique (@report :rdf-app/RDFImplementationReport :rdf-app/loadFileFn))
        bnode-graph (load-rdf-file bnode-test-data)
        assert-and-report! (partial igts/do-assert-and-report! report #'test-bnode-support)
        ]
    (assert-and-report!
     :rdf-app/AllSubjectsAreBnodes
     "All subjects should match the bnode spec"
     (mapv rdf/bnode-kwi? (subjects bnode-graph))
     ["_" "_"]
     )
    
    (assert-and-report!
     :rdf-app/BnodeQueryForSuperSub
     "Query for super/sub"
     (empty? (unique (igraph/query bnode-graph super-sub-query)))
     false
     )
    
    (assert-and-report!
     :rdf-app/BnodeSuperShouldRoundTrip
     "Super/sub query returns a round-trippable blank node"
     (let [binding (unique (igraph/query bnode-graph super-sub-query))
           ]
       (if binding
         (empty? (bnode-graph (:super binding)))
         ;; else
         ::no-binding))
     false)
    
    (assert-and-report!
     :rdf-app/BnodeTraversalsShouldWork
     "A call to t-comp should work across blank nodes"
     (let [binding (unique (igraph/query bnode-graph super-sub-query))
           super (:super binding)
           ]
       (if super
         (empty? (bnode-graph super
                              (igraph/t-comp [:rdf-app/hasSubordinate
                                              :rdf-app/value])))
         ::no-super))
     false)
    
    report
    ))

(defn test-load-of-web-resource
  "Updates and returns a report on tests for loading web resources"
  [report]
  (let [load-rdf-file (unique (@report :rdf-app/RDFImplementationReport
                               :rdf-app/loadFileFn))
        rdfs-graph (load-rdf-file (java.net.URL. "http://www.w3.org/2000/01/rdf-schema#"))
        assert-and-report! (partial igts/do-assert-and-report! report #'test-load-of-web-resource)
        ]
      (assert-and-report!
       :rdf-app/RdfsSubjectsShouldNotBeEmpty
       "Loading a URL for a web resource"
       (let [subjects (igraph/subjects rdfs-graph)
             ]
         (empty? subjects))
       false))
  report)

(defn test-read-rdf-methods
  "Updates and returns a report testing read-rdf"
  [report]
  (let [make-graph (unique (@report
                            :rdf-app/RDFImplementationReport
                            :rdf-app/makeGraphFn))
        read-rdf-file (unique (@report
                               :rdf-app/RDFImplementationReport
                               :rdf-app/readFileFn))
        assert-and-report! (partial igts/do-assert-and-report! report #'test-load-of-web-resource)
        ]
    ;; Read web resource (rdfs schema)
    (let [rdfs-graph (read-rdf-file (make-graph)
                                    (java.net.URL.
                                     "http://www.w3.org/2000/01/rdf-schema#"))
          ]

      (assert-and-report!
       :rdf-app/RdfsSubjectsShouldNotBeEmpty
       "Loading a URL for a web resource"
       (let [subjects (igraph/subjects rdfs-graph)
             ]
         (empty? subjects))
       false)))
  report)

(defn test-write-rdf-methods
  "Updates and returns a report testing write-rdf"
  [report]
  (let [load-rdf-file (unique (@report
                               :rdf-app/RDFImplementationReport
                               :rdf-app/loadFileFn))
        write-rdf-file (unique (@report
                               :rdf-app/RDFImplementationReport
                               :rdf-app/writeFileFn))
        assert-and-report! (partial igts/do-assert-and-report! report #'test-load-of-web-resource)
        ]
    (let [g (load-rdf-file (java.net.URL. "http://www.w3.org/2000/01/rdf-schema#"))
          output  (io/file "/tmp/test-write-methods.ttl")
          ]
      (write-rdf-file g output)
      (assert-and-report!
       :rdf-app/WrittenFileShouldExist
       "Writing the file should exist"
       (.exists (io/file output))
       true)
      (assert-and-report!
       :rdf-app/WrittenFileShouldNotBeEmpty
       "Writing the file should exist"
       (> (.length (io/file output)) 0)
       true))
    report))


(def transit-test-map
  "A map containing clojure constructs serializable under transit"
  {:sub-map {:a "this is a string"}
   :vector [1 2 3]
   :set #{:a :b :c}
   :list '(1 2 3)
   :lstr #voc/lstr "jail@en-US"
   })

(defn test-transit-support
  "Side-effects: Updates `report` with results of tests related to transit serialization
   Returns: modified `report`
  - Where
    - `report` is an atom containing a native-normal graph
    - (keys (@`report` :rdf-app/RDFImplementationReport)) :~ #{:rdf-app/makeGraphFn, :rdf-app/loadFileFn, :rdf-app/writeFileFn}
    - `makeGraphFn` links to fn[] -> empty RDF IGraph implemenation
    - `loadFileFn` links to fn[file-name] -> RDF IGraph implementation
    - `writeFileFn` links to fn[graph file-name] -> ?, with side-effects that contents
         of `graph` are written to a file readable by `loadFileFn`.
  "
  
  [report]
  (let [make-graph (unique (@report :rdf-app/RDFImplementationReport :rdf-app/makeGraphFn))
        load-rdf-file  (unique (@report :rdf-app/RDFImplementationReport :rdf-app/loadFileFn))
        write-rdf-file (unique (@report :rdf-app/RDFImplementationReport :rdf-app/writeFileFn))
        assert-and-report! (partial igts/do-assert-and-report!
                                    report
                                    #'test-transit-support)
        ]
    (assert-and-report!
     :rdf-app/TransitDataShouldRoundTrip
     "Writing a graph with transit-test-map to a test file and reading back in"
     (let [g (make-graph)
           temp-file (File/createTempFile "test-transit-support" ".ttl")
           ]
       (add! g  [:rdf-app/TransitTestMap
                 :rdf-app/hasMap transit-test-map])
       (write-rdf-file g temp-file)
       (let [g' (load-rdf-file temp-file)]
         (= (unique (g' :rdf-app/TransitTestMap
                        :rdf-app/hasMap))
            transit-test-map)))
     true)
    report))
