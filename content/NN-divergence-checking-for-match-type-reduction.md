---
layout: sip
number: NN
permalink: /sips/:number.html
redirect_from:
  - /sips/:number
  - /sips/:title.html
stage: pre-sip
status: under-review
presip-thread: https://contributors.scala-lang.org/t/pre-sip-divergence-checking-for-match-type-reduction/7490
title: Divergence Checking for Match Type Reduction
---

**By: Lluc Santamaria Riba**

This work was carried out at LAMP (EPFL), directed by Martin Odersky and supervised by Matthieu Bovel.

## History

| Date          | Version            |
|---------------|--------------------|
| Jul 1st 2026  | Initial Draft      |

## Summary

Match type reduction in Scala 3 can diverge: a recursive match type with no
reachable base case makes the compiler loop until the JVM stack is exhausted.
Today the compiler detects this by catching `StackOverflowError` and reporting
"Recursion limit exceeded". Catching `StackOverflowError` is unsafe on the JVM
— `finally` blocks may be skipped and locks may remain held — so the compiler
may continue with corrupted internal state, and whether a given program
compiles at all depends on the platform and on the stack size configured with
`-Xss`.

This SIP replaces that mechanism with a *static divergence check*: it extends
the domination criterion that [SIP-31](https://docs.scala-lang.org/sips/byname-implicits.html)
introduced for implicit resolution (type complexity plus covering sets) from
single types to the argument lists of match type applications. A reduction
that revisits earlier arguments, or grows them without introducing new type
symbols, is rejected before it can consume the stack, with a dedicated error
carrying the full reduction trace. The check is proven to guarantee
termination of match type reduction.

Because exact termination checking is undecidable, the check is necessarily
conservative: it rejects a small class of *terminating* programs. That moves
the boundary of which programs are valid Scala — the definition of a language
change, and why this proposal is a SIP rather than a compiler patch: it turns
the implementation-defined recursion limit of match type reduction into a
specified termination discipline, exactly as SIP-31 did for implicit search.

## Motivation

### Match types can diverge

Match types ([SIP-56](https://docs.scala-lang.org/sips/match-types-spec.html))
enable type-level computation, and recursion is what makes them useful:

~~~ scala
type Unwrap[X] = X match
  case Option[t] => Unwrap[t]
  case _         => X
~~~

But nothing forces a recursive match type to make progress. All of the
following type-check today and loop forever when applied:

~~~ scala
// Cycles through a finite set of states, never reaching a base case
type Loop[X] = X match
  case Int    => Loop[Float]
  case Float  => Loop[Double]
  case Double => Loop[String]
  case String => Loop[Int]

// Argument grows at every step
type Wrap[X] = X match
  case AnyVal => Wrap[List[X]]
  case AnyRef => Wrap[List[X]]

// Nested self-application
type F[A] = A match
  case Int => F[F[A]]
~~~

### The status quo: catching `StackOverflowError`

The compiler currently lets such reductions run until the JVM stack overflows,
catches the `StackOverflowError`, and reports:

~~~
Recursion limit exceeded.
Maybe there is an illegal cyclic reference?
If that's not the case, you could also try to
increase the stacksize using the -Xss JVM option.
~~~

This mechanism is broken in three independent ways:

1. **Catching `StackOverflowError` is unsafe on the JVM.**
   [JDK-8177802](https://bugs.openjdk.org/browse/JDK-8177802) documents
   `finally` blocks being bypassed when a `StackOverflowError` is caught, and
   [JDK-8318888](https://bugs.openjdk.org/browse/JDK-8318888) documents
   `ReentrantLock` remaining locked afterwards, even under the recommended
   try/finally pattern (reproduced on JDK 11, 17, 21 and 22). A compiler that
   recovers from a stack overflow may therefore continue with corrupted
   internal state: caches half-updated, invariants silently broken. The JVM
   specification classifies `StackOverflowError` under `VirtualMachineError`
   — the JVM "is broken or has run out of resources necessary for it to
   continue operating".

2. **Acceptance is implementation-defined.** Whether a program compiles
   depends on `-Xss`, the JVM vendor and version, and how deep in the
   compiler the reduction happens to start. The same program can compile on
   one machine and fail on another. There is no specification of *which*
   recursive match types are legal; the de facto answer is "those that happen
   to fit in the stack".

3. **The diagnostics are unactionable.** "Recursion limit exceeded" does not
   say which match type diverged or why. Users are advised to raise `-Xss`,
   which for a genuinely divergent type only makes compilation fail slower.

The goal of this proposal is to specify a termination discipline for match
type reduction: divergence is detected *before* it consumes the stack and
reported as a well-defined error with an explanatory reduction trace.

Out of scope: the reduction semantics of match types themselves (match order,
disjointness checking, pattern matching rules — all as specified by SIP-56)
are untouched. This proposal only bounds how long a reduction may go on and
specifies what happens when the bound would be violated.

## Proposed solution

### High-level overview

Scala already solved this exact problem once. Implicit resolution is a
recursive type-level search that can diverge in the same ways, and SIP-31
specifies a divergence check for it based on two measures of a type: its
**complexity** (structural size) and its **covering set** (the type symbols
it mentions). A search is aborted when the current target *dominates* an
earlier one — it is equivalent to it (a cycle), or strictly larger while
using the same symbols (growth without progress).

This proposal applies the same criterion to match type reduction, lifted from
single types to argument lists. During reduction the compiler keeps, per
match type constructor `M`, the stack of argument lists with which `M` has
already been expanded on the current reduction path. Before expanding
`M[T₁, …, Tₙ]`, the current arguments are compared against that history, and
reduction is rejected if they dominate some previous ones:

- **cycle**: the argument lists are equivalent — the reduction revisits a
  state it has seen before; or
- **strict growth**: same covering set as before, every argument at least as
  complex, at least one strictly more complex — the reduction builds
  ever-bigger types out of the same material and cannot be making progress.

On the divergent examples above:

~~~ scala
type Test = Loop[Int]
// rejected: Loop[Int] -> Loop[Float] -> Loop[Double] -> Loop[String] -> Loop[Int]
// the arguments [Int] repeat: cycle

type Test = Wrap[Int]
// rejected: Wrap[Int] -> Wrap[List[Int]] -> Wrap[List[List[Int]]]
// covering set {List, Int} stable, complexity 1 -> 2 strictly grows
~~~

Ordinary recursive match types keep compiling: either their arguments shrink
(`Unwrap[Option[Option[Int]]]` has argument sizes 2, 1, 0), or their covering
set changes when they grow — the same property that lets SIP-31 accept
inductive generic derivation.

#### Known false positives and their mitigations

The check is conservative, and its false positives are well understood. Three
families of terminating patterns are structurally indistinguishable from
divergence, and the design addresses each deliberately:

1. **Type-level arithmetic** (Peano-style recursion, bounded accumulator
   loops): the recursive argument progresses through `scala.compiletime.ops`
   while an accumulator grows over a stable covering set.

   ~~~ scala
   import scala.compiletime.ops.int.*

   type Peano[N <: Int & Singleton, Acc] = N match
     case 0 => Acc
     case _ => Peano[N - 1, S[Acc]]      // Acc grows, but N - 1 progresses

   type Loop[Idx <: Int, Limit <: Int, Acc <: Tuple] <: Tuple = Idx match
     case Limit => Acc
     case _     => Loop[Idx + 1, Limit, "Token" *: Acc]
   ~~~

   Termination here is governed by the *values*, not by the structural size
   of the types, so a structural measure is the wrong instrument.
   *Mitigation — the operation-progression exemption:* argument lists that
   step through `compiletime.ops` applications or constant types at some
   position are exempt from the domination check and fall back to the
   existing global recursion limit, which is the right bound for value-like
   iteration. Both examples above are accepted.

2. **Reduction under an unreduced alias** (observed in shapeless3):

   ~~~ scala
   type Tail[X] = X match
     case _ *: t => t
     
   type Apply[C[_], T] = T match
     case EmptyTuple => EmptyTuple
     case h *: _     => C[h] *: Apply[C, Tail[T]]
   ~~~

   The check measures arguments unreduced, so it sees `Tail[T]`,
   `Tail[Tail[T]]`, … — sizes N, N+1, N+2, apparently growing — even though
   each `Tail[T]` reduces to something smaller than `T` for any concrete
   tuple. *Mitigation — the grace threshold:* the domination check only
   engages once the reduction history for a constructor exceeds a fixed
   depth (10 steps in the current implementation). Realistic tuples reach
   their base case well within the grace window; as a bonus, shallow
   reductions — the overwhelmingly common case — are never analyzed at all,
   eliminating any compile-time overhead for them. Notably, the
   direct-binding formulation of the same combinator,
   `case h *: t => C[h] *: Apply[C, t]`, is accepted by the domination
   criterion itself, without needing grace.

For any residual false positive, two rewrites cover the known space: bind the
recursive component in the pattern rather than re-projecting it through an
alias, or drive the iteration through `compiletime.ops`.

#### Error reporting

When divergence is detected, the compiler reports one of two new errors
instead of "Recursion limit exceeded", at the same source positions, each
with an `-explain` text containing the complete reduction trace, the
complexity vectors, and the covering sets:

~~~
-- [EXXX] Type Error: example.scala ------------------------------------------
  |  Match type reduction failed due to potentially infinite recursion in the
  |  reduction steps.
  |
  |  Explanation (with -explain):
  |  The reduction step for Wrap resulted in divergent arguments:
  |    Previous Arguments:  [List[Int]]       (complexities: [1])
  |    Current  Arguments:  [List[List[Int]]] (complexities: [2])
  |    Covering Set: [Int, List]
  |  ...
  |  Trace: Wrap[Int] -> Wrap[List[Int]] -> Wrap[List[List[Int]]]
~~~

### Specification

#### Definitions

Let `c(T)` be the **complexity** of a type `T`: its structural size, i.e. the
number of nodes of the tree formed by its type constructors and leaf types.
This is the same notion used by SIP-31. Match type aliases occurring inside
`T` are counted *unreduced* — each contributes its node without being
expanded — which keeps the measure well-defined even when arguments
themselves mention match types.

Let `cs(T)` be the **covering set** of `T`: the set of type symbols (classes,
type constructors, abstract types) mentioned in `T`, again as in SIP-31.
Singleton literal types contribute no symbols. For example, with

~~~ scala
type A = List[(Int, Int)]
type B = List[(Int, (Int, Int))]
type C = List[(Int, String)]
~~~

we have `cs(A) = cs(B) = {List, Tuple2, Int}` and
`cs(C) = {List, Tuple2, Int, String}`.

Both notions extend pointwise to argument lists `𝐓 = [T₁, …, Tₙ]`:

- complexity vector `𝐜(𝐓) = [c(T₁), …, c(Tₙ)]`,
- covering set `cs(𝐓) = cs(T₁) ∪ … ∪ cs(Tₙ)`.

**Domination.** For argument lists `𝐓` and `𝐔` of the same length, `𝐓`
*dominates* `𝐔` iff one of:

1. *Equivalence*: `Tᵢ` and `Uᵢ` are equivalent types for all `i`; or
2. *Strict growth*: `cs(𝐓) = cs(𝐔)`, and `c(Tᵢ) ≥ c(Uᵢ)` for all `i`, and
   `c(Tⱼ) > c(Uⱼ)` for at least one `j`.

This is exactly SIP-31's domination relation, with the complexity comparison
lifted from a single number to the component-wise partial order on ℕⁿ.

**Operation progression.** Argument lists `𝐓` (current) and `𝐔` (previous)
exhibit an *operation progression* if for some position `i`, `Tᵢ` and `Uᵢ`
are both applications of the same `scala.compiletime.ops` type, or both
constant (singleton literal) types.

#### The check

During match type reduction the compiler maintains, per match type
constructor `M`, the history `H(M)`: the argument lists of the applications
of `M` currently being reduced on this reduction path (innermost first). The
history is part of the reduction state, threaded through nested reductions;
two independent reductions do not share history.

To reduce `M[𝐓]`:

1. If any component of `𝐓` reduces to an error type, reduction fails with
   the `MatchTypeReductionWithErrorArgs` error.
2. If `|H(M)| ≥ G` (the grace threshold, `G = 10`), then for each previous
   argument list `𝐔 ∈ H(M)`:
   - if `𝐓` dominates `𝐔` by strict growth and `𝐓`, `𝐔` do not exhibit an
     operation progression, reduction fails with the
     `IncreasingMatchReduction` error;
   - if `𝐓` dominates `𝐔` by equivalence, reduction fails with the
     `CyclicMatchTypeReduction` error.
3. Otherwise, push `𝐓` onto `H(M)` and reduce `M[𝐓]` according to the match
   type reduction rules of SIP-56, using the extended history for any nested
   reductions.

Reductions exempted through operation progression remain subject to the
compiler's global recursion limit, as today: the exemption trades the static
guarantee for compatibility on value-like iteration without admitting
unbounded reduction.

The three error conditions are registered as dedicated error codes with
their own documentation pages and `-explain` texts.

#### Termination

With the check in place (and setting aside the op-progression exemption,
which is bounded by the global recursion limit), every reduction sequence
`M[𝐓₀] → M[𝐓₁] → ⋯` terminates. Proof sketch, by contradiction on an
infinite sequence that never triggers the check:

- **Lemma 1 (finitely many covering sets).** Each reduction step substitutes
  components of the current arguments into a right-hand side from the program
  source, so `cs(𝐓ₖ) ⊆ cs(𝐓₀) ∪ S_prog`, where `S_prog` is the finite set of
  symbols in the program. Only finitely many covering sets can occur, so by
  the pigeonhole principle some covering set `C*` occurs infinitely often;
  restrict to that subsequence.
- **Lemma 2 (Dickson's Lemma).** Every infinite sequence of vectors in ℕⁿ
  contains an infinite component-wise non-decreasing subsequence. Apply it to
  the complexity vectors of the `C*`-subsequence.
- Within that non-decreasing subsequence, either some pair grows strictly —
  then strict-growth domination fires (covering sets are equal by
  construction), contradiction — or the complexity vector is eventually
  constant. **Lemma 3:** only finitely many distinct types exist with a given
  complexity over a given finite symbol set, so by pigeonhole some argument
  list repeats — and equivalence domination fires. Contradiction either
  way. ∎

The full formal development (definitions, the three lemmas with proofs,
worked reduction traces) is in the
[project report](https://github.com/Lluc24/divergence/blob/main/report/Report.md).

The check is *sound* — whatever it admits terminates — but *incomplete*: it
rejects some terminating reductions, and must, since termination is
undecidable. The known incompleteness is exactly the false-positive families
listed in the overview, each covered by its mitigation.

### Compatibility

**Binary and TASTy compatibility.** The proposal changes no syntax, no term
semantics, and no result of any successful reduction: a match type that
reduces today reduces to exactly the same type under the check. No new trees
or types are serialized, so bytecode and TASTy for accepted programs are
identical before and after, and backward binary and TASTy compatibility hold
by construction.

**Source compatibility** is where the substance is. Programs fall into three
classes:

1. *Genuinely divergent programs*: fail before and after; only the error
   changes, from the implementation-defined "Recursion limit exceeded" to a
   specified, explainable divergence error at the same positions. A strict
   improvement.
2. *Terminating programs accepted by the check*: the overwhelming majority.
   The entire existing Scala 3 test suite compiles unchanged, and
   community-build-style patterns (generic derivation over `Mirror`,
   tuple-recursive match types with direct structural descent) are accepted
   by the domination criterion itself, without relying on any exemption.
3. *Terminating programs rejected by the check*: the false-positive families
   of the overview. With their mitigations (op-progression exemption, grace
   threshold), no known real-world pattern is rejected — the one wild
   pattern that needed care, shapeless3's `Apply[C, Tail[T]]`, is admitted
   by the grace threshold, and its direct-binding sibling is accepted
   outright. For anything the analysis missed, the two rewrites given in the
   overview provide a migration path, and the `-explain` output shows the
   exact trace and measures that triggered rejection.

The check does not change the stack behavior of the reductions it accepts:
reduction itself executes exactly as before, so a deeply nested terminating
reduction that needs a raised `-Xss` today still needs it. What changes is
that divergence is no longer *diagnosed* by exhausting the stack — divergent
reductions fail with a specified error long before the stack is at risk,
instead of by crashing into it.

**Rollout.** The check can follow the usual staging: ship first as a warning
(or under `-source:future`) with the stack-overflow fallback still in place,
becoming an error in a later minor release. A community-build run is the
natural acceptance gate before the error becomes default, and the warning
stage exists to surface any pattern not covered by the mitigations.

### Feature interactions

- **Match type reduction (SIP-56).** The check is a guard *around* reduction,
  not a change *to* it: match order, provably-disjoint checking, and variable
  binding are untouched. Whenever the check does not fire, `M[𝐓]` reduces
  exactly as specified by SIP-56.
- **Implicit search divergence (SIP-31).** The two checks are the same
  criterion applied to different recursion engines, and they compose: a given
  whose type involves match types is guarded by SIP-31 on the search side and
  by this check on the reduction side, with independent histories. The
  language ends up with one divergence story, not two.
- **`compiletime.ops` and singleton types.** Deliberately exempted (see
  overview). Constant folding of `compiletime.ops` applications happens
  before the domination comparison would, so type-level arithmetic is never
  misjudged by a structural measure.
- **Generic derivation (`Mirror`, tuple traversal).** Structural descent on
  tuples shrinks complexity and is accepted directly; derivation that grows
  while introducing new symbols changes the covering set and is likewise
  accepted — the same property that lets SIP-31 accept inductive implicits.
- **`inline` and `summonFrom`.** Inline expansion can trigger match type
  reduction; the check applies wherever reduction is attempted, with the
  history threaded through the reduction state, so inlining neither escapes
  nor double-counts it.
- **Error types.** Erroneous arguments previously could drive reduction into
  the recursion limit, piling a misleading error on top of the real one. The
  check reports `MatchTypeReductionWithErrorArgs` immediately, pointing at
  the erroneous arguments.
- **Higher-kinded and mutually recursive match types.** The history is keyed
  by the match type constructor; applications of different constructors never
  compare against each other, so mutual recursion between distinct match
  types is bounded by each constructor's own history along the shared
  reduction path.

### Other concerns

- **Performance.** The check runs only when a constructor's reduction history
  exceeds the grace threshold — never for common shallow reductions. When it
  does run, it compares against the current path's history only, computing
  sizes with an accumulator that counts match aliases unreduced (so size
  computation cannot itself trigger reduction), and detecting cycles by
  reference equality before falling back to equivalence, avoiding expensive
  subtype checks. No compile-time regression is measurable on the existing
  test suite.
- **Specification text.** SIP-56's reduction rules gain a side condition (the
  guard above), and the spec gains the definitions of complexity, covering
  set and domination — shareable with SIP-31's, since they are the same
  notions.
- **Cross-platform.** Divergence is no longer diagnosed through a
  JVM-specific `StackOverflowError`: divergent reductions are rejected with
  the same specified error regardless of `-Xss` or the JVM vendor. This also
  benefits tooling — in Metals' presentation compiler, an unrecoverable stack
  overflow is particularly harmful.

### Open questions

1. **The grace threshold value.** `G = 10` is empirical: large enough that
   every non-contrived pattern found (including the shapeless3 one) reaches
   its base case or a covering-set change before the check engages, small
   enough that divergence is caught long before the stack is at risk. Should
   `G` be fixed by the spec, specified as a lower bound implementations must
   honor, or left implementation-defined?
2. **Scope of the op-progression exemption.** Currently: same
   `compiletime.ops` constructor on both sides at some position, or constant
   types at some position. Should it be narrowed (require the *scrutinee*
   position to progress) or widened (user-annotated measures, see
   Alternatives)?
3. **Rollout staging.** Warning first (with the stack-overflow fallback
   retained) then error, or error under `-source:future` from the start?
4. **Should the exempted reductions get a specified bound**, rather than
   falling back to the implementation's global recursion limit?

## Alternatives

1. **Status quo — catch `StackOverflowError`.** No false positives, but
   unsafe (JDK-8177802, JDK-8318888: corrupted compiler state after
   recovery), implementation-defined acceptance, and unactionable
   diagnostics. This proposal exists because the status quo is not tenable.
2. **A plain fuel/depth limit** (as Rust's `recursion_limit`): count
   reduction steps, fail at N. Simple and spec-able, but it cannot
   distinguish deep terminating reductions from divergence: large terminating
   computations fail with no better explanation than today, while true cycles
   burn N steps before failing with a message that cannot say *why*. Retained
   only as the fallback for the exempted op-progression class, where it is
   the right tool because that class is value-bounded.
3. **Termination annotations / user-supplied measures** (Agda/Coq-style
   measures, or an `@terminates` escape hatch). Maximally expressive, but
   pushes proof obligations onto users for the common case, adds surface
   syntax, and still needs a default story for unannotated match types —
   which is exactly this proposal. Could be layered on later as an opt-out
   for residual false positives; deliberately not part of this SIP.
4. **Syntactic structural-recursion restriction** (recursive calls only on
   pattern-bound components, as in total languages). Sound and simple to
   state, but far too restrictive: it rejects state machines, accumulators,
   and arithmetic, all of which the domination criterion accepts.
5. **Lexicographic or sum-based orderings instead of pointwise domination.**
   The implementation went through both: a strict size-decreasing check (sum
   of complexities must shrink — rejects any accumulator) and a
   lexicographic variant (rejects terminating reorderings, accepts some
   divergent ones, and is sensitive to argument order). Pointwise domination
   with covering-set stability is the most permissive of the three for which
   the termination proof goes through.
6. **The same check as an unspecified compiler heuristic.** Rejected on
   principle: since the check has false positives, it defines which programs
   are valid Scala. That boundary belongs in the specification, not in
   undocumented compiler behavior that other implementations would have to
   reverse-engineer. See FAQ.

## Related work

- **Pre-SIP discussion:**
  [Divergence Checking for Match Type Reduction](https://contributors.scala-lang.org/t/pre-sip-divergence-checking-for-match-type-reduction/7490)
  — includes the SIP chair's endorsement of taking this through the SIP
  process, and the objections addressed in the FAQ.
- **SIP-31 — Byname implicits:** the direct ancestor; defines complexity,
  covering sets and domination for implicit search. This proposal is its
  extension from single types to argument vectors.
- **SIP-56 — Match types specification:** specifies the reduction this
  proposal guards.
- **Issue and implementation:**
  [scala/scala3#22587](https://github.com/scala/scala3/issues/22587) (the
  request to stop catching `StackOverflowError`),
  [scala/scala3#24661](https://github.com/scala/scala3/pull/24661) (complete
  implementation: check, the three errors with `-explain` traces, error-code
  documentation, positive/negative tests).
- **Formal report:**
  [Match Types Divergence Check](https://github.com/Lluc24/divergence/blob/main/report/Report.md)
  — full definitions, the three lemmas, the termination theorem, worked
  traces.
- **Other languages:** Haskell requires instance resolution to satisfy the
  Paterson conditions (a size-decreasing criterion) unless
  `UndecidableInstances` opts into a plain depth limit; Rust bounds trait
  resolution and const evaluation with `recursion_limit`; total languages
  (Agda, Coq, Idris, Lean) use structural or measure-based termination
  checkers. This proposal sits between Haskell's two modes: a criterion more
  permissive than Paterson's (accumulators and same-size state changes are
  accepted), with the depth-limit fallback reserved for value-like
  arithmetic.

## FAQ

**Why does this need a SIP at all? Divergent code doesn't compile today and
won't compile tomorrow — isn't this just a better error message?**

This was the main objection in the pre-SIP thread, and it would be decisive
if the check were complete. It is not, and cannot be: termination checking is
undecidable, so the check rejects a nonempty set of *terminating* programs.
That moves the boundary of valid Scala — the definition of a language change,
requiring specification and committee review. The
mitigations exist precisely to make the rejected set as close to empty as
practically possible, but "close to empty" is a spec-level commitment, not an
implementation detail.

**Are the old and new systems equivalent?**

Not quite. Newly rejected: the false-positive families of the overview,
absent their mitigations — with them, no known real-world pattern. Accepted
reductions behave exactly as before, including their stack consumption: the
check is a guard around reduction, not a change to how it executes.

**Do we know if match types in the wild break under this?**

The full Scala 3 test suite passes unchanged. The one wild pattern found to
need care is shapeless3's alias-projection combinator (`Apply[C, Tail[T]]`),
which the grace threshold admits — and whose direct-binding sibling is
accepted outright. A community-build run and the warning-first rollout stage
are the safety nets for anything the analysis missed.

**Is this check performed at runtime?**

No — everything happens at compile time. "Static" versus the status quo
distinguishes *analyzing the reduction before it runs away* from *executing
the reduction until the compiler's own stack overflows*. Only the failure
mode differs.

**Why exempt `compiletime.ops` instead of modeling arithmetic progress?**

Modeling "N − 1 approaches 0" statically means evaluating the arithmetic,
which is exactly what reduction does anyway; a structural measure cannot see
it. The exemption acknowledges that this class is value-bounded rather than
structure-bounded and hands it to the step limit, the correct measure for
value iteration. A future refinement could specify decreasing-measure
detection for the common `ops.int` operations, shrinking the exempted class.

**What if my terminating match type is still rejected?**

This SIP specifies what a well-founded recursive match type is, and a
rejection means the recursion does not make its progress visible — the fix is
to adapt it to the discipline. If progress is structural, make it explicit by
binding the recursive component in the pattern (`case _ *: t => … F[t]`)
rather than re-projecting through an alias (`F[Tail[T]]`); if the types do
not change structurally, drive the iteration through a `compiletime.ops`
index. The `-explain` output shows the trace and measures that fired.
