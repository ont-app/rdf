(ns ont-app.rdf.core
  {:doc "This is a backstop for shared logic between various RDF-based
  implementations of IGraph. 
It includes:
- support for LangStr using the #lstr custom reader
- support for ^^transit:json datatype tags
- templating utilities for the standard IGraph member access methods.
 "
   :author "Eric D. Scott"
   }
  (:require
   [clojure.string :as s]
   ;; [clojure.java.io :as io]
   [clojure.spec.alpha :as spec]
   ;; 3rd party
   ;; [selmer.parser :as selmer]
   [cljstache.core :as stache]
   ;; [taoensso.timbre :as timbre]
   [cognitect.transit :as transit]
   ;; ont-app
   [ont-app.graph-log.core :as glog]
   #?(:clj [ont-app.graph-log.levels :as levels
            :refer [warn debug trace value-trace value-debug]]
      :cljs [ont-app.graph-log.levels :as levels
            :refer-macros [warn debug trace value-trace value-debug]])
   [ont-app.igraph.core :as igraph]
   [ont-app.igraph.graph :as graph]
   [ont-app.vocabulary.core :as voc]
   ;; local
   [ont-app.vocabulary.lstr :as lstr :refer [->LangStr]]
   [ont-app.rdf.ont :as ont]
   )
  #?(:clj
     (:import
      [java.io ByteArrayInputStream ByteArrayOutputStream]
      )))

(voc/cljc-put-ns-meta!
 'ont-app.rdf.core
 {
  :voc/mapsTo 'ont-app.rdf.ont
  }
 )

;; aliases 
(def prefixed voc/prepend-prefix-declarations)

(def ontology @ont/ontology-atom)

;; FUN WITH READER MACROS

#?(:cljs
   (enable-console-print!)
   )

#?(:cljs
   (defn on-js-reload [] )
   )

;; standard clojure containers are declared by default as descendents of
;; :rdf-app/TransitData, which keys to the render-literal method for rendering
;; transit data. renders as transit by default.
;; Note that you can undo this with 'underive', in which case
;; it will probably be rendered as a string, unless you want
;; to write your own method...
(derive #?(:clj clojure.lang.PersistentVector
           :cljs cljs.core.PersistentVector)
        :rdf-app/TransitData)
(derive #?(:clj clojure.lang.PersistentHashSet
           :cljs cljs.core.PersistentHashSet )
        :rdf-app/TransitData)
(derive #?(:clj clojure.lang.PersistentArrayMap
           :cljs cljs.core.PersistentArrayMap )
        :rdf-app/TransitData)
(derive #?(:clj clojure.lang.PersistentList
           :cljs cljs.core.PersistentList )
        :rdf-app/TransitData)
(derive #?(:clj clojure.lang.Cons
           :cljs cljs.core.Cons )
        :rdf-app/TransitData)
(derive #?(:clj clojure.lang.LazySeq
           :cljs cljs.core.LazySeq )
        :rdf-app/TransitData)

(declare transit-read-handlers)
(defn read-transit-json
  "Returns a value parsed from transit string `s`
  Where
  <s> is a &quot;-escaped string encoded as transit
  Note: custom datatypes will be informed by @transit-read-handlers
  "
     [^String s]
     #?(:clj
        (transit/read
         (transit/reader
          (ByteArrayInputStream. (.getBytes (clojure.string/replace
                                             s
                                             "&quot;" "\"")
                                            "UTF-8"))
          :json
          {:handlers @transit-read-handlers}))
        :cljs
        (transit/read
         (transit/reader
          :json
          {:handlers @transit-read-handlers})
         (clojure.string/replace
          s
          "&quot;" "\""))))

(declare transit-write-handlers)
(defn render-transit-json 
  "Returns a string of transit for `value`
  Where
  <value> is any value that be handled by cognitict/transit
  Note: custom datatypes will be informed by @transit-write-handlers
  "
  [value]
  #?(:clj
     (let [output-stream (ByteArrayOutputStream.)
           ]
       (transit/write
        (transit/writer output-stream :json {:handlers @transit-write-handlers})
        value)
       (String. (.toByteArray output-stream)))
     :cljs
     (transit/write
      (transit/writer :json {:handlers @transit-write-handlers})
      value)))

;; SPECS
(def transit-re
  "Matches data tagged as transit:json"
  #"^\"(.*)\"\^\^transit:json$")

(spec/def ::transit-tag (spec/and string? (fn [s] (re-matches transit-re s))))

(defn bnode-kwi?
  "True when `kwi` matches output of `bnode-translator`."
  [kwi]
  (->> (namespace kwi)
       (re-matches #"^_.*"))) 

(spec/def ::bnode-kwi bnode-kwi?)

;;;;;;;;;;;;;;;;;;;;
;; LITERAL SUPPORT
;;;;;;;;;;;;;;;;;;;;

(defn quote-str 
  "Returns `s`, in escaped quotation marks.
Where
<s> is a string, typically to be rendered in a query or RDF source.
"
  [s]
  (value-trace
   ::QuoteString
   (str "\"" s "\"")
   ))

(def transit-write-handlers
  "Atom of the form {<Class> <write-handler>, ...}
  Where
  <Class> is a direct reference to the class instance to be encoded
  <write-handler> := fn [s] -> {<field> <value>, ...}
  " 
  (atom
   {ont_app.vocabulary.lstr.LangStr
    (cognitect.transit/write-handler
     "ont-app.vocabulary.lstr.LangStr"
     (fn [ls]
       {:lang (.lang ls)
        :s (.s ls)
        }))
    }))

(def transit-read-handlers
  "Atom of the form {<className> <read-handler>
  Where
  <className> is a fully qualified string naming a class to be encoded
  <read-handler> := fn [from-rep] -> <instance>
  <from-rep> := an Object s.t. (<field> from-rep), encoded in corresponding
    write-handler in @`transit-write-handlers`.
  "
  (atom
   {"ont-app.vocabulary.lstr.LangStr"
    (cognitect.transit/read-handler
     (fn [from-rep]
       (->LangStr (:s from-rep) (:lang from-rep))))
    }
    ))


(defn render-literal-as-transit-json
  "Returns 'x^^transit:json'
  NOTE: this will be encoded on write and decoded on read by the
    cognitect/transit library."
  [x]
  (stache/render "\"{{x}}\"^^transit:json" {:x (render-transit-json x)}))

;; RENDER LITERAL

(def special-literal-dispatch
  "A function [x] -> <dispatch-value>
  Where
  <x> is any value, probabaly an RDF literal
  <dispatch-value> is a value to be matched to a render-literal-dispatch method.
  Default is to return nil, signalling no special dispatch."
  (atom (fn [_] nil)))

(defn render-literal-dispatch
  "Returns a key for the render-literal method to dispatch on given `literal`
  Where
  <literal> is any non-keyword
  NOTE: LangStr and non-nil `special-dispatch` are special cases; otherwise
    (type <literal>)
  "
  [literal]
  (value-trace
   ::RenderLiteralDispatch
   [:log/literal literal]
   (if-let [special-dispatch (@special-literal-dispatch literal)]
     special-dispatch
     ;; else no special dispatch...
   (cond
     (instance? ont_app.vocabulary.lstr.LangStr literal) :rdf-app/LangStr
     :default (type literal)))))

(defmulti render-literal
  "Returns an RDF (Turtle) rendering of `literal`"
  render-literal-dispatch)

(defmethod render-literal :rdf-app/TransitData
  [v]
  (render-literal-as-transit-json v))

(defmethod render-literal :default
  [s]
  (quote-str s))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; STANDARD TEMPLATES FOR IGRAPH MEMBER ACCESS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- query-template-map 
  "Returns {<k> <v>, ...} appropriate for <rdf-store>
Where
<k> and <v> are cljstache template parameters which may appear in some query, 
  e.g. named graph open/close clauses
<rdf-store> is an RDF store.
"
  [graph-uri rdf-store]
  {:graph-name-open (if graph-uri
                      (str "GRAPH <" (voc/iri-for graph-uri) "> {")
                      "")
   :graph-name-close (if graph-uri 
                      (str "}")
                      "")
   })                  

(def subjects-query-template
  ;; note the use of 3 brackets to turn off escaping
  "
  Select Distinct ?s Where
  {
    {{{graph-name-open}}} 
    ?s ?p ?o.
    {{{graph-name-close}}}
  }
  ")

(defn query-for-subjects 
  "Returns [<subject> ...] at endpoint of `rdf-store`
Where
  <subject> is the uri of a subject from <rdf-store>, 
  rendered per the binding translator of <rdf-store>
  <rdf-store> conforms to ::sparql-client spec
  <query-fn> := fn [repo] -> bindings
  <graph-uri> is a URI  or KWI naming the graph  (or nil if DEFAULT graph)
"
  ([query-fn rdf-store]
   (query-for-subjects (fn [_] nil) query-fn rdf-store)
   )
  
  ([graph-uri query-fn rdf-store]
   (let [query (stache/render subjects-query-template
                              (query-template-map graph-uri rdf-store))
         ]
     (map :s
          (query-fn rdf-store query)))))

(def normal-form-query-template
  "
  Select ?s ?p ?o
  Where
  {
    {{{graph-name-open}}}
    ?s ?p ?o
    {{{graph-name-close}}}
  }
  ")

(defn query-for-normal-form
  "Returns IGraph normal form for <graph> named by `graph-uri` in `rdf-store`
  Where
  <graph> is a named graph in <rdf-store>
  <graph-uri> is a URI or KWI naming the graph (default nil -> DEFAULT graph)
  <rdf-store> is an RDF store 
  <query-fn> := fn [rdf-store sparql-query] -> #{<bmap>, ...}
  <bmap> := {:?s :?p :?o}
  <sparql-query> :- `normal-form-query-template`
  "
  ([query-fn rdf-store]
   (query-for-normal-form nil query-fn rdf-store))
  
  ([graph-uri query-fn rdf-store]
   (letfn [
           (add-o [o binding]
             (conj o (:o binding)))
           (add-po [po binding]
             (assoc po (:p binding)
                    (add-o (get po (:p binding) #{})
                           binding)))
           (collect-binding [spo binding]
             (value-trace
              ::CollectNormalFormBinding
              [:log/spo spo
               :log/binding binding]
              (assoc spo (:s binding)
                     (add-po (get spo (:s binding) {})
                             binding))))
          
          ]
    (let [query (stache/render normal-form-query-template
                               (query-template-map graph-uri rdf-store))
          ]
      (value-trace
       ::QueryForNormalForm
       [:log/query query
        :log/graph-uri graph-uri
        :log/query-fn query-fn]
       (reduce collect-binding
               {}
               (query-fn rdf-store query)))))))


(defn check-ns-metadata 
  "Logs a warning when `kwi` is in a namespace with no metadata."
  [kwi]
  (let [n (symbol (namespace kwi))]
    (if-let [the-ns (find-ns n)]
      (when (not (meta the-ns))
        (warn ::NoMetaDataInNS
              :glog/message "The namespace for {{log/kwi}} is in a namespace with no associated metadata."
              :log/kwi kwi))))
  kwi)


(defn check-qname 
  "Traps the keyword assertion error in voc and throws a more meaningful error 
about blank nodes not being supported as first-class identifiers."
  [uri-spec]
  (if (bnode-kwi? uri-spec)
    uri-spec
    ;;else not a blank node
    (try
      (voc/qname-for (check-ns-metadata uri-spec))
      (catch java.lang.AssertionError e
        (if (= (str e)
               "java.lang.AssertionError: Assert failed: (keyword? kw)")
          (throw (ex-info (str "The URI spec " uri-spec " is not a keyword.\nCould it be a blank node?\nIf so, blank nodes cannot be treated as first-class identifiers in SPARQL. Use a dedicated query that traverses the blank node instead.")
                          (merge (ex-data e)
                                 {:type ::Non-Keyword-URI-spec
                                  ::uri-spec uri-spec
                                  })))
                             
          ;; else it's some other message
          (throw e))))))

(def query-for-p-o-template
  "
  Select ?p ?o Where
  {
    {{{graph-name-open}}}
    {{{subject}}} ?p ?o.
    {{{graph-name-close}}}
  }
  ")

(defn query-for-p-o 
  "Returns {<p> #{<o>...}...} for `s` at endpoint of `rdf-store`
Where
  <p> is a predicate URI rendered per binding translator of <rdf-store>
  <o> is an object value, rendered per the binding translator of <rdf-store>
  <s> is a subject uri keyword. ~ voc/voc-re
  <rdf-store> is and RDF store.
  <query-fn> := fn [repo] -> bindings
  <graph-uri> is a URI or KWI naming the graph (or nil if DEFAULT graph)
"
  ([query-fn rdf-store s]
   (query-for-p-o nil  query-fn rdf-store s)
   )
  (
  [graph-uri query-fn rdf-store s]
  (let [query  (prefixed
                (stache/render query-for-p-o-template
                               (merge (query-template-map graph-uri rdf-store)
                                      {:subject (check-qname s)})))
        collect-bindings (fn [acc b]
                           (update acc (:p b)
                                   (fn[os] (set (conj os (:o b))))))
                                                
        ]
    (value-debug
     ::query-for-po
     [::query query ::subject s]
     (reduce collect-bindings {}
             (query-fn rdf-store query))))))


(def query-for-o-template
  "
  Select ?o Where
  {
    {{{graph-name-open}}}
    {{{subject}}} {{{predicate}}} ?o.
    {{{graph-name-close}}}
  }
  ")

(defn query-for-o 
  "Returns #{<o>...} for `s` and `p` at endpoint of `rdf-store`
Where:
  <o> is an object rendered per binding translator of <rdf-store>
  <s> is a subject URI rendered per binding translator of <rdf-store>
  <p> is a predicate URI rendered per binding translator of <rdf-store>
  <rdf-store> is an RDF store
  <query-fn> := fn [repo] -> bindings
  <graph-uri> is a URI or KWI naming the graph (or nil if DEFAULT graph)
  "
  ([query-fn rdf-store s p]
   (query-for-o nil  query-fn rdf-store s p))
  
  ([graph-uri query-fn rdf-store s p]
   (let [query  (prefixed
                 (stache/render
                  query-for-o-template
                  (merge (query-template-map graph-uri rdf-store)
                         {:subject (check-qname s)
                          :predicate (check-qname p)})))
        
         collect-bindings (fn [acc b]
                            (conj acc (:o b)))
                                                
        ]
     (value-debug
      ::query-for-o-return
      [::query query
       ::subject s
       ::predicate p]
      (reduce collect-bindings #{}
              (query-fn rdf-store query))))))

(def ask-s-p-o-template
  "ASK where
  {
    {{{graph-name-open}}}
    {{{subject}}} {{{predicate}}} {{{object}}}.
    {{graph-name-close}}
  }"
  )


(defn ask-s-p-o 
  "Returns true if `s` `p` `o` is a triple at endpoint of `rdf-store`
Where:
  <s> <p> <o> are subject, predicate and object
  <rdf-store> is an RDF store
  <graph-uri> is a URI or KWI naming the graph (or nil if DEFAULT graph)
  <ask-fn> := fn [repo] -> bindings
"
  ([ask-fn rdf-store s p o]
   (ask-s-p-o nil ask-fn rdf-store s p o)
   )
  ([graph-uri ask-fn rdf-store s p o]
  
  (let [query (prefixed
               (stache/render
                ask-s-p-o-template
                (merge (query-template-map graph-uri rdf-store)
                       {:subject (check-qname s)
                        :predicate (check-qname p)
                        :object (if (keyword? o)
                                  (voc/qname-for o)
                                  (render-literal o))})))
        starting (debug ::Starting_ask-s-p-o
                        :log/query query
                        :log/subject s
                        :log/predicate p
                        :log/object o)
        ]
    (value-debug
     ::ask-s-p-o-return
     [:log/resultOf starting]
     (ask-fn rdf-store query)))))





