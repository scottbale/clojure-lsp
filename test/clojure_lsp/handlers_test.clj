(ns clojure-lsp.handlers-test
  (:require
    [clojure-lsp.db :as db]
    [clojure-lsp.handlers :as handlers]
    [clojure-lsp.test-helper :as h]
    [clojure.core.async :as async]
    [clojure.string :as string]
    [clojure.test :refer [deftest is testing]]
    [clojure.tools.logging :as log]))

(h/reset-db-after-test)

(defn code [& strings] (clojure.string/join "\n" strings))

(defn diagnostics-or-timeout []
  (first (async/alts!!
           [(async/timeout 1000)
            db/diagnostics-chan])))

(deftest did-open
  (let [_ (h/load-code-and-locs "(ns a) (when)")
        diagnostics (:diagnostics (diagnostics-or-timeout))]
    (is (some? (get-in @db/db [:analysis "/a.clj"])))
    (h/assert-submaps
      [{:code "missing-body-in-when"}
       {:code "invalid-arity"}]
      diagnostics)))

(deftest did-close
  (h/load-code-and-locs "(ns a)")
  (h/load-code-and-locs "(ns b)" "file:///b.clj")
  (testing "should remove references to file"
    (is (= ["file:///a.clj" "file:///b.clj"] (keys (:documents @db/db))))
    (handlers/did-close {:textDocument "file:///a.clj"})
    (is (= ["file:///b.clj"] (keys (:documents @db/db))))))

(deftest hover
  (let [start-code "```clojure"
        end-code "```"
        join (fn [coll] (string/join "\n" coll))
        code (str "(ns a)\n"
                  "(defn foo \"Some cool docs :foo\" [x] x)\n"
                  "(defn bar [y] y)\n"
                  "(|foo 1)\n"
                  "(|bar 1)")
        [foo-pos bar-pos] (h/load-code-and-locs code)]
    (testing "with docs"
      (let [sym "a/foo"
            sig "[x]"
            doc "Some cool docs :foo"
            filename "/a.clj"]
        (testing "show-docs-arity-on-same-line? disabled"
          (testing "plain"
            (is (= [(join [sym
                           sig
                           "" "----"
                           doc
                           "----"
                           filename])]
                   (:contents (handlers/hover {:textDocument "file:///a.clj" :position (h/->position foo-pos)})))))
          (testing "markdown"
            (swap! db/db merge {:client-capabilities {:text-document {:hover {:content-format ["markdown"]}}}})
            (is (= {:kind  "markdown"
                    :value (join [start-code sym end-code
                                  start-code sig end-code
                                  "" "----"
                                  start-code doc end-code
                                  "----"
                                  (str "*" filename "*")])}
                   (:contents (handlers/hover {:textDocument "file:///a.clj" :position (h/->position foo-pos)}))))))

        (testing "show-docs-arity-on-same-line? enabled"
          (testing "plain"
            (swap! db/db merge {:settings {:show-docs-arity-on-same-line? true} :client-capabilities nil})
            (is (= [(join [(str sym " " sig)
                           "" "----"
                           doc
                           "----"
                           filename])]
                   (:contents (handlers/hover {:textDocument "file:///a.clj" :position (h/->position foo-pos)})))))

          (testing "markdown"
            (swap! db/db merge {:client-capabilities {:text-document {:hover {:content-format ["markdown"]}}}})
            (is (= {:kind  "markdown"
                    :value (join [start-code (str sym " " sig) end-code
                                  "" "----"
                                  start-code doc end-code
                                  "----"
                                  (str "*" filename "*")])}
                   (:contents (handlers/hover {:textDocument "file:///a.clj" :position (h/->position foo-pos)}))))))))

    (testing "without docs"
      (let [sym "a/bar"
            sig "[y]"
            filename "/a.clj"]
        (testing "show-docs-arity-on-same-line? disabled"
          (testing "plain"
            (swap! db/db merge {:settings {:show-docs-arity-on-same-line? false} :client-capabilities nil})
            (is (= [(join [sym
                           sig
                           "" "----"
                           filename])]
                   (:contents (handlers/hover {:textDocument "file:///a.clj" :position (h/->position bar-pos)})))))
          (testing "markdown"
            (swap! db/db merge {:client-capabilities {:text-document {:hover {:content-format ["markdown"]}}}})
            (is (= {:kind  "markdown"
                    :value (join [start-code sym end-code
                                  start-code sig end-code
                                  "" "----"
                                  (str "*" filename "*")])}
                   (:contents (handlers/hover {:textDocument "file:///a.clj" :position (h/->position bar-pos)}))))))

        (testing "show-docs-arity-on-same-line? enabled"
          (testing "plain"
            (swap! db/db merge {:settings {:show-docs-arity-on-same-line? true} :client-capabilities nil})
            (is (= [(join [(str sym " " sig)
                           "" "----"
                           filename])]
                   (:contents (handlers/hover {:textDocument "file:///a.clj" :position (h/->position bar-pos)})))))

          (testing "markdown"
            (swap! db/db merge {:client-capabilities {:text-document {:hover {:content-format ["markdown"]}}}})
            (is (= {:kind "markdown"
                    :value (join [start-code (str sym " " sig) end-code
                                  "" "----"
                                  (str "*" filename "*")])}
                   (:contents (handlers/hover {:textDocument "file:///a.clj" :position (h/->position bar-pos)}))))))))))

(deftest document-symbol
  (h/load-code-and-locs "(ns a) (def bar ::bar) (def ^:m baz 1)")
  (is (= 1 (count (handlers/document-symbol {:textDocument "file:///a.clj"})))))

(deftest document-highlight
  (let [[bar-start] (h/load-code-and-locs "(ns a) (def |bar ::bar) (def ^:m baz 1)")]
    (h/assert-submaps
      [{:range {:start {:line 0 :character 12} :end {:line 0 :character 15}}}]
      (handlers/document-highlight {:textDocument "file:///a.clj"
                                    :position (h/->position bar-start)}))))



(deftest references
  (testing "simple single reference"
    (let [[bar-def-pos] (h/load-code-and-locs "(ns a) (def |bar 1)")
          _ (h/load-code-and-locs "(ns b (:require [a :as foo])) (foo/bar)" "file:///b.clj")]
      (h/assert-submaps
        [{:uri "file:///b.clj"
          :range {:start {:line 0 :character 31} :end {:line 0 :character 38}}}]
        (handlers/references {:textDocument "file:///a.clj"
                              :position (h/->position bar-def-pos)}))))
  (testing "when including declaration"
    (let [[bar-def-pos] (h/load-code-and-locs "(ns a) (def |bar 1)")
          _ (h/load-code-and-locs "(ns b (:require [a :as foo])) (foo/bar)" "file:///b.clj")]
      (h/assert-submaps
        [{:uri "file:///a.clj"
          :range {:start {:line 0 :character 12} :end {:line 0 :character 15}}}
         {:uri "file:///b.clj"
          :range {:start {:line 0 :character 31} :end {:line 0 :character 38}}}]
        (handlers/references {:textDocument "file:///a.clj"
                              :position (h/->position bar-def-pos)
                              :context {:includeDeclaration true}})))))

(deftest test-rename
  (let [[abar-start abar-stop
         akwbar-start akwbar-stop
         abaz-start abaz-stop] (h/load-code-and-locs "(ns a) (def |bar| ::|bar|) (def ^:m |baz| 1)" "file:///a.clj")
        [balias-start balias-stop
         ba1-start ba1-stop
         bbar-start bbar-stop
         ba2-start ba2-stop
         bkwbar-start bkwbar-stop] (h/load-code-and-locs "(ns b (:require [a :as |aa|])) (def x |aa|/|bar|) ::|aa|/|bar| :aa/bar" "file:///b.clj")
        [cbar-start cbar-stop
         cbaz-start cbaz-stop] (h/load-code-and-locs "(ns c (:require [a :as aa])) (def x aa/|bar|) ^:xab aa/|baz|" "file:///c.clj")]
    (testing "on symbol without namespace"
      (let [changes (:changes (handlers/rename {:textDocument "file:///a.clj"
                                                :position (h/->position abar-start)
                                                :newName "foo"}))]
        (is (= {"file:///a.clj" [{:new-text "foo" :range (h/->range abar-start abar-stop)}]
                "file:///b.clj" [{:new-text "foo" :range (h/->range bbar-start bbar-stop)}]
                "file:///c.clj" [{:new-text "foo" :range (h/->range cbar-start cbar-stop)}]}
               changes))))
    (testing "on symbol with metadata namespace"
      (let [changes (:changes (handlers/rename {:textDocument "file:///a.clj"
                                                :position (h/->position abaz-start)
                                                :newName "qux"}))]
        (is (= {"file:///a.clj" [{:new-text "qux" :range (h/->range abaz-start abaz-stop)}]
                "file:///c.clj" [{:new-text "qux" :range (h/->range cbaz-start cbaz-stop)}]}
               changes))))
    (testing "on symbol with namespace adds existing namespace"
      (let [changes (:changes (handlers/rename {:textDocument "file:///b.clj"
                                                :position (h/->position [(first bbar-start) (dec (second bbar-start))])
                                                :newName "foo"}))]
        (is (= {"file:///a.clj" [{:new-text "foo" :range (h/->range abar-start abar-stop)}]
                "file:///b.clj" [{:new-text "foo" :range (h/->range bbar-start bbar-stop)}]
                "file:///c.clj" [{:new-text "foo" :range (h/->range cbar-start cbar-stop)}]}
               changes))))
    (testing "on symbol with namespace removes passed-in namespace"
      (let [changes (:changes (handlers/rename {:textDocument "file:///b.clj"
                                                :position (h/->position bbar-start)
                                                :newName "aa/foo"}))]
        (is (= {"file:///a.clj" [{:new-text "foo" :range (h/->range abar-start abar-stop)}]
                "file:///b.clj" [{:new-text "foo" :range (h/->range bbar-start bbar-stop)}]
                "file:///c.clj" [{:new-text "foo" :range (h/->range cbar-start cbar-stop)}]}
               changes))))
    (testing "on a namespace"
      (reset! db/db {:project-root "file:///my-project"
                     :settings {:source-paths #{"/my-project/src" "/my-project/test"}}
                     :client-capabilities {:workspace {:workspace-edit {:document-changes true}}}})
      (h/load-code-and-locs "(ns foo.bar-baz)" "file:///my-project/src/foo/bar_baz.clj")
      (is (= {:document-changes
              [{:text-document {:version 0
                                :uri "file:///my-project/src/foo/bar_baz.clj"}
                :edits [{:range
                         {:start {:line 0 :character 4}
                          :end {:line 0 :character 15}}
                         :new-text "foo.baz-qux"}]}
               {:kind "rename"
                :old-uri "file:///my-project/src/foo/bar_baz.clj"
                :new-uri "file:///my-project/src/foo/baz_qux.clj"}]}
             (handlers/rename {:textDocument "file:///my-project/src/foo/bar_baz.clj"
                               :position {:line 0 :character 4}
                               :newName "foo.baz-qux"}))))))

(deftest test-find-diagnostics
  (testing "wrong arity"
    (testing "for argument destructuring"
      (reset! db/db {})
      (alter-var-root #'db/diagnostics-chan (constantly (async/chan 1)))
      (let [code "(defn foo ([x] x) ([x y] (x y)))
                  (defn bar [y & rest] ((foo y y y) (bar rest)))
                  (defn baz [{x :x y :y :as long}
                             {:keys [k v] :as short}
                             [_ a b]]
                    (x y k v a b long short))
                  (baz :broken :brokken [nil :ok :okay])
                  (baz {bar baz foo :no?})
                  (bar)
                  (bar {:a [:b]})
                  (bar :one-fish :two-fish :red-fish :blue-fish)
                  [foo]
                  {foo 1 2 3}
                  [foo 1 (foo 5 6 7)]
                  (foo)
                  (foo 1)
                  (foo 1 ['a 'b])
                  (foo 1 2 3 {:k 1 :v 2})"]
        (h/load-code-and-locs code)
        (let [usages (:diagnostics (diagnostics-or-timeout))]
          (is (= ["user/foo is called with 3 args but expects 1 or 2"
                  "user/baz is called with 1 arg but expects 3"
                  "user/bar is called with 0 args but expects 1 or more"
                  "user/foo is called with 3 args but expects 1 or 2"
                  "user/foo is called with 0 args but expects 1 or 2"
                  "user/foo is called with 4 args but expects 1 or 2"]
                 (map :message usages))))))
    (testing "for threading macros"
      (reset! db/db {})
      (alter-var-root #'db/diagnostics-chan (constantly (async/chan 1)))
      (let [code "(defn foo ([x] x) ([x y z] (z x y)))
                  (defn bar [] :bar)
                  (defn baz [arg & rest] (apply arg rest))
                  (->> :test
                       (foo)
                       (foo 1)
                       (bar))
                  (-> 1
                      (baz)
                      (->> (baz)
                           (foo 1 2))
                      (baz :p :q :r)
                      bar)
                  (cond-> 0
                    int? (bar :a :b)
                    false (foo)
                    :else (baz 3))
                  (doto 1
                    (foo)
                    (foo 1)
                    (bar))"]
        (h/load-code-and-locs code)
        (let [usages (:diagnostics (diagnostics-or-timeout))]
          (is (= ["user/foo is called with 2 args but expects 1 or 3"
                  "user/bar is called with 1 arg but expects 0"
                  "user/bar is called with 1 arg but expects 0"
                  "user/bar is called with 3 args but expects 0"
                  "user/foo is called with 2 args but expects 1 or 3"
                  "user/bar is called with 1 arg but expects 0"]
                 (map :message usages))))))
    (testing "with annotations"
      (reset! db/db {})
      (alter-var-root #'db/diagnostics-chan (constantly (async/chan 1)))
      (let [code "(defn foo {:added \"1.0\"} [x] (inc x))
                  (defn ^:private bar ^String [^Class x & rest] (str x rest))
                  (foo foo)
                  (foo foo foo)
                  (bar :a)
                  (bar :a :b)"]
        (h/load-code-and-locs code)
        (let [usages (:diagnostics (diagnostics-or-timeout))]
          (is (= ["user/foo is called with 2 args but expects 1"]
                 (map :message usages))))))
    (testing "for schema defs"
      (reset! db/db {})
      (alter-var-root #'db/diagnostics-chan (constantly (async/chan 1)))
      (let [code "(ns user (:require [schema.core :as s]))
                  (s/defn foo :- s/Str
                    [x :- Long y :- Long]
                    (str x y))
                  (foo)
                  (foo 1 2)
                  (foo 1)"]
        (h/load-code-and-locs code)
        (let [usages (:diagnostics (diagnostics-or-timeout))]
          (is (= ["user/foo is called with 0 args but expects 2"
                  "user/foo is called with 1 arg but expects 2"]
                 (map :message usages)))))))
  (testing "custom unused namespace declaration"
    (reset! db/db {})
    (alter-var-root #'db/diagnostics-chan (constantly (async/chan 1)))
    (h/load-code-and-locs "(ns foo.bar)")
    (let [usages (:diagnostics (diagnostics-or-timeout))]
      (is (empty?
            (map :message usages))))))

(deftest test-formatting
  (reset! db/db {:documents {"file://a.clj" {:text "(a  )\n(b c d)"}}})
  (is (= "(a)\n(b c d)"
         (:new-text (first (handlers/formatting {:textDocument "file://a.clj"}))))))

(deftest test-formatting-noop
  (reset! db/db {:documents {"file://a.clj" {:text "(a)\n(b c d)"}}})
  (let [r (handlers/formatting {:textDocument "file://a.clj"})]
    (is (empty? r))
    (is (vector? r))))

(deftest test-range-formatting
  (reset! db/db {:documents {"file://a.clj" {:text "(a  )\n(b c d)"}}})
  (is (= [{:range {:start {:line 0 :character 0}
                   :end {:line 0 :character 5}}
           :new-text "(a)"}]
         (handlers/range-formatting "file://a.clj" {:row 1 :col 1 :end-row 1 :end-col 4}))))

(deftest test-code-actions-handle
  (h/load-code-and-locs (str "(ns some-ns)\n"
                             "(def foo)")
                        "file://a.clj")
  (h/load-code-and-locs (str "(ns other-ns (:require [some-ns :as sns]))\n"
                             "(def bar 1)\n"
                             "(defn baz []\n"
                             "  bar)")
                        "file://b.clj")
  (h/load-code-and-locs (str "(ns another-ns)\n"
                             "(def bar ons/bar)\n"
                             "(def foo sns/foo)\n"
                             "(deftest some-test)\n"
                             "MyClass.\n"
                             "Date.")
                        "file://c.clj")
  (testing "when it has unresolved-namespace and can find namespace"
    (is (some #(= (:title %) "Add missing 'some-ns' require")
              (handlers/code-actions
                {:textDocument "file://c.clj"
                 :context {:diagnostics [{:code "unresolved-namespace"
                                          :range {:start {:line 2 :character 10}}}]}
                 :range {:start {:line 2 :character 10}}}))))
  (testing "without workspace edit client capability"
    (swap! db/db merge {:client-capabilities {:workspace {:workspace-edit false}}})
    (is (not-any? #(= (:title %) "Clean namespace")
                  (handlers/code-actions
                    {:textDocument "file://b.clj"
                     :context {:diagnostics []}
                     :range {:start {:line 1 :character 1}}}))))
  (testing "with workspace edit client capability"
    (swap! db/db merge {:client-capabilities {:workspace {:workspace-edit true}}})
    (is (some #(= (:title %) "Clean namespace")
              (handlers/code-actions
                {:textDocument "file://b.clj"
                 :context {:diagnostics []}
                 :range {:start {:line 1 :character 1}}})))))

(deftest test-code-lens
  (h/load-code-and-locs (str "(ns some-ns)\n"
                             "(def foo 1)\n"
                             "(defn- foo2 []\n"
                             " foo)\n"
                             "(defn bar [a b]\n"
                             "  (+ a b (foo2)))\n"
                             "(s/defn baz []\n"
                             "  (bar 2 3))\n"))
  (testing "references lens"
    (is (= '({:range
              {:start {:line 1 :character 5} :end {:line 1 :character 8}}
              :data ["file:///a.clj" 2 6]}
             {:range
              {:start {:line 2 :character 7} :end {:line 2 :character 11}}
              :data ["file:///a.clj" 3 8]}
             {:range
              {:start {:line 4 :character 6} :end {:line 4 :character 9}}
              :data ["file:///a.clj" 5 7]})
           (handlers/code-lens {:textDocument "file:///a.clj"})))))

(deftest test-code-lens-resolve
  (h/load-code-and-locs (str "(ns some-ns)\n"
                             "(def foo 1)\n"
                             "(defn- foo2 []\n"
                             " foo)\n"
                             "(defn bar [a b]\n"
                             "  (+ a b (foo2)))\n"
                             "(s/defn baz []\n"
                             "  (bar 2 3))\n"))
  (testing "references"
    (testing "empty lens"
      (is (= {:range   {:start {:line      0
                                :character 5}
                        :end   {:line      0
                                :character 12}}
              :command {:title   "0 references"
                        :command "code-lens-references"
                        :arguments ["file:///a.clj" 0 5]}}
             (handlers/code-lens-resolve {:data ["file:///a.clj" 0 5]
                                          :range {:start {:line 0 :character 5} :end {:line 0 :character 12}}}))))
    (testing "some lens"
      (is (= {:range   {:start {:line      1
                                :character 5}
                        :end   {:line      1
                                :character 12}}
              :command {:title   "1 references"
                        :command "code-lens-references"
                        :arguments ["file:///a.clj" 2 6]}}
             (handlers/code-lens-resolve {:data ["file:///a.clj" 2 6]
                                          :range {:start {:line 1 :character 5} :end {:line 1 :character 12}}})))
      (is (= {:range   {:start {:line      2
                                :character 7}
                        :end   {:line      2
                                :character 11}}
              :command {:title   "1 references"
                        :command "code-lens-references"
                        :arguments ["file:///a.clj" 3 8]}}
             (handlers/code-lens-resolve {:data ["file:///a.clj" 3 8]
                                          :range {:start {:line 2 :character 7} :end {:line 2 :character 11}}}))))))
