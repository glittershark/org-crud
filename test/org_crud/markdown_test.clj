(ns org-crud.markdown-test
  (:require
   [org-crud.markdown :as sut]
   [clojure.test :refer [deftest testing is]]
   [org-crud.core :as org]
   [org-crud.fs :as fs]
   [clojure.string :as string]))

(def fixture-dir (str fs/*cwd* "/test/org_crud/markdown_fixtures"))

(defn parsed-org-files []
  (org/dir->nested-items fixture-dir))

(defn parsed-org-file [fname]
  (org/path->nested-item (str fixture-dir "/" fname)))

(defn build-md-files
  "Converts all the .org files in fixture-dir to .md files."
  []
  (doall
    (->> (parsed-org-files)
         (map sut/item->md-item)
         (map (partial sut/write-md-item fixture-dir)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; general
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest build-and-write-count-test
  (build-md-files)
  (let [files (fs/list-dir fixture-dir)]
    (testing "same number of org and md files"
      (is (> (->> files (filter #(= (fs/extension %) ".org")) count) 0))
      (is (= (->> files (filter #(= (fs/extension %) ".org")) count)
             (->> files (filter #(= (fs/extension %) ".md")) count))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; item->frontmatter
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest frontmatter-test
  (let [example-org (parsed-org-file "example.org")
        lines       (-> example-org sut/item->frontmatter)]
    (testing "org items convert to a proper frontmatter"
      (is (= "---" (first lines)))
      (is (= "---" (last lines))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; item->md-body
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest markdown-body-comment-test
  (let [example-org (parsed-org-file "example.org")
        lines       (-> example-org sut/item->md-body)]
    (testing "drops org comments"
      (is (= () (->> lines (filter #(string/starts-with? % "#+"))))))))

(def example-item
  {:level :root,
   :name  "Example Org File",
   :body  [{:line-type :comment, :text "#+TITLE: Example Org File"}
           {:line-type :blank, :text ""}
           {:line-type :table-row, :text "Some org content."}
           {:line-type :blank, :text ""}],
   :items
   [{:name  "An org header",
     :level 1,
     :items
     [{:level 2,
       :name  "A nested org header",
       :body
       [{:line-type :table-row,
         :text      "Content therein."}],}]
     :body  []}
    {:level 1,
     :name  "Conclusion",
     :body  []}]})

(deftest markdown-body-test
  (let [example-org example-item
        lines       (->> example-org
                         sut/item->md-body
                         (remove empty?))]
    (testing "converts items to headers based on level"
      (is (= "# An org header" (some->> lines
                                        (filter #(string/starts-with? % "#"))
                                        first)))
      (is (= "## A nested org header"
             (some->> lines
                      (filter #(string/starts-with? % "##"))
                      first)))
      (is (= "# Conclusion" (some->> lines
                                     (filter #(string/starts-with? % "#"))
                                     last)))
      (is (= "Some org content." (some->> lines
                                          (remove #(string/starts-with? % "#"))
                                          first)))
      (is (= "Content therein." (some->> lines
                                         (remove #(string/starts-with? % "#"))
                                         last))))))


(def example-item-src-block
  {:level :root,
   :name  "Example Org File",
   :body  [{:line-type :comment, :text "#+TITLE: Example Org File"}
           {:line-type :blank, :text ""}]
   :items
   [{:name  "A src-block org header",
     :level 1,
     :body
     [{:type       :block,
       :content
       [{:line-type :table-row, :text "(-> \"hello\""}
        {:line-type :table-row, :text "    (println \"world\"))"}],
       :block-type "SRC",
       :qualifier  "clojure"}],}]})

(deftest markdown-code-block-test
  (let [example-org example-item-src-block
        lines       (->> example-org sut/item->md-body
                         (remove empty?))]
    (testing "builds markdown source blocks"
      (is (= "``` clojure" (->> lines
                                (filter #(string/starts-with? % "```"))
                                first)))
      (is (= "```" (->> lines
                        (filter #(string/starts-with? % "```"))
                        last))))))
