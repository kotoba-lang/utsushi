(ns json.core
  "Pure JSON encode/decode plus a pretty emitter used by kotoba DSLs."
  (:refer-clojure :exclude [read])
  (:require [clojure.string :as str]))

(def ^:private hex-digits "0123456789abcdef")

(defn- hex4
  "4-digit hex for a JSON `\\uXXXX` escape (portable: bit ops + a lookup
  table, no Long/Integer interop that would only work on :clj)."
  [n]
  (apply str (for [shift [12 8 4 0]] (nth hex-digits (bit-and (bit-shift-right n shift) 0xf)))))

(defn- char-code [ch]
  #?(:clj (int ch) :cljs (.charCodeAt ch 0)))

(defn- esc [s]
  (apply str
         (map (fn [ch]
                (case ch
                  \" "\\\""
                  \\ "\\\\"
                  \backspace "\\b"
                  \formfeed "\\f"
                  \newline "\\n"
                  \return "\\r"
                  \tab "\\t"
                  ;; RFC 8259 §7: EVERY control character U+0000-U+001F must
                  ;; be escaped, not just the 7 named above -- the fallback
                  ;; case used to pass the rest through raw, producing a
                  ;; JSON string literal with a literal control byte
                  ;; embedded in it (invalid per both python's json and jq).
                  (if (< (char-code ch) 0x20)
                    (str "\\u" (hex4 (char-code ch)))
                    (str ch))))
              (str s))))

(defn- kstr [k] (if (keyword? k) (name k) (str k)))

(defn- numstr [n]
  #?(:clj
     (if (and (float? n) (== (double n) (Math/rint (double n))))
       (str (long n))
       (str n))
     :cljs
     (if (and (number? n) (js/Number.isInteger n))
       (str (js/Math.trunc n))
       (str n))))

(declare emit)
(defn- emit [x ind pretty?]
  (let [pad  (apply str (repeat ind "  "))
        pad+ (str pad "  ")
        sep  (if pretty? ",\n" ",")
        kv   (if pretty? ": " ":")]
    (cond
      (nil? x)                      "null"
      (boolean? x)                  (str x)
      (number? x)                   (if pretty? (str x) (numstr x))
      (or (string? x) (keyword? x)) (str \" (esc (kstr x)) \")
      (map? x) (if (empty? x) "{}"
                 (let [entries (if pretty? x (sort-by (comp kstr key) x))]
                   (str "{"
                        (when pretty? "\n")
                        (str/join sep
                                  (for [[k v] entries]
                                    (str (when pretty? pad+)
                                         \" (esc (kstr k)) \" kv
                                         (emit v (inc ind) pretty?))))
                        (when pretty? (str "\n" pad))
                        "}")))
      (sequential? x) (if (empty? x) "[]"
                        (str "["
                             (when pretty? "\n")
                             (str/join sep
                                       (for [v x]
                                         (str (when pretty? pad+)
                                              (emit v (inc ind) pretty?))))
                             (when pretty? (str "\n" pad))
                             "]"))
      :else (str \" (esc (str x)) \"))))

(defn json
  "Serialize EDN to a 2-space pretty JSON string. Keyword keys/values become strings."
  [x]
  (emit x 0 true))

(defn encode
  "Serialize EDN to compact JSON with deterministic map key order."
  [x]
  (emit x 0 false))

(defn- hex-val [ch]
  (let [i (int ch)]
    (cond
      (<= (int \0) i (int \9)) (- i (int \0))
      (<= (int \a) i (int \f)) (+ 10 (- i (int \a)))
      (<= (int \A) i (int \F)) (+ 10 (- i (int \A)))
      :else (throw (ex-info "invalid JSON unicode escape" {:char ch})))))

(defn- codepoint [s i]
  (reduce (fn [n ch] (+ (* n 16) (hex-val ch))) 0 (subs s i (+ i 4))))

(defn- ws? [ch] (contains? #{\space \tab \newline \return} ch))
(defn- skip-ws [s i]
  (let [n (count s)]
    (loop [i i]
      (if (and (< i n) (ws? (nth s i)))
        (recur (inc i))
        i))))

(declare read-value)

(defn- read-string* [s i]
  (loop [i (inc i) out []]
    (let [ch (nth s i nil)]
      (cond
        (nil? ch) (throw (ex-info "unterminated JSON string" {:pos i}))
        (= ch \") [(apply str out) (inc i)]
        (= ch \\) (let [e (nth s (inc i) nil)]
                    (case e
                      \" (recur (+ i 2) (conj out \"))
                      \\ (recur (+ i 2) (conj out \\))
                      \/ (recur (+ i 2) (conj out \/))
                      \b (recur (+ i 2) (conj out \backspace))
                      \f (recur (+ i 2) (conj out \formfeed))
                      \n (recur (+ i 2) (conj out \newline))
                      \r (recur (+ i 2) (conj out \return))
                      \t (recur (+ i 2) (conj out \tab))
                      \u (recur (+ i 6) (conj out (char (codepoint s (+ i 2)))))
                      (throw (ex-info "invalid JSON escape" {:pos i :escape e}))))
        :else (recur (inc i) (conj out ch))))))

;; RFC 8259 §6 number grammar, exactly: optional '-', an integer part (0, or
;; 1-9 followed by more digits -- no leading zeros), an optional '.' + digits
;; fraction, an optional e/E [+-] digits exponent.
(def ^:private number-re #"-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?")

(defn- read-number [s i]
  (let [n (count s)
        end (loop [j i]
              (if (and (< j n) (contains? #{\0 \1 \2 \3 \4 \5 \6 \7 \8 \9 \e \E \+ \- \.} (nth s j)))
                (recur (inc j))
                j))
        token (subs s i end)]
    ;; The scan above just grabs any run of digit/e/E/+/-/. chars -- it does
    ;; NOT itself validate JSON number syntax, so a malformed token like
    ;; "1.2.3" or "1e+5e3" reaches the host parser unchecked. JVM's
    ;; Double/parseDouble and Long/parseLong both throw on those (fail
    ;; closed) -- but JS's parseFloat/parseInt are lenient: they silently
    ;; parse just the valid PREFIX and stop (parseFloat("1.2.3") => 1.2,
    ;; parseInt("12--3", 10) => 12), never throwing -- so the exact same
    ;; malformed JSON that correctly errors on :clj would silently decode
    ;; to a truncated, wrong number on :cljs. Validated explicitly here,
    ;; on BOTH platforms, so behavior doesn't depend on which host parser
    ;; happens to be stricter.
    (when-not (re-matches number-re token)
      (throw (ex-info "invalid JSON number" {:pos i :token token})))
    [(if (re-find #"[.eE]" token)
       #?(:clj (Double/parseDouble token)
          :cljs (js/parseFloat token))
       #?(:clj (Long/parseLong token)
          :cljs (js/parseInt token 10)))
     end]))

(defn- read-array [s i]
  (loop [i (skip-ws s (inc i)) out []]
    (case (nth s i nil)
      \] [out (inc i)]
      (let [[v j] (read-value s i)
            j (skip-ws s j)
            ch (nth s j nil)]
        (case ch
          \, (recur (skip-ws s (inc j)) (conj out v))
          \] [(conj out v) (inc j)]
          (throw (ex-info "expected comma or array end" {:pos j :char ch})))))))

(defn- read-object [s i]
  (loop [i (skip-ws s (inc i)) out {}]
    (case (nth s i nil)
      \} [out (inc i)]
      (let [[k j] (read-string* s i)
            j (skip-ws s j)]
        (when-not (= \: (nth s j nil))
          (throw (ex-info "expected object colon" {:pos j})))
        (let [[v j] (read-value s (skip-ws s (inc j)))
              j (skip-ws s j)
              ch (nth s j nil)]
          (case ch
            \, (recur (skip-ws s (inc j)) (assoc out k v))
            \} [(assoc out k v) (inc j)]
            (throw (ex-info "expected comma or object end" {:pos j :char ch}))))))))

(defn- starts? [s i lit] (= lit (subs s i (min (count s) (+ i (count lit))))))

(defn- read-value [s i]
  (let [i (skip-ws s i)
        ch (nth s i nil)]
    (cond
      (= ch \") (read-string* s i)
      (= ch \{) (read-object s i)
      (= ch \[) (read-array s i)
      (= ch \t) (if (starts? s i "true") [true (+ i 4)] (throw (ex-info "invalid JSON token" {:pos i})))
      (= ch \f) (if (starts? s i "false") [false (+ i 5)] (throw (ex-info "invalid JSON token" {:pos i})))
      (= ch \n) (if (starts? s i "null") [nil (+ i 4)] (throw (ex-info "invalid JSON token" {:pos i})))
      (or (= ch \-) (and ch (<= (int \0) (int ch) (int \9)))) (read-number s i)
      :else (throw (ex-info "invalid JSON value" {:pos i :char ch})))))

(defn decode
  "Parse JSON into Clojure data. Object keys are strings."
  [s]
  (let [[v i] (read-value s 0)
        i (skip-ws s i)]
    (when-not (= i (count s))
      (throw (ex-info "trailing JSON input" {:pos i})))
    v))
