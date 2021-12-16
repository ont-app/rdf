(defproject ont-app/rdf "0.1.4"
  :description "Backstop for shared logic among RDF-based IGraph implementations"
  :url "https://github.com/ont-app/rdf"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [;; clojure
                 [org.clojure/clojure "1.10.3"]
                 
                 [org.clojure/spec.alpha "0.3.218"]
                 ;; 3rd party libs
                 [cheshire "5.10.1"]
                 [cljstache "2.0.6"]
                 [com.cognitect/transit-clj "1.0.324"]
                 [com.cognitect/transit-cljs "0.8.269"]
                 [com.taoensso/timbre "5.1.2"]
                 ;; Ont-app libs
                 [ont-app/graph-log "0.1.5"]
                 ]
  
  ;; :main ^:skip-aot ont-app.rdf.core
  :target-path "target/%s"
  :resource-paths ["resources" "target/cljsbuild"]
  ;; NOTE CLJS STUFF DISABLED FOR NOW
  :plugins [[lein-codox "0.10.6"]
            #_[lein-cljsbuild "1.1.7"
             :exclusions [[org.clojure/clojure]]]
            #_[lein-doo "0.1.11"]
            ]
  :source-paths ["src"]
  :test-paths ["src" "test"]
  ;;:cljsbuild
  #_{
   :test-commands {"test" ["lein" "doo" "node" "test" "once"]}
   :builds
   {
    :dev {:source-paths ["src"]
           :compiler {
                      :main ont-app.rdf.core 
                      :asset-path "js/compiled/out"
                      :output-to "resources/public/js/rdf.js"
                      :source-map-timestamp true
                      :output-dir "resources/public/js/compiled/out"
                      :optimizations :none
                      }
          }
    ;; for testing the cljs incarnation
    ;; run with 'lein doo node test
    :test {:source-paths ["src" "test"]
           :compiler {
                      :main ont-app.rdf.doo
                      :target :nodejs
                      :asset-path "resources/test/js/compiled/out"
                      :output-to "resources/test/compiled.js"
                      :output-dir "resources/test/js/compiled/out"
                      :optimizations :advanced ;;:none
                      }
           }
   }} ;; end cljsbuild

  :codox {:output-path "doc"}

  :profiles {:uberjar {:aot :all}
             :dev {:dependencies [[binaryage/devtools "1.0.4"]
                                  ;;[lein-doo "0.1.11"]
                                  ;;[org.clojure/clojurescript "1.10.896"]
                                  ]
                   :source-paths ["src"] 
                   :clean-targets
                   ^{:protect false}
                   ["resources/public/js/compiled"
                    "resources/test"
                    :target-path
                    ]
                   }
             })
