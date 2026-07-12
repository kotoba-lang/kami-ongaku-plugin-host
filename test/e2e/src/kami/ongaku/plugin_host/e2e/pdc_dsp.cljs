(ns kami.ongaku.plugin-host.e2e.pdc-dsp
  "E2E-only, worklet-side bundle for kami-ongaku-plugin-host's real-audio PDC
   (plugin delay compensation) proof (see README, 'Real-audio PDC proof').
   Requires this repo's own `kami.ongaku.plugin-host/compute-pdc` directly
   (not a reimplementation) plus `kotoba-lang/audio`'s own
   `audio.effects/delay-line` (also not a reimplementation -- configured
   feedback 0.0 / wet-dry 1.0, which `delay-line`'s own docstring says makes
   an impulse round-trip land on a precise sample, i.e. an exact N-sample
   pure delay). So the compensation number `compute-pdc` produces literally
   becomes the delay-line's `:delay-samples` parameter here -- not a
   hand-transcribed constant living only in a comment.

   Exposes one render-scenario entrypoint via ^:export (-> goog.exportSymbol,
   same mechanism org-w3-webaudio's worklet_dsp.cljs uses and documents --
   a manual goog.global property write is NOT DCE-safe, don't substitute
   it), callable from the hand-written AudioWorkletProcessor tail
   (test/e2e/page/worklet-processor-tail.js) at its munged path
   kami.ongaku.plugin_host.e2e.pdc_dsp.render_scenario."
  (:require [audio.effects :as effects]
            [kami.ongaku.plugin-host :as ph]))

(defn- impulse
  "-> vector of n doubles, all 0.0 except a single 1.0 at idx -- the
   'trigger' both paths receive at the same nominal start time."
  [n idx]
  (vec (map #(if (= % idx) 1.0 0.0) (range n))))

(defn ^:export render-scenario
  "n: buffer length (samples). impulse-idx: sample index of the impulse in
   the stimulus. path-a-latency: samples of REAL delay path A's plugin
   chain adds (simulating e.g. a look-ahead limiter's reported latency).

   Builds two PDC paths with kami.ongaku.plugin-host's real
   plugin-descriptor/plugin-instance/compute-pdc: path A carries one plugin
   instance whose :instance/latency-samples is path-a-latency, path B is an
   empty chain (0 latency). compute-pdc says: path A needs 0 extra
   compensation (it's already the slowest path), path B needs
   +path-a-latency compensation to stay time-aligned with A at the mix
   point.

   Renders three REAL buffers through audio.effects/delay-line (feedback
   0.0, wet-dry 1.0 -> exact N-sample pure delay, per delay-line's own
   docstring):
     pathA               - stimulus delayed by path-a-latency (the plugin's
                            own real, unavoidable processing latency)
     pathBUncompensated  - the raw stimulus, no delay at all (what path B
                            sounds like before any PDC is applied)
     pathBCompensated    - stimulus delayed by compute-pdc's own
                            :path/compensation-samples for path B

   -> js object {pathA pathBUncompensated pathBCompensated
                 pdcPathALatency pdcPathACompensation
                 pdcPathBLatency pdcPathBCompensation}."
  [n impulse-idx path-a-latency]
  (let [stimulus (impulse n impulse-idx)
        limiter (ph/plugin-descriptor
                 {:id "lookahead-limiter" :name "Look-ahead Limiter" :vendor "kami"
                  :category :effect :latency-samples path-a-latency
                  :params [(ph/plugin-param {:key :ceiling :label "Ceiling"
                                              :min -12 :max 0 :default 0 :unit :db})]})
        path-a-chain [(ph/plugin-instance limiter {:id "lim1"})]
        path-b-chain []
        pdc (ph/compute-pdc {:path-a path-a-chain :path-b path-b-chain})
        path-a-comp (get-in pdc [:path-a :path/compensation-samples])
        path-b-comp (get-in pdc [:path-b :path/compensation-samples])
        path-a-out (if (pos? path-a-latency)
                     (effects/delay-line stimulus {:delay-samples path-a-latency
                                                    :feedback 0.0 :wet-dry 1.0})
                     stimulus)
        path-b-uncompensated stimulus
        path-b-compensated (if (pos? path-b-comp)
                             (effects/delay-line stimulus {:delay-samples path-b-comp
                                                            :feedback 0.0 :wet-dry 1.0})
                             stimulus)]
    #js {:pathA (js/Float32Array.from (clj->js path-a-out))
         :pathBUncompensated (js/Float32Array.from (clj->js path-b-uncompensated))
         :pathBCompensated (js/Float32Array.from (clj->js path-b-compensated))
         :pdcPathALatency (get-in pdc [:path-a :path/latency-samples])
         :pdcPathACompensation path-a-comp
         :pdcPathBLatency (get-in pdc [:path-b :path/latency-samples])
         :pdcPathBCompensation path-b-comp}))
