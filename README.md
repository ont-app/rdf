# <img src="http://ericdscott.com/NaturalLexiconLogo.png" alt="NaturalLexicon logo" :width=100 height=100/> ont-app/rdf

A backstop for shared logic between rdf-based implementations of IGraph.

Part of the ont-app library, dedicated to Ontology-driven development.

## Contents
- [Dependencies](#h2-dependencies)
- [Motivation](#h2-motivation)
- [Supporting Ontology](supporting-ontology)
- [Literals](#h2-literals)
  - [The `render-literal` multimethod](#h3-render-literal-multimethod)
    - [`@special-literal-dispatch`](#h4-special-literal-dispatch)
  - [Language-tagged strings](#h3-language-tagged-strings)
  - [`xsd` values](#h3-xsd-values)
  - [Transit-encoded-values](#h3-transit-encoded-values)
- [Query templates supporting the IGraph member-access methods](#h2-query-templates)
- [Debugging](#h2-debugging)
- [URI namespace mappings](uri-namespace-mappings)
<a name="h2-dependencies"></a>
## Dependencies

[![Clojars Project](https://img.shields.io/clojars/v/ont-app/rdf.svg)](https://clojars.org/ont-app/rdf)

Require thus:
```
(:require 
  [ont-app.rdf.core :as rdf-app]
  )      
```
    
<a name="h2-motivation"></a>
## Motivation
There are numerous RDF-based platforms, each with its own
idosyncracies, but there is also a significant overlap between the
underlying logical structure of each such platform. This library aims
to capture that overlap, parameterized appropriately for
implementation-specific variations.

This includes:
- A multimethod `render-literal`, aimed at translating between Clojure
  data and data to be stored in an RDF store.
- Support for language-tagged strings with `#voc/lstr` reader macro
  defined in the [vocabulary](https://github.com/ont-app/vocabulary) module.
- Support for a `^^transit:json` datatype tag, allowing for arbitrary
  Clojure content to be serialized/deserialized as strings in
  an RDF store.
- SPARQL query templates and supporting code to query for the standard
  member access methods of the
  [IGraph](https://github.com/ont-app/igraph) protocol.

## Supporting ontology

There is a small supporting ontology defined in `ont-app.rdf.ont`,
which the namepace metadata of the core maps to. Its preferred prefix
is `rdf-app` (since `rdf` is already spoken for with
_ont-app.vocabulary.rdf_).

The preferred namespace URI is declared as
`"http://rdf.naturallexicon.org/rdf/ont#"`.

<a name="h2-literals"></a>
## Literals

<a name="h3-render-literal-multimethod"></a>
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

Integers and floats both derive from `::number`, and will be rendered
directly as they are in Clojure by default. Values unhandled by a
specific method will default to be rendered as strings in quotes.

Instances of `LangStr` will be rendered as [discussed
below](h3-language-tagged-strings).

All of this behavior can be overridden with the
`@special-literal-dispatch` atom descussed in the following section.

<a name="h4-special-literal-dispatch"></a>
#### `@special-literal-dispatch` 

Often there is platform-specific behavior required for specific types
of literals, for example grafter has its own way of handling xsd values.

There is an atom defined called `special-literal-dispatch` (defult
nil) which if non-nil should be a function `f [x] ->
<dispatch-value>`. Any non-nil dispatch value returned by this
function will override the default behavior of
_render-literal-dispatch_, and provide a dispatch value to which you
may target the appropriate methods.

The [igraph-grafter](https://github.com/ont-app/igraph-grafter) source
has examples of this.

<a name="h3-language-tagged-strings"></a>
### Language-tagged strings

This library imports 'ont-app.vocabulary.lstr', along with its #voc/lstr
reader macro.

Such values will be dispatched on their type
(`ont_app.vocabulary.lstr.LangStr`), and rendered as say `"my English
words"@en`.

<a name="h3-xsd-values"></a>
### `xsd` values

Most RDF-platforms will typically provide some means of dealing with
_xsd_-encoded values, which encode the usual scalar values such as
integers, floats, dates, etc. 

Part of adapting IGraph to any new RDF-based platform will involve
defining _special-literal-dispatch_ and _render-literal_ methods as
appropriate.

The existing `sparql-client` and `igraph-grafter` implementations
should serve as instructive examples.

<a name="h3-transit-encoded-values"></a>
### Transit-encoded values

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

> (defn round-trip [x]
    "Returns `x` after converting it to a transit literal and re-parsing it"
    (as-> (rdf-app/render-literal x) 
        it
        (re-matches rdf-app/transit-re it)
        (nth it 1))
        (rdf-app/read-transit-json it))
        
> (round-trip `(fn [x] "yowsa"))
(clojure.core/fn [x] "yowsa")

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
- Transit rendering for any such type can disabled using _underive_.


The datatype URI whose qname is _transit:json_ expands to
`<http://rdf.natural-lexicon.org/ns/cognitect.transit#json>`, based on
the following delcaration in _ont-app.rdf.ont_:

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

<a name="h2-query-templates"></a>
## Query templates supporting the IGraph member-access methods

It is expected that the basic IGraph member-access methods will be
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

<a name="h3-round-tripping"></a>

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


<a name="h2-debugging"></a>
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
