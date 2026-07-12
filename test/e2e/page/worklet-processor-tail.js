// Hand-written registerProcessor tail, appended after the compiled
// pdc-dsp bundle (see scripts/build-e2e-bundles.sh). Deliberately plain
// native JS class-extends syntax (real `super()` semantics), same reason
// org-w3-webaudio's worklet-processor-tail.js gives: extending a native
// built-in like AudioWorkletProcessor from cljs is not a solved idiom,
// whereas this is a handful of lines and keeps registerProcessor's real
// `class` requirements unambiguous. All PDC math and all DSP (the delay
// line) still come from the compiled bundle's
// kami.ongaku.plugin_host.e2e.pdc_dsp.render_scenario (i.e. from this
// repo's own kami.ongaku.plugin-host/compute-pdc and kotoba-lang/audio's
// own audio.effects/delay-line) -- this file only streams the three
// precomputed channel buffers out through the realtime process() quantum
// callback.
class KamiPdcProofProcessor extends AudioWorkletProcessor {
  constructor(options) {
    super();
    const p = (options && options.processorOptions) || {};
    const result = kami.ongaku.plugin_host.e2e.pdc_dsp.render_scenario(
      p.n, p.impulseIdx, p.pathALatency);
    this.pathA = result.pathA;
    this.pathBUncompensated = result.pathBUncompensated;
    this.pathBCompensated = result.pathBCompensated;
    this.readIdx = 0;
  }
  process(_inputs, outputs) {
    const output = outputs[0];
    if (!output || output.length < 3) return this.readIdx < this.pathA.length;
    const n = output[0].length;
    for (let k = 0; k < n; k++) {
      const gi = this.readIdx + k;
      output[0][k] = gi < this.pathA.length ? this.pathA[gi] : 0;
      output[1][k] = gi < this.pathBUncompensated.length ? this.pathBUncompensated[gi] : 0;
      output[2][k] = gi < this.pathBCompensated.length ? this.pathBCompensated[gi] : 0;
    }
    this.readIdx += n;
    return this.readIdx < this.pathA.length;
  }
}
registerProcessor('kami-pdc-proof-processor', KamiPdcProofProcessor);
