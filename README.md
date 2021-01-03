# <img src="http://ericdscott.com/NaturalLexiconLogo.png" alt="NaturalLexicon logo" :width=100 height=100/> ont-app/rdf

A backstop for shared logic between rdf-based implementations of IGraph.

Part of the ont-app library, dedicated to Ontology-driven development.

## Contents
- [Dependencies](#h2-dependencies)
- [Motivation](#h2-motivation)
- [Literals](#h2-literals)
  - [The `render-literal` multimethod](#h3-render-literal-multimethod)
    - [`@special-literal-dispatch`](#h4-special-literal-dispatch)
  - [Language-tagged strings](#h3-language-tagged-strings)
  - [`xsd` values](#h3-xsd-values)
  - [Transit-encoded-values](#h3-transit-encoded-values)
- [Query templates supporting the IGraph member-access methods](#h2-query-templates)

<a name="h2-dependencies"></a>
## Dependencies

```
[ont-app/rdf "0.1.0-SNAPSHOT"]
```

Require thus:
```
(:require 
  [ont-app.rdf.core :as rdf-app]
  )      
```

### URI namespace mappings
The namepace metadata of the core maps to `ont-app.rdf.ont`, whose
preferred prefix is `rdf-app` (since `rdf` is already spoken for).

The preferred namespace URI is declared as
`"http://rdf.naturallexicon.org/rdf/ont#"`.
    
<a name="h2-motivation"></a>
## Motivation
There are numerous RDF-based platforms, each with its own
idosyncracies, but there is also a significant overlap between the
underlying logical structure of each such platform. This library aims
to capture that overlap, parameterized appropriately for
implementation-specific variations.

This includes:
- A multimethod `render-literal`, aimed at translating between clojure
  data and data to be stored in an RDF store.
- Support for language-tagged strings and a declaration of an `#lstr`
  reader macro.
- Support for a `^^transit:json` datatype tag, allowing for arbitrary
  clojure content to be serialized/deserialized as strings in
  an RDF store.
- SPARQL query templates and supporting code to query for the standard
  member access methods of the IGraph protocol.

<a name="h2-literals"></a>
## Literals

<a name="h3-render-literal-multimethod"></a>
### The `render-literal` multimethod

Each RDF-based implementation of IGraph will need to translate between
clojure data and RDF literals. These will include langage-tagged
strings, xsd types for the usual scalar values, and possibly custom
URI-tagged data. Sometimes the specific platform will already define
its own intermediate datastructures for such literals. 

The `render-literal` multimethod exists to handle the task of
translating between these contexts.

`render-literal` is dispatched by the function
`render-literal-dispatch`, which takes as an argument a single
literal, and returns a value expected to be fielded by some
_render-literal_ method keyed to that value.

By default, instances of the LangStr record (described below) will be
dispatched on ::LangStr, with a method defined to return say `"my word"@en`.
otherwise the dispatch value will be the type of the literal.

Integers and floats will be rendered directly by default. Values
unhandled by a specific method will default to be rendered in quotes.

There is a _translate-literal_ method defined for :rdf-app/TransitData,
discussed in more detail below.

<a name="h4-special-literal-dispatch"></a>
#### `@special-literal-dispatch` 

Often there is platform-specific behavior required for specific types
of literals, for example grafter has its own way of handling xsd values.

There is an atom defined called `special-literal-dispatch` (defult nil) which if non-nil should be a function `f [x] -> <dispatch-value>`. Any non-nil dispatch value returned by this function will override the default behavior of _render-literal-dispatch_, and provide a dispatch value to which you may target the appropriate methods.

<a name="h3-language-tagged-strings"></a>
### Language-tagged strings

This library imports 'ont-app.vocabulary.lstr', along with its #lstr
reader macro.

Such values will be dispatched to _render-literal_ as _:rdf/LangStr_,
but the _render-literal_ method for LangStr is expected to be
platform-sepcific.

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
user-defined records and datatypes will not be handled by the xsd
standard.

This library supports storing such literals in serialized form using a
`^^transit.json` datatype URI tag. 

The _render-literal_ method keyed to `:rdf/TransitData` is the handler
encoding data as transit. To use it, take the following steps:

- As needed, add a _read handler_
  `transit-read-handlers_ (described below). 
  - Note that standard Clojure containers and LangStr already have
    such handlers
- As needed, add a _write handler_ to `transite-write-handlers`
  - Again, the standard clojure containers and LangStr records are
    already covered.
- Use _derive_ to make the data type to be rendered a descendent of
  `:rdf/TransitData`. This will make the eligible for handling by that
  _render-literal_ method.
  - e.g. `(derive clojure.lang.PersistentVector :rdf/TransitData)`
  - All the usual clojure containers already have such _derive_ declarations.
- Transit rendering for any such type can disabled using _underive_.


The datatype URI whose qname is _transit.json_ expands to
`<http://rdf.natural-lexicon.org/ns/cognitect.transit#json>`, based on
the follwing delcaration in _ont-app.rdf.ont_:

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

For example, here is a template that should serve to acquire normal form of a given graph (modulo tractability):

```
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

```

This template can be referenced by a function `query-for-normal-form`

```
> (query-for-normal-form <query-fn> <rdf-store>)
> (query-for-normal-form <graph-kwi> <query-fn> <rdf-store>)
```
Where:

- `<graph-kwi>` is a KWI for the named graph URI (defaults to _nil_, indicating the DEFAULT graph)
- `<query-fn>` is a platform-specific function to pose the rendered query template to _rdf-store_.
- `<rdf-store>` is a platform-specific point of access to the RDF store, e.g. a database connection or SPARQL endpoint.

Analogous template/function ensembles are defined for:
- `query-for-subjects`
- `query-for-p-o`
- `query-for-o`
- `ask-s-p-o`

Wherever KWIs are involved, checks will be performed to flag warnings
in cases where the metadata has not been properly specified for the
implied namespace of the KWI.


## License

Copyright © 2020 Eric D. Scott

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
