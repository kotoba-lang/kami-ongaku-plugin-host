#!/usr/bin/env bash
# Compiles the two bundles needed for the kami-ongaku-plugin-host real-audio
# PDC (plugin delay compensation) E2E (test/e2e/run_e2e.cljk):
#
#   1. test/e2e/src/kami/ongaku/plugin_host/e2e/main_driver.cljk -> main-thread
#      bundle (page/main-driver-bundle.js). Uses org-w3-webaudio's own
#      src/w3/webaudio.cljs binding layer to drive
#      OfflineAudioContext/AudioWorkletNode from the page.
#   2. test/e2e/src/kami/ongaku/plugin_host/e2e/pdc_dsp.cljk -> worklet-side
#      bundle (page/worklet-processor.js). Requires this repo's own
#      kami.ongaku.plugin-host (compute-pdc) and kotoba-lang/audio's own
#      audio.effects (delay-line) directly, and exports a render-scenario
#      entrypoint consumed by the hand-written AudioWorkletProcessor
#      registration in page/worklet-processor-tail.js.
#
# Both MUST compile with --optimizations advanced, and both need the
# self-polyfill prepended -- this is the exact recipe kotoba-lang/org-w3-webaudio
# derived and documented in full (README "Blocker resolved (Wave 6)" section
# and this script's sibling in that repo): cljs.main's default (non-:advanced)
# build bundles clojure.browser.repl's dev REPL-connect bootstrap, which
# references `document` (absent in AudioWorkletGlobalScope) at module
# top-level and throws silently (no rejected promise, no console/pageerror
# event) before registerProcessor ever runs; :advanced's whole-program DCE
# proves that call unreachable and removes it. Separately, ^:export (needed
# so the hand-written worklet tail can call into the compiled bundle) makes
# Closure's goog.global detection reference bare `self`, which
# AudioWorkletGlobalScope (unlike WorkerGlobalScope) does not define --
# fixed by prepending self-polyfill.js. Not re-derived here; see
# org-w3-webaudio's README/script for the full empirical derivation, this is
# the same AudioWorkletGlobalScope + same Closure Library + same fix.
#
# Requires the Clojure CLI (JVM) -- the ClojureScript compiler itself runs on
# the JVM; this is a BUILD-time tool only, not an app-runtime choice.
set -euo pipefail
cd "$(dirname "$0")/.."

rm -rf test/e2e/.build-main test/e2e/.build-worklet
mkdir -p test/e2e/page

echo "compiling main-thread driver bundle (kami.ongaku.plugin-host.e2e.main-driver)..."
clojure -M:e2e -m cljs.main -d test/e2e/.build-main \
  --optimizations advanced \
  --output-to test/e2e/page/main-driver-bundle.raw.js \
  -c kami.ongaku.plugin-host.e2e.main-driver
cat test/e2e/page/self-polyfill.js test/e2e/page/main-driver-bundle.raw.js \
    > test/e2e/page/main-driver-bundle.js
rm -f test/e2e/page/main-driver-bundle.raw.js

echo "compiling worklet PDC-DSP bundle (kami.ongaku.plugin-host.e2e.pdc-dsp)..."
clojure -M:e2e -m cljs.main -d test/e2e/.build-worklet \
  --optimizations advanced \
  --output-to test/e2e/page/worklet-dsp-bundle.raw.js \
  -c kami.ongaku.plugin-host.e2e.pdc-dsp
cat test/e2e/page/self-polyfill.js \
    test/e2e/page/worklet-dsp-bundle.raw.js \
    test/e2e/page/worklet-processor-tail.js \
    > test/e2e/page/worklet-processor.js
rm -f test/e2e/page/worklet-dsp-bundle.raw.js

echo "wrote test/e2e/page/main-driver-bundle.js and test/e2e/page/worklet-processor.js"
