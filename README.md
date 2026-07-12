# kami-ongaku-plugin-host

VST3/Audio-Unit-equivalent plugin **hosting** layer for the `ongaku`
(music production) engine stack defined in
[ADR-2607121400](https://github.com/com-junkawasaki/root/blob/main/90-docs/adr/2607121400-kami-ongaku-eizo-commercial-grade-cljs-stack.md)
(Wave 3). Sits between
[`kami-ongaku-project`](https://github.com/kotoba-lang/kami-ongaku-project)
(whose `:bus/plugin-chain` holds opaque `:plugin-ref/id` refs) and
[`audio`](https://github.com/kotoba-lang/audio) (which will eventually
supply the real DSP `:fn` per plugin descriptor). Portable `.cljc`.

This is the **hosting contract**, not DSP and not a full graph
execution *engine*. The graph shape here is compatible with
[`comfyui`](https://github.com/kotoba-lang/comfyui)'s node-registry /
workflow contract (`class_type`/`inputs`/`outputs`), but this repo has
**zero code dependency on comfyui**: comfyui is GPL-3.0 licensed, and a
hard `deps.edn` dependency on it would make this repo (meant to be
Apache-2.0, matching `kami-ongaku-notation`/`-project`/`-sampler`,
`kami-eizo-timeline`, `org-iso-h264`) a GPL derivative. An earlier
version of this repo did take that dependency by mistake; it's been
removed. The generic graph algorithms (registry, topo-sort, structural
`validate`) are now implemented natively in
`kami.ongaku.plugin-host.graph` — same behavior, zero comfyui code. A
consumer that has separately accepted comfyui's GPL terms can still
wire this repo's node-type maps into a live `comfyui.node/registry`
unchanged; only the algorithm implementation moved in-repo. This
mirrors how `kami-eizo-compositor` handles the same situation for video
compositing.

## Model

- **`plugin-descriptor`** — a plugin *type*: id, vendor, category
  (`:effect`/`:instrument`/`:analyzer`), a parameter list (each with
  range/default/unit), and a fixed `latency-samples` (PDC input).
- **`plugin-instance`** — references a descriptor, holds current
  parameter values (range-clamped against the descriptor, falling back
  to the default for out-of-range or non-numeric input), bypass flag,
  wet/dry mix. `:instance/id` matches
  `kami.ongaku.project/plugin-ref`'s `:plugin-ref/id`.
- **`plugin-chain->workflow`** — turns an ordered plugin chain into a
  comfyui-API-format-compatible workflow (node `"0"` = `SignalIn`, each
  subsequent node wired `:in` to the previous node's output). This
  workflow genuinely **topo-sorts and validates** via the native
  `kami.ongaku.plugin-host.graph` (registry/topo-sort/validate,
  functionally equivalent to comfyui's but with zero comfyui code
  dependency — see the module docstring for why) against a registry
  built by `plugin-chain->registry` — see the test suite. Node `:fn`s
  deliberately raise (`unimplemented-fn`): executing real audio through
  the graph needs a sample-buffer host capability, out of scope here —
  the graph's job stops at "well-formed, comfyui-compatible data."
- **`compute-pdc`** — plugin delay compensation. Given parallel signal
  paths (each an ordered seq of instances), computes the compensation
  delay every path needs so all paths are time-aligned at the mix
  point: `compensation = max-path-latency - this-path-latency`.
- **`resolve-automation-value`** / **`resolve-and-clamp`** — resolves an
  automation lane (same tick-keyed breakpoint shape as
  `kami-ongaku-project`'s automation lanes) to an effective parameter
  value at a given tick, honoring per-segment `:linear`/`:hold`
  interpolation (`:curve` is v0-simplified to linear, same documented
  simplification `kami-eizo-grade` uses), then clamps against the
  target plugin's descriptor range.

```clojure
(require '[kami.ongaku.plugin-host :as ph]
         '[kami.ongaku.plugin-host.graph :as workflow])

(def gain (ph/plugin-descriptor
           {:id "gain" :name "Gain" :vendor "kami" :category :effect
            :params [(ph/plugin-param {:key :db :label "Gain" :min -60 :max 12 :default 0})]}))

(def chain [(ph/plugin-instance gain {:id "g1" :values {:db -3}})])
(def wf (ph/plugin-chain->workflow chain))
(def reg (ph/plugin-chain->registry [gain]))

(workflow/topo-sort wf)          ;=> ["0" "1"]
(:valid? (workflow/validate reg wf)) ;=> true
```

## Not in v0

- No audio rendering / no plugin execution (node `:fn`s raise on call —
  needs a sample-buffer host capability, a later wave/host's job).
- No VST3/AU binary plugin loading — this hosts the *contract shape*,
  not real third-party plugin binaries.
- `:curve` automation interpolation is linear (documented simplification).

## Test

```bash
clojure -M:test   # 11 tests, 38 assertions
clojure -M:lint   # 0 errors, 0 warnings
```
