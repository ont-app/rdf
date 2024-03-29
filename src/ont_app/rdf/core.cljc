(ns ont-app.rdf.core
  {:doc "This is a backstop for shared logic between various RDF-based
  implementations of IGraph. 
It includes:
- support for LangStr using the #voc/lstr custom reader
- support for typed literals using the #voc/dstr custom reader
- support for ^^transit:json datatype tags
- templating utilities for the standard IGraph member access methods.
- i/o methods `load-rdf` `read-rdf` and `write-rdf`.
 "
   :author "Eric D. Scott"
   ;; These errors were found to be spurious, related to cljs ...
   :clj-kondo/config '{:linters {:unresolved-symbol {:level :off}
                                 :unresolved-namespace {:level :off}}}
   } ;; meta
  (:require
   [clojure.string :as str]
   [clojure.spec.alpha :as spec]
   ;; 3rd party
   [cljstache.core :as stache]
   ;; ont-app
   [ont-app.igraph.core :as igraph :refer [unique]]
   [ont-app.igraph.graph :as native-normal]
   [ont-app.vocabulary.core :as voc]
   [ont-app.rdf.ont :as ont]
   ;; reader conditionals
   #?(:clj [clj-http.client :as http]) ;; todo add cljs-http.client?
   #?(:clj [clojure.java.io :as io])
   #?(:clj [cognitect.transit :as transit]) ;; todo remove conditional after issue 4
   #?(:clj [ont-app.graph-log.levels :as levels
            :refer [warn debug trace value-trace value-debug]]
      :cljs [ont-app.graph-log.levels :as levels
             :refer-macros [warn debug value-trace value-debug]])
   #?(:clj [ont-app.vocabulary.lstr :as lstr :refer [->LangStr]]) ;; todo remove conditional after issue 4
   #?(:clj [ont-app.vocabulary.dstr :as dstr :refer [->DatatypeStr]]);; todo remove conditional after issue 4

   ) ;; require
  #?(:clj
     (:import
      [java.io ByteArrayInputStream ByteArrayOutputStream]
      [java.io File]
      [ont_app.vocabulary.lstr LangStr]
      [ont_app.vocabulary.dstr DatatypeStr]
      )))

(voc/put-ns-meta! 'ont-app.rdf.core {:voc/mapsTo 'ont-app.rdf.ont})

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
(declare transit-datum-re)

(defn read-transit-json
  "Returns a value parsed from transit string `s`
  Where
  - `s` is a &quot;-escaped string encoded as transit
  Note: custom datatypes will be informed by @transit-read-handlers
  "
  [^String s]
  (trace ::starting-read-transit-json
         ::s s)
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
     (throw (ex-info "read-transit-json not supported in cljs"
                     {:type ::NotSupportedInCljs
                      ::fn #'read-transit-json
                      ::args [s]
                      }))
     #_(transit/read
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
     (throw (ex-info "render-transit-json not supported in cljs"
                     {:type ::NotSupportedInCljs
                      ::fn #'render-transit-json
                      ::args [value]
                      }))
     #_(transit/write
        (transit/writer :json {:handlers @transit-write-handlers})
        value)))

(defmethod voc/tag :transit/json
  [datum _]
  (dstr/->DatatypeStr (render-transit-json datum)
                      "transit:json"))

(defmethod voc/untag :transit/json
  [dstr & _]
  (-> (read-transit-json (str dstr))))

(defn cljc-file-exists?
  "True when `path` exists in the local file system"
  [path]
  #?(:clj
     (.exists (io/file path))
     :cljs
     (let []
       (warn ::FileExistsCheckInCljs
             :glog/message "Checking for existence of local file {{path}} in clojurescript (returning false)"
             ::path path)
       false
       )))

(defn cljc-is-local-file?
  "True when `f` is a file in the local file system"
  [f]
  #?(:clj
     (instance? java.io.File f)
     :cljs
     (let []
       (warn ::IsLocalFileCheckInCljs
             :glog/message "Checking whether {{f}} is local file in clojurescript (returning false)"
             ::f f)

       false)))

(defn cljc-make-file
  "Returns new file object for `path`. not yet supported under cljs."
  [path]
  #?(:clj
     (io/file path)
     :cljs
     (throw (ex-info "Make-file not supported in cljs"
                     {:type ::NotSupportedInCljs
                      ::fn #'cljc-make-file
                      ::args [path]
                      }))))
  
(defn cljc-file-length
  "Returns length of file `f`. not yet supported under cljs."
  [f]
  #?(:clj
     (.length f)
     :cljs
     (throw (ex-info "File-length not supported in cljs"
                     {:type ::NotSupportedInCljs
                      ::fn #'cljc-file-length
                      ::args [f]
                      }))))

(defn cljc-make-parents
  "Ensures directory path for file `f`. not yet supported under cljs."
  [f]
  #?(:clj
     (io/make-parents f)
     :cljs
     (throw (ex-info "Make-parents not supported in cljs"
                     {:type ::NotSupportedInCljs
                      ::fn #'cljc-make-parents
                      ::args [f]
                      }))))

(defn cljc-resource
  "Returns the resource named by `r`. not yet supported under cljs."
  [r]
  #?(:clj
     (io/resource r)
     :cljs
     (throw (ex-info "Resource not supported in cljs"
                     {:type ::NotSupportedInCljs
                      ::fn #'cljc-resource
                      ::args [r]
                      }))))

(defn cljc-create-temp-file
  "Returns a temporary file named per `stem` and `ext`. not yet supported under cljs.
  - where
    - `stem` is a general name for the file
    - `ext` is a file extension typically starting with '.'
  "
  [stem ext]
  #?(:clj
     (File/createTempFile stem ext)
     :cljs
     (throw (ex-info "Create-temp-file not supported in cljs"
                     {:type ::NotSupportedInCljs
                      ::fn #'cljc-create-temp-file
                      ::args [stem ext]
                      }))))


(defn cljc-http-get
  "Makes a GET call to `url` with `req`. Not yet supported in cljs.
  - Where
    - `url` is a URL or a URL string
    - `req` is an http request map
  "
  [url req]
  #?(:clj
     (http/get (str url) req)
     :cljs
     ;; TODO: probably need to import cljs-http. Pending issue 4
     (throw (ex-info "Http-get not yet supported in cljs"
                     {:type ::NotSupportedInCljs
                      ::fn #'cljc-http-get
                      ::args [url req]
                      }))))

;; NO READER MACROS BELOW THIS POINT
;; except in try/catch clauses

;; SPECS

(def quoted-str-re
  "matches output of `quote-str` -> [_ _ unquoted _]
  "
  (re-pattern (str "^"
                   "("   ;; start group 1
                   "\""  ;;   open single quote
                   "|"   ;;   or
                   "'''" ;;   open '''
                   ")"   ;; end group 1
                   "("   ;; start group 2
                   ".*"  ;;   anything
                   ")"   ;; end group 2
                   "("   ;; start group 3
                   "\""  ;;   close single quote
                   "|"   ;;   or
                   "'''" ;;   close '''
                   ")"   ;; end group 3
                   "$"
                   )))

(spec/def ::transit-datum (spec/and string? (fn [s] (re-matches quoted-str-re s))))

(def transit-re
  "Matches data tagged as transit:json"
  (let [tdr (str quoted-str-re)]
    (re-pattern (str "^"
                     ;;"("
                     (subs tdr 1 (dec (count tdr))) ;; remove ^ and $ from quoted-str-re
                     ;;")"
                     "\\^\\^" ;; ^^
                     "transit:json$" ;; end with type tag
                     ))))

(spec/def ::transit-tag (spec/and string? (fn [s] (re-matches transit-re s))))

(def bnode-name-re "A regex to parse bnodes"
  (re-pattern
   (str "^"
        "_"   ;; starts with _
        "[:]" ;; followed by colon
        "("   ;; start group
        ".*"  ;;   anything after the colon
        ")"   ;; end group
        )))

(defn bnode-kwi?
  "True when `kwi` matches the canonical bnode representation."
  [kwi]
  (and (keyword? kwi)
       (= (namespace kwi) "rdf-app")
       (some->> (name kwi)
                (str)
                (re-matches bnode-name-re))))

(spec/def ::bnode-kwi bnode-kwi?)

(spec/def ::file-resource (fn [url] (and (instance? java.net.URL url)
                                             (-> (.getProtocol url)
                                                 #{"file" "jar"}))))
(spec/def ::web-resource (fn [url] (and (instance? java.net.URL url)
                                        (-> (.getProtocol url)
                                            #{"http" "https"}))))

(defn normalize-bnode-string
  "Returns `b`, possibly prepending '_:', checking for match to bnode-name-re"
  [b]
  {:post [(re-matches bnode-name-re %)]}
  (if (re-matches bnode-name-re b)
    b
    (str "_:" b)))

;;;;;;;;;;;;;;;;;;
;; Vocabulary
;;;;;;;;;;;;;;;;;;

(voc/register-resource-type-context! ::resource-type-context ::voc/resource-type-context)

(defmethod voc/resource-type [::resource-type-context (type "")]
  [this]
  ;; check for a string formatted like a bnode...
  (if (re-matches #"^_[:].*" this)
    :rdf-app/BnodeString
    ;; else handle it like any other string....
    (let [m (methods voc/resource-type)
          f (m [::voc/resource-type-context (type this)])]
      (f this))))

(defmethod voc/resource-type [::resource-type-context (type :x)] ;; keyword
  [this]
  ;; Check for a bnode kwi...
  (if (bnode-kwi? this)
    :rdf-app/BnodeKwi
    ;; else handle like any other keyword...
    (let [m (methods voc/resource-type)
          f (m [::voc/resource-type-context (type this)])]
      (f this))))

(defmethod voc/as-uri-string :rdf-app/BnodeKwi
  [this]
  ;; Non-re-trippable. Typically appearing in code
  (let [[_ parsed-name] (re-matches bnode-name-re (name this))]
    (when (not parsed-name)
      (throw (ex-info (str "Cound not parse bnode kwi " this)
                      {:type ::CoundNotParseBnodeKwi
                       ::this this})))
    (str "_:" parsed-name)))

(defmethod voc/as-kwi :rdf-app/BnodeString
  [this]
  {:post [(spec/assert :voc/kwi-spec %)]}
  (let [kw-method (get-method voc/as-kwi :voc/Kwi)
        default (get-method voc/as-kwi :default)]
    (cond
      (re-matches bnode-name-re this)
      (kw-method (keyword "rdf-app" this ))

      :else
      (default this))))

(defmethod voc/as-uri-string :rdf-app/BnodeKwi
  [this]
  (name this))

(defmethod voc/as-kwi :rdf-app/BnodeKwi
  [this]
  this)

(defmethod voc/as-qname :rdf-app/BnodeKwi
  [this]
  (voc/as-uri-string this))

;; URLs

(defmethod voc/resource-type [::resource-type-context java.net.URL]
  [this]
  (if (spec/valid? ::file-resource this)
    :rdf-app/FileResource
    ;; else
    :rdf-app/WebResource))

(derive :rdf-app/FileResource :rdf-app/URL)
(derive :rdf-app/WebResource :rdf-app/URL)

(defmethod voc/as-uri-string :rdf-app/URL
  [this]
  (str this))

(defmethod voc/as-kwi :rdf-app/URL
  [this]
  (voc/as-kwi (str this)))

(defmethod voc/as-qname :rdf-app/URL
  [this]
  (voc/as-qname (str this)))

;;;;;;;;;;;;;;;;;;
;; INPUT/OUTPUT
;;;;;;;;;;;;;;;;;;


;; KWI/URI conversion for catalog contents
(defn coerce-graph-element
  "Returns `x`, possibly coerced to either a kwi or a java.net.URI per `policy`
  - where
    - `policy` := m s.t. (keys m) :- #{::kwi-if ::uri-if}
    - `x` is any candidate as an element in an IGraph
    - `kwi-if` := fn [x] -> truthy if `x` should be translated to a keyword id
    - `uri-if` := fn [x] -> truthy if `x` should be translated to a java.net.URI
  - NOTE: Some implementations of IGraph may be a lot more tolarant of datatypes
    in s/p/o position than the URI/URI/URI-or-literal that  RDF expects.
  "
  ([x]
   (coerce-graph-element {::kwi-if (fn [x] (re-matches (voc/namespace-re) (str x)))
                          ::uri-if (fn [x] (or
                                            (re-matches voc/ordinary-iri-str-re  (str x))
                                            (re-matches voc/exceptional-iri-str-re (str x))))
                          }
                         x))
  ([policy x]
   (cond
     ((::kwi-if policy) x)
     (if (keyword? x)
       x
       (voc/as-kwi (str x)))

     ((::uri-if policy) x)
     (if (instance? java.net.URI x)
       x
       (java.net.URI. (str x)))

     :else x
     )))

(defn collect-ns-catalog-metadata
  "Reducing function outputs `gacc'` given voc metadata assigned to namespace
  - NOTE: typically used to initialize the resource catalog.
  "
  [gacc _prefix ns]
  (let [m (voc/get-ns-meta ns)
        uri (:vann/preferredNamespaceUri m)
        prefix (:vann/preferredNamespacePrefix m)
        download-url (:dcat/downloadURL m)
        appendix (:voc/appendix m)
        ]
    (if (and download-url appendix)
      ;; appendix is one or more triples expressed as vectors
      (-> gacc
          (igraph/add [(coerce-graph-element uri)
                       :dcat/downloadURL (coerce-graph-element download-url)
                       :vann/preferredNamespacePrefix prefix
                       ])
          (igraph/add (mapv (fn [v] (mapv coerce-graph-element v))
                            appendix)))
      gacc)))

(def resource-catalog
  "A native normal graph using this vocabulary:
  - [`namespace-uri` :dcat/downloadURL `download-url`]
  - [`namespace-uri` :vann/preferredNamespacePrefix `prefix`]
  - [`download-url` :dcat/mediaType `media-type`]
  - where
    - `download-url` is a URL string
    - `media-type` := :rdf/type :dct/MediaTypeOrExtent
  "
  (atom (->> (voc/prefix-to-ns)
             (reduce-kv collect-ns-catalog-metadata
                        (native-normal/make-graph)))))

(defn add-catalog-entry!
  "Adds an entry in @resource-catalog for `download-url` `namespace-uri` `prefix` `media-type`
  - Where
    - `download-url` is a URL (or string) naming a place on the web containing an RDF file
    - `namespace-uri` is the primary URI, associated with `prefix`
    - `prefix` is the preferred prefix for `namespace-uri`
    - `media-type` is the MIME type, of `download-url` eg 'text/turtle'
  "
  [download-url namespace-uri prefix media-type]
  (swap! resource-catalog
         igraph/add
         [[(coerce-graph-element namespace-uri)
           :vann/preferredNamespacePrefix prefix
           :dcat/downloadURL (coerce-graph-element download-url)]
          [(coerce-graph-element download-url)
           :dcat/mediaType media-type
           ]]))

(def default-io-context
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

(defn standard-data-transfer-dispatch
  "Returns a standard `dispatch-key` for `to-transfer`, defaulting to (type to-transfer)
  - Where
    - `to-transfer` is typically an argument to the `load-rdf`, `read-rdf` or `write-rdf`       methods.
    - `dispatch-key` :~ #{:rdf-app/LocalFile, :rdf-app/FileResource :rdf/WebResource}
      or the type of `to-transfer`.
    - :rdf-app/LocalFile indicates that `to-transfer` is a local path string
    - :rdf-app/FileResource indicates that `to-transfer` is a file resource (maybe from a jar)
    - :rdf-app/WebResource indicates something accessible through a curl call.
  "
  [to-transfer]
  (cond
    (and (string? to-transfer)
         (cljc-file-exists? to-transfer))
    :rdf-app/LocalFile

    (cljc-is-local-file? to-transfer)
    :rdf-app/LocalFile
    
    (spec/valid? ::file-resource to-transfer)
    :rdf-app/FileResource

    (spec/valid? ::web-resource to-transfer)
    :rdf-app/WebResource

    :else (type to-transfer))
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
      ... optional. Defaults to output of `standard-data-transfer-dispatch`
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
  (value-trace
   ::load-rdf-dispatch
   [::context context
    ::to-load to-load
    ]
   ;; return [graph-dispatch, to-load-dispatch] ...
   [(unique (context #'load-rdf :rdf-app/hasGraphDispatch))
    ,
    (if-let [to-load-dispatch (unique (context #'load-rdf  :rdf-app/toImportDispatchFn))]
      (to-load-dispatch to-load)
      ;; else no dispatch function was provided
      (standard-data-transfer-dispatch to-load))
    ]))
   

;; URL caching

(defn cached-file-path
  "Returns a canonical path for cached contents read from a URL."
  [& {:keys [dir url stem ext]}]
  (assert dir)
  (str dir  "/" stem "_hash=" (hash url) "." ext))


(defn catalog-lookup
  "Returns `catalog-entry` for `url`
  - Where
    - `catalog-entry` := m s.t. (keys m) :~ #{?media-type :?prefix :?suffix :?media-url}
    - `url` is a URL that may be in the resource catalog
    - `:?prefix` is the preferred prefix associated with `url` (which informs the stem)
    - `:?suffix` is the suffix associated with the `:?media-url` (informs the extension)
  "
  [url]
  (let [g (igraph/union @resource-catalog
                        ontology)
        url (coerce-graph-element url)
        ]
    (-> (igraph/query g
                      [[url :dcat/mediaType :?media-type]
                       [:?media-url  :formats/media_type :?media-type]
                       [:?media-url :formats/preferred_suffix :?suffix]
                       [:?namespace-uri :dcat/downloadURL url]
                       [:?namespace-uri :vann/preferredNamespacePrefix :?prefix]
                       ])
        (unique))))

(defn lookup-file-specs-in-catalog
  "Returns `file-specs` for `url`
  - Where
    - `file-specs` := m s.t. (keys m) :~ #{:url :path :stem :ext}
    - `url` (as arg) is a URL we may want to get from an http call
    - `url` (as key) is the string version of `url`
    - `path` is the file path of `url`
    - `stem` is the preferred prefix for `url` in the catalog
    - `ext` is the file suffix associated with the media type of `url` in the catalog
  "
  [url]
  (when-let [lookup (catalog-lookup url)
             ]
    {:url (str url)
     :path (.getPath url)
     :stem (:?prefix lookup)
     :ext (clojure.string/replace (:?suffix lookup) #"\." "")
     }))

  
(defn http-get-from-catalog
  "returns an http response to a GET request for `url`
  - Where
    - `url` is a URL with an entry in the @`resource-catalog`
  "
  [url]
  (let [lookup (catalog-lookup url)
        ]
    (when lookup
      (cljc-http-get (str url)
                     {:accept (:?media-type lookup)})
      )))

(def parse-url-re
  "A regex to parse a file URL string with a file name and an extension.
  - matches :~ [_ stem ext]
  "
  (re-pattern
   (str "^.*/"    ;; start with anything ending in slash
        "([^/]+)" ;; at least one non-slash (group 1)
        "\\."     ;; dot
        "(.*)$"   ;; any ending, (group 2)
        )))

(defn parse-url
  "Returns a file specification parsed directly from a URL (not in the catalog), or nil
  - where
    - `url` is a URL, probably a file resource"
  [url]
  (let [path (.getPath url)
        matches (re-matches parse-url-re path)
        ]
    (when-let [[_ stem ext] matches]
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
      - optional. Default will try to infer `m` from `url` automatically
        Either through `lookup-file-specs-in-catalog` or by parsing `url` itself.
    - [:rdf-app/UrlCache :rdf-app/directory `dir`]  
    - `cached-file-path-fn` := fn (uri) -> `m`
  "
  [context url]

  (value-trace
   ::get-cached-file-spec
   [::context context
    ::url url
    ]
   (if-let [cached-file-path-fn (unique (context :rdf-app/UrlCache :rdf-app/pathFn))
            ]
     (cached-file-path-fn url)
     ;; else there is no pathFn, try to parse the URL...
     (let [dir (unique (context :rdf-app/UrlCache :rdf-app/directory))
           ]
       (assoc (or (lookup-file-specs-in-catalog url)
                  (parse-url url))
              :dir dir)))))

(defn infer-media-type
  "Returns the `media-type` associated with the extension of `url`, if it exists
  - Where
    - `url` is a URL or URL string
    - `media-type` is appropriate for an HTTP 'accept' header, e.g. 'text/turtle'
  "
  [url]
  {:pre [(= (type url) java.net.URL)]}
  (-> 
   (igraph/query ontology [[:?media-url
                            :formats/preferred_suffix (str "."
                                                           (-> (parse-url url)
                                                               :ext))]
                           [:?media-url :formats/media_type :?media-type]])
   unique
   :?media-type))

(defn cache-url-as-local-file
  "RETURNS `cached-file`, with contents of `url` loaded
  SIDE-EFFECT: creates file named `cached-file` if it does not already exist.
  - Where
    - `context` is a native-normal graph informed by vocabulary below.
    - `url` := a URL or string naming URL
    - `cached-file-path` names a local file to contain contents from `url`
  - VOCABULARY (for `context`)
    - [:rdf-app/UrlCache :rdf-app/pathFn `cached-file-path-fn`]
      - optional. Default will try to derive `parse-map` from `url` first by looking
        it up in the @`resource-catalog` and then by parsing the `url` itself
    - [:rdf-app/UrlCache :rdf-app/directory `cache-directory`]
    - `cached-file-path-fn` := fn (uri) -> `parse-map`
    - `parse-map` := m s.t (keys m) :~ #{:url :path :stem :ext} for `url` informed by `context`
  "
  [context url]
  (value-trace
   ::cache-url-as-local-file
   [::context context
    ::url url
    ]
  (if-let [temp-file-path (some-> (get-cached-file-path-spec context url)
                                  (cached-file-path))
           ]
    (let [cached-file (cljc-make-file temp-file-path)
          ]
      (when (not (and (cljc-file-exists? cached-file)
                      (> (cljc-file-length cached-file) 0)))
        (cljc-make-parents cached-file)
        (spit cached-file
              (cond
                (context url :rdf/type :rdf-app/FileResource)
                (slurp url)

                (context url :rdf/type :rdf-app/WebResource)
                (-> (or (http-get-from-catalog url)
                        (when-let [media-type (infer-media-type url)]
                          (-> (cljc-http-get (str url)
                                             {:accept media-type}))))
                    :body)

                :else
                (throw (ex-info "Resource type not sufficiently specified in context"
                                {:type ::ResourceNotSufficientlySpecifiedInContext
                                 ::context context
                                 ::url url
                                 })))))
                
      cached-file)
    ;; else no cached-file-path
    (throw (ex-info (str "No caching path could be inferred for %s" url)
                    {:type ::NOCachingPathCouldBeInferredForURL
                     ::context context
                     ::url url
                     })))))

(defn clear-url-cache!
  "Side-effect: deleletes cached local files for `url` in `urls` per `context`, or the whole cache if only `context` is specified.
  - Where
    - `context` is a native-normal graph informed by vocabulary below.
    - `urls` := [`url`, ...]
    - `url` is an instance of `java.net.URL`
  - VOCABULARY (for `context`)
    - [:rdf-app/UrlCache :rdf-app/pathFn `cached-file-path-fn`]
      - optional. Default will try to derive `parse-map` from `url` first by looking
        it up in the @`resource-catalog` and then by parsing the `url` itself
    - [:rdf-app/UrlCache :rdf-app/directory `cache-directory`]
    - `cached-file-path-fn` := fn (uri) -> `parse-map`
    - `parse-map` := m s.t (keys m) :~ #{:url :path :stem :ext} for `url` informed by `context`
  "
  [context & urls]
  (let [cache-dir (->> (unique (context :rdf-app/UrlCache :rdf-app/directory))
                       (io/file))
        ]
    (if (empty? urls)
      (doseq [f (filter #(not (.isDirectory %)) (file-seq cache-dir))
              ]
        (io/delete-file f))
      ;; else we specified which URLs to delete
      (doseq [url urls]
        (let [to-delete (->> url
                             (get-cached-file-path-spec context)
                             (cached-file-path)
                             (io/file))
              ]
          (if (.exists to-delete)
            (io/delete-file to-delete)
            ;; else it does not exist
              (warn ::no-cache-file-to-delete
                    :glog/message "URL {{url}} is not cached. Nothing to clear."
                    :url url
                    ::to-delete to-delete)))))))

(defmethod load-rdf [:rdf-app/IGraph :rdf-app/CachedResource]
  ;; This will make a local copy of the resource and defer to your implementation's
  ;; to enable (derive <my-Igraph> :rdf-app/IGraph)
  [context url]
  (->> (cache-url-as-local-file (igraph/add context
                                            [url
                                             :rdf/type
                                             (standard-data-transfer-dispatch url)])
                                url)
       (load-rdf context)))


(defmethod load-rdf :default
  [context file-id]
  (throw (ex-info "No method for rdf/load-rdf"
                  {:type ::NoMethodForLoadRdf
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
    ... optional. Defaults to output of `standard-data-transfer-dispatch`
  - [`#'read-rdf` :rdf-app/extensionFn (fn [to-read] -> file-extension)]
    ... optional. By default it parses the presumed path name described by `to-read`
  "
  ;; There's a tricky circular dependency here in reference to #'read-rdf....
  (fn [context g to-read] (read-rdf-dispatch context g to-read)))

(defn read-rdf-dispatch
  "Returns [graph-dispatch to-read-dispatch]. See docstring for `rdf/read-rdf`"
  [context g to-read]
  {:pre [(instance? ont_app.igraph.graph.Graph context)
         (context #'read-rdf :rdf-app/hasGraphDispatch)
         ]
   }
  (trace
   ::starting-read-rdf-dispatch
   ::context context
   ::g g
   ::to-read to-read
   )
  (value-trace
   ::value-of-read-rdf-dispatch
   [::context context
    ::g g
    ::to-read to-read
    ]
   ;; return vector...
   [(unique (context #'read-rdf :rdf-app/hasGraphDispatch))
    ,
    (if-let [to-read-dispatch (unique (context #'read-rdf :rdf-app/toImportDispatchFn))]
      (to-read-dispatch to-read)
      ;; else no despatch function was provided
      (standard-data-transfer-dispatch to-read))
    ]))

(defmethod read-rdf [:rdf-app/IGraph :rdf-app/CachedResource]
  [context g url]
  (->> (cache-url-as-local-file (igraph/add context
                                            [url
                                             :rdf/type
                                             (standard-data-transfer-dispatch url)
                                             ]
                                            )
                                url)
       (read-rdf context g)))

(defmethod read-rdf :default
  [context g file-id]
  (throw (ex-info "No method for rdf/read-rdf"
                  {:type ::NoMethodForReadRdf
                   ::context context
                   ::g g
                   ::file file-id
                   ::dispatch (read-rdf-dispatch context g file-id)
                   })))

;; write-rdf

(declare write-rdf-dispatch)
(defmulti write-rdf
  "Side-effect: writes contents of  `g` to  `to-write` in `fmt`,
  Returns: modified `g`
  - args: [context g to-write fmt]
  - dispatched on: [graph-dispatch to-write-dispatch fmt]
  - Where
    - `context` is a native-normal graph with descriptions per the vocabulary below.
       It may also provide platform-specific details that inform specific methods.
    - `to-write` is typically a path or URL, but could be anything you write a method for
      - if this is a file name that exists in the local file system this will be
        dispatched as `:rdf-app/LocalFile`.
    - `graph-dispatch` is the dispatch value identifying the IGraph implementation
    - `to-write-dispatch` is the dispatch value derived for `to-write`
    - `fmt` is typically a KWI derived from `:dct/MediaTypeOrExtent`

  - VOCABULARY (in `context`)
  - [`#'write-rdf` :rdf-app/hasGraphDispatch `graph-dispatch`]
  - [`#'write-rdf` :rdf-app/toExportDispatchFn (fn [to-write] -> `to-write-dispatch`)]
    ... optional. Defaults to (type to-write)
  "
  ;; There's a tricky circular dependency here in reference to #'write-rdf....
  (fn [context g to-write fmt] (write-rdf-dispatch context g to-write fmt)))

(defn write-rdf-dispatch
  "Returns [graph-dispatch to-write-dispatch fmt]. See docstring for `rdf/write-rdf`"
  [context g to-write fmt]
  {:pre [(instance? ont_app.igraph.graph.Graph context)
         (context #'write-rdf :rdf-app/hasGraphDispatch)
         ]
   }
  (trace
   ::starting-write-rdf-dispatch
   ::context context
   ::g g
   ::to-write to-write
   ::fmt fmt
   )
  (value-trace
   ::value-of-write-rdf-dispatch
   [::context context
    ::g g
    ::to-write to-write
    ::fmt fmt
    ]
   ;; return vector...
   [(unique (context #'write-rdf :rdf-app/hasGraphDispatch))
    ,
    (if-let [to-write-dispatch (unique (context #'write-rdf :rdf-app/toExportDispatchFn))]
      (to-write-dispatch to-write)
      ;; else no despatch function was provided
      (standard-data-transfer-dispatch to-write))
    ,
    fmt
    ]))

(defmethod write-rdf :default
  [context g file-id fmt]
  (throw (ex-info "No method for rdf/write-rdf"
                  {:type ::NoMethodForWriteRdf
                   ::context context
                   ::g g
                   ::file file-id
                   ::fmt fmt
                   ::dispatch (write-rdf-dispatch context g file-id fmt)
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
  (if (str/includes? s  "\"")
    (str "'''" s "'''")
    ;;else
    (str "\"" s "\"")))


(defn unquote-str
  "Returns `quoted-str` without the quotes. Inverse of `quote-str`."
  [quoted-str]
  (let [[_ _ s _] (re-matches quoted-str-re quoted-str)]
    s))

(defn triple-single-quote-str
  [s]
  (str "'''" s "'''"))

(defn remove-newlines
  "Returns `s` with \n removed. Addresses a lot of RDF parse errors."
  [s]
  (clojure.string/replace s #"\n" " "))

(def transit-write-handlers
  "Atom of the form {`Class` `write-handler`, ...}
  Where
  - `Class`, a symbol, is a direct reference to the class instance to be encoded
  - `write-handler` := fn [s] -> {`field` `value`, ...}
  " 
  (atom
   #?(:clj
      {LangStr
       (cognitect.transit/write-handler
        "ont-app.vocabulary.lstr.LangStr"
        (fn [ls]
          {:lang (.lang ls)
           :s (.s ls)
           }))
       DatatypeStr
       (cognitect.transit/write-handler
        "ont-app.vocabulary.dstr.DatatypeStr"
        (fn [ds]
          {:datatype (.datatype ds)
           :datum (.s ds)
           }))
       }
      :cljs
      {})))

(def transit-read-handlers
  "Atom of the form {`className` `read-handler, ...}`
  Where
  - `className` is a fully qualified string naming a class to be encoded
  - `read-handler` := fn [from-rep] -> `instance`
  - `from-rep` := an Object s.t. (`field` from-rep), encoded in corresponding
    write-handler in @`transit-write-handlers`.
  "
  (atom
   #?(:clj
      {"ont-app.vocabulary.lstr.LangStr"
       (cognitect.transit/read-handler
        (fn [from-rep]
          (->LangStr (:s from-rep) (:lang from-rep))))
       "ont-app.vocabulary.dstr.DatatypeStr"
       (cognitect.transit/read-handler
        (fn [from-rep]
          (->DatatypeStr (:datum from-rep) (:datatype from-rep))))
       }
      :cljs
      {})
   ))

;; RENDER LITERAL

(def special-render-literal-dispatch
  "A function [x] -> `dispatch-value`
  Where
  - `x` is any value, probabaly an RDF literal
  - `dispatch-value` is a value to be matched to a `render-literal-dispatch`
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
   (if-let [special-dispatch (@special-render-literal-dispatch literal)]
     special-dispatch
     ;; else there's no compiled object
     (type literal))))

(defmulti render-literal
  "Returns an RDF (Turtle) rendering of `literal`
  for methods with signature (fn [literal] -> `rdf`)"
  render-literal-dispatch)

(defmethod render-literal :rdf-app/TransitData
  ;; data structures #'derived from transit data like maps, etc
  [v]
  (render-literal (voc/tag v :transit/json)))

(defmethod render-literal LangStr
  [ls]
  (str (quote-str (.s ls)) "@" (.lang ls)))

(defmethod render-literal DatatypeStr
  [dstr]
  ;; we need to escape quotes properly....
  (stache/render "{{{datum}}}^^{{type}}" {:datum (quote-str (str dstr))
                                          :type (dstr/datatype dstr)}))

(defmethod render-literal ::number
  ;; ints and floats all derive from ::number
  ;; just insert the value directly
  [n]
  n)

(defmethod render-literal :default
  [s]
  (let [quote-fn (if (str/includes? s  "\"")
                   triple-single-quote-str
                   quote-str)]
    (quote-fn s)))

;; READING LITERALS

(def special-read-literal-dispatch
  "A function [x] -> `dispatch-value`
  Where
  - `x` is any value, probabaly something parsed from a SPARQL binding
  - `dispatch-value` is a value to be matched to a `read-literal-dispatch`
  Default is to return nil, signalling no special dispatch."
  (atom (fn [_] nil)))

(defn read-literal-dispatch
  [literal]
  (value-trace
   ::read-literal-dispatch-result
   [::literal literal]
   (if-let [special-dispatch (@special-read-literal-dispatch literal)]
     special-dispatch
     (type literal))))

(defmulti read-literal
  "Signature: [literal] -> igraph-element
  - Where
    -`literal` is a value returned by an operation against an RDF store
    - `igraph-element` is the corresponding clojure construct to be added to an IGraph representation, e.g. a KWI, int, string.
  - This is the inverse of `render-literal`
  "
  read-literal-dispatch)

(defmethod read-literal (type "")
  [s]
  (if-let [[_ _ datum _] (re-matches transit-re s)]
    (read-transit-json datum)
    s))

(defmethod read-literal DatatypeStr
  [this]
  (voc/untag this))

(defmethod read-literal :default
  [literal]
  literal)

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
                            (str/join "\n"
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
          raw-result (query-fn rdf-store query)
          ]
      (value-trace
       ::QueryForNormalForm
       [:query query
        :graph-uri graph-uri
        :query-fn query-fn
        :raw-result raw-result
        ]
       (reduce collect-binding {} raw-result))))))


(defn check-ns-metadata 
  "Logs a warning when `kwi` is in a namespace with no metadata."
  [kwi]
  (when-let [ns' (namespace kwi)]
    (let [n (symbol ns')]
      (when-let [the-ns (find-ns n)]
        (when (not (meta the-ns))
          (warn ::NoMetaDataInNS
                :glog/message "The namespace for {{kwi}} is in a namespace with no associated metadata."
                :kwi kwi)))))
    kwi)


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
                                       {:subject (voc/as-qname s)})))
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
  - `s` is a subject URI rendered per binding translator of `rdf-store`
  - `p` is a predicate URI rendered per binding translator of `rdf-store`
  - `o` is an object rendered per binding translator of `rdf-store`
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
                         {:subject (voc/as-qname s)
                          :predicate (voc/as-qname p)})))
        
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
                       {:subject (voc/as-qname s)
                        :predicate (voc/as-qname p)
                        :object (if (keyword? o)
                                  (voc/as-qname o)
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


(def ^:deprecated special-literal-dispatch
  "Deprecated. Use special-render-literal-dispatch"
  special-render-literal-dispatch)


(def ^:deprecated default-context
  "Deprecated. Use default-io-context."
  default-io-context)
