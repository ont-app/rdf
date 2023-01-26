(ns ont-app.rdf.ont
  (:require
   ;;
   [ont-app.igraph.core :as igraph :refer [add]]
   [ont-app.igraph.graph :as g :refer [make-graph]]
   [ont-app.vocabulary.core :as voc]
   )
  )
(voc/put-ns-meta!
 'ont-app.rdf.ont
 {
  :vann/preferredNamespacePrefix "rdf-app"
  :vann/preferredNamespaceUri "http://rdf.naturallexicon.org/ont-app/rdf/ont#"
  :doc "Namespace for constructs used by RDF-based Igraph implementations"
  :dc/description "Namespace for constructs used by RDF-based Igraph implementations in Clojure."
  :dc/creator "Eric D. Scott"
  })

(def ontology-atom "An atom holding a native-normal graph containing a supporting vocabulary for the code in `ont-app.rdf.core`"
  (atom (make-graph)))

(defn ^:private update-ontology! [to-add]
  (swap! ontology-atom add to-add))

;; I/O methods
(update-ontology!
 [[:rdf-app/dispatchAs
   :rdfs/comment "asserts an ID characterizing how some element of a multi-method should be dispatched. Typically referenced in the 'context' argument of on i/o method"
   ]])

;; LITERAL TYPES
(update-ontology!
 [[:rdf-app/LiteralType
   :rdf/type :rdfs/Class
   :rdfs/comment "Refers to a type of literal which may have its own 
render-literal method."
   ]
  [:rdf-app/Instant
   :rdf/type :rdf-app/LiteralType
   :rdfs/comment "A dispatch value for Clojure instants"
   ]
  [:rdf-app/XsdDatatype
   :rdf/type :rdf-app/LiteralType
   :rdfs/comment "A dispatch value for literals tagged as xsd datatypes."
   ]
  [:rdf-app/LangStr
   :rdf/type :rdf-app/LiteralType
   :rdfs/comment "A dispatch value for literals encoded as a language-tagged 
   string"
   ]
  [:rdf-app/TransitData
   :rdf/type :rdf-app/LiteralType
   :rdfs/comment "Refers to Clojure data which should be encoded/decoded as 
transit via a `derive` statement. There is a render-literal keyed to the KWI 
for this class."
   ]
  ])


;; TRANSIT SUPPORT
(voc/put-ns-meta!
 'cognitect.transit
 {
  :vann/preferredNamespacePrefix "transit"
  :vann/preferredNamespaceUri "http://rdf.naturallexicon.org/ns/cognitect.transit#"
  :dc/description "Functionality for the transit serialization format"
  :foaf/homepage "https://github.com/cognitect/transit-format"
  })


(update-ontology!
 [[:igraph/SerializationFormat
   :rdf/type :rdfs/Class
   :rdfs/comment "Refers to a format used to encode/decode serialized values"
   ]
  [:transit/format
   :rdfs/domain :igraph/SerializationFormat
   :rdfs/range :rdf/Literal
   :rdfs/comment "Asserts the name of the transit encoding format"
   ]
  [:transit/json
   :rdf/type :igraph/SerializationFormat
   :transit/format :json
   :igraph/mimeType "application/transit+json"
   :rdfs/comment "Refers to transit data encoded as json. Literals whose 
  :datatype metadata is :transit/json should be readable with transit/read 
   encoded for format :json"
   ]
  [:transit/msgpack
   :rdf/type :igraph/SerializationFormat
   :transit/format :msgpack
   :igraph/mimeType "application/transit+msgpack"
   :rdfs/comment "Refers to the Transit data encoded as msgpack. Literals whose 
  :datatype metadata is :transit/msgpack should be readable with transit/read 
   encoded for format :msgpack (not currently supported)"
   ]
  ])

;; TEST SUPPORT
(update-ontology!
 [[:rdf-app/RDFImplementationReport
   :rdf/type :rdfs/Class
   :dc/description "A report for some implemenation of the rdf module testing things like how blank nodes are handled and transit serialization. Builds on ont-app.igraph.test-support"
   ]
  [:rdf-app/makeGraphFn
   :rdfs/domain :rdf-ap/RDFImplemantationReport
   :dc/description "A function [] -> RDF IGraph implemenation, used to produce the implemenation report"
   ]
  [:rdf-app/readFileFn
   :rdfs/domain :rdf-ap/RDFImplemantationReport
   :dc/description "A function [g rdf-file-path] -> g'. Where g' is an RDF IGraph implemenation; used to produce the implemenation report"
   ]
  [:rdf-app/writeFileFn
   :rdfs/domain :rdf-ap/RDFImplemantationReport
   :dc/description "A function [g rdf-file-path] -> ?. With side-effect of writing an RDF file to file-path s.t. it can be read by readFileFn; used to produce the implemenation report"
   ]
  [:rdf-app/TestSupportTest
   :rdf/type :rdfs/Class;
   :dc/description "Names a to be performed against an implementation of the igraph/rdf library"
   ]
  [:rdf-app/AllSubjectsAreBnodes :rdf/type :rdf-app/TestSupportTest]
  [:rdf-app/BnodeQueryForSuperSub :rdf/type :rdf-app/TestSupportTest]
  [:rdf-app/BnodeSuperShouldRoundTrip :rdf/type :rdf-app/TestSupportTest]
  [:rdf-app/BnodeTraversalsShouldWork :rdf/type :rdf-app/TestSupportTest]
  [:rdf-app/TransitDataShouldRoundTrip :rdf/type :rdf-app/TestSupportTest]
  ] 
 )

