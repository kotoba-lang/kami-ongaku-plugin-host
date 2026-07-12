// AudioWorkletGlobalScope has no `self` (WorkletGlobalScope, unlike
// WorkerGlobalScope, does not define it), but Closure's goog.global
// detection (`goog.global = this || self`, in the Closure Library base
// bundled by cljs.main) unconditionally references bare `self` whenever
// anything in the build needs goog.global -- which this bundle's ^:export
// does. Referencing an undeclared bare identifier throws ReferenceError;
// `typeof self` is the safe form of the same check. Identical fix and
// identical root cause to kotoba-lang/org-w3-webaudio's E2E (see that
// repo's test/e2e/page/self-polyfill.js and scripts/build-e2e-bundles.sh
// for the full derivation -- not re-derived here, this is the same
// AudioWorkletGlobalScope, same Closure Library, same fix). Must be
// textually BEFORE the compiled bundle in the same worklet module file.
if (typeof self === "undefined") { globalThis.self = globalThis; }
