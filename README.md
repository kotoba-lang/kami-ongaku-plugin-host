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
  `test/e2e/` (below) adds a real-browser *proof* that `compute-pdc`'s
  output does genuine work when fed into a real delay DSP, but it is a
  narrow test harness, not this repo taking on plugin-execution
  responsibility — the graph's node `:fn`s still raise, unchanged.
- No VST3/AU binary plugin loading — this hosts the *contract shape*,
  not real third-party plugin binaries.
- `:curve` automation interpolation is linear (documented simplification).

## Real-audio PDC proof (`test/e2e/`)

**This is a test/proof harness, not a production render pipeline.** Every
existing test in `test/kami/ongaku/plugin_host_test.cljk` (including
`compute-pdc`'s own tests) checks the PDC math against hand-picked
synthetic latency integers — never real audio. This E2E closes that
specific gap: it proves `compute-pdc`'s compensation number, fed back
through a real delay DSP, actually keeps two real audio signals
time-aligned — not just that the arithmetic is correct on paper.

It builds directly on `kotoba-lang/org-w3-webaudio`'s own real-browser
AudioWorklet DSP E2E proof (`org-w3-webaudio` `test/e2e/`, commit
`e554d853d640`) — same recipe (`:optimizations advanced` +
`self-polyfill.js`, both required to load a Closure-compiled bundle inside
`AudioWorkletGlobalScope`; see that repo's README for the full derivation,
not repeated here), same nbb+Playwright harness, same local HTTP server
(`audioWorklet` needs a secure context; `about:blank`/`file:` don't expose
it), same real headless Chromium.

**Scenario:** two parallel signal paths both receive the same impulse
trigger at the same nominal start time. Path A's plugin chain is one
instance (a look-ahead-limiter stand-in, built with this repo's own
`plugin-descriptor`/`plugin-instance`) reporting 200 samples of real
latency; path B's chain is empty (0 latency). This repo's own
`compute-pdc` — called for real, not hand-simulated — says path A needs 0
extra compensation (it's already the slowest path) and path B needs +200
samples to stay aligned at the mix point. Both paths' actual delay is
then realized via `kotoba-lang/audio`'s own `audio.effects/delay-line`
(feedback `0.0`, wet-dry `1.0` → an exact N-sample pure delay, per that
function's own docstring): 200 samples for path A always, and either 0
(uncompensated) or `compute-pdc`'s own computed 200 (compensated) for path
B.

`test/e2e/src/kami/ongaku/plugin_host/e2e/pdc_dsp.cljk` (worklet-side
bundle) requires this repo's own `kami.ongaku.plugin-host` (`compute-pdc`,
`plugin-descriptor`, `plugin-instance`) and `kotoba-lang/audio`'s own
`audio.effects` directly — not reimplementations — and exports a
`render-scenario` entrypoint that a hand-written `AudioWorkletProcessor`
subclass (`test/e2e/page/worklet-processor-tail.js`, real ES6
`class ... extends`, native `super()` — extending a native built-in from
cljs isn't a solved idiom, same reasoning org-w3-webaudio's tail gives)
calls once in its constructor, streaming all three rendered channels
(pathA / pathB-uncompensated / pathB-compensated) out through the
realtime `process()` quantum callback. A main-thread bundle
(`test/e2e/src/kami/ongaku/plugin_host/e2e/main_driver.cljk`) uses
`org-w3-webaudio`'s own binding layer (`new-offline-audio-context!` with 3
channels, `add-worklet-module!`, `create-worklet-node!`, `connect!`,
`start-rendering!`) to load the worklet module into a real headless
Chromium and capture the actual rendered 3-channel PCM.

`test/e2e/run_e2e.cljk` (nbb) independently recomputes the identical
scenario (same `compute-pdc` + `audio.effects/delay-line` source, no
browser involved) as ground truth, diffs it against the browser-captured
PCM, and then — the actual proof — measures the real sample offset
between path A and path B *on the captured PCM itself*, both by
peak-position comparison (exact for a clean impulse response) and by
discrete cross-correlation (the general technique, included so this isn't
just "read off the one impulse sample" — both must agree), before and
after compensation.

Real measured result (Chromium, Playwright-bundled, run 2026-07-13):

```
=== kami-ongaku-plugin-host real-audio PDC proof result ===
compute-pdc (real kami.ongaku.plugin-host/compute-pdc, not synthetic-only):
  path-a: latency= 200 compensation= 0
  path-b: latency= 0 compensation= 200
captured length: 1024 reference length: 1024
max abs diff vs independent offline reference -- pathA: 0 pathB-uncompensated: 0 pathB-compensated: 0 tolerance: 0.000001
--- measured sample offset (path A vs path B) on REAL captured PCM ---
BEFORE compensation -- peak-position: 200 cross-correlation: 200 (expected ~ 200 )
AFTER  compensation -- peak-position: 0 cross-correlation: 0 (expected ~0)
PASS: true
```

Before PDC is applied, path A's real DSP output measurably arrives 200
samples later than path B's, on real captured PCM — both measurement
methods agree exactly. After applying `compute-pdc`'s own computed
compensation (200 samples) to path B through the same real delay-line
DSP, the measured offset collapses to exactly 0 — again, both methods
agree. This is the real proof: **PDC's output is not just a number that
happens to equal a latency figure — routed back through the same
production DSP path, it genuinely eliminates a real, measurable
misalignment.**

Setup and run:

```bash
bash scripts/build-e2e-bundles.sh          # compiles both bundles with
                                            # :optimizations advanced (JVM
                                            # Clojure CLI build step, not an
                                            # app-runtime choice)
npm --prefix test/e2e install              # Playwright
npx --prefix test/e2e playwright install chromium
AUDIO_SRC_PATH=/path/to/kotoba-lang/audio/src
kbb --backend sci -cp "src:$AUDIO_SRC_PATH" test/e2e/run_e2e.cljk
```

Exits 0 and prints the PDC numbers, the offline cross-verification diffs,
and both offset measurements on pass; exits 1 on any real failure (browser
setup error, browser-vs-offline mismatch beyond tolerance, offset not
collapsing after compensation) — no silent degradation. The `:e2e`
deps.edn alias takes `kotoba-lang/audio` and `kotoba-lang/org-w3-webaudio`
as real git dependencies (pinned by commit SHA); `test/e2e/page/*-bundle.js`,
`test/e2e/page/worklet-processor.js`, and `test/e2e/node_modules/` are
build artifacts, gitignored.

## Test

```bash
kbb -M:test   # 11 tests, 38 assertions
kbb -M:lint   # 0 errors, 0 warnings
```
