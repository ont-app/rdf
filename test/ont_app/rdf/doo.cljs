(ns ont-app.rdf.doo
  (:require [doo.runner :refer-macros [doo-tests]]
            [ont-app.rdf.core-test]
            ))

(doo-tests
 'ont-app.rdf.core-test
 )
