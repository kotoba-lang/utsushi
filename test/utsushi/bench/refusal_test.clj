(ns utsushi.bench.refusal-test
  "Tests for the benchmark's REFUSALS, not for its numbers.

   A benchmark harness's most dangerous failure is the one where it could
   not measure and said nothing — an empty table under a green exit code.
   Every assertion here is about that: a probe that did not run must not
   report what a probe that ran would have, and zero must never render as
   clean.

   These are in the ordinary test suite (`clojure -M:test`) rather than
   behind the `:bench` alias, so the refusal logic is checked on every run
   even though the timing is not."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [utsushi.bench.ffmpeg-comparison :as bench]
            [utsushi.bench.engines :as engines]))

(deftest exit-codes-are-three-distinct-values
  (testing "'could not measure' is neither 0 nor 1 — collapsing it into
            either is the failure this harness exists to avoid"
    (is (= 0 bench/exit-measured))
    (is (= 1 bench/exit-mismatch))
    (is (not (contains? #{0 1} bench/exit-could-not-measure)))))

(deftest absent-ffmpeg-is-recorded-not-assumed
  (testing "no binary produces an :unavailable provenance carrying a reason,
            never an empty map that reads like a measurement"
    (let [p (bench/ffmpeg-provenance nil)]
      (is (= :unavailable (:status p)))
      (is (string? (:reason p)))
      (is (seq (:reason p)))
      (is (nil? (:version p))))))

(deftest a-slope-that-did-not-resolve-is-not-a-fast-engine
  (testing "a non-positive mean slope means the added frames cost less than
            the invocation's own jitter — the measurement did not reach the
            quantity, and dividing it into anything invents a ratio"
    (is (false? (bench/resolved? [-0.4 -0.2 0.1])))
    (is (false? (bench/resolved? [0.0 0.0])))
    (is (true? (bench/resolved? [7.5 7.7 7.4])))))

(deftest refused-report-renders-as-refused-with-its-zeroes-visible
  (testing "the ffmpeg-unavailable report says REFUSED, names the reason, and
            shows a zero fixture count rather than an empty table"
    (let [report {:verdict :refused
                  :reason :ffmpeg-unavailable
                  :host-summary {:machine-id "x" :provenance :measured
                                 :fingerprint 1 :cores 2 :source "sysctl"}
                  :ffmpeg {:status :unavailable :reason "no ffmpeg on PATH"}
                  :engines [{:engine/id :ffmpeg :engine/role :reference
                             :status :unavailable :stage :cli
                             :reason "no ffmpeg on PATH"}]
                  :evidence {:engines-in-roster 1 :engines-measurable 0
                             :fixtures-attempted 3 :fixtures-compared 0
                             :samples-taken 0 :samples-per-arm 5
                             :mismatches 0 :errors 0}
                  :results [] :verdicts []
                  :note "ffmpeg is the reference"}
          text (str/join "\n" (bench/render-lines report))]
      (is (str/includes? text "VERDICT   refused"))
      (is (str/includes? text "ffmpeg-unavailable"))
      (is (str/includes? text "fixtures compared 0 of 3"))
      (is (str/includes? text "timed samples taken 0"))
      (is (str/includes? text "no ffmpeg on PATH"))
      (is (not (str/includes? text "measured\n")) "must not read as a clean run"))))

(deftest every-engine-in-the-roster-is-rendered-even-when-it-cannot-run
  (testing "ADR 0226's skippedEngines discipline: an adapter that cannot run
            is named with a reason, never silently absent — otherwise the
            table implies the two engines that DO run are the whole question"
    (let [roster (bench/probe-engines
                  {:ffmpeg-provenance {:status :unavailable :reason "absent"}
                   :skip-kotoba-probe? true})
          report {:verdict :refused :reason :ffmpeg-unavailable
                  :host-summary {} :ffmpeg {:status :unavailable :reason "absent"}
                  :engines roster
                  :evidence {:engines-in-roster (count roster) :engines-measurable 0
                             :fixtures-attempted 0 :fixtures-compared 0
                             :samples-taken 0 :samples-per-arm 5
                             :mismatches 0 :errors 0}
                  :results [] :verdicts []}
          text (str/join "\n" (bench/render-lines report))]
      (testing "the roster carries the incumbent AND every kotoba backend"
        (is (= (into #{:ffmpeg :utsushi-jvm} (map :engine/id engines/kotoba-backends))
               (set (map :engine/id roster)))))
      (testing "the JVM arm is labelled the incumbent oracle, not the destination"
        (is (= :incumbent-oracle
               (:engine/role (first (filter #(= :utsushi-jvm (:engine/id %)) roster))))))
      (doseq [id (map :engine/id engines/kotoba-backends)]
        (is (str/includes? text (name id))
            (str (name id) " must appear in the report even though it cannot run"))))))

(deftest a-probe-that-did-not-run-is-not-a-probe-that-found-nothing
  (testing ":not-probed and :unavailable are different values; --skip-kotoba-probe
            must not be able to masquerade as a measured absence"
    (let [skipped (bench/probe-engines {:ffmpeg-provenance {:status :measured}
                                        :skip-kotoba-probe? true})
          kotoba (filter #(str/starts-with? (name (:engine/id %)) "kotoba") skipped)]
      (is (seq kotoba))
      (is (every? #(= :not-probed (:status %)) kotoba))
      (is (every? #(str/includes? (:reason %) "NOT a measurement") kotoba)))))

(deftest kotoba-probe-reports-a-stage-when-the-toolchain-is-missing
  (testing "an unfindable amu is :cli-stage unavailability naming the paths
            tried — not a claim about the compiler's capabilities"
    (with-redefs [engines/find-amu (constantly nil)]
      (let [r (engines/probe-kotoba-backend {:target :wasm32})]
        (is (= :unavailable (:status r)))
        (is (= :cli (:stage r)))
        (is (str/includes? (:reason r) "amu CLI not found")))))
  (testing "an unfindable source tree is likewise reported as :cli, not as a
            compiler verdict about code nobody read"
    (with-redefs [engines/find-amu (constantly "/nonexistent/amu")
                  engines/find-h264-src (constantly nil)]
      (let [r (engines/probe-kotoba-backend {:target :aarch64})]
        (is (= :unavailable (:status r)))
        (is (str/includes? (:reason r) "org-iso-h264 source not found"))))))

(deftest the-stage-ladder-is-ordered-and-distinct
  (testing "four different distances must stay four different values"
    (is (= 5 (count (set engines/stage-ladder))))
    (is (< (engines/stage-index :source-read) (engines/stage-index :check)))
    (is (< (engines/stage-index :check) (engines/stage-index :compile)))
    (is (< (engines/stage-index :compile) (engines/stage-index :execute)))))
