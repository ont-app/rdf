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
   [clojure.java.io :as io]
   [clojure.spec.alpha :as spec]
   ;; 3rd party
   [selmer.parser :as selmer]
   [taoensso.timbre :as timbre]
   [cognitect.transit :as transit]
   ;; ont-app
   [ont-app.graph-log.core :as glog]
   [ont-app.graph-log.levels :as levels :refer :all]
   [ont-app.igraph.core :as igraph :refer :all]
   [ont-app.igraph.graph :as graph]
   [ont-app.vocabulary.core :as voc]
   ;; local
   [ont-app.rdf.ont :as ont]
   )
   (:import [java.io ByteArrayInputStream ByteArrayOutputStream])
            
  )

(voc/cljc-put-ns-meta!
 'ont-app.rdf.core
 {
  :voc/mapsTo 'ont-app.rdf.ont
  }
 )

(def ontology @ont/ontology-atom)

;; FUN WITH READER MACROS

#?(:cljs
   (enable-console-print!)
   )

#?(:cljs
   (defn on-js-reload [] )
   )

;; NO READER MACROS BEYOND THIS POINT


(def prefixed voc/prepend-prefix-declarations)


;; SPECS
(def transit-re
  "Matches data tagged as transit:json"
  #"^\"(.*)\"\^\^transit:json$")

(spec/def ::transit-tag (spec/and string? (fn [s] (re-matches transit-re s))))

;;;;;;;;;;;;;;;;;;;;
;; LITERAL SUPPORT
;;;;;;;;;;;;;;;;;;;;

(defn quote-str [s]
  "Returns `s`, in escaped quotation marks.
Where
<s> is a string, typically to be rendered in a query or RDF source.
"
  (value-trace
   ::QuoteString
   (str "\"" s "\"")
   ))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; LANGSTR
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(deftype LangStr [s lang]
  Object
  (toString [_] s)
  (equals [this that]
    (and (instance? LangStr that)
         (= s (.s that))
         (= lang (.lang that)))))


(defn lang [langStr]
  (.lang langStr))

(defmethod print-method LangStr
  [literal ^java.io.Writer w]
  (.write w (str "#lstr \"" literal "@" (.lang literal) "\"")))

(defmethod print-dup LangStr [o ^java.io.Writer w]
  (print-method o w))

(defn ^LangStr read-LangStr [form]
  (let [langstring-re #"^(.*)@([-a-zA-Z]+)" 
        m (re-matches langstring-re form)
        ]
    (when (not= (count m) 3)
      (throw (ex-info "Bad LangString fomat"
                      {:type ::BadLangstringFormat
                       :regex langstring-re
                       :form form})))
    (let [[_ s lang] m]
      (LangStr. s lang))))


(def transit-write-handlers
  "Atom of the form {<Class> <write-handler>, ...}
  Where
  <Class> is a direct reference to the class instance to be encoded
  <write-handler> := fn [s] -> {<field> <value>, ...}
  " 
  (atom
   {LangStr
    (cognitect.transit/write-handler
     "ont-app.igraph-grafter.rdf.LangStr"
     (fn [ls]
       {:lang (.lang ls)
        :s (.s ls)
        }))
    }))

  
(defn render-transit-json 
  "Returns a string of transit for `value`
  Where
  <value> is any value that be handled by cognitict/transit
  Note: custom datatypes will be informed by @transit-write-handlers
  "
  [value]
  (let [output-stream (ByteArrayOutputStream.)
        ]
    (transit/write
     (transit/writer output-stream :json {:handlers @transit-write-handlers})
     value)
    (String. (.toByteArray output-stream))))


(def transit-read-handlers
  "Atom of the form {<className> <read-handler>
  Where
  <className> is a fully qualified string naming a class to be encoded
  <read-handler> := fn [from-rep] -> <instance>
  <from-rep> := an Object s.t. (<field> from-rep), encoded in corresponding
    write-handler in @`transit-write-handlers`.
  "
  (atom
   {"ont-app.igraph-grafter.rdf.LangStr"
    (cognitect.transit/read-handler
     (fn [from-rep]
       (->LangStr (:s from-rep) (:lang from-rep))))
    }
    ))

(defn read-transit-json
  "Returns a value parsed from transit string `s`
  Where
  <s> is a &quot;-escaped string encoded as transit
  Note: custom datatypes will be informed by @transit-read-handlers
  "
  [^String s]
  (transit/read
   (transit/reader
    (ByteArrayInputStream. (.getBytes (clojure.string/replace s "&quot;" "\"")
                                      "UTF-8"))
    :json
    {:handlers @transit-read-handlers})))

(defn render-literal-as-transit-json
  "Returns 'x^^transit:json'
  NOTE: this will be encoded on write and decoded on read by the
    cognitect/transit library."
  [x]
  (selmer/render "\"{{x}}\"^^transit:json" {:x (render-transit-json x)}))

;; RENDER LITERAL

(def special-literal-dispatch
  "A function [x] -> <dispatch-value>
  Where
  <x> is any value, probabaly an RDF literal
  <dispatch-value> is a value to be matched to a render-literal-dispatch method.
  Default is to return nil."
  (atom (fn [_] nil)))

(defn render-literal-dispatch
  "Returns a key for the render-literal method to dispatch on given `literal`
  Where
  <literal> is any non-keyword
  NOTE: ::instant and ::xsd-type are special cases, otherwise (type <literal>)
  "
  [literal]
  (value-trace
   ::RenderLiteralDispatch
   [:log/iteral literal]
   (if-let [special-dispatch (@special-literal-dispatch literal)]
     special-dispatch
     ;; else no special dispatch...
   (cond
     ;; (inst? literal) ::instant
     ;; (endpoint/xsd-type-uri literal) ::xsd-type
     (instance? LangStr literal) ::LangStr
     :default (type literal)))))

(defmulti render-literal
  "Returns an RDF (Turtle) rendering of `literal`"
  render-literal-dispatch)

;; standard clojure containers renders as transit by default.
;; Note that you can undo this with 'underive', in which case
;; it will probably be rendered as a string, unless you want
;; to write your own method...
(derive clojure.lang.PersistentVector :rdf/TransitData)
(derive clojure.lang.PersistentHashSet :rdf/TransitData)
(derive clojure.lang.PersistentArrayMap :rdf/TransitData)
(derive clojure.lang.PersistentList :rdf/TransitData)
(derive clojure.lang.Cons :rdf/TransitData)
(derive clojure.lang.LazySeq :rdf/TransitData)

(defmethod render-literal :rdf/TransitData
  [v]
  (render-literal-as-transit-json v))

(defmethod render-literal :default
  [s]
  (quote-str s)
  )


(defn bnode-kwi?
  "True when `kwi` matches output of `bnode-translator`."
  [kwi]
  (->> (namespace kwi)
       (re-matches #"^_.*")))

(spec/def ::bnode-kwi bnode-kwi?)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; STANDARD TEMPLATES FOR IGRAPH MEMBER ACCESS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- query-template-map [graph-uri-fn client]
  "Returns {<k> <v>, ...} appropriate for <client>
Where
<k> and <v> are selmer template parameters which may appear in some query, e.g.
  named graph open/close clauses
<client> is a ::sparql-client
"
  {:graph-name-open (if-let [graph-uri (graph-uri-fn client)]
                      (str "GRAPH <" (voc/iri-for graph-uri) "> {")
                      "")
   :graph-name-close (if-let [graph-uri (graph-uri-fn client)]
                      (str "}")
                      "")
   })                          

(def subjects-query-template
  "
  Select Distinct ?s Where
  {
    {{graph-name-open|safe}}
    ?s ?p ?o.
    {{graph-name-close|safe}}
  }
  ")

(defn query-for-subjects 
  "Returns [<subject> ...] at endpoint of `client`
Where
<subject> is the uri of a subject from <client>, 
  rendered per the binding translator of <client>
<client> conforms to ::sparql-client spec
"
  ([query-fn client]
   (query-for-subjects (fn [_] nil) query-fn client)
   )
  
  ([graph-uri-fn query-fn client]
   
   (let [query (selmer/render subjects-query-template
                              (query-template-map graph-uri-fn client))
         ]
     (map :s
          (query-fn client query)))))

(def normal-form-query-template
  "
  Select ?s ?p ?o
  Where
  {
    {{graph-name-open|safe}}
    ?s ?p ?o
    {{graph-name-close}}
  }
  ")

(defn query-for-normal-form
  ([query-fn client]
   (query-for-normal-form (fn [_] nil) query-fn client))
  
  ([graph-uri-fn query-fn client]
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
    (let [query (selmer/render normal-form-query-template
                               (query-template-map graph-uri-fn client))
          ]
      (reduce collect-binding
              {}
              (query-fn client query))))))


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


(defn check-qname [uri-spec]
  "Traps the keyword assertion error in voc and throws a more meaningful error about blank nodes not being supported as first-class identifiers."
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
    {{graph-name-open|safe}}
    {{subject|safe}} ?p ?o.
    {{graph-name-close|safe}}
  }
  ")

(defn query-for-p-o 
  "Returns {<p> #{<o>...}...} for `s` at endpoint of `client`
Where
<p> is a predicate URI rendered per binding translator of <client>
<o> is an object value, rendered per the binding translator of <client>
<s> is a subject uri keyword. ~ voc/voc-re
<client> conforms to ::sparql-client
"
  ([query-fn client s]
   (query-for-p-o (fn [_] nil) query-fn client s)
   )
  (
  [graph-uri-fn query-fn client s]
  (let [query  (prefixed
                (selmer/render query-for-p-o-template
                               (merge (query-template-map graph-uri-fn client)
                                      {:subject (check-qname s)})))
        collect-bindings (fn [acc b]
                           (update acc (:p b)
                                   (fn[os] (set (conj os (:o b))))))
                                                
        ]
    (value-debug
     ::query-for-po
     [::query query ::subject s]
     (reduce collect-bindings {}
             (query-fn client query))))))


(def query-for-o-template
  "
  Select ?o Where
  {
    {{graph-name-open|safe}}
    {{subject|safe}} {{predicate|safe}} ?o.
    {{graph-name-close|safe}}
  }
  ")

(defn query-for-o 
  "Returns #{<o>...} for `s` and `p` at endpoint of `client`
Where:
<o> is an object rendered per binding translator of <client>
<s> is a subject URI rendered per binding translator of <client>
<p> is a predicate URI rendered per binding translator of <client>
<client> conforms to ::sparql-client
"
  ([query-fn client s p]
   (query-for-o (fn [_] nil) query-fn client s p))
  
  ([graph-uri-fn query-fn client s p]
   (let [query  (prefixed
                 (selmer/render
                  query-for-o-template
                  (merge (query-template-map graph-uri-fn client)
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
              (query-fn client query))))))

(def ask-s-p-o-template
  "ASK where
  {
    {{graph-name-open|safe}}
    {{subject|safe}} {{predicate|safe}} {{object|safe}}.
    {{graph-name-close}}
  }"
  )


(defn ask-s-p-o 
  "Returns true if `s` `p` `o` is a triple at endpoint of `client`
Where:
<s> <p> <o> are subject, predicate and object
<client> conforms to ::sparql-client
"
  ([ask-fn client s p o]
   (ask-s-p-o (fn [_] nil) ask-fn client s p o)
   )
  ([graph-uri-fn ask-fn client s p o]
  
  (let [query (prefixed
               (selmer/render
                ask-s-p-o-template
                (merge (query-template-map graph-uri-fn client)
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
     (ask-fn client query)))))

