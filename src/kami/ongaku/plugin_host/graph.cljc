(ns kami.ongaku.plugin-host.graph
  "Self-contained node-registry / workflow-graph primitives: a small
  registry (type-name -> node-type map), link resolution, topological
  sort, and structural validation over a ComfyUI-API-format workflow
  (a map of node-id -> {:class_type ... :inputs {...}}).

  This repo previously took a hard `deps.edn` dependency on
  `kotoba-lang/comfyui` for exactly this (comfyui.node/registry,
  comfyui.workflow/topo-sort, comfyui.workflow/validate). comfyui is
  GPL-3.0 licensed; a code dependency on it would make this repo (meant
  to be Apache-2.0, matching kami-ongaku-notation/project/sampler,
  kami-eizo-timeline, org-iso-h264) a GPL derivative. kami-eizo-compositor
  hit the identical situation and avoided it the same way: emit data
  compatible with comfyui's node-registry/workflow contract shape, but
  implement the (generic, not comfyui-specific) graph algorithms
  natively here. A consumer that has separately accepted comfyui's GPL
  terms can still wire this repo's node-type maps into a live
  comfyui.node/registry unchanged -- the shape is unchanged, only the
  algorithm implementation moved in-repo.")

;; ---------------------------------------------------------------------
;; registry: type-name (string) -> node-type map
;; ---------------------------------------------------------------------

(defn registry
  "Builds a registry (plain map, not an atom -- this repo never mutates
  it after construction) from a coll of node-type maps, each needing at
  least :type (string) and :fn (ifn)."
  [node-types]
  (into {}
        (map (fn [t]
               (when-not (and (string? (:type t)) (ifn? (:fn t)))
                 (throw (ex-info "Node type needs :type (string) and :fn" {:node-type t})))
               [(:type t) t]))
        node-types))

(defn get-type [reg type-name]
  (get reg type-name))

(defn required-inputs
  "Input specs without :optional true and without a :default."
  [node-type]
  (into {}
        (remove (fn [[_ spec]] (or (:optional spec) (contains? spec :default))))
        (:inputs node-type)))

;; ---------------------------------------------------------------------
;; workflow: node-id (string) -> {:class_type ... :inputs {...}}
;; ---------------------------------------------------------------------

(defn link?
  "[node-id output-index]?"
  [v]
  (and (vector? v) (= 2 (count v)) (string? (first v)) (nat-int? (second v))))

(defn dependencies
  "Node ids this node links from, deduped."
  [workflow node-id]
  (->> (get-in workflow [node-id :inputs])
       vals
       (filter link?)
       (map first)
       distinct
       vec))

(defn topo-sort
  "Topological order of the workflow's node ids. Deterministic:
  lexicographic among the nodes ready at each step (all dependencies
  already placed). Throws ex-info on a cycle (info: {:remaining [...]})."
  [workflow]
  (let [ids (sort (keys workflow))]
    (loop [order [] done #{} remaining (vec ids)]
      (if (empty? remaining)
        order
        (let [ready (filter (fn [id] (every? done (dependencies workflow id)))
                             remaining)]
          (when (empty? ready)
            (throw (ex-info "Cycle in workflow" {:remaining remaining})))
          (recur (into order ready)
                 (into done ready)
                 (vec (remove (set ready) remaining))))))))

(defn- cycle-errors [workflow]
  (try (topo-sort workflow) []
       (catch #?(:clj Exception :cljs :default) e
         [{:error :cycle :nodes (:remaining (ex-data e))}])))

(defn- node-errors [reg workflow [id {:keys [class_type inputs]}]]
  (if-let [t (get-type reg class_type)]
    (concat
     (for [[in-name _] (required-inputs t)
           :when (not (contains? inputs in-name))]
       {:node id :error :missing-input :input in-name})
     (for [[in-name v] inputs
           :let [spec (get-in t [:inputs in-name])
                 err (cond
                       (nil? spec)
                       {:error :unknown-input :input in-name}

                       (link? v)
                       (let [[src idx] v]
                         (cond
                           (not (contains? workflow src))
                           {:error :dangling-link :input in-name :link v}

                           (>= idx (count (:outputs (get-type reg (get-in workflow [src :class_type])))))
                           {:error :no-such-output :input in-name :link v})))]
           :when err]
       (assoc err :node id)))
    [{:node id :error :unknown-class :class_type class_type}]))

(defn validate
  "Validates a workflow against a node registry. Returns
  {:valid? bool :errors [{:node id :error kw ...} ...]}. Checks: known
  class_type, required inputs present, links point at existing
  nodes/output indices, no cycles."
  [reg workflow]
  (let [errors (vec (concat (mapcat (partial node-errors reg workflow) workflow)
                             (cycle-errors workflow)))]
    {:valid? (empty? errors) :errors errors}))
