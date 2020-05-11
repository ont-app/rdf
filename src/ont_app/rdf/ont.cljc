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
 'ont-app.validation.ont
 {
  :vann/preferredNamespacePrefix "rdf"
  :vann/preferredNamespaceUri "http://rdf.naturallexicon.org/rdf/ont#"
  })

(def ontology-atom (atom (make-graph)))

(defn update-ontology [to-add]
  (swap! ontology-atom add to-add))

