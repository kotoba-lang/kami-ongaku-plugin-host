(ns kami.ongaku.plugin-host.e2e.main-driver
  "E2E-only, main-thread bundle for kami-ongaku-plugin-host's real-audio PDC
   proof. Uses kotoba-lang/org-w3-webaudio's own src/w3/webaudio.cljs binding
   layer (not raw AudioContext calls) to set up a 3-channel
   OfflineAudioContext, load the worklet-side PDC-DSP bundle
   (test/e2e/page/worklet-processor.js) via audioWorklet.addModule, create
   the AudioWorkletNode, render, and hand back the rendered PCM (channel 0 =
   pathA, channel 1 = pathBUncompensated, channel 2 = pathBCompensated) for
   comparison (by test/e2e/run_e2e.cljs) against an independent offline
   recomputation of the same scenario.

   Compiled the same way as pdc_dsp.cljs (:optimizations advanced,
   self-polyfill prepended, ^:export not a manual goog.global write) for the
   same reasons org-w3-webaudio's main_driver.cljs documents."
  (:require [w3.webaudio :as w3a]))

(defn ^:export run-e2e [params]
  (let [{:keys [n impulseIdx pathALatency sr workletUrl processorName]}
        (js->clj params :keywordize-keys true)
        ctx (w3a/new-offline-audio-context! 3 n sr)]
    (-> (w3a/add-worklet-module! ctx workletUrl)
        (.then
          (fn [_]
            (let [node (w3a/create-worklet-node!
                        ctx processorName
                        #js {:numberOfInputs 0
                             :numberOfOutputs 1
                             :outputChannelCount #js [3]
                             :processorOptions
                             #js {:n n :impulseIdx impulseIdx :pathALatency pathALatency}})]
              (w3a/connect! node (w3a/destination ctx))
              (w3a/start-rendering! ctx))))
        (.then
          (fn [audio-buffer]
            #js {:pathA (js/Array.from (.getChannelData audio-buffer 0))
                 :pathBUncompensated (js/Array.from (.getChannelData audio-buffer 1))
                 :pathBCompensated (js/Array.from (.getChannelData audio-buffer 2))
                 :length (.-length audio-buffer)
                 :sampleRate (w3a/sample-rate ctx)})))))
