(ns org-crud.lines
  (:require
   [clojure.string :as string]
   [org-crud.util :as util]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; tags
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn append-tags [line tags]
  (if-not tags
    line
    (let [tags
          (if (coll? tags) (set tags) (set [tags]))]
      (str line
           (when (seq tags)
             (str " :"
                  (string/join ":" tags)
                  ":"))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; property bucket
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn k->org-prop-key [k]
  (string/lower-case (name k)))

(defn new-property-text [k value]
  (str ":" (k->org-prop-key k) ": " value))

(comment
  (name :org.prop/my-key)
  (new-property-text :org.prop/my-key "hi"))

(defn prop->new-property [[k val]]
  (if (coll? val)
    (map-indexed (fn [i v]
                   (new-property-text
                     (str (k->org-prop-key k) (when (> i 0) "+")) v)) val)
    (new-property-text k val)))

(defn new-property-bucket [item]
  (let [res
        (flatten
          (seq [":PROPERTIES:"
                (->> item
                     (util/ns-select-keys "org.prop")
                     (map prop->new-property)
                     flatten
                     sort)
                ":END:"]))]
    res))


(comment
  (new-property-bucket
    {:org/name       "hi"
     :org/tags       #{" "}
     :org.prop/title "2020-08-02"
     :org.prop/id    "e79bec75-6e54-4ccb-b753-3ec359291355"
     :org/id         nil})
  (new-property-bucket
    {:org/name      "item name"
     :org/tags      #{"hello" "world"}
     :org/id        nil
     :org.prop/some "value"
     :org.prop/id   "and such"
     :org.prop/urls ["blah" "other blah.com"]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; root comment/properties
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn new-root-property-text [k value]
  (str "#+" (k->org-prop-key k) ": " value))

(defn prop->new-root-property
  "Flattens multi-values."
  [blah]
  (let [[k val] blah]
    (if (coll? val)
      (map-indexed
        (fn [i v]
          (new-root-property-text
            (str (k->org-prop-key k) (when (> i 0) "+")) v)) val)
      (new-root-property-text k val))))

(defn new-root-property-bucket
  "Make sure #+title lands on top to support `deft`."
  [item]
  (let [item (update item :props #(into {} %))
        prop-bucket
        (->>
          (concat
            [[:title (:org/name item)]
             [:id (or (:org/id item) (:org.prop/id item))]
             (when (->> item :org/tags (map string/trim) (remove empty?) seq)
               [:roam_tags (string/join " " (:org/tags item))])
             (when-let [k (:org.prop/roam-key item)]
               [:roam_key k])]
            (some->
              (util/ns-select-keys "org.prop" item)
              (dissoc :org.prop/title
                      :org.prop/id :org/tags
                      :org.prop/roam_tags :org.prop/roam-tags
                      :org.prop/roam-key)))
          (remove nil?)
          (remove (comp nil? second))
          (map prop->new-root-property)
          flatten
          (remove nil?))]
    prop-bucket))

(comment
  (new-root-property-bucket
    {:org/level      :root
     :org/name       "hi"
     :org/tags       #{" "}
     :org.prop/title "2020-08-02"
     :org.prop/id    "e79bec75-6e54-4ccb-b753-3ec359291355"
     :org/id         nil})
  (new-root-property-bucket
    {:org/name      "item name"
     :org/tags      #{"hello" "world"}
     :org/id        nil
     :org.prop/some "value"
     :org.prop/id   "and such"
     :org.prop/urls ["blah" "other blah.com"]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; body text
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn body->lines [body]
  (reduce
    (fn [agg line]
      (cond
        ;; includes blank lines
        ;; also writes scheduled lines
        (:text line)
        (conj agg (:text line))

        (and (= :block (:type line))
             (= "SRC" (:block-type line)))
        (apply conj agg (flatten [(str "#+BEGIN_SRC " (:qualifier line))
                                  (map :text (:content line))
                                  "#+END_SRC"]))

        (and (= :drawer (:type line))
             (= :property-drawer-item (some-> line :content first :line-type)))
        ;; skip property drawers, they are handled elsewhere
        ;; could write these here, but i like them coming from `props` as a map
        agg

        :else
        (do
          (println "unhandled line in item->lines/body->lines" line)
          agg)))
    []
    body))

(defn root-body->lines [body]
  (->> body
       (remove (fn [line]
                 (some-> line :text (string/starts-with? "#+"))))
       body->lines))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; name / status
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn status->status-text [status]
  (when status
    (case status
      :status/cancelled   "CANCELLED"
      :status/done        "[X]"
      :status/not-started "[ ]"
      :status/in-progress "[-]"
      ;; anything else clears the status completely
      "")))

(defn headline-name
  [{:keys [org/status org/tags org/name]} level]
  (let [level     (or level 1)
        level-str (apply str (repeat level "*"))
        headline  (str level-str
                       (when status
                         (str " " (status->status-text status)))
                       " " name)]
    (append-tags headline tags)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; item->lines as headline
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare item->root-lines)

(defn item->lines
  ([item] (item->lines item (:org/level item)))
  ([{:keys [org/body org/items] :as item} level]
   (if (= :level/root level)
     (item->root-lines item)
     (let [headline       (headline-name item level)
           prop-lines     (new-property-bucket item)
           body-lines     (body->lines body)
           children-lines (->> items (mapcat item->lines))]
       (concat
         (conj
           (concat prop-lines body-lines)
           headline)
         children-lines)))))

(comment
  (item->lines {:org/name "hi" :org/tags ["next"] :org.prop/hi :bye} :level/root)
  (item->lines {:org/name "hi" :org/tags ["next"] :org.prop/hi :bye} 3)
  (new-property-bucket {:org/name "hi" :org/tags ["next"] :org.prop/hi :bye})
  )

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; item->root-lines as full file
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; TODO elevate to exposed api temp-buffer support
(defn item->root-lines
  [{:keys [org/body org/items] :as item}]
  (let [root-prop-lines (new-root-property-bucket item)
        body-lines      (root-body->lines body)
        children-lines  (->> items (mapcat item->lines))]
    (concat
      root-prop-lines
      body-lines
      children-lines)))

(comment
  (item->root-lines {:org/name "hi" :org/tags ["next" "day"] :org.prop/hi :bye}))
