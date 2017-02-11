(ns sablono.compiler-test
  (:refer-clojure :exclude [compile])
  (:require [clojure.spec :as s]
            [clojure.spec.gen :as gen]
            [clojure.test :refer :all]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.properties :as prop]
            [clojure.walk :refer [prewalk]]
            [sablono.compiler :refer :all]
            [sablono.core :refer [attrs html html-expand]]
            [sablono.interpreter :as interpreter])
  (:import cljs.tagged_literals.JSValue))

(deftype JSValueWrapper [val]
  Object
  (equals [this other]
    (and (instance? JSValueWrapper other)
         (= (.val this) (.val other))))
  (hashCode [this]
    (.hashCode (.val this)))
  (toString [this]
    (.toString (.val this))))

(defmethod print-method JSValueWrapper
  [^JSValueWrapper v, ^java.io.Writer w]
  (.write w "#js ")
  (.write w (pr-str (.val v))))

(defn wrap-js-value [forms]
  (prewalk
   (fn [form]
     (if (instance? JSValue form)
       (JSValueWrapper. (wrap-js-value (.val form))) form))
   forms))

(defn replace-gensyms [forms]
  (prewalk
   (fn [form]
     (if (and (symbol? form)
              (re-matches #"attrs\d+" (str form)))
       'attrs form))
   forms))

(defmacro compile [form]
  `(wrap-js-value (replace-gensyms (macroexpand '(html ~form)))))

(defn js [x]
  (JSValueWrapper. (wrap-js-value x)))

(defmacro are-html-expanded [& body]
  `(are [form# expected#]
       (is (= (wrap-js-value expected#)
              (wrap-js-value (replace-gensyms (html-expand form#)))))
     ~@body))

(deftest test-compile-attrs-js
  (are [attrs expected]
      (= (wrap-js-value expected)
         (wrap-js-value (compile-attrs-js attrs)))
    nil nil
    {:class "my-class"}
    #js {:className "my-class"}
    {:class '(identity "my-class")}
    #js {:className (sablono.util/join-classes (identity "my-class"))}
    {:class "my-class"
     :style {:background-color "black"}}
    #js {:className "my-class"
         :style #js {:backgroundColor "black"}}
    {:class '(identity "my-class")
     :style {:background-color '(identity "black")}}
    #js {:className (sablono.util/join-classes (identity "my-class"))
         :style #js {:backgroundColor (identity "black")}}
    {:id :XY}
    #js {:id "XY"}))

(deftest test-attrs
  (are [form expected]
      (= (wrap-js-value expected)
         (wrap-js-value (attrs form)))
    nil nil
    {:class "my-class"}
    #js {:className "my-class"}
    {:class ["a" "b"]}
    #js {:className "a b"}
    {:class '(identity "my-class")}
    #js {:className (sablono.util/join-classes '(identity "my-class"))}
    {:class "my-class"
     :style {:background-color "black"}}
    #js {:className "my-class"
         :style #js {:backgroundColor "black"}}
    {:class '(identity "my-class")
     :style {:background-color '(identity "black")}}
    #js {:className (sablono.util/join-classes '(identity "my-class"))
         :style #js {:backgroundColor '(identity "black")}}))

(deftest test-to-js
  (let [v (to-js [])]
    (is (instance? JSValue v))
    (is (= [] (.val v))))
  (let [v (to-js {})]
    (is (instance? JSValue v))
    (is (= {} (.val v))))
  (let [v (to-js [1 [2] {:a 1 :b {:c [2 [3]]}}])]
    (is (instance? JSValue v))
    (is (= 1 (first (.val v))))
    (is (= [2] (.val (second (.val v)))))
    (let [v (nth (.val v) 2)]
      (is (instance? JSValue v))
      (is (= 1 (:a (.val v))))
      (let [v (:b (.val v))]
        (is (instance? JSValue v))
        (let [v (:c (.val v))]
          (is (instance? JSValue v))
          (is (= 2 (first (.val v))))
          (is (= [3] (.val (second (.val v))))))))))

(defspec test-tag-empty
  (prop/for-all
   [tag (s/gen keyword?)]
   (= (eval `(compile [~tag]))
      (js {:$$typeof sablono.core/react-element-sym
           :type (name tag)
           :props (js {})}))))

(deftest test-tag-keyword
  (is (= (compile [:div])
         (js {:$$typeof sablono.core/react-element-sym
              :type "div"
              :props #js {}}))))

(deftest test-tag-string
  (is (= (compile ["div"])
         (js {:$$typeof sablono.core/react-element-sym
              :type "div"
              :props #js {}}))))

(deftest test-tag-symbol
  (is (= (compile ['div])
         (js {:$$typeof sablono.core/react-element-sym
              :type "div"
              :props (js {})}))))

(deftest test-tag-syntax-sugar-id
  (is (= (compile [:div#foo])
         (js {:$$typeof sablono.core/react-element-sym
              :type "div"
              :props (js {:id "foo"})}))))

(deftest test-tag-syntax-sugar-class
  (is (= (compile [:div.foo])
         (js {:$$typeof sablono.core/react-element-sym
              :type "div"
              :props (js {:className "foo"})}))))

(deftest test-tag-syntax-sugar-class-multiple
  (is (= (compile [:div.a.b.c])
         (js {:$$typeof sablono.core/react-element-sym
              :type "div"
              :props (js {:className "a b c"})}))))

(deftest test-tag-syntax-sugar-id-class
  (is (= (compile [:div#a.b.c])
         (js {:$$typeof sablono.core/react-element-sym
              :type "div"
              :props (js {:id "a" :className "b c"})}))))

(deftest tag-tag-syntax-sugar-class-fn-child
  (is (= (compile [:div.foo (str "bar" "baz")])
         (wrap-js-value
          '(let* [attrs (str "bar" "baz")]
             (clojure.core/apply
              js/React.createElement "div"
              (if (clojure.core/map? attrs)
                (sablono.interpreter/attributes
                 (sablono.normalize/merge-with-class {:class ["foo"]} attrs))
                #js {:className "foo"})
              (if (clojure.core/map? attrs)
                nil [(sablono.interpreter/interpret attrs)])))))))

(deftest test-tag-content-text
  (is (= (compile [:text "Lorem Ipsum"])
         (js {:$$typeof sablono.core/react-element-sym
              :type "text"
              :props (js {:children "Lorem Ipsum"})}))))

(deftest test-tag-content-concat-string
  (is (= (compile [:div "foo" "bar"])
         (js {:$$typeof sablono.core/react-element-sym
              :type "div"
              :props (js {:children (js ["foo" "bar"])})}))))

(deftest test-tag-content-concat-elements
  (is (= (compile [:div [:p] [:br]])
         (js {:$$typeof sablono.core/react-element-sym
              :type "div"
              :props (js {:children (js '[(js/React.createElement "p" nil)
                                          (js/React.createElement "br" nil)])})}))))

(deftest test-tag-content-seqs-are-expanded
  (are-html-expanded
   '[:div (list "foo" "bar")]
   '(let* [attrs (list "foo" "bar")]
      (clojure.core/apply
       js/React.createElement "div"
       (if (clojure.core/map? attrs)
         (sablono.interpreter/attributes attrs)
         nil)
       (if (clojure.core/map? attrs)
         nil [(sablono.interpreter/interpret attrs)])))
   '(list [:p "a"] [:p "b"])
   '(sablono.interpreter/interpret (list [:p "a"] [:p "b"]))))

(deftest tag-content-vectors-dont-expand
  (is (thrown? Exception (html (vector [:p "a"] [:p "b"])))))

(deftest tag-content-can-contain-tags
  (testing "tags can contain tags"
    (are-html-expanded
     '[:div [:p]]
     (js {:$$typeof sablono.core/react-element-sym
          :type "div"
          :props
          (js {:children
               (js {:$$typeof sablono.core/react-element-sym
                    :type "p"
                    :props (js {})})})})

     '[:div [:b]]
     (js {:$$typeof sablono.core/react-element-sym
          :type "div"
          :props
          (js {:children
               (js {:$$typeof sablono.core/react-element-sym
                    :type "b"
                    :props (js {})})})})


     '[:p [:span [:a "foo"]]]
     (js {:$$typeof sablono.core/react-element-sym
          :type "p"
          :props
          (js {:children
               (js {:$$typeof sablono.core/react-element-sym
                    :type "span"
                    :props
                    (js {:children
                         (js {:$$typeof sablono.core/react-element-sym
                              :type "a"
                              :props
                              (js {:children "foo"})})})})})}))))

(deftest test-tag-attributes-tag-with-empty-attribute-map
  (is (= (compile [:div {}])
         (js {:$$typeof sablono.core/react-element-sym
              :type "div"
              :props (js {})}))))

(deftest test-tag-attributes-tag-with-populated-attribute-map
  (is (= (compile [:div {:min "1", :max "2"}])
         (js {:$$typeof sablono.core/react-element-sym
              :type "div"
              :props (js {:min "1", :max "2"})})))

  (is (= (compile [:img {"id" "foo"}])
         (js {:$$typeof sablono.core/react-element-sym
              :type "img"
              :props (js {"id" "foo"})})))

  (is (= (compile [:img {:id "foo"}])
         (js {:$$typeof sablono.core/react-element-sym
              :type "img"
              :props (js {:id "foo"})}))))

(deftest test-tag-attributes-attribute-values-are-escaped
  (is (= (compile [:div {:id "\""}])
         (js {:$$typeof sablono.core/react-element-sym
              :type "div"
              :props (js {:id "\""})}))))

(deftest test-tag-attributes-attributes-are-converted-to-their-DOM-equivalents
  (are-html-expanded
   '[:div {:class "classy"}]
   (js {:$$typeof sablono.core/react-element-sym
        :type "div"
        :props (js {:className "classy"})})

   '[:div {:data-foo-bar "baz"}]
   (js {:$$typeof sablono.core/react-element-sym
        :type "div"
        :props (js {:data-foo-bar "baz"})})

   '[:label {:for "foo"}]
   (js {:$$typeof sablono.core/react-element-sym
        :type "label"
        :props (js {:htmlFor "foo"})})))

(deftest test-tag-attributes-boolean-attributes
  (are-html-expanded
   '[:input {:type "checkbox" :checked true}]
   '(sablono.interpreter/create-element "input" #js {:checked true, :type "checkbox"})
   '[:input {:type "checkbox" :checked false}]
   '(sablono.interpreter/create-element "input" #js {:checked false, :type "checkbox"})))

(deftest test-tag-attributes-nil-attributes
  (is (= (compile [:span {:class nil} "foo"])
         (js {:$$typeof sablono.core/react-element-sym
              :type "span"
              :props (js {:className nil, :children "foo"})}))))

(deftest test-tag-attributes-empty-attributes
  (is (= (compile [:span {} "foo"])
         (js {:$$typeof sablono.core/react-element-sym
              :type "span"
              :props (js {:children "foo"})}))))

(deftest test-tag-attributes-tag-with-aria-attributes
  (is (= (compile [:div {:aria-disabled true}])
         (js {:$$typeof sablono.core/react-element-sym
              :type "div"
              :props (js {:aria-disabled true})}))))

(deftest test-tag-attributes-tag-with-data-attributes
  (is (= (compile [:div {:data-toggle "modal" :data-target "#modal"}])
         (js {:$$typeof sablono.core/react-element-sym
              :type "div"
              :props (js {:data-toggle "modal", :data-target "#modal"})}))))

(deftest compiled-tags
  (testing "tag content can be vars, and vars can be type-hinted with some metadata"
    (let [x "foo"
          y {:id "id"}]
      (are-html-expanded
       '[:span x]
       '(let* [attrs x]
          (clojure.core/apply
           js/React.createElement "span"
           (if (clojure.core/map? attrs)
             (sablono.interpreter/attributes attrs)
             nil)
           (if (clojure.core/map? attrs)
             nil [(sablono.interpreter/interpret attrs)])))
       '[:span ^:attrs y]
       '(let* [attrs y]
          (clojure.core/apply
           js/React.createElement "span"
           (sablono.interpreter/attributes attrs) nil)))))
  (testing "tag content can be forms, and forms can be type-hinted with some metadata"
    (are-html-expanded
     '[:span (str (+ 1 1))]
     '(let* [attrs (str (+ 1 1))]
        (clojure.core/apply
         js/React.createElement "span"
         (if (clojure.core/map? attrs)
           (sablono.interpreter/attributes attrs)
           nil)
         (if (clojure.core/map? attrs)
           nil [(sablono.interpreter/interpret attrs)])))

     [:span ({:foo "bar"} :foo)]
     #js {:$$typeof sablono.core/react-element-sym
          :type "span"
          :props #js {:children "bar"}}

     '[:span ^:attrs (merge {:type "button"} attrs)]
     '(let* [attrs (merge {:type "button"} attrs)]
        (clojure.core/apply
         js/React.createElement "span"
         (sablono.interpreter/attributes attrs) nil))))
  (testing "attributes can contain vars"
    (let [id "id"]
      (are-html-expanded
       '[:div {:id id}] '(js/React.createElement "div" #js {:id id})
       '[:div {:id id} "bar"] '(js/React.createElement "div" #js {:id id} "bar"))))
  (testing "attributes are evaluated"
    (are-html-expanded
     '[:img {:src (str "/foo" "/bar")}]
     '(js/React.createElement "img" #js {:src (str "/foo" "/bar")})
     '[:div {:id (str "a" "b")} (str "foo")]
     '(js/React.createElement "div" #js {:id (str "a" "b")} (sablono.interpreter/interpret (str "foo")))))
  (testing "type hints"
    (let [string "x"]
      (are-html-expanded
       '[:span ^String string] '(js/React.createElement "span" nil string))))
  (testing "values are evaluated only once"
    (let [times-called (atom 0)
          foo #(swap! times-called inc)]
      (html-expand [:div (foo)])
      (is (= @times-called 1)))))

(deftest test-benchmark-template
  (are-html-expanded
   '[:li
     [:a {:href (str "#show/" (:key datum))}]
     [:div {:id (str "item" (:key datum))
            :class ["class1" "class2"]}
      [:span {:class "anchor"} (:name datum)]]]
   '(js/React.createElement
     "li" nil
     (js/React.createElement
      "a" #js {:href (str "#show/" (:key datum))})
     (js/React.createElement
      "div" #js {:id (str "item" (:key datum)), :className "class1 class2"}
      (js/React.createElement
       "span" #js {:className "anchor"}
       (sablono.interpreter/interpret (:name datum)))))))

(deftest test-issue-2-merge-class
  (are-html-expanded
   '[:div.a {:class (if (true? true) "true" "false")}]
   '(js/React.createElement
     "div" #js {:className (sablono.util/join-classes ["a" (if (true? true) "true" "false")])})
   '[:div.a.b {:class (if (true? true) ["true"] "false")}]
   '(js/React.createElement
     "div" #js {:className (sablono.util/join-classes ["a" "b" (if (true? true) ["true"] "false")])})))

(deftest test-issue-3-recursive-js-literal
  (are-html-expanded
   '[:div.interaction-row {:style {:position "relative"}}]
   #js {:$$typeof sablono.core/react-element-sym
        :type "div"
        :props #js {:style #js {:position "relative"}
                    :className "interaction-row"}})
  (let [username "foo" hidden #(if %1 {:display "none"} {:display "block"})]
    (are-html-expanded
     '[:ul.nav.navbar-nav.navbar-right.pull-right
       [:li.dropdown {:style (hidden (nil? username))}
        [:a.dropdown-toggle {:role "button" :href "#"} (str "Welcome, " username)
         [:span.caret]]
        [:ul.dropdown-menu {:role "menu" :style {:left 0}}]]]
     '(js/React.createElement
       "ul"
       #js {:className "nav navbar-nav navbar-right pull-right"}
       (js/React.createElement
        "li"
        #js {:style (clj->js (hidden (nil? username))), :className "dropdown"}
        (js/React.createElement
         "a"
         #js {:role "button", :href "#", :className "dropdown-toggle"}
         (sablono.interpreter/interpret (str "Welcome, " username))
         #js {:$$typeof sablono.core/react-element-sym
              :type "span"
              :props #js {:className "caret"}})
        #js {:$$typeof sablono.core/react-element-sym
             :type "ul"
             :props #js {:role "menu"
                         :style #js {:left 0}
                         :className "dropdown-menu"}})))))

(deftest test-issue-22-id-after-class
  (are-html-expanded
   [:div.well#setup]
   #js {:$$typeof sablono.core/react-element-sym
        :type "div"
        :props #js {:id "setup"
                    :className "well"}}))

(deftest test-issue-25-comma-separated-class
  (are-html-expanded
   '[:div.c1.c2 "text"]
   #js {:$$typeof sablono.core/react-element-sym
        :type "div"
        :props #js {:className "c1 c2"
                    :children "text"}}

   '[:div.aa (merge {:class "bb"})]
   '(let* [attrs (merge {:class "bb"})]
      (clojure.core/apply
       js/React.createElement "div"
       (if (clojure.core/map? attrs)
         (sablono.interpreter/attributes
          (sablono.normalize/merge-with-class {:class ["aa"]} attrs))
         #js {:className "aa"})
       (if (clojure.core/map? attrs)
         nil [(sablono.interpreter/interpret attrs)])))))

(deftest test-issue-33-number-warning
  (are-html-expanded
   '[:div (count [1 2 3])]
   '(let* [attrs (count [1 2 3])]
      (clojure.core/apply
       js/React.createElement "div"
       (if (clojure.core/map? attrs)
         (sablono.interpreter/attributes attrs)
         nil)
       (if (clojure.core/map? attrs)
         nil [(sablono.interpreter/interpret attrs)])))))

(deftest test-issue-37-camel-case-style-attrs
  (are-html-expanded
   '[:div {:style {:z-index 1000}}]
   #js {:$$typeof sablono.core/react-element-sym
        :type "div"
        :props #js {:style #js {:zIndex 1000}}}))

(deftest shorthand-div-forms
  (are-html-expanded
   [:#test]
   #js {:$$typeof sablono.core/react-element-sym
        :type "div"
        :props #js {:id "test"}}

   '[:.klass]
   #js {:$$typeof sablono.core/react-element-sym
        :type "div"
        :props #js {:className "klass"}}

   '[:#test.klass]
   #js {:$$typeof sablono.core/react-element-sym
        :type "div"
        :props #js {:id "test"
                    :className "klass"}}

   '[:#test.klass1.klass2]
   #js {:$$typeof sablono.core/react-element-sym
        :type "div"
        :props #js {:id "test"
                    :className "klass1 klass2"}}

   '[:.klass1.klass2#test]
   #js {:$$typeof sablono.core/react-element-sym
        :type "div"
        :props #js {:id "test"
                    :className "klass1 klass2"}}))

(deftest test-namespaced-fn-call
  (are-html-expanded
   '(some-ns/comp "arg")
   '(sablono.interpreter/interpret (some-ns/comp "arg"))
   '(some.ns/comp "arg")
   '(sablono.interpreter/interpret (some.ns/comp "arg"))))

(deftest test-compile-div-with-nested-lazy-seq
  (is (= (compile [:div (map identity ["A" "B"])])
         '(let* [attrs (map identity ["A" "B"])]
            (clojure.core/apply
             js/React.createElement "div"
             (if (clojure.core/map? attrs)
               (sablono.interpreter/attributes attrs)
               nil)
             (if (clojure.core/map? attrs)
               nil
               [(sablono.interpreter/interpret attrs)]))))))

(deftest test-compile-div-with-nested-list
  (is (= (compile [:div '("A" "B")])
         (js {:$$typeof sablono.core/react-element-sym
              :type "div"
              :props (js {:children (js ["A" "B"])})}))))

(deftest test-compile-div-with-nested-vector
  (is (= (compile [:div ["A" "B"]])
         (js {:$$typeof sablono.core/react-element-sym
              :type "div"
              :props (js {:children #js ["A" "B"]})})))
  (is (= (compile [:div (vector "A" "B")])
         '(let* [attrs (vector "A" "B")]
            (clojure.core/apply
             js/React.createElement "div"
             (if (clojure.core/map? attrs)
               (sablono.interpreter/attributes attrs)
               nil)
             (if (clojure.core/map? attrs)
               nil
               [(sablono.interpreter/interpret attrs)]))))))

(deftest test-class-as-set
  (is (= (compile [:div.a {:class #{"a" "b" "c"}}])
         (js {:$$typeof sablono.core/react-element-sym
              :type "div"
              :props (js {:className "a a b c"})}))))

(deftest test-class-as-list
  (is (= (compile [:div.a {:class (list "a" "b" "c")}])
         (wrap-js-value
          '(js/React.createElement "div" #js {:className (sablono.util/join-classes ["a" (list "a" "b" "c")])})))))

(deftest test-class-as-vector
  (is (= (compile [:div.a {:class (vector "a" "b" "c")}])
         (wrap-js-value
          '(js/React.createElement
            "div" #js {:className (sablono.util/join-classes ["a" (vector "a" "b" "c")])})))))

(deftest test-class-merge-symbol
  (let [class #{"b"}]
    (is (= (eval `(compile [:div.a {:class ~class}]))
           (js {:$$typeof sablono.core/react-element-sym
                :type "div"
                :props (js {:className "a b"})})))))

(deftest test-issue-90
  (is (= (compile [:div nil (case :a :a "a")])
         '(js/React.createElement
           "div" nil nil
           (sablono.interpreter/interpret
            (case :a :a "a"))))))

(deftest test-compile-attr-class
  (are [form expected]
      (= expected (compile-attr :class form))
    nil nil
    "foo" "foo"
    '("foo" "bar" ) "foo bar"
    ["foo" "bar"] "foo bar"
    #{"foo" "bar"} "foo bar"
    '(set "foo" "bar")
    '(sablono.util/join-classes (set "foo" "bar"))
    '[(list "foo" "bar")]
    '(sablono.util/join-classes [(list "foo" "bar")])))

(deftest test-optimize-let-form
  (is (= (compile (let [x "x"] [:div "x"]))
         (wrap-js-value
          '(let* [x "x"]
             #js {:$$typeof sablono.core/react-element-sym
                  :type "div"
                  :props #js {:children "x"}})))))

(deftest test-optimize-for-loop
  (is (= (compile [:ul (for [n (range 3)] [:li n])])
         '(js/React.createElement
           "ul" nil
           (into-array
            (clojure.core/for [n (range 3)]
              (clojure.core/let [attrs n]
                (clojure.core/apply
                 js/React.createElement "li"
                 (if (clojure.core/map? attrs)
                   (sablono.interpreter/attributes attrs)
                   nil)
                 (if (clojure.core/map? attrs)
                   nil [(sablono.interpreter/interpret attrs)]))))))))
  (is (= (compile [:ul (for [n (range 3)] [:li ^:attrs n])])
         '(js/React.createElement
           "ul" nil
           (into-array
            (clojure.core/for [n (range 3)]
              (clojure.core/let [attrs n]
                (clojure.core/apply
                 js/React.createElement "li"
                 (sablono.interpreter/attributes attrs) nil))))))))

(deftest test-optimize-if
  (is (= (compile (if true [:span "foo"] [:span "bar"]) )
         (wrap-js-value
          '(if true
             #js {:$$typeof sablono.core/react-element-sym
                  :type "span"
                  :props #js {:children "foo"}}
             #js {:$$typeof sablono.core/react-element-sym
                  :type "span"
                  :props #js {:children "bar"}})))))

(deftest test-issue-115
  (is (= (compile [:a {:id :XY}])
         (js {:$$typeof sablono.core/react-element-sym
              :type "a"
              :props (js {:id "XY"})}))))

(deftest test-issue-130
  (let [css {:table-cell "bg-blue"}]
    (is (= (compile [:div {:class (:table-cell css)} [:span "abc"]])
           (wrap-js-value
            '(js/React.createElement
              "div"
              #js {:className (sablono.util/join-classes [(:table-cell css)])}
              #js {:$$typeof sablono.core/react-element-sym
                   :type "span"
                   :props #js {:children "abc"}}))))))

(deftest test-issue-141-inline
  (testing "with attributes"
    (is (= (compile [:span {} ^:inline (constantly 1)])
           '(js/React.createElement "span" nil (constantly 1)))))
  (testing "without attributes"
    (is (= (compile [:span ^:inline (constantly 1)])
           '(js/React.createElement "span" nil (constantly 1))))))

(deftest test-compile-attributes-non-literal-key
  (is (= (compile [:input {(case :checkbox :checkbox :checked :value) "x"}])
         '(sablono.interpreter/create-element
           "input" (sablono.interpreter/attributes
                    {(case :checkbox :checkbox :checked :value) "x"})))))
