# `bench/ffmpeg-comparison` — utsushi against ffmpeg, and against where utsushi is going

```sh
clojure -M:bench                    # 5 samples per arm, probes the kotoba toolchain
clojure -M:bench --samples 9 --out /tmp/report.edn
clojure -M:bench --skip-kotoba-probe    # fast; records the kotoba arms as :not-probed
```

Exit codes: **0** measured, **1** engines produced different pixels, **3** could not
measure. Three values because there are three outcomes, and *could not measure*
returning the same value as *measured, clean* is the exact failure this harness
exists to prevent.

Modelled on [`kotoba-lang/amu`'s `bench/runtime-comparison`](https://github.com/kotoba-lang/amu)
and its ADR 0226, *cross-language runtime evidence is workload-bound*. The
statistics are [`kotoba-lang/perfgate`](https://github.com/kotoba-lang/perfgate)'s;
nothing here computes a mean and calls it a result.

## The engine roster is the point

ADR 0226 defines a comparison as a set of **adapters**, with unavailable ones
recorded by name and reason (`skippedEngines`) rather than omitted. This bench
keeps that discipline, and the reason is not tidiness:

| engine | role | status |
|---|---|---|
| `ffmpeg` | reference | measured |
| `utsushi-jvm` | **incumbent oracle** | measured |
| `kotoba-wasm32` | target backend | unavailable — stage recorded |
| `kotoba-native-aarch64` | target backend | unavailable — stage recorded |
| `kotoba-script-js` | target backend | unavailable — stage recorded |

A two-engine table would read as *utsushi (JVM) vs ffmpeg*, and therefore as if
closing that gap were the whole job. It is not.
[ADR-2607198300](https://github.com/com-junkawasaki/root) says the destination is
an artifact that needs no JVM, Node or Rust at run time. The JVM arm is the
**oracle** — it says which pixels are correct — and the incumbent. It is not the
finish line, and a report that cannot show the difference is a report that hides it.

### A `.cljc` extension does not mean amu can compile the file

amu's `.cljc` is the **Kotoba guest grammar** wearing a `.cljc` extension. It is a
much narrower language than the full Clojure `utsushi` and `org-iso-h264` are
written in. So the kotoba arms' unavailability is not one fact but a ladder, and
this bench measures which rung each one stops on by *running amu*, not by asserting:

| stage | meaning |
|---|---|
| `:cli` | the compiler CLI was not found at all |
| `:source-read` | amu's reader rejected the source — it is not guest grammar |
| `:check` | admitted by `amu check`; effects and exports resolved |
| `:compile` | a backend accepted or rejected the admitted program |
| `:execute` | an artifact ran and produced pixels |

Measured 2026-08-29 on this workspace (amu at `orgs/kotoba-lang/amu/bin/amu`,
sources at `orgs/kotoba-lang/org-iso-h264/src/h264`):

```
amu check   h264/decode.cljc        -> exit 65  :kotoba/source-read-failed
amu check   h264/expgolomb.kotoba   -> exit 0   admitted, effects #{}
amu compile h264/expgolomb.kotoba --target aarch64 -> exit 70 :kotoba/target-rejected
    "typed values currently require the kotoba-script web target, typed Wasm
     target, or qualified native string/scalar-record/option-i64/result-i64 features"
amu compile h264/expgolomb.kotoba --target wasm32  -> exit 70 :kotoba/internal-error
```

Four different distances. Reporting them all as *not supported* would hide where
the work actually stops — and three of the four stop somewhere other than where a
reader would guess. **The decoder itself does not reach the compiler at all**: of
the H.264 namespaces in `org-iso-h264/src/h264`, only three
(`expgolomb`, `rbsp`, `sps` — none of them the decoder) have a guest-grammar twin.

## Same observable result, or no comparison

Before either engine is timed, both decode the fixture and their `yuv420p` bytes
must be **identical**. A fixture whose engines disagree is reported as a
`:mismatch` and nothing about it is timed: a speed ratio between two different
answers is not a speed ratio.

ffmpeg's much longer stretched stream (below) is not decoded by utsushi — that
would take the better part of an hour per fixture — so it is checked a different
way that costs nothing and is still a real check: N copies of a self-contained
GOP must decode to the short decode repeated N times, and ffmpeg's own long
output is asserted to be exactly that.

## Steady state is a slope, and the two engines need different stretches

An ffmpeg invocation is a **process**: `exec`, dynamic linking, demux setup and
teardown swamp the decode of a handful of small frames. A utsushi invocation is a
warm JVM method. Dividing wall time by frame count therefore measures mostly the
fixed cost — measured, on the 32×32 fixture that division made utsushi look
**1.6× faster than ffmpeg**, which is an artifact of ffmpeg's process startup and
nothing else.

So each fixture is decoded at two frame counts — itself, and itself concatenated
N times through `utsushi.pipeline.remux/concat-mp4s` (stream copy, no re-encode)
— and the per-frame cost is the **slope**, at which the fixed cost cancels.

The two engines get **different N** (`fixtures.edn`), because a slope resolves a
per-frame cost only when the added frames cost more than the invocation's own
jitter, and these engines' fixed and marginal costs are four orders of magnitude
apart. At a shared stretch of 2, ffmpeg's slope came out **negative**. A negative
per-frame cost is nonsense the arithmetic will happily produce and a report will
happily print, so a non-positive slope is reported as
`:unresolved-below-fixed-cost` and **no ratio is derived from it**.

## Load is reported, because a quiet host is not available

`load1` is read before and after every engine's samples and printed next to the
numbers. amu ADR 0281 (2026-08-29) measured that no host in this fleet reaches
`load1 <= 1.0`, so "benchmarked on a quiet machine" is not a claim anything here
can make. The honest alternative is not to wait for quiet — it is to state the
load and let the reader discount accordingly. At the loads this workspace runs
at, `perfgate` refuses nearly everything for `:too-noisy`, and that refusal is
reported rather than worked around.

## What these numbers are not

- **Not a general video decode ranking.** The fixtures are flat, DC-heavy,
  Intra_16x16 baseline content, because that is the only real libx264 output
  `org-iso-h264` decodes at all (it has no Intra_4x4 path, and libx264 cannot be
  told to stop emitting Intra_4x4 — see `utsushi.pipeline.mp4-h264`'s docstring).
- **Not a like-for-like harness cost.** ffmpeg is timed decoding to `-f null -`:
  it decodes and discards. utsushi builds Clojure vectors of pixels. That
  difference favours ffmpeg and is not corrected for.
- **Not a claim.** `perfgate.core/claim` refuses to seal an unqualified verdict,
  and on this host nothing qualifies. The ratio is still printed — hiding it
  would be its own dishonesty — and it is printed marked UNQUALIFIED.

## Regenerating the fixtures

```sh
clojure -M -m utsushi.bench.generate-fixtures     # needs ffmpeg on PATH
```

The encoder settings are load-bearing and are documented in that namespace and in
`test/utsushi/pipeline/mp4_h264_gop_test.clj`.
