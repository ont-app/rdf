- v 0.3.2:
  - Adding resource-type for URLs
  - Refactoring the way transit data is quoted (issue 15)
- v 0.3.1:
  - Tweak to test support for bnodes
- v 0.3.0:
  - Changes per vocabulary 0.4.0
    - refactoring for resource-type methods
    - voc/dstr tags esp. for transit/json
    - tweaks to bnode handling
  - Adding a read-literal method
  - Tweaks to render-literal
  - fix to issue 12 (qname error)
  - fix to issue 14 (reading raw from github)
- v 0.2.9:
  - Reforming caching behavior
    - Only load-rdf and read-rdf methods defined here dispatch on :rdf-app/CachedResource
    - Adding clear-url-cache! function
- v 0.2.8:
  - Clarifying documentation of test support
- v 0.2.7:
  - Wrapping all the transit stuff in reader conditionals.
- v 0.2.6:
  - Wrapping all the http stuff in reader conditionals.
- v 0.2.5:
  - Wrapping all the i/o stuff in reader conditionals.
- v 0.2.4:
  - commenting out deps on clojurescript pending resolution of issue 4
- v 0.2.3:
  - i/o
    - load-rdf multimethod
    - read-rdf multimethod
    - write-rdf multimethod
    - ontology support for MIME formats
  - added a test-support module
- v 0.2.2:
  - Got clumsy with the release tagging, which CI did not
    forgive. Bumping the version to goose it a bit.
  - Tweaking Makefile
- v 0.2.1:
  - commenting transit-cljs back in to address issue #6
- v 0.2.0:
  - lein project.clj -> clojure deps.edn
  - Fix for issue 5 (multiple FROM clauses)
- v 0.1.4 :
  - disabling cljs deps and tests
  - dependencies update: graph-log 0.1.5
- v 0.1.3 : 
  - adding parameters to query templates for re-binding bnodes for
    round-tripping on implementations that can support that
- V 0.1.2 : 
  - adding render-literal support for lstr and numbers
- V 0.1.1 : 
  - upgrading vocabulary and igraph-vocabulary to 0.1.2
