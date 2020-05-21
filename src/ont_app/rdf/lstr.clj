(ns ont-app.rdf.lstr
  {:doc "Defines LangStr type to inform #lstr custom reader tag"
   :author "Eric D. Scott"
   }
  (:require [cljs.compiler])
  )

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

(defmethod print-method LangStr
  [literal ^java.io.Writer w]
  (.write w (str "#lstr \"" literal "@" (.lang literal) "\"")))

(defmethod print-dup LangStr [o ^java.io.Writer w]
  (print-method o w))

(defn lang [langStr]
  "returns the language tag associated with `langStr`"
  (.lang langStr))

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

;; Handles 'is not a valid ClojureScript constant' error on cljs side...
(defmethod cljs.compiler/emit-constant* ont_app.rdf.lstr.LangStr
  [x]
  (cljs.compiler/emits "new ont_app.rdf.lstr.LangStr (\""
                       (.s x)
                       "\" , \""
                       (.lang x)
                       "\")"))

