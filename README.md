# ont-app/rdf

A backstop for shared logic between rdf-based implementations of IGraph.

Part of the ont-app library, dedicated to Ontology-driven development.

## Contents
- [Dependencies](#h2-dependencies)
- [Motivation](#h2-motivation)
- [Literals](#h2-literals)
  - [The `render-literal` multimethod](#h3-render-literal-mulitmethod)

## Dependencies
Watch this space.

## Motivation
There are numerous RDF-based platforms, each with its own
idosyncracies, but there is also a significant overlap between the
underlying logical structure of each such platform. This library aims
to capture that overlap, parameterized appropriately for
platform-specific variations.

This includes:
- A multimethod `render-literal`, aimed at translating between clojure
  data and data to be stored in an RDF store.
- Support for language-tagged strings and a declaration of a `#lstr`
  reader macro.
- Support for a `^^transit:json` datatype tag, allowing for arbitrary
  clojure content to be serialized/deserialized as json-based text in
  an RDF store.
- SPARQL query templates and supporting code to query for the standard
  member access methods of the IGraph protocol.

## Literals

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
_render-literal_ method keyed to it.

By default, instances of the LangStr record (described below) will be
dispatched on ::LangStr, otherwise the type of the literal will be the
dispatch value.

There is a _translate-literal_ method defined for :rdf/TransitData,
discussed in more detail below.

#### `@special-literal-dispatch` 

Often there are planform-specific behavior required for specific types
of literals, for example in the case where grafter has its own regime
in place for handling xsd values.

There is an atom defined called `special-literal-dispatch` (defult nil) which if non-nil should be a function `f [x] -> <dispatch-value>`. Any non-nil dispatch value returned by this function will override the default behavior, and provide a dispatch value to which you may target your own platform-specific methods.

### Language-tagged strings

RDF entails use of language-tagged strings (e.g. `"gaol"@en-GB`) when
providing natural-language content. Doing this directly in Clojure is a bit hard to type, since the inner quotes would need to be escaped. 

This library defines a reader macro `#lstr` and accompanying record _LangStr_ to facilitate wriing these in clojure. The value above for example would be written: `#lstr "gaol@en-GB"`.

The such values will be dispatched as :rdf/LangStr, but the
_render-literal_ method for LangStr is expected to be
platform-sepcific.

### `xsd` values

Most RDF-platforms will typically provide some means of dealing with
_xsd_-encoded values, which encode the usual scalar values such as
integers, floats, dates, etc. 

Part of adapting IGraph to any new RDF-based platform will involve
defining _special-literal-dispatch_ and _render-literal_ methods as
appropriate.

The existing `sparql-client` and `igraph-grafter` implementations
should serve as instructive examples.

### Transit-based values

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
TODO: import readme content from spaql-client.

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
