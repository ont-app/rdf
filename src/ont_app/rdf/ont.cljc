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
   :formats/media_type "application/transit+json"
   ;;:igraph/mimeType "application/transit+json"
   :rdfs/comment "Refers to transit data encoded as json. Literals whose 
  :datatype metadata is :transit/json should be readable with transit/read 
   encoded for format :json"
   ]
  [:transit/msgpack
   :rdf/type :igraph/SerializationFormat
   :transit/format :msgpack
   :formats/media_type "application/transit+msgpack"
   ;; :igraph/mimeType "application/transit+msgpack"
   :rdfs/comment "Refers to the Transit data encoded as msgpack. Literals whose 
  :datatype metadata is :transit/msgpack should be readable with transit/read 
   encoded for format :msgpack (not currently supported)"
   ]
  ])

;; MIME types

(voc/put-ns-meta!
 'ont-app.rdf.formats
 {
  :vann/preferredNamespacePrefix "formats"
  :vann/preferredNamespaceUri "http://www.w3.org/ns/formats/"
  :dc/description "File formats for various RDF data"
  :foaf/homepage "https://www.w3.org/ns/formats/"
  })

(update-ontology! [:dct/MediaTypeOrExtent
                   :rdfs/seeAlso (voc/keyword-for "https://www.w3.org/ns/formats/")
                   ])



(def formats
  "A listing of formats to be loaded into `ontology-atom`"
  [[:formats/JSON-LD "application/ld+json" ".jsonld"]
   [:formats/N3 "text/rdf+n3" ".n3"]
   [:formats/N-triples "application/n-triples" ".nt"]
   [:formats/N-Quads "application/n-quads" ".nq"]
   [:formats/LD_patch "text/ldpatch" ".ldp"]
   [:formats/OWL_XML "application/owl+xml" ".owx"]
   [:formats/OWL_Functional "text/owl-functional" ".ofn"]
   [:formats/OWL_Manchester "text/owl-manchester" ".ofm"]
   [:formats/POWDER "powder+xml" ".wdr"]
   [:formats/POWDER-S "powder-s+xml" ".wdrs"]
   [:formats/PROV-N "text/provenance-notation" ".provn"]
   [:formats/PROV-XML "application/provenance+xml" ".provx"]
   [:formats/RDF_JSON "applicaton/rdf+json" ".rj"]
   [:formats/RDF_XML "application/rdf+xml" ".rdf"]
   [:formats/RIF_XML "applicaton/rif+xml" ".rif" ] ;; rule interchange
   [:formats/SPARQL_RESULTS_XML "application/sparql-results+xml" ".srx"]
   [:formats/SPARQL_RESULTS_JSON "application/sparql-results+json" ".srj"]
   [:formats/SPARQL_RESULTS_CSV "text/csv" ".csv"]
   [:formats/SPARQL_RESULTS_TSV "text/tab-separated-values" ".tsv"]
   [:formats/Turtle "text/turtle" ".ttl"]
   [:formats/TriG "applicaton/trig" ".trig"]
   ])

(defn add-media-type
  "sided-effect adds entries in the ontology for `kwi` `media-type` `suffix`
  Typically applied to `formats` listing.
  "
  [kwi media-type suffix]
  (update-ontology! [kwi
                     :rdf/type :dct/MediaTypeOrExtent
                     :formats/media_type media-type
                     :formats/preferred_suffix suffix
                     ]))

(doseq [[k m s] formats]
  (add-media-type k m s))
         
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

