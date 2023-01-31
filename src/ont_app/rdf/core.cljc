(ns ont-app.rdf.core
  {:doc "This is a backstop for shared logic between various RDF-based
  implementations of IGraph. 
It includes:
- support for LangStr using the #voc/lstr custom reader
- support for ^^transit:json datatype tags
- templating utilities for the standard IGraph member access methods.
 "
   :author "Eric D. Scott"
   ;; These errors were found to be spurious, related to cljs ...
   :clj-kondo/config '{:linters {:unresolved-symbol {:level :off}
                                 }}
   }
  (:require
   [clojure.string :as s]
   [clojure.spec.alpha :as spec]
   [clojure.java.io :as io]
   ;; 3rd party
   [clj-http.client :as http]
   [cljstache.core :as stache]
   [cognitect.transit :as transit]
   ;; ont-app
   #?(:clj [ont-app.graph-log.levels :as levels
            :refer [warn debug trace value-trace value-debug]]
      :cljs [ont-app.graph-log.levels :as levels
             :refer-macros [warn debug value-trace value-debug]])
   [ont-app.igraph.core :as igraph :refer [unique]]
   [ont-app.igraph.graph :as native-normal]
   [ont-app.vocabulary.core :as voc]
   [ont-app.vocabulary.lstr :as lstr :refer [->LangStr]]
   [ont-app.rdf.ont :as ont]
   )
  #?(:clj
     (:import
      [java.io ByteArrayInputStream ByteArrayOutputStream]
      [ont_app.vocabulary.lstr LangStr]
      )))

(voc/put-ns-meta!
 'ont-app.rdf.core
 {
  :voc/mapsTo 'ont-app.rdf.ont
  }
 )

;; aliases 
(def prefixed
  "Returns `query`, with prefix declarations prepended
  Where
  - `query` is presumed to be a SPARQL query"
  voc/prepend-prefix-declarations)

(def ontology
  "The supporting vocabulary for the RDF module"
  @ont/ontology-atom)

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
#?(:clj (derive java.lang.Long ::number)
   :cljs (derive cljs.core.Long ::number)
   )
#?(:clj (derive java.lang.Double ::number)
   :cljs (derive cljs.core..Double ::number)
   )

(declare transit-read-handlers)
(defn read-transit-json
  "Returns a value parsed from transit string `s`
  Where
  - `s` is a &quot;-escaped string encoded as transit
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
  - `value` is any value that be handled by cognitict/transit
  - Note: custom datatypes will be informed by @transit-write-handlers
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

;; NO READER MACROS BELOW THIS POINT
;; except in try/catch clauses


;; SPECS
(def transit-re
  "Matches data tagged as transit:json"
  (re-pattern (str "^\"" ;; start with quote
                   "(.*)" ;; anything (group 1)
                   "\"" ;; terminal quote
                   "\\^\\^" ;; ^^
                   "transit:json$" ;; end with type tag
                   )))


(spec/def ::transit-tag (spec/and string? (fn [s] (re-matches transit-re s))))

(defn bnode-kwi?
  "True when `kwi` matches the canonical bnode representation."
  [kwi]
  (and (keyword? kwi)
       (some->> (namespace kwi)
                (str)
                (re-matches #"^_.*"))))

(spec/def ::bnode-kwi bnode-kwi?)

(spec/def ::file-resource (fn [url] (and (instance? java.net.URL url)
                                             (-> (.getProtocol url)
                                                 #{"file"}))))


(spec/def ::web-resource (fn [url] (and (instance? java.net.URL url)
                                        (-> (.getProtocol url)
                                            #{"http" "https"}))))



;;;;;;;;;;;;;;;;;;
;; INPUT/OUTPUT
;;;;;;;;;;;;;;;;;;

(defn collect-ns-catalog-metadata
  "Reducing function outputs `gacc'` given voc metadata assigned to namespace
  - NOTE: typically used to initialize the resource catalog.
  "
  [gacc prefix ns]
  (let [m (voc/get-ns-meta ns)
        download-url (:dcat/downloadURL m)
        appendix (:voc/appendix m)
        ]
    (if (and download-url appendix)
      (igraph/add gacc appendix)
      gacc)))

(def resource-catalog
  "A native normal graph using this vocabulary:
  - [`download-url` :dcat/mediaType `media-type`]
  - where
    - `download-url` is a URL string
    - `media-type` should be appropriate for an http call.
  "
  (atom (->> (voc/prefix-to-ns)
             (reduce-kv collect-ns-catalog-metadata
                        (native-normal/make-graph)))))
                                   
                          
(def default-context
  "An atom containing a native-normal graph with default i/o context configurations.
  - NOTE: This would typically be the starting point for the i/o context of  individual
    implementations.
  - VOCABULARY
    - [:rdf-app/UrlCache :rdf-app/directory `URL cache directory`]
  "
  (atom (-> (native-normal/make-graph)
            (igraph/add [[:rdf-app/UrlCache
                          :rdf-app/directory "/tmp/rdf-app/UrlCache"]
                         ]))))

(defn standard-import-dispatch
  "Returns a standard `dispatch-key` for `to-import`
  - Where
    - `to-import` is typically an argument to the `load-rdf` or `read-rdf` methods.
    - `dispatch-key` :~ #{:rdf-app/LocalFile, :rdf-app/FileResource :rdf/WebResource}
      or the type of `to-import`.
    - :rdf-app/LocalFile indicates that `to-import` is a local path string
    - :rdf-app/FileResource indicates that `to-import` is a resource in a JAR
    - :rdf-app/WebResource indicates something accessible through a curl call.
  "
  [to-import]
  (cond
    (and (string? to-import)
         (.exists (io/file to-import)))
    :rdf-app/LocalFile

    (and (instance? java.io.File to-import)
         (.exists to-import))
    :rdf-app/LocalFile
    
    (spec/valid? ::file-resource to-import)
    :rdf-app/FileResource

    (spec/valid? ::web-resource to-import)       
    :rdf-app/WebResource

    :else (type to-import))
  )


(declare load-rdf-dispatch)
(defmulti load-rdf
  "Returns a new IGraph with contents for `to-load`,
  - args: [`context` `to-load`]
  - dispatched on: [`graph-dispatch` `to-load-rdf-dispatch`]
  - Where
    - `context` is a native-normal graph with descriptions per the vocabulary below.
       It may also provide platform-specific details that inform specific methods.
    - `to-load` is typically a path or URL, but could be anything you write a method for
      - if this is a file name that exists in the local file system this will be
        dispatched as `:rdf-app/LocalFile`. We may need to derive `file-extension`.
    - `graph-dispatch` is the dispatch value identifying the IGraph implementation
    - `to-load-rdf-dispatch` is the dispatch value derived for `to-load-rdf`
    - `file-extension` may be implicit from a file name or derived per vocabulary below
       It may be necesary to inform your RDF store about the expected format.
 
  - VOCABULARY (in `context`)
    - [`#'load-rdf` :rdf-app/hasGraphDispatch `graph-dispatch`]
    - [`#'load-rdf` :rdf-app/toImportDispatchFn (fn [to-load] -> to-load-dispatch)]
      ... optional. Defaults to (type to-load)
    - [`#'load-rdf` :rdf-app/extensionFn (fn [to-load] -> file-extension)]
      ... optional. By default it parses the presumed path name described by `to-load`
    - [rdf-app/UrlCache rdf-app/directory `cache-directory`]
    - [rdf-app/UrlCache rdf-app/cacheMaintenance :rdf-app/DeleteOnRead]
      ... optional. specifies that a cached file should be deleted after a read.
      - by default it will not be deleted.
  "
  ;; There's a tricky circular dependency here in reference to #'load-rdf....
  (fn [context to-load] (load-rdf-dispatch context to-load)))

(defn load-rdf-dispatch
  "Returns [graph-dispatch to-load-dispatch]. See docstring for `rdf/load-rdf`"
  [context to-load]
  {:pre [(fn [context _] (context #'load-rdf :rdf-app/hasGraphDispatch))
         ]
   }
  ;; return [graph-dispatch, to-load-dispatch] ...
  [(unique (context #'load-rdf :rdf-app/hasGraphDispatch))
   ,
   (if-let [to-load-dispatch (unique (context #'load-rdf  :rdf-app/toImportDispatchFn))]
     (to-load-dispatch to-load)
     ;; else no despatch function was provided
     (standard-import-dispatch to-load))
   ])
   

;; URL caching

(defn cached-file-path
  "Returns a canonical path for cached contents read from a URL."
  [& {:keys [dir url stem ext]}]
  (assert dir)
  (str dir  "/" stem "_hash=" (hash url) "." ext))


(defn parse-url
  [url]
  (let [path (.getPath url)
        stem-extension (re-pattern
                        (str "^.*/" ;; start with anything ending in slash
                             "([^/]+)" ;; at least one non-slash (group 1)
                             "\\." ;; dot
                             "(.*)$" ;; any ending, (group 2)
                             ))
        matches (re-matches stem-extension path)
    ]
  (if-let [[_ stem ext] matches]
    {:url (str url)
     :path path
     :stem stem
     :ext ext
     })))

(defn get-cached-file-path-spec
  "Returns `m` s.t (keys m) :~ #{:url :path :stem :ext} for `url` informed by `context`
  - Where
    - `url` (as arg) is x s.t. (str x) -> a URL string
    - `context` is an native-normal graph describing the I/O context
    - `url` (as key) is a URL string
    - `path` is the path component of `url`
    - `stem` is the 'stem portion of /path/to/<stem>.<ext>
    - `ext`  is the 'ext' portion of /path/to/<stem>.<ext>
    - `dir`  is the directory containing cache files
  - NOTE: this should be sufficient to create a unique temp file path for caching
    contents of `url`.
  - VOCABULARY
    - [:rdf-app/UrlCache :rdf-app/pathFn `cached-file-path-fn`]
      - optional. Default will try to parse `m` from `url` itself
    - [:rdf-app/UrlCache :rdf-app/directory `dir`]  
    - `cached-file-path-fn` := fn (uri) -> `m`
  "
  [context url]

  (or (if-let [cached-file-path-fn (unique (context :rdf-app/UrlCache :rdf-app/pathFn))
              ]
        (cached-file-path-fn url)
        ;; else there is no pathFn, try to parse the URL...
        (let [dir (unique (context :rdf-app/UrlCache :rdf-app/directory))
              ]
          (assoc (parse-url url)
                 :dir dir)))))

(defn import-url-via-temp-file
  "RETURNS `g`, with contents of `url` loaded
  SIDE-EFFECT: creates file named `cached-file-path` if it does not already exist.
  - Where
    - `context` is a native-normal graph informed by vocabulary below.
    - `import-fn` := fn [context cached-file-path] -> g,
    - `url` := a URL or string naming URL
    - `cached-file-path` names a local file to contain contents from `url`
  - VOCABULARY (for `context`)
    - [:rdf-app/UrlCache :rdf-app/pathFn `cached-file-path-fn`]
      - optional. Default will try to parse `parse-map` from `url` itself
    - [:rdf-app/UrlCache :rdf-app/directory `cache-directory`]
    - [:rdf-app/UrlCache :rdf-app/cacheMaintenance `rdf-app/DeleteOnRead`]
      - optional. Will delete the cached file on successful read when specified
    - `cached-file-path-fn` := fn (uri) -> `parse-map`
    - `parse-map` := m s.t (keys m) :~ #{:url :path :stem :ext} for `url` informed by `context`
  "
  [context import-fn url]
  (if-let [temp-file-path (some-> (get-cached-file-path-spec context url)
                                  (cached-file-path))
           ]
    (let [read-successful? (atom false)
          ]
      (when (not (.exists (io/file temp-file-path)))
        (io/make-parents temp-file-path)
        (spit temp-file-path
              (slurp url)))
      (try (let [g (import-fn context temp-file-path)
                 ]
             (reset! read-successful? true)
           g)
         (catch #?(:clj Throwable :cljs js/Error) e
           (throw (ex-info "Error importing from URL"
                           (merge (ex-data e)
                                  {:type ::ErrorImportingFromURL
                                   :context context
                                   :url (str url)
                                   :temp-file-path temp-file-path
                                   }))))
         (finally (when (and
                         @read-successful?
                         (context :rdf-app/UrlCache
                                  :rdf-app/cacheMaintenance :rdf-app/DeleteOnRead))
                    (io/delete-file temp-file-path)))))
    ;; else no temp-file-path could be inferred
    (throw (ex-info (str "No caching path could be inferred for %s" url)
                    {:type ::NOCachingPathCouldBeInferredForURL
                     ::context context
                     ::url url
                     }))
    ))


(defmethod load-rdf [:rdf-app/IGraph java.net.URL]
  ;; default behavior to load URLs.
  ;; to enable (derive <my-Igraph> :rdf-app/IGraph)
  [context to-load]
  (import-url-via-temp-file context load-rdf to-load))
  
(defmethod load-rdf :default
  [context file-id]
  (throw (ex-info "No method for rdf/load-rdf"
                  {:type ::NoMethodForTempLoadRdf
                   ::context context
                   ::file file-id
                   ::dispatch (load-rdf-dispatch context file-id)
                   })))

(declare read-rdf-dispatch)
(defmulti read-rdf
  "Side-effect: updates `g` with added contents from `to-read`,
  Returns: modified `g`
  - args: [context g to-read]
  - dispatched on: [graph-dispatch to-read-dispatch]
  - Where
    - `context` is a native-normal graph with descriptions per the vocabulary below.
       It may also provide platform-specific details that inform specific methods.
    - `to-read` is typically a path or URL, but could be anything you write a method for
      - if this is a file name that exists in the local file system this will be
        dispatched as `:rdf-app/LocalFile`. We may need to derive `file-extension`.
    - `graph-dispatch` is the dispatch value identifying the IGraph implementation
    - `to-read-dispatch` is the dispatch value derived for `to-read`
  
    - `file-extension` may be implicit from a file name or derived per vocabulary below
       It may be necesary to inform your RDF store about the expected format.
 
  - VOCABULARY (in `context`)
  - [`#'read-rdf` :rdf-app/hasGraphDispatch `graph-dispatch`]
  - [`#'read-rdf` :rdf-app/toImportDispatchFn (fn [to-read] -> `to-read-dispatch`)]
    ... optional. Defaults to (type to-read)
  - [`#'read-rdf` :rdf-app/extensionFn (fn [to-read] -> file-extension)]
    ... optional. By default it parses the presumed path name described by `to-read`
  "
  ;; There's a tricky circular dependency here in reference to #'read-rdf....
  (fn [context g to-read] (read-rdf-dispatch context g to-read)))

(defn read-rdf-dispatch
  "Returns [graph-dispatch to-read-dispatch]. See docstring for `rdf/read-rdf`"
  [context g to-read]
  {:pre [(fn [context _] (context #'read-rdf :rdf-app/hasGraphDispatch))
         ]
   }
  ;; return vector...
  [(unique (context #'read-rdf :rdf-app/hasGraphDispatch))
   ,
   (if-let [to-read-dispatch (unique (context #'read-rdf :rdf-app/toImportDispatchFn))]
     (to-read-dispatch to-read)
     ;; else no despatch function was provided
     (standard-import-dispatch to-read))
   ])

(defmethod read-rdf [:rdf-app/IGraph java.net.URL]
  ;; default behavior to load URLs.
  ;; to enable (derive <my-Igraph> :rdf-app/IGraph)
  [context g url]
  (let [do-read-rdf (fn [context url]
                      (read-rdf context g url))
        ]
  (import-url-via-temp-file context do-read-rdf url)))
  
(defmethod read-rdf :default
  [context file-id]
  (throw (ex-info "No method for rdf/read-rdf"
                  {:type ::NoMethodForTempLoadRdf
                   ::context context
                   ::file file-id
                   ::dispatch (read-rdf-dispatch context file-id)
                   })))

;;;;;;;;;;;;;;;;;;;;
;; LITERAL SUPPORT
;;;;;;;;;;;;;;;;;;;;

(defn quote-str 
  "Returns `s`, in escaped quotation marks.
Where
  - `s` is a string, typically to be rendered in a query or RDF source.
"
  [s]
  (value-trace
   ::QuoteString
   (str "\"" s "\"")
   ))

(def transit-write-handlers
  "Atom of the form {`Class` `write-handler`, ...}
  Where
  - `Class`, a symbol, is a direct reference to the class instance to be encoded
  - `write-handler` := fn [s] -> {`field` `value`, ...}
  " 
  (atom
   {LangStr
    (cognitect.transit/write-handler
     "ont-app.vocabulary.lstr.LangStr"
     (fn [ls]
       {:lang (.lang ls)
        :s (.s ls)
        }))
    }))

(def transit-read-handlers
  "Atom of the form {`className` `read-handler, ...}`
  Where
  - `className` is a fully qualified string naming a class to be encoded
  - `read-handler` := fn [from-rep] -> `instance`
  - `from-rep` := an Object s.t. (`field` from-rep), encoded in corresponding
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
  "A function [x] -> `dispatch-value`
  Where
  - `x` is any value, probabaly an RDF literal
  - `dispatch-value` is a value to be matched to a `render-literal-dispatch` method.
  Default is to return nil, signalling no special dispatch."
  (atom (fn [_] nil)))

(defn render-literal-dispatch
  "Returns a key for the render-literal method to dispatch on given `literal`
  Where
  - `literal` is any non-keyword
  - NOTE: LangStr and non-nil `special-dispatch` are special cases; otherwise
    (type `literal`)
  "
  [literal]
  (value-trace
   ::RenderLiteralDispatch
   [:literal literal]
   (if-let [special-dispatch (@special-literal-dispatch literal)]
     special-dispatch
     ;; else no special dispatch...
     (type literal))))

(defmulti render-literal
  "Returns an RDF (Turtle) rendering of `literal`
  for methods with signature (fn [literal] -> `rdf`)"
  render-literal-dispatch)

(defmethod render-literal :rdf-app/TransitData
  [v]
  (render-literal-as-transit-json v))


(defmethod render-literal LangStr
  [ls]
  (str (quote-str (.s ls)) "@" (.lang ls)))


(defmethod render-literal ::number
  ;; ints and floats all derive from ::number
  ;; just insert the value directly
  [n]
  n)

(defmethod render-literal :default
  [s]
  (quote-str s))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; STANDARD TEMPLATES FOR IGRAPH MEMBER ACCESS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def query-template-defaults
  "Default key/value pairs appicable to query templates for your platform.
  Where
  - `:from-clauses` one FROM clause for each graph informing the query
  - `:rebind-_s` asserts new binding for ?_s in ?_s ?_p ?_o 
  - `:rebind-_p` asserts a new binding the value retrieved for ?_p in ?_s ?_p ?_o 
  - `:rebind-_o` aserts a new binding the value retrieved for ?_o in ?_s ?_p ?_o
  - NOTE: For example we may assert :rebind-_s as `IRI(?_S)` in jena to set up bnode round-tripping for ?_s.
  "
  (atom
   {:from-clauses ""
    :rebind-_s "?_s"
    :rebind-_p "?_p"
    :rebind-_o "?_o"
    }))

(defn from-clause-for
  "
  Returns FROM `graph-uri`'
  Note: typically informs `query-template-map` 
  "
  [graph-uri]
  (stache/render "FROM <{{{graph-uri}}}>"
                 {:graph-uri graph-uri}))

(defn- query-template-map 
  "Returns {`k` `v`, ...} appropriate for `rdf-store` with `graph-uri`
  Where
  - `k` and `v` are cljstache template parameters which may appear in some query, 
  e.g. named graph open/close clauses
  - `rdf-store` is an RDF store.
  - `graph-uri` is either nil, a single graph-uri or a set of graph-uris
  "
  [graph-uri _rdf-store]
  (let [as-set (fn [gu] (if (set? gu) gu (set gu)))
        ]
    (merge @query-template-defaults
           {:from-clauses (if graph-uri
                            (s/join "\n"
                                    (map (comp from-clause-for voc/iri-for)
                                         (as-set graph-uri)))
                            ;; else no graph uri
                            "")
            })))

(def subjects-query-template "A 'stache template for a query ref'd in `query-for-subjects`, informed by `query-template-map` "
  ;; note the use of 3 brackets to turn off escaping
  "
  Select Distinct ?s
  {{{from-clauses}}} 
  Where
  {
    ?_s ?_p ?_o.
    Bind ({{{rebind-_s}}} as ?s)
    Bind ({{{rebind-_p}}} as ?p)
    Bind ({{{rebind-_o}}} as ?o)
  }
  ")

(defn query-for-subjects 
  "Returns [`subject` ...] at endpoint of `rdf-store` for `graph-uri`
Where
  - `subject` is the uri of a subject from `rdf-store`, 
  rendered per the binding translator of `rdf-store`
  - `rdf-store` conforms to ::sparql-client spec
  - `query-fn` := fn [repo] -> bindings
  - `graph-uri` is a URI  or KWI naming the graph, or a set of them  (or nil if DEFAULT
    graph)
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

(def normal-form-query-template "A 'stache template for a query ref'd in `query-for-normal-form`, informed by `query-template-map` "
  "
  Select ?s ?p ?o
  {{{from-clauses}}}
  Where
  {
    ?_s ?_p ?_o.
    Bind ({{{rebind-_s}}} as ?s)
    Bind ({{{rebind-_p}}} as ?p)
    Bind ({{{rebind-_o}}} as ?o)
  }
  ")

(defn query-for-normal-form
  "Returns IGraph normal form for `graph` named by `graph-uri` in `rdf-store`
  Where
  - `graph` is a named graph in `rdf-store`
  - `graph-uri` is a URI or KWI naming the graph, or a set of them
     (default nil -> DEFAULT graph)
  - `rdf-store` is an RDF store 
  - `query-fn` := fn [rdf-store sparql-query] -> #{`bmap`, ...}
  - `bmap` := {:?s :?p :?o}
  - `sparql-query` :- `normal-form-query-template`
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
              [:spo spo
               :binding binding]
              (assoc spo (:s binding)
                     (add-po (get spo (:s binding) {})
                             binding))))
          
          ]
    (let [query (stache/render normal-form-query-template
                               (query-template-map graph-uri rdf-store))
          ]
      (value-trace
       ::QueryForNormalForm
       [:query query
        :graph-uri graph-uri
        :query-fn query-fn]
       (reduce collect-binding
               {}
               (query-fn rdf-store query)))))))


(defn check-ns-metadata 
  "Logs a warning when `kwi` is in a namespace with no metadata."
  [kwi]
  (let [n (symbol (namespace kwi))]
    (when-let [the-ns (find-ns n)]
      (when (not (meta the-ns))
        (warn ::NoMetaDataInNS
              :glog/message "The namespace for {{kwi}} is in a namespace with no associated metadata."
              :kwi kwi))))
  kwi)


(defn check-qname 
  "Traps the keyword assertion error in voc and throws a more meaningful error 
about blank nodes not being supported as first-class identifiers."
  [uri-spec]
  (if (bnode-kwi? uri-spec)
    (name uri-spec)
    ;;else not a blank node
    (try
      (voc/qname-for (check-ns-metadata uri-spec))
      (catch Throwable e
        (throw (ex-info (str "The URI spec " uri-spec " is invalid.\nCould it be a blank node?")
                          (merge (ex-data e)
                                 {:type ::Invalid-URI-spec
                                  ::uri-spec uri-spec
                                  })))))))
(def query-for-p-o-template "A 'stache template for a query ref'd in `query-for-p-o`, informed by `query-template-map`"
  "
  Select ?p ?o
  {{{from-clauses}}}
  Where
  {
    {{{subject}}} ?_p ?_o.
    Bind ({{{rebind-_p}}} as ?p)
    Bind ({{{rebind-_o}}} as ?o)
  }
  ")

(defn query-for-p-o 
  "Returns {`p` #{`o`...}...} for `s` from query to `rdf-store`
  Where
  - `p` is a predicate URI rendered per binding translator of `rdf-store`
  - `o` is an object value, rendered per the binding translator of `rdf-store`
  - `s` is a subject uri keyword. ~ voc/voc-re
  - `rdf-store` is and RDF store.
  - `query-fn` := fn [repo] -> bindings
  - `graph-uri` is a URI or KWI naming the graph, or a set of them
    (or nil if DEFAULT graph)
  "
  ([query-fn rdf-store s]
   (query-for-p-o nil  query-fn rdf-store s)
   )
  ([graph-uri query-fn rdf-store s]
   {:pre [(not (nil? s))
          ]
    }
   (debug ::Starting_query-for-p-o
          ::graph-uri graph-uri
          ::query-fn query-fn
          ::rdf-store rdf-store
          ::s s)
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


(def query-for-o-template "A 'stache template for a query ref'd in `query-for-o`, informed by `query-template-map`"
  "
  Select ?o
  {{{from-clauses}}}
  Where
  {
    {{{subject}}} {{{predicate}}} ?_o.
    Bind ({{{rebind-_o}}} as ?o)
  }
  ")

(defn query-for-o 
  "Returns #{`o`...} for `s` and `p` at endpoint of `rdf-store`
Where:
  - `o` is an object rendered per binding translator of `rdf-store`
  - `s` is a subject URI rendered per binding translator of `rdf-store`
  - `p` is a predicate URI rendered per binding translator of `rdf-store`
  - `rdf-store` is an RDF store
  - `query-fn` := fn [repo] -> bindings
  - `graph-uri` is a URI or KWI naming the graph, or a set of them
     (or nil if DEFAULT graph)
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

(def ask-s-p-o-template "A 'stache template for a query ref'd in `ask-s-p-o`, informed by `query-template-map`"
  "ASK
  {{{from-clauses}}}
  where
  {
    {{{subject}}} {{{predicate}}} {{{object}}}.
  }"
  )


(defn ask-s-p-o 
  "Returns true if `s` `p` `o` is a triple at endpoint of `rdf-store`
Where:
  - `s` `p` `o` are subject, predicate and object
  - `rdf-store` is an RDF store
  - `graph-uri` is a URI or KWI naming the graph, or a set of them
     (or nil if DEFAULT graph)
  - `ask-fn` := fn [repo] -> bindings
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
                        :query query
                        :subject s
                        :predicate p
                        :object o)
        ]
    (value-debug
     ::ask-s-p-o-return
     [:resultOf starting]
     (ask-fn rdf-store query)))))

;;;;;;;;;;;;;;;
;;; DEPRECATED
;;;;;;;;;;;;;;
^:deprecated
(defmethod render-literal :rdf-app/LangStr ;; using the type is fine
  [ls]
  (str (quote-str (.s ls)) "@" (.lang ls)))
