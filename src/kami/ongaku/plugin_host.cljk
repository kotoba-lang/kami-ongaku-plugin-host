(ns kami.ongaku.plugin-host
  "VST3/Audio-Unit-equivalent plugin hosting layer for the `ongaku`
  (music production) engine stack, ADR-2607121400 Wave 3.

  This is the *hosting contract*, not DSP (that's `audio`'s job) and not
  a graph execution engine. The graph *shape* here is compatible with
  comfyui's node-registry/workflow contract (class_type/inputs/outputs),
  but this repo has zero code dependency on comfyui (GPL-3.0) -- the
  generic graph algorithms (registry, topo-sort, structural validate)
  are implemented natively in kami.ongaku.plugin-host.graph. A consumer
  that has separately accepted comfyui's GPL terms can still wire these
  node-type maps into a live comfyui.node/registry unchanged. Think of
  it as the VST3/AU host layer sitting between
  `kami-ongaku-project` (whose `:bus/plugin-chain` holds opaque
  `:plugin-ref/id` refs, kami.ongaku.project/plugin-ref) and `audio`
  (which will eventually supply the real `:fn` per plugin descriptor).

  Portable .cljc across JVM / ClojureScript. No audio I/O, no comfyui
  execution context construction (that requires a Datomic-API `db-api`
  backend the caller supplies) -- this repo defines the node *types* and
  the pure host-level algorithms (latency compensation, automation
  resolution), and shows them wired into a comfyui workflow shape."
  (:require [kami.ongaku.plugin-host.graph :as graph]))

;; ---------------------------------------------------------------------
;; 1. Plugin contract: descriptor + instance
;; ---------------------------------------------------------------------

(def param-units #{:linear :db :hz :ms :percent :semitones :ratio})

(defn plugin-param
  "A single parameter on a plugin descriptor: name, numeric range,
  default, and unit (for host UI / automation display only -- this repo
  does no unit conversion math)."
  [{:keys [key label min max default unit] :or {unit :linear}}]
  (when (and (keyword? key)
             (string? label) (seq label)
             (number? min) (number? max) (<= min max)
             (number? default) (<= min default max)
             (contains? param-units unit))
    {:param/key key :param/label label :param/min min :param/max max
     :param/default default :param/unit unit}))

(def plugin-categories #{:effect :instrument :analyzer})

(defn plugin-descriptor
  "A plugin *type* -- id, vendor metadata, category, and its parameter
  list. `latency-samples` is the fixed processing latency this plugin
  type reports (PDC input); 0 for a zero-latency plugin."
  [{:keys [id name vendor category params latency-samples]
    :or {latency-samples 0}}]
  (when (and (string? id) (seq id)
             (string? name) (seq name)
             (string? vendor)
             (contains? plugin-categories category)
             (vector? params) (every? some? params)
             (int? latency-samples) (>= latency-samples 0))
    {:descriptor/id id :descriptor/name name :descriptor/vendor vendor
     :descriptor/category category :descriptor/params params
     :descriptor/latency-samples latency-samples}))

(defn- param-by-key [descriptor k]
  (some #(when (= (:param/key %) k) %) (:descriptor/params descriptor)))

(defn clamp-param-value
  "Clamp v into [min max]; if v is not a number, fall back to the
  param's default. This is the range-clamping + default-fallback
  behaviour every real plugin host applies to malformed automation/UI
  input rather than crashing."
  [descriptor param-key v]
  (when-let [p (param-by-key descriptor param-key)]
    (let [v (if (number? v) v (:param/default p))]
      (max (:param/min p) (min (:param/max p) v)))))

(defn plugin-instance
  "A plugin *instance* -- references a descriptor by id (`:id` here
  matches `kami.ongaku.project/plugin-ref`'s `:plugin-ref/id`), holds
  current parameter values (a map of param-key -> value, range-clamped
  against the descriptor), bypass flag, and wet/dry mix."
  [descriptor {:keys [id values bypass? wet-dry] :or {values {} bypass? false wet-dry 1.0}}]
  (when (and (map? descriptor) (:descriptor/id descriptor)
             (string? id) (seq id)
             (map? values)
             (number? wet-dry) (<= 0.0 wet-dry 1.0))
    (let [clamped (reduce-kv
                   (fn [m k v]
                     (if-let [cv (clamp-param-value descriptor k v)]
                       (assoc m k cv)
                       m))
                   {}
                   values)
          ;; fill in defaults for any param not explicitly set
          defaulted (reduce
                     (fn [m p]
                       (if (contains? m (:param/key p))
                         m
                         (assoc m (:param/key p) (:param/default p))))
                     clamped
                     (:descriptor/params descriptor))]
      {:instance/id id
       :instance/descriptor-id (:descriptor/id descriptor)
       :instance/values defaulted
       :instance/bypass? (boolean bypass?)
       :instance/wet-dry wet-dry
       :instance/latency-samples (:descriptor/latency-samples descriptor)})))

;; ---------------------------------------------------------------------
;; 2. Plugin chain -> comfyui workflow
;; ---------------------------------------------------------------------
;; A plugin chain (ordered vector of instances, matching
;; kami.ongaku.project's :bus/plugin-chain shape) becomes a linear
;; comfyui workflow: node "0" is the incoming signal, node "1".."n" are
;; the plugin instances in order, each :inputs :in wired to the previous
;; node's output. This is real comfyui API-format data (workflow.cljc's
;; validate/topo-sort/exec.cljc's cached executor operate on exactly
;; this shape) -- see comfyui-workflow-test for a validated example.
;;
;; The node :fn intentionally raises: executing real audio through this
;; graph requires a sample-buffer host capability (analogous to
;; std/host-fn-node in comfyui.std) that only a realtime/offline audio
;; host (out of scope here, `audio`'s or a future host's job) can supply.
;; This repo's job stops at "the graph is well-formed comfyui data and
;; validates/topo-sorts correctly," matching the brief's scope line.

(defn- node-id [i] (str i))

(defn instance->node
  "One plugin instance -> one comfyui API-format node map (not yet
  keyed into a workflow -- see plugin-chain->workflow)."
  [instance upstream-node-id]
  {:class_type (str "PluginHost/" (:instance/descriptor-id instance))
   :inputs {:in [upstream-node-id 0]
            :bypass (:instance/bypass? instance)
            :wet_dry (:instance/wet-dry instance)
            :values (:instance/values instance)}})

(defn plugin-chain->workflow
  "Build a comfyui API-format workflow (a map of node-id -> node) for an
  ordered plugin chain. Node \"0\" is a SignalIn source node with no
  inputs (the chain's input point); each subsequent node wires :in to
  the previous node's output 0, matching comfyui.workflow's expected
  `[upstream-id output-idx]` link shape."
  [plugin-chain]
  (when (and (vector? plugin-chain) (every? map? plugin-chain))
    (into {"0" {:class_type "PluginHost/SignalIn" :inputs {}}}
          (map-indexed
           (fn [i instance]
             [(node-id (inc i)) (instance->node instance (node-id i))])
           plugin-chain))))

(defn- unimplemented-fn [descriptor-id]
  (fn [_]
    (throw (ex-info
            (str "PluginHost node types describe hosting shape only; "
                 "executing " descriptor-id " requires a realtime/offline "
                 "sample-buffer host capability, out of scope for this repo")
            {:descriptor-id descriptor-id}))))

(defn descriptor->node-type
  "comfyui node-type map for one plugin descriptor (see comfyui.node's
  docstring for the shape). Registerable via comfyui.node/register!.
  `:fn` deliberately raises -- see unimplemented-fn."
  [descriptor]
  {:type (str "PluginHost/" (:descriptor/id descriptor))
   :category (str "ongaku/" (name (:descriptor/category descriptor)))
   :inputs {:in {:type "*"}
            :bypass {:type "BOOLEAN" :default false}
            :wet_dry {:type "FLOAT" :default 1.0}
            :values {:type "*" :optional true}}
   :outputs [{:name "out" :type "*"}]
   :fn (unimplemented-fn (:descriptor/id descriptor))})

(def signal-in-node-type
  {:type "PluginHost/SignalIn"
   :category "ongaku/io"
   :inputs {}
   :outputs [{:name "signal" :type "*"}]
   :fn (unimplemented-fn "SignalIn")})

(defn plugin-chain->registry
  "A comfyui.node/registry pre-populated with SignalIn plus one node
  type per distinct descriptor referenced by `plugin-chain`'s instances.
  `descriptors` is a coll of plugin-descriptor maps (the chain's
  instances only carry :instance/descriptor-id -- the registry needs
  the full descriptor to build accurate :inputs specs)."
  [descriptors]
  (graph/registry (into [signal-in-node-type] (map descriptor->node-type) descriptors)))

;; ---------------------------------------------------------------------
;; 3. Plugin delay compensation (PDC)
;; ---------------------------------------------------------------------
;; Given a set of parallel signal *paths* (each an ordered seq of plugin
;; instances / anything with :instance/latency-samples), each path's
;; total latency is the sum of its instances' latencies. All paths must
;; be delayed to match the slowest path so they stay time-aligned at
;; the point they remix -- the compensation for each path is
;; (max-path-latency - this-path-latency).

(defn path-latency
  "Total latency in samples for one ordered path of plugin instances."
  [path]
  (reduce + 0 (map :instance/latency-samples path)))

(defn compute-pdc
  "paths: a map of path-id -> ordered vector of plugin instances (or any
  seq of maps with :instance/latency-samples).

  Returns a map of path-id -> {:path/latency-samples N
  :path/compensation-samples C} where every path's
  (latency + compensation) equals the same value (the max latency across
  all paths) -- i.e. every path is delay-matched at the mix point."
  [paths]
  (when (and (map? paths) (seq paths))
    (let [latencies (reduce-kv (fn [m id path] (assoc m id (path-latency path))) {} paths)
          max-latency (apply max (vals latencies))]
      (reduce-kv
       (fn [m id lat]
         (assoc m id {:path/latency-samples lat
                      :path/compensation-samples (- max-latency lat)}))
       {}
       latencies))))

;; ---------------------------------------------------------------------
;; 4. Automation -> parameter resolution
;; ---------------------------------------------------------------------
;; Reuses kami-ongaku-project's automation-lane shape: a lane is
;; {:lane/target [id param-key] :lane/points [{:point/tick t :point/value v
;; :point/interp :linear|:hold|:curve} ...]} sorted by tick. We resolve
;; "what is the effective value of this plugin instance's parameter at
;; tick T" by interpolating between the surrounding points per the
;; segment's interpolation mode, then clamping against the descriptor's
;; param range (the instance/descriptor pair supplies the range; a lane
;; with no descriptor context just returns the raw interpolated value).

(defn automation-lane
  [{:keys [target points] :or {points []}}]
  (when (and (vector? target) (= 2 (count target))
             (string? (first target)) (keyword? (second target))
             (vector? points) (every? some? points)
             (apply <= (map :point/tick points)))
    {:lane/target target :lane/points points}))

(defn automation-point
  [{:keys [tick value interp] :or {interp :linear}}]
  (when (and (int? tick) (>= tick 0) (number? value)
             (contains? #{:linear :hold :curve} interp))
    {:point/tick tick :point/value value :point/interp interp}))

(defn- lerp [a b t] (+ a (* t (- b a))))

(defn resolve-automation-value
  "Effective value of `lane` at `tick`. Before the first point: that
  point's value. After the last point: that point's value (hold).
  Between two points p0 (<=tick) and p1 (>tick): if p0's interp is
  :hold, p0's value (step function, changes only at the next point).
  If :linear or :curve, linear-interpolate between p0/p1 (v0 treats
  :curve as linear -- a smoother spline is a later-wave refinement, same
  documented simplification kami-eizo-grade uses for curves)."
  [lane tick]
  (when-let [pts (seq (:lane/points lane))]
    (cond
      (<= tick (:point/tick (first pts))) (:point/value (first pts))
      (>= tick (:point/tick (last pts))) (:point/value (last pts))
      :else
      (let [pairs (partition 2 1 pts)
            [p0 p1] (some (fn [[a b]] (when (and (<= (:point/tick a) tick)
                                                  (<= tick (:point/tick b)))
                                        [a b]))
                          pairs)]
        (if (= :hold (:point/interp p0))
          (:point/value p0)
          (let [span (- (:point/tick p1) (:point/tick p0))
                t (if (zero? span) 0.0 (/ (- tick (:point/tick p0)) (double span)))]
            (lerp (:point/value p0) (:point/value p1) t)))))))

(defn resolve-and-clamp
  "resolve-automation-value, then clamp against `descriptor`'s param
  range for the lane's target param-key (target is [instance-id param-key])."
  [lane descriptor tick]
  (let [[_ param-key] (:lane/target lane)
        raw (resolve-automation-value lane tick)]
    (clamp-param-value descriptor param-key raw)))
