(ns utsushi.bench.engines
  "The engine roster, and what each engine's availability actually means.

  Modelled on `kotoba-lang/amu`'s `bench/runtime-comparison` and ADR 0226:
  a comparison is a set of ADAPTERS, and an adapter that cannot run is
  recorded BY NAME with a reason (ADR 0226's `skippedEngines`), never
  silently absent. A two-engine table implies the two engines are the whole
  question. They are not.

  ## Why the kotoba arms are here even though none of them runs

  `utsushi` and `org-iso-h264` are written in full Clojure on the JVM. That
  is today's working path and this bench's incumbent — it is the ORACLE
  that says what the right pixels are. It is not the destination.
  ADR-2607198300 (`kotoba の実行は最終的に JVM/Node/Rust を経由しない`) says
  the destination is an artifact that needs none of them.

  A `.cljc` extension does not make a file compilable by amu. amu's `.cljc`
  is the Kotoba GUEST GRAMMAR carrying a `.cljc` extension — a different,
  much narrower language than the Clojure these codecs are written in.
  Leaving the kotoba arms out of the table would let the JVM number read as
  the finish line. Naming them and reporting exactly where each one stops
  is the point.

  ## Four distances, not one 'not supported'

  Each engine records the furthest STAGE it reached. These are measured by
  running the tool, not asserted:

  | stage | meaning |
  |---|---|
  | `:cli` | the compiler CLI itself was not found |
  | `:source-read` | amu's reader rejected the source — it is not guest grammar |
  | `:check` | admitted by `amu check` (effects/exports resolved) |
  | `:compile` | a backend accepted or rejected the admitted program |
  | `:execute` | an artifact ran and produced pixels |

  Collapsing these hides where the work actually stops. Measured
  2026-08-29 on this workspace, all four are distinct and all four are real:
  `h264/decode.cljc` stops at `:source-read` (exit 65,
  `:kotoba/source-read-failed`); `h264/expgolomb.kotoba` passes `:check`
  (exit 0) and then stops at `:compile` differently per backend — aarch64
  exit 70 `:kotoba/target-rejected`, wasm32 exit 70
  `:kotoba/internal-error`."
  (:require [clojure.java.io :as io]
            [kotoba.lang.text :as str]
            [clojure.edn :as edn]
            [clojure.java.shell :as sh]))

(def stage-ladder
  "Ordered. `stage-index` is used to report which engine got furthest."
  [:cli :source-read :check :compile :execute])

(defn stage-index [s] (.indexOf ^java.util.List stage-ladder s))

;; ── locating the kotoba toolchain ────────────────────────────────────────
;;
;; amu and org-iso-h264 are west siblings of this repo, not deps of it, so
;; there is no path that is correct in every checkout. Env var first, then
;; the sibling layout, then PATH — and when none of them resolves, that is
;; reported as `:cli`/`:source` unavailability with the paths that were
;; tried, which is a usable answer. Guessing a path and reporting its
;; failure as the compiler's failure would not be.

(def ^:private amu-candidates
  ["../amu/bin/amu" "../../amu/bin/amu" "../kotoba-lang/amu/bin/amu"])

(defn find-amu []
  (or (when-let [e (System/getenv "AMU_BIN")] (when (.isFile (io/file e)) e))
      (first (filter #(.isFile (io/file %)) amu-candidates))
      (let [{:keys [exit out]} (sh/sh "sh" "-c" "command -v amu")]
        (when (zero? exit) (str/trim out)))))

(def ^:private h264-candidates
  ["../org-iso-h264/src/h264" "../../org-iso-h264/src/h264"
   "../kotoba-lang/org-iso-h264/src/h264"])

(defn find-h264-src []
  (or (when-let [e (System/getenv "H264_SRC")] (when (.isDirectory (io/file e)) e))
      (first (filter #(.isDirectory (io/file %)) h264-candidates))))

(defn parse-amu-output
  "Pull amu's EDN result map out of its stdout.

  amu prints ONE EDN map per invocation, but not with a stable first key:
  a failure leads with `:format`, a successful `check` leads with `:ok`.
  An earlier version of this parser matched on `{:format :kotoba` and
  therefore read every SUCCESS as an unrecognised failure — which is the
  exact shape this whole bench is built to catch, committed by the bench
  itself. It reported the kotoba frontier as `:source-read` when the real
  answer was two rungs further up.

  So: read the first line that starts a map, and say whether it parsed.
  `:parsed? false` is deliberately not the same value as a parsed failure."
  [text]
  (let [line (first (filter #(str/starts-with? (str/trim %) "{") (str/split-lines text)))]
    (if-not line
      {:parsed? false}
      (try {:parsed? true :value (edn/read-string (str/trim line))}
           (catch Exception e {:parsed? false :parse-error (.getMessage e)})))))

(defn- run-amu
  "Run one amu invocation and keep its exit code AND its diagnostic body.

  The body is where the answer is — `:kotoba/target-rejected` and
  `:kotoba/internal-error` are both exit 70 and mean entirely different
  things. Recording only the status would throw that away.

  `:ok?` is the EXIT CODE, not the parsed `:ok` field, because a parser
  that fails must not be able to turn a success into a failure (see
  `parse-amu-output`). The parsed field is reported separately so a
  disagreement between the two is visible rather than resolved silently."
  [amu args timeout-ms]
  (let [pb (ProcessBuilder. ^java.util.List (into [amu] args))
        _ (.redirectErrorStream pb true)
        proc (.start pb)
        out (future (slurp (.getInputStream proc)))
        finished? (.waitFor proc timeout-ms java.util.concurrent.TimeUnit/MILLISECONDS)]
    (if-not finished?
      (do (.destroyForcibly proc)
          {:status :timeout :ok? false :timeout-ms timeout-ms
           :command (str/join " " (into [amu] args))})
      (let [text @out
            exit (.exitValue proc)
            parsed (parse-amu-output text)
            diag (:value parsed)]
        {:status :ran
         :exit exit
         :ok? (zero? exit)
         :command (str/join " " (into [amu] args))
         :parsed? (:parsed? parsed)
         :reported-ok (:ok diag)
         :code (get-in diag [:diagnostic :code])
         :message (:message diag)}))))

(def ^:private shared-probe
  "The decoder source-read and guest `check` probes do not depend on the
  backend, and each amu invocation is a JVM launch. Memoized so probing
  three backends costs three compiles plus two checks, not nine."
  (memoize
   (fn [amu src timeout-ms]
     (let [decoder (io/file src "decode.cljc")
           guest (io/file src "expgolomb.kotoba")]
       {:decoder-file decoder
        :guest-file guest
        :dec-read (when (.isFile decoder)
                    (run-amu amu ["check" (.getPath decoder)] timeout-ms))
        :guest-check (when (.isFile guest)
                       (run-amu amu ["check" (.getPath guest)] timeout-ms))}))))

(defn probe-kotoba-backend
  "How far does the kotoba toolchain get toward decoding H.264, for one
  backend target?

  Runs, in order: the decoder source through amu's reader (the thing that
  would actually have to compile), then the one h264 namespace that IS
  written in guest grammar through `check` and then through this backend.
  The furthest stage anything reached is reported alongside the stage the
  DECODER reached, because those are different numbers and only the second
  one is the distance to a kotoba video decoder."
  [{:keys [target timeout-ms] :or {timeout-ms 300000}}]
  (let [amu (find-amu)
        src (find-h264-src)]
    (cond
      (nil? amu)
      {:status :unavailable :stage :cli
       :reason (str "amu CLI not found (AMU_BIN unset; tried "
                    (str/join ", " amu-candidates) "; not on PATH)")}

      (nil? src)
      {:status :unavailable :stage :cli
       :reason (str "org-iso-h264 source not found (H264_SRC unset; tried "
                    (str/join ", " h264-candidates) ")")}

      :else
      (let [{:keys [decoder-file guest-file dec-read guest-check]}
            (shared-probe amu src timeout-ms)
            decoder decoder-file
            guest guest-file
            guest-compile (when (and guest-check (:ok? guest-check))
                            (run-amu amu ["compile" (.getPath guest)
                                          "--target" (name target)
                                          "-o" (str (java.io.File/createTempFile
                                                     "utsushi-bench-kotoba" ".out"))]
                                     timeout-ms))
            decoder-stage (cond
                            (nil? dec-read) :cli
                            (:ok? dec-read) :check
                            :else :source-read)
            frontier (cond
                       (and guest-compile (:ok? guest-compile)) :execute
                       guest-compile :compile
                       (and guest-check (:ok? guest-check)) :check
                       :else :source-read)]
        {:status :unavailable
         :stage decoder-stage
         :frontier-stage frontier
         :reason
         (str "no H.264 decoder exists in Kotoba guest grammar. "
              "The decoder (" (.getPath decoder) ") stops at " (name decoder-stage)
              (when (and dec-read (not (:ok? dec-read)))
                (str ": exit " (:exit dec-read) " " (:code dec-read)
                     " \"" (:message dec-read) "\""))
              ". The furthest any h264 namespace reaches is " (name frontier)
              (when guest-compile
                (str ": " (.getName guest) " --target " (name target)
                     " exit " (:exit guest-compile) " " (:code guest-compile)
                     " \"" (:message guest-compile) "\"")))
         :evidence {:decoder-source-read dec-read
                    :guest-check guest-check
                    :guest-compile guest-compile
                    :guest-grammar-namespaces
                    (when src (vec (sort (map #(.getName ^java.io.File %)
                                              (filter #(str/ends-with? (.getName ^java.io.File %) ".kotoba")
                                                      (.listFiles (io/file src)))))))
                    :total-namespaces
                    (when src (count (filter #(str/ends-with? (.getName ^java.io.File %) ".cljc")
                                             (.listFiles (io/file src)))))}}))))

(def kotoba-backends
  "One slot per amu backend, named whether or not it runs."
  [{:engine/id :kotoba-wasm32
    :engine/role :target
    :engine/target :wasm32
    :engine/note "ADR-2607198300's destination: an artifact needing no JVM/Node/Rust"}
   {:engine/id :kotoba-native-aarch64
    :engine/role :target
    :engine/target :aarch64
    :engine/note "kotoba-native AOT — machine code emitted from cljc"}
   {:engine/id :kotoba-script-js
    :engine/role :target
    :engine/target :js
    :engine/note "restricted-ESM emitter (kotoba-script) — still a JS host, so not the endpoint"}])
