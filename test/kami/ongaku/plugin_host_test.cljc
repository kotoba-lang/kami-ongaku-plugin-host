(ns kami.ongaku.plugin-host-test
  (:require [clojure.test :refer [deftest testing is]]
            [kami.ongaku.plugin-host.graph :as workflow]
            [kami.ongaku.plugin-host :as ph]))

;; -----------------------------------------------------------------
;; fixtures
;; -----------------------------------------------------------------

(def gain-descriptor
  (ph/plugin-descriptor
   {:id "gain" :name "Gain" :vendor "kami" :category :effect
    :latency-samples 0
    :params [(ph/plugin-param {:key :db :label "Gain" :min -60 :max 12 :default 0 :unit :db})]}))

(def eq-descriptor
  (ph/plugin-descriptor
   {:id "eq3" :name "3-Band EQ" :vendor "kami" :category :effect
    :latency-samples 64
    :params [(ph/plugin-param {:key :low :label "Low" :min -12 :max 12 :default 0 :unit :db})
             (ph/plugin-param {:key :mid :label "Mid" :min -12 :max 12 :default 0 :unit :db})
             (ph/plugin-param {:key :high :label "High" :min -12 :max 12 :default 0 :unit :db})]}))

(def compressor-descriptor
  (ph/plugin-descriptor
   {:id "comp" :name "Compressor" :vendor "kami" :category :effect
    :latency-samples 128
    :params [(ph/plugin-param {:key :threshold :label "Threshold" :min -60 :max 0 :default -18 :unit :db})
             (ph/plugin-param {:key :ratio :label "Ratio" :min 1 :max 20 :default 4 :unit :ratio})]}))

;; -----------------------------------------------------------------
;; 1. descriptor / instance contract
;; -----------------------------------------------------------------

(deftest plugin-descriptor-test
  (is (some? gain-descriptor))
  (is (nil? (ph/plugin-descriptor {:id "" :name "x" :vendor "v" :category :effect :params []})))
  (is (nil? (ph/plugin-descriptor {:id "x" :name "x" :vendor "v" :category :bogus :params []}))))

(deftest plugin-instance-range-clamp-test
  (testing "in-range value passes through"
    (let [inst (ph/plugin-instance gain-descriptor {:id "i1" :values {:db -6}})]
      (is (= -6 (get (:instance/values inst) :db)))))
  (testing "out-of-range value is clamped"
    (let [inst (ph/plugin-instance gain-descriptor {:id "i1" :values {:db 100}})]
      (is (= 12 (get (:instance/values inst) :db))))
    (let [inst (ph/plugin-instance gain-descriptor {:id "i1" :values {:db -1000}})]
      (is (= -60 (get (:instance/values inst) :db)))))
  (testing "missing param falls back to default"
    (let [inst (ph/plugin-instance gain-descriptor {:id "i1" :values {}})]
      (is (= 0 (get (:instance/values inst) :db)))))
  (testing "non-numeric value falls back to default before clamping"
    (let [inst (ph/plugin-instance gain-descriptor {:id "i1" :values {:db "loud"}})]
      (is (= 0 (get (:instance/values inst) :db)))))
  (testing "unknown param key is dropped, not stored"
    (let [inst (ph/plugin-instance gain-descriptor {:id "i1" :values {:nonexistent 5}})]
      (is (not (contains? (:instance/values inst) :nonexistent)))))
  (testing "instance carries descriptor's latency"
    (let [inst (ph/plugin-instance eq-descriptor {:id "i2"})]
      (is (= 64 (:instance/latency-samples inst))))))

;; -----------------------------------------------------------------
;; 2. plugin chain -> comfyui workflow (real validation, not just shape)
;; -----------------------------------------------------------------

(deftest plugin-chain->workflow-validates-test
  (let [gain (ph/plugin-instance gain-descriptor {:id "g1" :values {:db -3}})
        eq (ph/plugin-instance eq-descriptor {:id "e1" :values {:low 2}})
        comp (ph/plugin-instance compressor-descriptor {:id "c1"})
        chain [gain eq comp]
        wf (ph/plugin-chain->workflow chain)
        reg (ph/plugin-chain->registry [gain-descriptor eq-descriptor compressor-descriptor])]
    (testing "workflow has one SignalIn + one node per instance"
      (is (= 4 (count wf))))
    (testing "topo-sorts without throwing (linear chain, no cycle)"
      (is (= ["0" "1" "2" "3"] (workflow/topo-sort wf))))
    (testing "validates clean against a registry built from the descriptors"
      (let [result (workflow/validate reg wf)]
        (is (true? (:valid? result)) (pr-str (:errors result)))))
    (testing "links reference the correct upstream node"
      (is (= ["0" 0] (get-in wf ["1" :inputs :in])))
      (is (= ["1" 0] (get-in wf ["2" :inputs :in])))
      (is (= ["2" 0] (get-in wf ["3" :inputs :in]))))))

(deftest plugin-chain->workflow-detects-missing-node-type-test
  (let [gain (ph/plugin-instance gain-descriptor {:id "g1"})
        wf (ph/plugin-chain->workflow [gain])
        ;; registry deliberately missing the gain node type
        reg (ph/plugin-chain->registry [])]
    (is (false? (:valid? (workflow/validate reg wf))))))

;; -----------------------------------------------------------------
;; 3. plugin delay compensation
;; -----------------------------------------------------------------

(deftest pdc-two-paths-test
  ;; path A: gain (0) -> eq (64)  = 64 total
  ;; path B: comp (128)           = 128 total
  ;; slowest path is B at 128, so A needs +64 compensation, B needs +0
  (let [gain (ph/plugin-instance gain-descriptor {:id "g1"})
        eq (ph/plugin-instance eq-descriptor {:id "e1"})
        comp (ph/plugin-instance compressor-descriptor {:id "c1"})
        pdc (ph/compute-pdc {:a [gain eq] :b [comp]})]
    (is (= 64 (get-in pdc [:a :path/latency-samples])))
    (is (= 128 (get-in pdc [:b :path/latency-samples])))
    (is (= 64 (get-in pdc [:a :path/compensation-samples])))
    (is (= 0 (get-in pdc [:b :path/compensation-samples])))
    (testing "every path is delay-matched at the mix point"
      (is (apply = (for [[_ {:keys [path/latency-samples path/compensation-samples]}] pdc]
                     (+ latency-samples compensation-samples)))))))

(deftest pdc-three-paths-test
  (let [comp (ph/plugin-instance compressor-descriptor {:id "c1"}) ; 128
        eq (ph/plugin-instance eq-descriptor {:id "e1"})           ; 64
        gain (ph/plugin-instance gain-descriptor {:id "g1"})       ; 0
        pdc (ph/compute-pdc {:slow [comp] :mid [eq] :fast [gain]})]
    (is (= 0 (get-in pdc [:slow :path/compensation-samples])))
    (is (= 64 (get-in pdc [:mid :path/compensation-samples])))
    (is (= 128 (get-in pdc [:fast :path/compensation-samples])))))

(deftest pdc-single-path-test
  (let [comp (ph/plugin-instance compressor-descriptor {:id "c1"})
        pdc (ph/compute-pdc {:only [comp]})]
    (is (= 0 (get-in pdc [:only :path/compensation-samples])))))

;; -----------------------------------------------------------------
;; 4. automation -> parameter resolution
;; -----------------------------------------------------------------

(deftest automation-resolution-linear-test
  (let [lane (ph/automation-lane
              {:target ["g1" :db]
               :points [(ph/automation-point {:tick 0 :value -12 :interp :linear})
                        (ph/automation-point {:tick 960 :value 0 :interp :linear})]})]
    (testing "exact breakpoints"
      (is (= -12 (ph/resolve-automation-value lane 0)))
      (is (= 0 (ph/resolve-automation-value lane 960))))
    (testing "midpoint interpolation"
      (is (= -6.0 (ph/resolve-automation-value lane 480))))
    (testing "quarter point"
      (is (= -9.0 (ph/resolve-automation-value lane 240))))
    (testing "before first / after last point holds edge value"
      (is (= -12 (ph/resolve-automation-value lane -100)))
      (is (= 0 (ph/resolve-automation-value lane 5000))))))

(deftest automation-resolution-hold-test
  (let [lane (ph/automation-lane
              {:target ["g1" :db]
               :points [(ph/automation-point {:tick 0 :value -12 :interp :hold})
                        (ph/automation-point {:tick 960 :value 0 :interp :hold})]})]
    (testing "hold segment stays at the earlier point's value until the next point"
      (is (= -12 (ph/resolve-automation-value lane 500)))
      (is (= -12 (ph/resolve-automation-value lane 959)))
      (is (= 0 (ph/resolve-automation-value lane 960))))))

(deftest resolve-and-clamp-test
  (let [lane (ph/automation-lane
              {:target ["g1" :db]
               :points [(ph/automation-point {:tick 0 :value -12})
                        (ph/automation-point {:tick 960 :value 100})]})] ; out of range at the end
    (testing "interpolated value beyond descriptor range is clamped"
      (is (= 12 (ph/resolve-and-clamp lane gain-descriptor 960))))
    (testing "in-range value passes through unclamped"
      (is (= -12 (ph/resolve-and-clamp lane gain-descriptor 0))))))

(deftest automation-lane-rejects-unsorted-points-test
  (is (nil? (ph/automation-lane
             {:target ["g1" :db]
              :points [(ph/automation-point {:tick 960 :value 0})
                       (ph/automation-point {:tick 0 :value -12})]}))))
