(ns frontend.format.block
  "Block code needed by app but not graph-parser"
  (:require [clojure.string :as string]
            [logseq.graph-parser.block :as gp-block]
            [frontend.config :as config]
            [frontend.db :as db]
            [frontend.format :as format]
            [frontend.state :as state]
            [logseq.graph-parser.property :as gp-property]
            [logseq.graph-parser.mldoc :as gp-mldoc]))

(defn extract-blocks
  "Wrapper around logseq.graph-parser.block/extract-blocks that adds in system state"
  [blocks content with-id? format]
  (gp-block/extract-blocks blocks content with-id? format
                           {:user-config (state/get-config)
                            :block-pattern (config/get-block-pattern format)
                            :supported-formats (config/supported-formats)
                            :db (db/get-db (state/get-current-repo))
                            :date-formatter (state/get-date-formatter)}))

(defn page-name->map
  "Wrapper around logseq.graph-parser.block/page-name->map that adds in db"
  ([original-page-name with-id?]
   (page-name->map original-page-name with-id? true))
  ([original-page-name with-id? with-timestamp?]
   (gp-block/page-name->map original-page-name with-id? (db/get-db (state/get-current-repo)) with-timestamp? (state/get-date-formatter))))

(defn parse-block
  ([block]
   (parse-block block nil))
  ([{:block/keys [uuid content page format] :as block} {:keys [with-id?]
                                                        :or {with-id? true}}]
   (when-not (string/blank? content)
     (let [block (dissoc block :block/pre-block?)
           ast (format/to-edn content format nil)
           blocks (extract-blocks ast content with-id? format)
           new-block (first blocks)
           parent-refs (->> (db/get-block-parent (state/get-current-repo) uuid)
                            :block/path-refs
                            (map :db/id))
           {:block/keys [refs]} new-block
           ref-pages (filter :block/name refs)
           path-ref-pages (->> (concat ref-pages parent-refs [(:db/id page)])
                               (remove nil?))
           block (cond->
                   (merge
                    block
                    new-block
                    {:block/path-refs path-ref-pages})
                   (> (count blocks) 1)
                   (assoc :block/warning :multiple-blocks))
           block (dissoc block :block/title :block/body :block/level)]
       (if uuid (assoc block :block/uuid uuid) block)))))

(defn parse-title-and-body
  ([block]
   (when (map? block)
     (merge block
            (parse-title-and-body (:block/uuid block)
                                  (:block/format block)
                                  (:block/pre-block? block)
                                  (:block/content block)))))
  ([block-uuid format pre-block? content]
   (when-not (string/blank? content)
     (let [content (if pre-block? content
                       (str (config/get-block-pattern format) " " (string/triml content)))]
       (if-let [result (state/get-block-ast block-uuid content)]
         result
         (let [ast (->> (format/to-edn content format (gp-mldoc/default-config format))
                        (map first))
               title (when (gp-block/heading-block? (first ast))
                       (:title (second (first ast))))
               body (vec (if title (rest ast) ast))
               body (drop-while gp-property/properties-ast? body)
               result (cond->
                        (if (seq body) {:block/body body} {})
                        title
                        (assoc :block/title title))]
           (state/add-block-ast-cache! block-uuid content result)
           result))))))

(defn macro-subs
  [macro-content arguments]
  (loop [s macro-content
         args arguments
         n 1]
    (if (seq args)
      (recur
       (string/replace s (str "$" n) (first args))
       (rest args)
       (inc n))
      s)))

(defn break-line-paragraph?
  [[typ break-lines]]
  (and (= typ "Paragraph")
       (every? #(= % ["Break_Line"]) break-lines)))

(defn trim-paragraph-special-break-lines
  [ast]
  (let [[typ paras] ast]
    (if (= typ "Paragraph")
      (let [indexed-paras (map-indexed vector paras)]
        [typ (->> (filter
                            #(let [[index value] %]
                               (not (and (> index 0)
                                         (= value ["Break_Line"])
                                         (contains? #{"Timestamp" "Macro"}
                                                    (first (nth paras (dec index)))))))
                            indexed-paras)
                           (map #(last %)))])
      ast)))

(defn trim-break-lines!
  [ast]
  (drop-while break-line-paragraph?
              (map trim-paragraph-special-break-lines ast)))
