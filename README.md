# <img src="http://ericdscott.com/NaturalLexiconLogo.png" alt="NaturalLexicon logo" :width=100 height=100/> ont-app/rdf

A backstop for shared logic between rdf-based implementations of IGraph.

Part of the ont-app library, dedicated to Ontology-driven development.

Note: clojurescript implementation is not supported at this time.

## Contents
- [Dependencies](#dependencies)
- [Motivation](#motivation)
- [Supporting Ontology](#supporting-ontology)
- [URIs](#uris)
- [Literals](#literals)
  - [The `render-literal` multimethod](#render-literal-multimethod)
    - [`@special-render-literal-dispatch`](#special-render-literal-dispatch)
  - [The `read-literal` multimethod](#the-read-literal-multimethod)
    - [`@special-read-literal-dispatch`](#special-literal-dispatch)
  - [Language-tagged strings](#language-tagged-strings)
  - [Datatype-tagged strings](#datatype-tagged-strings)
    - [`xsd` values](#xsd-values)
    - [Transit-encoded-values](#transit-encoded-values)
  - [String utilities](#string-utilities)
    - [`quote-str`](#quote-str)
    - [`remove-newlines`](#remove-newlines)
- [Query templates supporting the IGraph member-access methods](#query-templates)
- [I/O](#i-o)
  - [The context graph](#the-context-graph)
  - [Special dispatch KWIs](#special-dispatch-kwis)
  - [The resource catalog](#the-resource-catalog)
  - [`infer-media-type`](#infer-media-type)
  - [Input](#input)
    - [load-rdf](#load-rdf)
    - [read-rdf](#read-rdf)
    - [caching](#caching)
      - [Dispatch on :rdf-app/CachedResource](#dispatch-on-cached-resource)
      - [clear-url-cache!](#clear-url-cache)
  - [Output](#output)
    - [write-rdf](#write-rdf)
- [Test Support](#test-support)
- [Debugging](#debugging)

<a name="h2-dependencies"></a>
## Dependencies

[![Clojars Project](https://img.shields.io/clojars/v/ont-app/rdf.svg)](https://clojars.org/ont-app/rdf)

Require thus:
```
(:require 
  [ont-app.rdf.core :as rdf-app]
  )
```
    
## Motivation
There are numerous RDF-based platforms, each with its own
idiosyncrasies, but there is also a significant overlap between the
underlying logical structure of each RDF implementation. This library aims
to capture that overlap, parameterized appropriately for
implementation-specific variations.

This includes:
- A multimethod `render-literal`, aimed at translating between Clojure
  data and data to be stored in an RDF store.
- Support for language-tagged strings and tagged literals with the
  `#voc/lstr` and `#voc/dstr` reader macros defined in the
  [vocabulary](https://github.com/ont-app/vocabulary) module.
- Support for a `^^transit:json` datatype tag, allowing for arbitrary
  Clojure content to be serialized/deserialized as strings in
  an RDF store.
- SPARQL query templates and supporting code to query for the standard
  member access methods of the
  [IGraph](https://github.com/ont-app/igraph) protocol.
- Multi-methods for basic import/export of RDF-encoded data.

## Supporting ontology

There is a small supporting ontology defined in `ont-app.rdf.ont`,
which the namepace metadata of the core maps to. Its preferred prefix
is `rdf-app` (since `rdf` is already spoken for with
_ont-app.vocabulary.rdf_).

The preferred namespace URI is declared as
`"http://rdf.naturallexicon.org/rdf/ont#"`.

Among other things, it contains descriptions of various media types and formats.

## URIs

URIs are integrated with the ont-app/vocabulary
[resource-type](https://github.com/ont-app/vocabulary#the-resource-type-multimethod)
methods.

This resource-type context adds the following recognized
resource-types to [the types defined in
ont-app/vocabulary](https://github.com/ont-app/vocabulary#existing-resource-types):

| Resource| maps to resource type | Notes|
| --- | --- | --- |
| java.lang.String | :rdf-app/BnodeString | A string expressing a blank node. |
| clojure.lang.Keyword | :rdf-app/BnodeKwi | A keyword expressing a blank node. |


- `:rdf-app/BnodeString` matches the `bnode-name-re` pattern,
  e.g. "_:yadda-yadda".
- `:rdf-app/BnodeKwi` matches keywords interned in the `rdf-app`
  namespace with names matching `bnode-name-re`.

Note that the name of a bnode will not survive round-tripping, so the
primary use for this is usually to add triples with bnodes in them by
hand in cases where bnodes are called for. There are times when the
RDF standard requires you to use bnodes, but if not, why not just mint
a proper URI/KWI?

The operative
[resource-type-context](https://github.com/ont-app/vocabulary#resource-type-contexts)
for this library is `:ont-app.rdf.core/resource-type-context`, derived from
`:ont-app.vocabulary.core/resource-type-context`.

## Literals

### The `render-literal` multimethod

Each RDF-based implementation of IGraph will need to translate between
Clojure data and RDF literals. These will include langage-tagged
strings, xsd types for the usual scalar values, and possibly custom
URI-tagged data. Sometimes the specific platform will already define
its own intermediate data structures for such literals. 

The `render-literal`
[multimethod](https://clojure.org/reference/multimethods) exists to
handle the task of translating from Clojure to RDF.

`render-literal` is dispatched by the function
`render-literal-dispatch`, which takes as an argument a single
literal, and returns a value expected to be fielded by some
_render-literal_ method keyed to that value.

There is a _translate-literal_ method defined for
`:rdf-app/TransitData`, discussed in more detail
[below](#h3-transit-encoded-values). Otherwise `render-literal` is
dispatched on the type of the argument.

Instances of `DatatypeStr` will be rendered as discussed below.

Integers and floats both derive from `::number`, and will be rendered
directly as they are in Clojure by default. Values unhandled by a
specific method will be rendered by default as strings in quotes.



Instances of `LangStr` will be rendered as [discussed
below](#h3-language-tagged-strings).

All of this behavior can be overridden with the
`@special-literal-dispatch` atom discussed in the following section.

#### `@special-render-literal-dispatch`

Often there is platform-specific behavior required for specific types
of literals, for example
[Swirrl/grafter](https://github.com/Swirrl/grafter) has its own way of
handling xsd values.

There is an atom defined called `special-literal-dispatch` (defult
nil) which if non-nil should be a function `f [x] ->
<dispatch-value>`. Any non-nil dispatch value returned by this
function will override the default behavior of
_render-literal-dispatch_, and provide a dispatch value to which you
may target the appropriate methods.

The [igraph-grafter](https://github.com/ont-app/igraph-grafter) source
has examples of this.

### The `read-literal` multimethod

This method should return a KWI or a literal suitable for inclusion in
an IGraph.

Its signature is `[literal] -> graph-element`.

It is typically dispatched on `(type literal)`, but it may be
overriden by @special-read-literal-dispatch).

#### `@special-read-literal-dispatch`

This is basically the inverse or @`special-render-literal-dispatch`,
allowing you to override the default of dispatching on type.

### Language-tagged strings

This library imports 'ont-app.vocabulary.lstr', along with its `#voc/lstr`
reader macro.

Such values will be dispatched on their type
(`ont_app.vocabulary.lstr.LangStr`), and rendered as say `"my English
words"@en`.

### Datatype-tagged strings

This library imports `ont-app.vocabulary.dstr` along with its
`#voc/dstr` reader macro.

This will allow you to use the
[voc/tag](https://github.com/ont-app/vocabulary#the-tag-multimethod)
and
[voc/untag](https://github.com/ont-app/vocabulary#the-untag-multimethod)
methods to assert typed literals with arbitrary datatype tags.

#### `xsd` values

Xsd types are tagged with the `#voc/dstr` tag, and will untag to
reasonable clojure values.

You may want to use  _special-literal-dispatch_ and _render-literal_ methods as
appropriate for any specific RDF platform to override this behavior.

The existing `sparql-client` and `igraph-grafter` implementations
should serve as instructive examples.

#### Transit-encoded values

Of course some values such as the standard Clojure containers, and
user-defined records and datatypes are not handled by the xsd
standard.

This library supports storing such literals in serialized form using a
`^^transit.json` datatype URI tag. 

```
> (rdf-app/render-literal [1 2 3])
"\"[1, 2, 3]\"^^transit:json"

> (rdf-app/read-transit-json "[1,2,3]")
[1 2 3]

> (defn round-trip "Returns `x` after converting it to a transit literal and re-parsing it"
    [x]
    (as-> (rdf-app/render-literal x) 
        it
        (re-matches rdf-app/transit-re it)
        (nth it 1)
        (rdf-app/read-transit-json it))
        
> (round-trip [1 2 3])
[1 2 3]

```

These values are encoded as #voc/dstr reader macros, using `tag` and `untag` methods:

```
> (voc/tag #{1 2 3} :transit/json)
#voc/dstr "[\"~#set\",[1,3,2]]^^transit:json"
>
> (voc/untag *1)
#{1 3 2}
```

The _render-literal_ method keyed to `:rdf/TransitData` is the handler
encoding data as transit. To use it, take the following steps:

- As needed, add a transit [_read handler_](https://cognitect.github.io/transit-clj/#cognitect.transit/read-handler) to `transit-read-handlers`. 
  - Note in core.cljc that LangStr already has such such a handler
- As needed, add a transit [_write handler_](https://cognitect.github.io/transit-clj/#cognitect.transit/write-handler) to `transit-write-handlers`
  - Again, the LangStr record is already covered, and should serve as a good example.
- Use [_derive_](https://clojuredocs.org/clojure.core/derive) to make
  the data type to be rendered a descendent of
  `:rdf/TransitData`. This will make this eligible for handling by that
  _render-literal_ method.
  - e.g. `(derive clojure.lang.PersistentVector :rdf/TransitData)`
  - All the usual Clojure containers already have such _derive_ declarations.
- Transit rendering for any such type can be disabled using _underive_.


The datatype URI whose qname is _transit:json_ expands to
`<http://rdf.natural-lexicon.org/ns/cognitect.transit#json>`, based on
the following declaration in _ont-app.rdf.ont_:

```
(voc/put-ns-meta!
 'cognitect.transit
 {
  :vann/preferredNamespacePrefix "transit"
  :vann/preferredNamespaceUri "http://rdf.naturallexicon.org/ns/cognitect.transit#"
  :dc/description "Functionality for the transit serialization format"
  :foaf/homepage "https://github.com/cognitect/transit-format"
  })
```

### String utilities

Some convenience utilites of dealing with strings.

#### `quote-str`

Adds string-escapes:

```clj
> (quote-str "yadda")
"\"yadda\""
```

#### `remove-newlines`

Saves a bit of trouble if you're running into newline-based parse errors:

```clj
> my-query
"\nSelect *\nWhere\n{\n  ?s ?p ?o.\n}\n"
> (remove-newlines my-query)
" Select * Where {   ?s ?p ?o. } "
(add my-graph [:myns/MyThing :myns/informedByQuery (remove-newlines my-query)])

```

<a name="query-templates"></a>
## Query templates supporting the IGraph member-access methods

It is expected that the basic IGraph member-access methods can be
covered by a common set of SPARQL queries for most if not all
RDF-based implementations.

For example, here is a template that should serve to acquire normal
form of a given graph (modulo tractability):

```
(def normal-form-query-template
  "
  Select ?s ?p ?o
  {{{from-clauses}}}
  Where
  {
    ?_s ?_p ?_o
    Bind ({{{rebind-_s}}} as ?s)
    Bind ({{{rebind-_p}}} as ?p)
    Bind ({{{rebind-_o}}} as ?o)
  }
  ")
```

This template can be referenced by a function `query-for-normal-form`

```
> (query-for-normal-form <query-fn> <rdf-store>)
> (query-for-normal-form <graph-kwi> <query-fn> <rdf-store>)
```
Where:

- `graph-kwi` is a KWI for the named graph URI. May also be a set of named graph URIs. (defaults to _nil_, indicating the DEFAULT graph). 
- `query-fn` is a platform-specific function to pose the rendered query template to _rdf-store_.
- `rdf-store` is a platform-specific point of access to the RDF store, e.g. a database connection or SPARQL endpoint.

Analogous template/function ensembles are defined for:
- `query-for-subjects`
- `query-for-p-o`
- `query-for-o`
- `ask-s-p-o`

Wherever KWIs are involved, checks will be performed to flag warnings
in cases where the metadata has not been properly specified for the
implied namespace of the KWI.

Note that the query template above has clauses like:

```
    ...
    Bind ({{{rebind-_s}}} as ?s)
    ...
```

The purpose of this is to allow for rebinding of blank nodes to a
platform-specific scheme that supports
'[round-tripping](https://aidanhogan.com/docs/bnodes.pdf)' of blank
nodes in subsequent queries to the same endpoint. The
[igraph-jena](https://github.com/ont-app/igraph-jena) project provides
a working example of this.

These templates should allow you to port the IGraph protocols to new
platforms fairly quickly, but as your implementation matures you may
find more efficient platform-specific equivalents.

<a name="i-o"></a>
## I/O

There are multimethods defined to read RDF into a graph and to write
it out in a specified format.

See the implementation of
[ont-app/igraph-jena](https://github.com/ont-app/igraph-jena) for an
implementation (v. 0.2.2 or later).

### The context graph

Each of these methods takes a
[native-normal](https://github.com/ont-app/igraph#Graph) `context`
graph as its first argument. See the docstrings of each of the i/o
functions for the operative vocabularies.

As an example, here is the default 'starter' graph provided in rdf.core:


```
;; in ont-app-rdf.core

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
```

Note that it configures a default directory for caching imported web
resources.


And here is the current context graph for `igraph-jena`:

```
;; in ont-app.igraph-jena.core

(defrecord JenaGraph ...)

(def standard-io-context
  (-> @rdf/default-context
      (igraph/add [[#'rdf/load-rdf
                    :rdf-app/hasGraphDispatch JenaGraph
                    ]
                   [#'rdf/read-rdf
                    :rdf-app/hasGraphDispatch JenaGraph
                    ]
                   [#'rdf/write-rdf
                    :rdf-app/hasGraphDispatch JenaGraph
                    ]
                   ])))
```

### Special dispatch KWIs

These keyword identifiers will be inferred automatically as dispatch
values for to-load/to-read/to-write arguments:

- `:rdf-app/LocalFile` for file in the local file system
- `:rdf-app/FileResource` for a file bound to a URL such as a jar resource
- `:rdf-app/WebResource` for a resource available through http. See
  also the discussion of the resource catalog below.

These can be overridden with e.g. `(add <context> [#'rdf/load-rdf :rdf-app/toImportDisptachFn <dispatch-fn>])` (or `:rdf-app/toExportDispatchFn` for writes).


### The resource catalog

The @`resource-catalog` is a
[native-normal](https://github.com/ont-app/igraph#Graph) graph containing
descriptions of web resources that you may want to add or load,
including their MIME types.

This graph is automatically populated from details included in the
[ont-app/vocabulary](https://github.com/ont-app/vocabulary#common-linked-data-namespaces)
metadata.

You can add entries to it with the `add-catalog-entry!` function.

```
> (add-catalog-entry! <download-url> <namespace-uri> <prefix> <media-type>)
```

The media types align with other data included in the `ont-app.rdf.ont` module.

See the docstring for `add-catalog-entry` for details.

#### `infer-media-type`

We can access media types associated with the suffix in a URL with `infer-media-type`...

```clj
> (rdf-app/infer-media-type (java.net.URL. "file:///tmp/my-file.tsv"))
"text/tab-sparated-values"
```

This is informed by the ontology in `ont-app.rdf.ont`, containing
descriptions using the http://www.w3.org/ns/formats/ vocabulary to
describe instances of http://purl.org/dc/terms/MediaTypeOrExtent .

You can add your own media types with
`ont-app.rdf-ont/add-media-type!`, for which see the docstring.

### Input

There are two methods for input, `load-rdf`, which translates a file
or URL into a new graph, and `read-rdf` which adds a file or URL to an
existing graph.

#### load-rdf

```
> (rdf/load-rdf <context> <to-load>) -> g
```

It is dispatched by the function `load-rdf-dispatch` -> [graph-dispatch to-load-dispatch] 
- `graph-dispatch` is typically the class name of the record implementing
  IGraph. It is specified by the triple `[#'load-rdf
  :rdf-app/hasGraphDispatch <graph-dispatch>]` in the `context` graph.

- `to-load-dispatch` will typically be one of the special dispatch KWIs
  described in the section above, defaulting to the type of `to-load`
  
  
#### read-rdf

```
> (rdf/read-rdf <context> <g> <to-load>) -> g
```
It is dispatched by the function `read-rdf-dispatch` -> `[graph-dispatch to-load-dispatch]``
- `graph-dispatch` is typically the class name of the record implementing
  IGraph. It is specified by the triple `[#'read-rdf
  :rdf-app/hasGraphDispatch <graph-dispatch>]` in the `context` graph.

- `to-load-dispatch` will typically be one of the special dispatch KWIs
  described in the section above, defaulting to the type of `to-load`

#### Caching

It may be the case that you want to retrieve a resource and cache it locally.

Such files will be cached in the directory specified in your i/o
context per `(unique (<context> :rdf-lib/UrlCache
:rdf-lib/directory))`.

<a name=dispatch-on-cached-resource></a>
##### Dispatch on `:rdf-app/CachedResource`

Both `load-rdf` and `read-rdf` have methods dispatched on `:rdf-app/CachedResource`.

```
> (derive <my-graph-implementation> :rdf-app/IGraph)
> (derive :rdf-app/WebResource :rdf-app/CachedResource)
> (def g (load-rdf <http://path/to/resource>))
```

This will cache the resource as a local file and be imported into your
graph with your method to load/read local files.

##### clear-url-cache!

If  the contents at some URL have changed since being cached, you may clear the cache as follows:

With only the i/o context provided, the entire cache will be cleared:

```
> (clear-url-cache! <context>)
```

Additional arguments should be URLs, and will clear any cached files
associated with each such URL:

```
> (clear-url-cache! <context> (java.net.URL. "http://www.w3.org/2000/01/rdf-schema#"))
```

### Output

There is one method to export data. It is also informed by the context
graph, and dispatches in the same way as the input methods, but output
formats are informed by KWIs mapped to values descibed in
https://www.w3.org/ns/formats/ , and
[derived](https://clojuredocs.org/clojure.core/derive) from
`:dct/MediaTypeOrExtent`.

#### write-rdf

```
> (rdf/write-rdf <context> <g> <target> <fmt>) 
```

This is dispatched on the function `write-rdf-dispatch` -> `[graph-dispatch to-write-dispatch fmt]`

- `graph-dispatch` and `to-write-dispatch` are pretty much as above.
- `fmt` passed through directly, and is typically one one of the
  format KWIs declared in `ont.cljc`, e.g. `:formats/Turtle` or
  `:formats/JSON-LD`. Again, see https://www.w3.org/ns/formats/ .


## Test support

The `ont-app.rdf.test-support` module builds on the [igraph
test-support](https://github.com/ont-app/igraph#testing-support)
regime.

This is probably best described by an example taken from the test
module for
[ont-app/igraph-jena](https://github.com/ont-app/igraph-vocabulary/blob/develop/test/ont_app/igraph_vocabulary/core_test.cljc):

```
(ns ont-app.igraph-jena.core-test
  (:require
    ...
    [ont-app.igraph-jena.core :as core]
    [ont-app.igraph.test-support :as test-support]
    [ont-app.rdf.test-support :as rdf-test]
    ...))
    
(def rdf-test-report (atom nil))

(defn init-rdf-report
  []
  (let [call-write-method (fn call-write-method [g ttl-file]
                            (rdf/write-rdf
                             core/standard-io-context
                             g
                             ttl-file
                             :formats/Turtle))
        ]
  (-> (native-normal/make-graph)
      (add [:rdf-app/RDFImplementationReport
            :rdf-app/makeGraphFn core/make-jena-graph
            :rdf-app/loadFileFn core/load-rdf
            :rdf-app/readFileFn core/read-rdf
            :rdf-app/writeFileFn call-write-method
            ]))))

(defn do-rdf-implementation-tests
  []
  (reset! rdf-test-report (init-rdf-report))
  (-> rdf-test-report
      (rdf-test/test-bnode-support)
      (rdf-test/test-load-of-web-resource)
      (rdf-test/test-read-rdf-methods)
      (rdf-test/test-write-rdf-methods)
      (rdf-test/test-transit-support)))

(deftest rdf-implementation-tests
  (let [report (do-rdf-implementation-tests)]
    (is (empty? (test-support/query-for-failures @report)))))
```

This logic will create an atom to contain a native-normal `report`
graph, and after being intialized with implementation-specific details
a battery of tests will be run, populating the graph with descriptions
of those tests' outcomes. Bad outcomes are flagged as failures, which
can be queried for in the report graph. Empty results for
`query-for-failures` indicates a passing test.


## Debugging
Functions in this module are logged with the
[graph-log](https://github.com/ont-app/graph-log) logging library,
which in addition to doing standard logging records various execution
events at log levels `:glog/TRACE` and `:glog/DEBUG`.

This can be enabled thus:

```
(require [ont-app.graph-log.core :as glog])

> (glog/set-level! :glog/LogGraph :glog/TRACE)
> ;; DO STUFF
> (glog/entries)
[<entry 0>
 .....
 <entry n>
 ]
 > 
```

See the graph-log documentation for details.

## License

Copyright © 2020-23 Eric D. Scott

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at https://www.gnu.org/software/classpath/license.html.


<table>
<tr>
<td width=75>
<img src="http://ericdscott.com/NaturalLexiconLogo.png" alt="Natural Lexicon logo" :width=50 height=50/> </td>
<td>
<p>Natural Lexicon logo - Copyright © 2020 Eric D. Scott. Artwork by Athena M. Scott.</p>
<p>Released under <a href="https://creativecommons.org/licenses/by-sa/4.0/">Creative Commons Attribution-ShareAlike 4.0 International license</a>. Under the terms of this license, if you display this logo or derivates thereof, you must include an attribution to the original source, with a link to https://github.com/ont-app, or  http://ericdscott.com. </p> 
</td>
</tr>
<table>
