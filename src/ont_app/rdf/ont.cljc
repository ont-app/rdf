(ns ont-app.rdf.ont
  (:require
   ;;
   [ont-app.igraph.core :as igraph :refer [add]]
   [ont-app.igraph.graph :as g :refer [make-graph]]
   [ont-app.igraph-vocabulary.core :as igv]
   [ont-app.vocabulary.core :as voc]
   )
  )
(voc/cljc-put-ns-meta!
 'ont-app.rdf.ont
 {
  :vann/preferredNamespacePrefix "rdf-app"
  :vann/preferredNamespaceUri "http://rdf.naturallexicon.org/rdf/ont#"
  })

(def ontology-atom (atom (make-graph)))

(defn update-ontology! [to-add]
  (swap! ontology-atom add to-add))


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
   :rdfs/comment "Refers to a format used to encode/decode values"
   ]
  [:transit/format
   :rdfs/domain :igraph/SerializationFormat
   :rdfs/range :rdf-app/Literal
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
  [:rdf-app/TransitData
   :rdf/type :rdf/Class
   :rdfs/comment "Refers to Clojure data which should be encoded/decoded as 
transit via a `derive` statement. There is a render-literal keyed to the KWI 
for this class."
   ]
  ])
