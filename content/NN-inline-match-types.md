---
layout: sip
number: NN
permalink: /sips/:number.html
redirect_from:
  - /sips/:number
  - /sips/:title.html
stage: pre-sip
status: under-review
title: Inline Match Types
---

**By: Oron Port and Claude (AI)**

## History

| Date          | Version            |
|---------------|--------------------|
| May 2026      | Initial Draft      |

## Summary

This SIP proposes **inline match types**, a type-level analog of `inline x match`. The syntax is

```scala
type Foo[X] = inline X match
  case P1 => R1
  …
  case Pn => Rn
```

Semantically, an inline match type behaves like `transparent inline def` does at the value level: it is *not* a first-class type, but a compile-time computation that **requires its scrutinee to be concrete at every use site** and **expands eagerly to its reduced form**. Because no abstract `Foo[X]` ever exists as a type, the soundness pathologies that motivate [SIP-56](https://docs.scala-lang.org/sips/match-types-spec.html)'s disjointness machinery cannot occur — and the disjointness check between cases is therefore dropped. Cases are tried in declaration order and the first match wins, exactly as with `inline x match`.

The feature directly addresses the most common UX failure mode of SIP-56 match types: cases whose patterns are not provably disjoint (typically because of subtyping — `case List[t] => …` followed by `case Iterable[t] => …`) cause reduction to get stuck on the second case for any scrutinee that doesn't fit the first. With inline match types, "specific case first, general case last" works as written and requires neither `final`/`sealed` hierarchies nor `summonFrom` scaffolding.

Inline match types complement, not replace, regular match types: they trade away appearance in generic signatures (their scrutinee must be concrete at the use site) in exchange for unrestricted, first-match-wins pattern semantics. The two forms are designed to coexist and compose.

## Motivation

### 2.1 The "first specific, then general" pattern

A recurring user need is to dispatch on a type in priority order, with later cases as fallthroughs:

```scala
type Codec[X] = X match
  case String      => StringCodec
  case Int         => IntCodec
  case List[t]     => ListCodec[t]
  case Iterable[t] => IterCodec[t]
  case Any         => DefaultCodec
```

Under SIP-56, this *almost* works. `Codec[String]` reduces to `StringCodec`. `Codec[List[Int]]` reduces to `ListCodec[Int]`. But `Codec[Set[Int]]` — which the user wants to fall through to `IterCodec[Int]` — gets stuck. The reducer reaches case `List[t]`; `Set[Int]` does not subtype `List[t]`, so the case doesn't match; but `Set[Int] ⋔ List[t]` is also not provable (open hierarchy: a hypothetical class `extends Set[Int] with List[Int]` could exist), so the reducer cannot skip the case either, and reports it stuck.

The user's options today:

1. Make every type in the hierarchy `final`/`sealed` so disjointness becomes provable. Often impossible (third-party libraries) and intrusive (forces design choices for the wrong reason).
2. Reorder cases so the order matches subtype direction (`Iterable[t]` *before* `List[t]`). This loses the priority semantics and may not even help — `List[Int]` matches `Iterable[t]` first, so there's no way to give it a more specific codec.
3. Replace the match type with a `transparent inline def codec[X](x: X): Any = inline x match …`. Works, but couples the type-level decision to the existence of a value, requires every use site to materialize a value, and pollutes call sites with extra inline calls.
4. Hand-write a chain of `summonFrom`-based givens with `NotGiven` priorities. Verbose, fragile, and obscures intent.

Affects: [#11827](https://github.com/scala/scala3/issues/11827), [#12705](https://github.com/scala/scala3/issues/12705), [#12800](https://github.com/scala/scala3/issues/12800), [#10370](https://github.com/scala/scala3/issues/10370), [#11982](https://github.com/scala/scala3/issues/11982), [#23159](https://github.com/scala/scala3/issues/23159), [#21295](https://github.com/scala/scala3/issues/21295), [#10896](https://github.com/scala/scala3/issues/10896), and others — about a third of the open match-type backlog at the time of writing centers on this pattern.

### 2.2 Singleton-and-supertype dispatch

A specific instance of the same pattern is dispatching on a literal type before its supertype:

```scala
type Foo[X] = X match
  case 42  => "the answer"
  case Int => "some other Int"
```

`Foo[42]` works (`42 <: 42`). `Foo[5]` is stuck: case `42` doesn't match (`5 <: 42` is false), and `5 ⋔ 42` is not provable (the literal types `5` and `42` *are* disjoint, but the reducer needs to compare `5` against the *pattern* `42`, and the pattern's matching test against `Int`-shaped scrutinees doesn't combine cleanly). Even when `5 ⋔ 42` *is* recognized in newer compilers, `Foo[Int]` remains stuck — `Int` is not a subtype of `42` and is not disjoint from `42` either (since `42 <: Int`).

### 2.3 What we'd write if `inline match` worked at the type level

The value-level analog already works:

```scala
transparent inline def codec[X](x: X): Any = inline x match
  case _: String      => StringCodec
  case _: Int         => IntCodec
  case _: List[t]     => ListCodec[t]
  case _: Iterable[t] => IterCodec[t]
  case _              => DefaultCodec

val c1 = codec(List(1, 2, 3))   // statically typed as ListCodec[Int]
val c2 = codec(mySet)            // statically typed as IterCodec[Int]
```

`inline x match` requires the scrutinee to be statically known and skips disjointness — first match wins. The user gets exactly the dispatch they wanted. But they pay for it with an unnecessary value-level call.

This SIP exposes the same machinery directly at the type level.

### 2.4 Issues currently failing that this SIP would address

To estimate the empirical impact, the match-types issues on `scala/scala3` were classified by whether rewriting the affected match type with `inline X match` (and ensuring use sites are concrete) would resolve the user's complaint. The classification is conservative: only issues whose root cause is disjointness or "first-match-wins" semantics, and whose actual or near-equivalent use sites are concrete, are listed. Soundness bugs, abstract-scrutinee issues, compiler crashes, capture-bound issues, GADT-narrowing issues, opaque-cross-module issues, higher-kinded extraction, and pickling issues are *not* addressed by this SIP and are excluded — see §2.4.6 for the explicit non-coverage.

#### 2.4.1 Open-hierarchy / subtype-overlap disjointness (core cluster)

The dominant pattern: a case `case Pi` is not subsumed by an earlier case but is also not provably disjoint from it because the relevant class hierarchy is open (traits, non-`final`, non-`sealed`). Reduction stops on `Pi` instead of skipping it. With `inline X match` and a concrete scrutinee, the case either matches (and reduces) or doesn't (and the next case is tried), without any disjointness obligation.

- [#6571](https://github.com/scala/scala3/issues/6571) — match type is intersection-order-sensitive (`u & v`); inline first-match-wins makes ordering an explicit user choice.
- [#8647](https://github.com/scala/scala3/issues/8647) — nested parameterized type in earlier case (`Two[Bla[_], _]`) prevents subsequent `Two[String, _]` from matching.
- [#10370](https://github.com/scala/scala3/issues/10370) — match type with type parameter `T >: String` cannot reduce because `T` could still be `Any`.
- [#10896](https://github.com/scala/scala3/issues/10896) — `Tuple.Filter` doesn't work when result length > 1; user-defined `IsString` predicate fails because match types lack a subtype relation to `Boolean` cases.
- [#11827](https://github.com/scala/scala3/issues/11827) — pattern recursion fails when intermediate types are traits rather than `final class`es; the canonical "trait makes disjointness fail" report.
- [#12705](https://github.com/scala/scala3/issues/12705) — explicitly asks "should match type cases be checked for disjointness?"; the issue documents that without a sealed hierarchy, `case A => Int; case B => String` cannot reduce on `B`.
- [#12800](https://github.com/scala/scala3/issues/12800) — intersection types (`KeyTag2[K, V]`) and tuples don't compose; reduction blocks on uninhabitedness assumption.
- [#17204](https://github.com/scala/scala3/issues/17204) — `List[Int]` and `List[String]` are not provably disjoint because `Nil.type` derives from both; inline match skips the failing case.

#### 2.4.2 Singleton vs supertype dispatch

The "literal first, fallthrough second" pattern that motivates the worked example in §2.2.

- [#14572](https://github.com/scala/scala3/issues/14572) — `0` vs `S[n]` disjointness for natural-number encodings. (Also addressed by the bundled extensions SIP via spec change; either fix resolves the user complaint.)
- [#20453](https://github.com/scala/scala3/issues/20453) — `NamedTupleDecomposition.Names[f.type]` fails on singleton tuple values because the singleton refinement is not seen as concrete enough by the disjointness machinery; with inline reduction at the use site the singleton is concrete by construction.
- [#20897](https://github.com/scala/scala3/issues/20897) — disjointness on `1 | Nothing` vs `2 | Nothing` literal unions. (Already fixed in current `provablyDisjoint`; inline form would also resolve it without needing the disjointness improvement.)

#### 2.4.3 Reduction stuck at concrete use sites due to disjointness or eagerness

These issues compile when the user adds an explicit type ascription, splits a chained expression, or otherwise forces the type checker to materialize an intermediate result. The shared root cause is that the reducer suspends a match type that *could* reduce because it cannot prove a later case disjoint, even though the scrutinee at the use site is concrete. Inline match types reduce eagerly at concrete use sites and do not suspend.

- [#11236](https://github.com/scala/scala3/issues/11236), [#11247](https://github.com/scala/scala3/issues/11247) — match types do not work unless the result is type-ascribed.
- [#11729](https://github.com/scala/scala3/issues/11729) — `Return[X]` produces a `List[t]` body but the result is not recognized as a `List` for member lookup.
- [#14216](https://github.com/scala/scala3/issues/14216) — `Tuple.Drop[T, ?]` conformance fails when the index is `index + 1` but works when it is a literal `index`.
- [#14549](https://github.com/scala/scala3/issues/14549) — recursive `Fill[S[N], A] = A *: Fill[N, A]` not simplified in return position despite the equality being derivable from a case.
- [#14593](https://github.com/scala/scala3/issues/14593) — match type returning `BitSet` doesn't conform to declared `Set[Int]` because the body's reduction is incomplete.
- [#16081](https://github.com/scala/scala3/issues/16081) — `Range[0, 23]` partially reduces but produces a "semi-reduced form, required reduced form" error.
- [#16596](https://github.com/scala/scala3/issues/16596) — recursive match type fails after the third call when used with explicit type arguments.
- [#20475](https://github.com/scala/scala3/issues/20475) — wrapping a singleton-bounded match type result in a generic class inhibits reduction; inline match would not suspend.

#### 2.4.4 Tuple representation handling

Subset of issues where the tuple form (`(A, B)` vs `A *: B *: EmptyTuple`) or the size of the tuple causes reduction to stop because of disjointness ambiguity over open tuple bases. Inline match would dispatch first-match-wins regardless of representation.

- [#9871](https://github.com/scala/scala3/issues/9871) — `IsTypeInTuple` doesn't reduce on `(Int, String)` though it works on `Int *: String *: EmptyTuple`. (Note: also addressed by the bundled extensions SIP via tuple normalization; either fix resolves the user complaint.)
- [#16481](https://github.com/scala/scala3/issues/16481) — `Tuple#tail` along with `shapeless.labelled.FieldType` triggers a type error because cases involving `FieldType[K, V]` cannot be ruled disjoint from siblings.
- [#16583](https://github.com/scala/scala3/issues/16583) — `Tuple.Union` extraction during `.toList()` fails to reduce; first-match-wins on the concrete tuple would succeed.
- [#17115](https://github.com/scala/scala3/issues/17115) — inductive `Tuple.Tail[T]` given derivation fails for tuples larger than 3 elements; eager reduction at the implicit-search site would advance.
- [#17186](https://github.com/scala/scala3/issues/17186) — `Tuple.Head[Tuple.Tail[Tuple2[A, B]]]` fails to reduce as a type alias though it works in direct form; inline would not preserve the suspended form.

#### 2.4.5 Implicit/given resolution at concrete sites

The "cart-before-horse" cluster: implicit search runs against the unreduced match type and fails to find a candidate that *would* match the reduced form. Inline match types reduce eagerly, so search runs against the result.

- [#17395](https://github.com/scala/scala3/issues/17395) — implicit not found for the result of a match type, although it is declared in the companion object of the reduced result.
- [#17907](https://github.com/scala/scala3/issues/17907) — `given head[T <: NonEmptyTuple]: TupleSelector[T, Tuple.Head[T]]` cannot be resolved at concrete call sites.
- [#18211](https://github.com/scala/scala3/issues/18211) — recursive given search using match types fails to terminate or find candidates.

#### 2.4.6 Issues this SIP does *not* address (explicit non-coverage)

For honesty about scope, the following clusters are *not* helped by inline match types and remain open or addressed by other proposals:

- **Soundness in abstract signatures.** The `tests/neg/6570.scala` family ([#6570](https://github.com/scala/scala3/issues/6570), [#19746](https://github.com/scala/scala3/issues/19746), [#20189](https://github.com/scala/scala3/issues/20189), [#20194](https://github.com/scala/scala3/issues/20194), [#20518](https://github.com/scala/scala3/issues/20518), [#20515](https://github.com/scala/scala3/issues/20515)) involves match types appearing in abstract signatures. Inline match types *prevent* this configuration outright; the original user code (which wanted abstract appearance) is not "fixed", it is rejected differently. These remain regular-match-type concerns.
- **GADT narrowing into match-type reduction.** ([#25966](https://github.com/scala/scala3/issues/25966), [#21391](https://github.com/scala/scala3/issues/21391), [#21425](https://github.com/scala/scala3/issues/21425), [#18546](https://github.com/scala/scala3/issues/18546), [#16058](https://github.com/scala/scala3/issues/16058)) — addressed by the *local GADT-aware reduction* extension in the bundled match-types extensions SIP. Inline match types cannot help because their concreteness rule excludes GADT-narrowed symbols.
- **Capture bounds on extracted variables.** ([#25129](https://github.com/scala/scala3/issues/25129), [#24351](https://github.com/scala/scala3/issues/24351), [#24317](https://github.com/scala/scala3/issues/24317), [#19904](https://github.com/scala/scala3/issues/19904)) — addressed by the *capture bound preservation* extension in the bundled SIP.
- **Higher-kinded extraction.** ([#25968](https://github.com/scala/scala3/issues/25968), [#10077](https://github.com/scala/scala3/issues/10077), [#9890](https://github.com/scala/scala3/issues/9890), [#9675](https://github.com/scala/scala3/issues/9675)) — orthogonal; would need a separate SIP for higher-order matching.
- **Cross-module / opaque-type interactions.** ([#12944](https://github.com/scala/scala3/issues/12944), [#13190](https://github.com/scala/scala3/issues/13190), [#13802](https://github.com/scala/scala3/issues/13802), [#13804](https://github.com/scala/scala3/issues/13804), [#15724](https://github.com/scala/scala3/issues/15724), [#17580](https://github.com/scala/scala3/issues/17580), [#17944](https://github.com/scala/scala3/issues/17944), [#18175](https://github.com/scala/scala3/issues/18175), [#18261](https://github.com/scala/scala3/issues/18261), [#18448](https://github.com/scala/scala3/issues/18448), [#19326](https://github.com/scala/scala3/issues/19326), [#20136](https://github.com/scala/scala3/issues/20136)) — inline match types respect the same opaque-module boundary as regular ones; these are not addressed.
- **Compiler crashes / infinite loops / assertion failures.** ([#9239](https://github.com/scala/scala3/issues/9239), [#9757](https://github.com/scala/scala3/issues/9757), [#10349](https://github.com/scala/scala3/issues/10349), [#12050](https://github.com/scala/scala3/issues/12050), [#15352](https://github.com/scala/scala3/issues/15352), [#15564](https://github.com/scala/scala3/issues/15564), [#15687](https://github.com/scala/scala3/issues/15687), [#16265](https://github.com/scala/scala3/issues/16265), [#17132](https://github.com/scala/scala3/issues/17132), [#17404](https://github.com/scala/scala3/issues/17404), [#18171](https://github.com/scala/scala3/issues/18171), [#18257](https://github.com/scala/scala3/issues/18257), [#18336](https://github.com/scala/scala3/issues/18336), [#19385](https://github.com/scala/scala3/issues/19385), [#19640](https://github.com/scala/scala3/issues/19640), [#19692](https://github.com/scala/scala3/issues/19692), [#19706](https://github.com/scala/scala3/issues/19706), [#19821](https://github.com/scala/scala3/issues/19821), [#19857](https://github.com/scala/scala3/issues/19857), [#20187](https://github.com/scala/scala3/issues/20187), [#20205](https://github.com/scala/scala3/issues/20205), [#20265](https://github.com/scala/scala3/issues/20265), [#21015](https://github.com/scala/scala3/issues/21015), [#25204](https://github.com/scala/scala3/issues/25204), [#25843](https://github.com/scala/scala3/issues/25843)) — implementation bugs, not language-level.
- **Pickling / TASTy stability.** ([#13614](https://github.com/scala/scala3/issues/13614), and the original SIP-56 motivation.) Inline match types do not affect TASTy stability of regular match types.

#### 2.4.7 Tally

| Category | Issues addressed | Open at time of writing |
|---|---|---|
| Open-hierarchy disjointness | 8 | 4 |
| Singleton vs supertype | 3 | 1 |
| Stuck at concrete sites | 8 | 5 |
| Tuple representation | 5 | 1 |
| Implicit/given at concrete sites | 3 | 2 |
| **Total** | **27** | **13** |

The 27 addressed issues — about 14% of the total backlog — concentrate in the categories that current users complain about most often (the "trait disjointness" and "first specific then general" patterns). The 13 of them that are *currently open* would have a clean, principled workaround the day this SIP ships, even before the bundled-extensions SIP closes the abstract-signature side of the picture.

### 2.5 Scope

In scope:

- A new type-level construct `inline X match { … }` with first-match-wins semantics.
- A use-site concreteness rule that prevents the inline form from appearing in any context where SIP-56's soundness rules would otherwise be required.
- Pattern syntax identical to existing match-type patterns (so the same legality classification from SIP-56 §Legal patterns applies, modulo the disjointness check).

Out of scope:

- Changing regular match types in any way.
- Changing the inline-value-match algorithm.
- A "per-case `inline`" marker that mixes inline and non-inline cases in a single match. Discussed in §Alternatives.
- Higher-kinded captures or other extensions to legal patterns. (See the bundled match-types extensions SIP for those.)

## Proposed solution

### 3.1 Syntax

The grammar of types gains one production, parallel to the existing match-type production:

```
MatchType         ::=  AnnotType ‘match’ ‘{’ TypeCaseClauses ‘}’
InlineMatchType   ::=  ‘inline’ AnnotType ‘match’ ‘{’ TypeCaseClauses ‘}’
```

The pattern grammar (`TypeCaseClauses`) is unchanged. Captures, wildcards, abstract type constructors, refined-member extractors, and `compiletime.ops.int.S` patterns all behave identically to their use in regular match types.

Indented form:

```scala
type Foo[X] = inline X match
  case P1 => R1
  case P2 => R2
```

The `inline` modifier appears immediately before the scrutinee, mirroring `inline x match` for value-level inline matches.

### 3.2 Use-site concreteness

An inline match type `inline X match { … }` is *legal at a use site* only if `X` is **concrete** at that use site. A type is *concrete* iff every type symbol it transitively references is one of:

- a class type (possibly applied to concrete arguments);
- a literal type or singleton type whose underlying is concrete;
- a stable path-dependent type (e.g. `p.A`) whose underlying is concrete and whose prefix `p` is a stable value;
- another inline match type that itself reduces to a concrete type at this use site;
- a regular match type whose declared upper bound is concrete (the regular match type itself need not reduce; the inline match observes the upper bound, not the reduced form, when the scrutinee passes through it);
- a type alias (transparent or opaque-within-its-defining-module) whose definition unfolds to a concrete type;
- an `AndType` or `OrType` of concrete types;
- a refined type whose parent and member types are concrete.

A type is *non-concrete* if it transitively references any of:

- a `TypeParamRef` (a type parameter of an enclosing definition);
- a `TypeRef` whose symbol is an abstract type member without a concrete bound;
- a GADT-narrowed type symbol (the narrowing makes it more specific but does not make it concrete);
- a `TypeVar` that has not yet been instantiated.

An inline match type appearing at a non-concrete use site is a **compile error** at that use site, with message:

> `inline match` requires its scrutinee to be concrete at the use site, but `X` is not concrete here. (`A` is an unbound type parameter.)

The error references the specific abstract symbol that prevents concreteness, to help the user thread a more specific type or refactor.

### 3.3 Definition-site validation

The *definition* `type Foo[X] = inline X match { … }` is allowed unconditionally — `Foo` may be defined with type parameters that will be supplied at use sites. The cases are validated for shape (legal-pattern check from SIP-56 §Legal patterns) but **not** for disjointness (no inter-case check) and **not** for exhaustiveness (an inline match without a matching case at a use site produces a use-site error, mirroring `inline x match`).

A use of `Foo` *inside* the body of another definition that is itself polymorphic (e.g. `def f[X](x: X): Foo[X]`) inherits the use-site rule: `Foo[X]` for abstract `X` triggers the use-site error at the definition of `f`. This is exactly how `inline def` propagates: a non-inline caller of an inline function with a non-inline argument gets an error at the call.

### 3.4 Reduction algorithm

For an inline match type `inline X match { case P1 => R1; …; case Pn => Rn }` at a use site where `X` is concrete:

1. **Sequentially**, for `i = 1, …, n`:
   - Run SIP-56's matching algorithm (`matchSpeccedPatMat` or its updated equivalent) on `(X, Pi)`. If the algorithm succeeds with capture instantiations `[ts := ts′]`, **reduce to `[ts := ts′] Ri`** and stop. There is **no disjointness check** between `X` and `Pi`, **no empty-scrutinee guard**, and **no `MatchResult.ReducedAndDisjoint` produced**.
   - If matching fails (the case structurally cannot apply to `X`), continue to case `i + 1`.
   - If matching gets stuck (the algorithm cannot decide because some intermediate result is non-concrete), this is a bug — the use-site concreteness rule should have prevented it. Emit an internal compiler error.

2. If no case matches, emit a use-site error:

   > Inline match type cannot be reduced: scrutinee `X` matches none of the cases.

3. The reduced result is **materialized** — the type assigned to the use site is the concrete reduction, not `Foo[X]`. The match-type form does not appear in the type tree after reduction.

#### 3.4.1 Why no disjointness?

The empty-scrutinee soundness rule (SIP-56 §Disjointness, `MatchResult.ReducedAndDisjoint`) exists to prevent `M[Cov[String & Int]]` from reducing to a definite type while subtyping says `Cov[String & Int] <: Cov[Int]` makes it equivalent to `M[Cov[Int]]` — see §3.6 for the full argument. With inline match types, `M` does not exist as a type that two reductions could disagree on; each use site produces a fresh, concrete result. The soundness obligation that disjointness discharges is satisfied vacuously.

### 3.5 Subtyping

Inline match types **do not participate** in subtyping as a named form. There is no rule "`Foo[X] <: Foo[Y]`" or "`S <: Foo[X] match …`". By the time any subtyping check is asked about a position holding an inline match type, the type at that position is the *reduced concrete result* — subtyping then proceeds normally on the result.

This means an inline match type cannot be the declared upper bound of a type parameter, cannot appear in an opaque type alias's bound, cannot be the declared return type of an `abstract` member of a class or trait that is intended to be overridden with a different reduction, and so on. All of these cases are non-concrete uses and are rejected by §3.2.

### 3.6 Soundness argument

The full unsoundness chain SIP-56's disjointness machinery prevents (the `tests/neg/6570.scala` family) requires:

1. A match type `M` appearing in a *signature* with an abstract scrutinee, e.g., `trait Root[A] { def thing: M[A] }`.
2. Subtyping flowing through that abstract scrutinee via variance, e.g., `Cov[String & Int] <: Cov[Int]`.
3. Different concrete reductions of the *same* `M[X]` being demanded across an override: `Asploder extends Root[Cov[String & Int]]` with `def thing = new Trait1 {}`, and a caller `def foo[T <: Cov[Int]](c: Root[T]): Trait2 = c.thing`.

Step 1 *requires* `M[A]` to exist as a type with `A` abstract. Inline match types make this illegal: `M[A]` for abstract `A` is rejected at the trait declaration. The exploit cannot be set up.

More precisely: every legal use of an inline match type has a concrete scrutinee, which means the scrutinee is the same at every observation. The reduction is a function of the (concrete) scrutinee, so it is unique per use site. Two use sites with different concrete scrutinees produce different concrete results, but those results never need to be reconciled with each other through (G2) because `Foo` doesn't appear as a type — only its expansions do, and the expansions are unrelated types from subtyping's point of view.

The disjointness check is what (regular) match types pay to be allowed to appear in abstract signatures. Inline match types pay a different price (no abstract appearance) and avoid the obligation.

### 3.7 Reduction termination

Inline match types may be recursive:

```scala
type Map[T, F[_]] = inline T match
  case h *: t     => F[h] *: Map[t, F]
  case EmptyTuple => EmptyTuple

type Result = Map[(Int, String, Boolean), List]
//          = List[Int] *: Map[(String, Boolean), List]
//          = List[Int] *: List[String] *: Map[(Boolean,), List]
//          = List[Int] *: List[String] *: List[Boolean] *: Map[EmptyTuple, List]
//          = List[Int] *: List[String] *: List[Boolean] *: EmptyTuple
```

Each step requires the next scrutinee (`t`, then `t.tail`, etc.) to be concrete, which it is by construction in this example. Termination is the user's responsibility, with the same ergonomics as `inline def` recursion: a per-site recursion budget governed by the existing `-Xmax-inlines` flag (renamed if the SIP committee prefers; the mechanism is the same).

A recursive inline match type that fails to terminate at a use site is an error at that use site, blamed on the deepest step still expanding when the budget is exhausted. (Consistent with the existing `Maximal number of successive inlines exceeded` error.)

### 3.8 Implementation summary

| Component | Files most affected | Rough size |
|---|---|---|
| Parsing `inline X match` | `Parsers.scala` | ~30 lines |
| New type form `InlineMatchType` (or flag on `MatchType`) | `Types.scala` | ~80 lines |
| Use-site concreteness check | `Typer.scala`, `MatchTypes.scala` | ~60 lines |
| Inline reduction (no-disjointness path) | `TypeComparer.scala` (new `MatchReducer.matchInlineCases` parallel to `matchCases`) | ~100 lines |
| Eager materialization in tree | `Typer.scala`, `Inliner` integration | ~50 lines |
| Recursion budget | reuses existing `-Xmax-inlines` machinery | ~10 lines |
| TASTy | inline match types do not appear in pickled trees post-reduction; the `InlineMatchType` form needs a TASTy tag for cross-module *unreduced* forms (e.g. inside another inline def's body) | ~30 lines |

Total estimated diff: ~360 lines plus tests.

## Compatibility

### Backward source compatibility

The proposal is strictly additive. The keyword `inline` already exists; `inline X match` is not currently legal type syntax, so no existing program changes meaning. No legal pattern shape is added or removed.

### Backward TASTy compatibility

Inline match types are eagerly reduced at use sites and materialized as their concrete result; pickled TASTy never contains an unreduced `inline X match` *applied form* unless it is inside another inline definition's body (where it would be re-reduced upon expansion). A new TASTy tag is needed for the `InlineMatchType` form, but is only emitted inside inline-def bodies — old TASTy readers that don't recognize the tag will only encounter it when reading an inline def from a newer library, in which case the existing inline-def cross-version handling applies.

### Backward binary compatibility

Reductions of inline match types to concrete types produce regular concrete types in bytecode; erasure proceeds normally on the result. There is no new bytecode shape.

### Forward TASTy compatibility

A TASTy file pickled by a newer compiler that contains an `InlineMatchType` inside an inline def's body cannot be read by older compilers (which lack the tag). This matches the standard forward-compatibility profile for new type-system features.

### Migration

Existing match types that work today continue to work unchanged. Users who hit "stuck on case `Pi`" failures of the kind described in §2.1–§2.2 may opt into `inline X match` if their use is at a sufficiently concrete call site. No automatic migration is proposed; the choice is a deliberate trade between generic-signature use and pattern flexibility.

## Feature Interactions

### With regular match types

Regular and inline match types compose, with the inline form propagating its concreteness requirement upward only as far as it must:

```scala
type Inner[X] = inline X match
  case Int    => "an int"
  case String => "a string"

type Outer[X] = X match
  case List[t] => Inner[t]

type R1 = Outer[List[Int]]      // = Inner[Int] = "an int"
type R2 = Outer[List[String]]   // = Inner[String] = "a string"

def f[X <: List[?]]: Outer[X] = ???   // ERROR: when reducing Outer[X] for abstract X,
                                       // even if Outer matches, Inner[t] for abstract t is illegal
```

The rule: an inline match type's expansion is attempted whenever it is *encountered* during reduction with a concrete scrutinee at that point. If the scrutinee is non-concrete, the encompassing reduction is rejected at the use site (not stuck). This produces clear errors at definitions like `f`, which today silently typecheck and then fail much later with cryptic messages.

### With `transparent inline def`

The two compose without conflict:

```scala
transparent inline def first[X](xs: List[X]): X = xs.head

type Codec[X] = inline X match
  case Int    => IntCodec
  case String => StringCodec

val c = Codec[first(List(1, 2, 3))]   // first(...) reduces at typer to 1, of type Int
                                       // Codec[Int] reduces inline to IntCodec
```

`transparent inline def`'s narrowing of return types is the existing mechanism for getting concrete singleton types into a place where an inline match can pick them up. They were designed for the same elaboration model.

### With opaque types

An inline match type *inside* an opaque type's defining module sees through the alias normally (per the standard opaque-type rules). *Outside* the module, the opaque type is not concrete in the sense of §3.2 — an inline match cannot inspect an opaque value's underlying representation. This is the same boundary that regular match types respect today (#17580 is "by design") and is enforced for the same reason.

### With type members

An inline match type may match on a path-dependent type `p.A` provided `p.A` is concrete at the use site. If `A` is abstract in `p`'s static type, the use is rejected. If `A` has an alias to a concrete type, the alias is unfolded.

Refined-member extractors (SIP-56 §Legal patterns case 4) work identically inside inline match types — the concreteness check applies to the scrutinee, and the matching algorithm is unchanged.

### With GADT narrowing

A GADT-narrowed type symbol is *not* concrete in the sense of §3.2 — even though the narrowing makes the symbol more specific, it is not a fixed concrete type, and using it as an inline scrutinee would re-introduce the cross-context disagreement that the concreteness rule was designed to prevent. So `inline X match` inside the body of a GADT-narrowing case `case Bar(v) => …` is rejected unless `X` is concretized through some other means.

A future SIP that introduces *local GADT-aware reduction* for regular match types (see the bundled extensions SIP) would not extend to inline match types: the inline form already gets the answers it needs by demanding concreteness, and the LR mechanism's "result conformed against declared upper bound, not pickled" guard does not apply since inline match types don't have a declared upper bound to conform against.

### With implicit search

Implicit search consumes the *reduced* result of an inline match type. There is no "reduce later" suspension as with regular match types ([#17395](https://github.com/scala/scala3/issues/17395)) — by the time implicit search sees the type, it has been reduced to a concrete result. The "cart before horse" failure mode is structurally absent.

### With `match` on union scrutinees

Union types as scrutinees:

```scala
type IsStringOrInt[X] = inline X match
  case String => true
  case Int    => true
  case _      => false
```

`IsStringOrInt[String | Int]` is a single use site with a concrete scrutinee. SIP-56's matching algorithm decomposes union scrutinees normally; with `inline`, the first matching case wins for each branch of the decomposition independently. (The result is `true | true = true`, which collapses to `true`.) No new rule needed.

### With `compiletime.ops`

`compiletime.ops.int` and friends produce concrete literal types when applied to literal inputs. They compose with inline match types without ceremony.

## Other concerns

### Compilation performance

Eager expansion at every use site means the cost is paid up front. For shallow matches (most user-written cases), the cost is comparable to a `transparent inline def` call without the value-level overhead. For deeply recursive cases (e.g. `Tuple.Map`-style over large tuples), the cost can be significant — the same hazard as inline functions producing large bytecode. The existing `-Xmax-inlines` budget is the safety valve.

A microbenchmark on a `Tuple.Map`-heavy library (e.g., `iron`, `chimney`, or a `shapeless`-style HList implementation) is recommended before final acceptance to confirm the cost is in line with the existing inline-def cost for equivalent code.

### Diagnostics

The use-site error must be specific. Recommended template:

> `inline match` cannot be reduced: scrutinee `X` is not concrete at this use site.
>   `X` references type parameter `A` of `f` (defined at …).
> Hint: make `f` itself an `inline def`, or supply a concrete type argument for `A`.

For a non-matching case at use site:

> `inline match` cannot be reduced: scrutinee `Set[Int]` matches none of the cases.
>   Cases tried: `case String` (no), `case Int` (no), `case List[t]` (no).
> Hint: add a `case Any => …` fallthrough.

### Tooling

The presentation compiler, IDE hover, and Scaladoc see the *reduced* result for any concrete use. For a definition `type Foo[X] = inline X match { … }`, hovering over `Foo` shows the unreduced declaration; hovering over `Foo[Int]` (a concrete use) shows the reduced result. This is exactly how `transparent inline def` is presented today.

### Open questions

1. **Should the keyword be `inline match` (after the scrutinee) or `inline X match` (before)?** The proposal uses `inline X match` to mirror `inline x match` exactly. An alternative `X inline match` was considered and rejected as awkward and inconsistent with value-level syntax. A third option `transparent type` keyword on the type alias was considered and rejected as obscuring the reduction model.

2. **Should `inline` be allowed on individual cases of a regular match type (`inline case`) instead of on the whole match?** This would allow mixed disjointness/non-disjointness. The proposal does not include this — see §Alternatives A2.

3. **Should reduction failure (no case matched) be a hard error, or should it produce `Nothing`?** The proposal makes it a hard error to mirror `inline x match`, which produces a compile error rather than silently choosing a body. A `Nothing` policy would let inline match types appear in positions that are tolerant of `Nothing` (covariant positions); this is rejected as confusing.

4. **Should there be a way to *export* an inline match type's reduction for a specific argument as a regular type alias?** E.g., `type Foo42 = Foo[42]` already does this naturally — the alias is reduced eagerly at its definition. So no new mechanism is needed.

5. **Should the compiler warn on a regular `match` type that *would* reduce if changed to `inline match`?** Useful for migration. Probably yes, behind a flag like `-Wmatch-types`. Out of scope for the language SIP; left to compiler implementation.

## Alternatives

### A1. Status quo (use `transparent inline def` for everything)

Force users to wrap every type-level decision in a value-level inline call. Works, but couples the type-level decision to the existence of a value, which is unnatural for cases that are purely about types (codec resolution, type-level config, tuple shape transformations on phantom inputs). Adds cognitive and runtime overhead. Does not eliminate the temptation to write a regular match type that gets stuck.

### A2. Per-case `inline case` modifier

Allow individual cases of a regular match type to be marked `inline`, meaning "skip the disjointness check for this case":

```scala
type Codec[X] = X match
  inline case Int    => IntCodec
  inline case String => StringCodec
  case List[t]       => ListCodec[t]
```

Sounds more flexible, but mixing the two semantics in one match makes the soundness argument intricate: the type can appear in an abstract signature *if* none of the inline cases would fire, but you can't tell at definition time. The user gets stuck with a complex mental model and the compiler has a complex acceptance check. The all-or-nothing `inline X match` is simpler and provides the same expressive power once you allow defining multiple match types.

### A3. Relax the concreteness rule

Allow inline match types in abstract positions if their declared upper bound is concrete (the result is the upper bound until concretized). This is essentially "regular match types" with a different name — the disjointness rule must come back to ensure soundness, defeating the purpose. Rejected.

### A4. Do nothing; resolve the use cases via the bundled match-types extensions SIP

The bundled extensions SIP closes some of the §2.1 cases (those involving capture bounds, tuple normalization, `S` disjointness, multi-member extractors, and the principled invariant disjointness rule). It does **not** close the "first specific, then general" case where the patterns are genuinely overlapping in subtyping (`List[t]` ⊂ `Iterable[t]`). That remaining set is exactly what this SIP addresses. The two proposals are complementary and can be evaluated independently.

## Related work

- **[SIP-56: Proper Specification for Match Types](https://docs.scala-lang.org/sips/match-types-spec.html).** This SIP extends, but does not modify, SIP-56. The matching algorithm and legal-pattern classification carry over verbatim; the disjointness check is the only piece skipped.
- **[Bundled match-types extensions SIP](./NN-match-types-extensions.html).** Complementary; closes a different cluster of issues. Either may be evaluated independently or together.
- **`inline def` and `inline match`** in the Scala 3 reference, especially `transparent inline def` with `inline match` bodies. The mechanism this SIP exposes at the type level is in regular use at the value level.
- **TypeScript's conditional types** (`T extends Array<infer X> ? X : never`). Conditional types reduce on non-ground types and use first-match-wins semantics, similar to inline match types but without a concreteness rule (TypeScript does not have separate compilation in the TASTy sense, so the (G3) obligation does not arise).
- **Haskell's closed type families with non-linear matching.** Closed type families pick the first matching equation, much like inline match types, but Haskell's elaboration model is different and the cross-package soundness story is enforced by other means.
- **C++ template specialization.** First-match-most-specific specialization is the closest C++ analog. Less expressive but the same "use-site only" model.

## FAQ

### Why "inline match types" and not just "fix disjointness"?

The bundled match-types extensions SIP does fix a substantial fraction of disjointness limitations. But some overlapping-pattern cases are *fundamentally* not provably disjoint in an open hierarchy (`Set[Int]` and `List[Int]` could be inhabited by a hypothetical class extending both), and no soundness-preserving disjointness rule can change that. For those, the user wants "first match wins, I take responsibility for ordering" — which is what inline match types provide.

### Doesn't this just push the soundness problem into the user's lap?

No, because the user can't *write* the unsound code. Inline match types refuse to appear in the abstract-signature contexts where the unsoundness chain would form. The user cannot accidentally produce a `ClassCastException` by misordering inline match cases — the worst they can do is get a "matches none of the cases" or wrong-but-locally-consistent result at a specific use site.

### Can I use this in the standard library, e.g. for `Tuple.Map`?

Mechanically, yes. Politically, it's a question for the library design committee — a stdlib `Tuple.Map` based on inline match types would have different ergonomics than today's regular-match-type version (it could not appear in abstract signatures like `def map[T <: Tuple, F[_]](t: T): Tuple.Map[T, F]`). The choice between the two is a library-design trade-off; the language change just makes both available.

### Does this conflict with the bundled match-types extensions SIP?

No. The bundled extensions SIP modifies the regular match-type reducer to admit more reductions while preserving SIP-56's invariants. This SIP introduces a new construct that bypasses those invariants by way of a concreteness restriction. They touch disjoint code paths and disjoint feature surfaces. Either may be accepted independently of the other.

### What about pickling? Won't every use site bloat TASTy?

Each *use site* pickles its reduced concrete result. The reduced result is typically smaller than the unreduced match type plus the scrutinee (which is what regular match types pickle). For deeply recursive cases (`Tuple.Map` over a 22-element tuple), the materialized result *is* large — but no larger than what `transparent inline def` already pickles for the same computation.

### Is this just `transparent type` from Haskell/Idris?

It is closer to OCaml's `[@inline]` on type abbreviations or to a *macro* over types. The Haskell analog would be a `Constraint` synonym driven by closed type families with `Generic`-style expansion at the use site. The proposal does not introduce any new metatheory; it composes existing mechanisms (inline expansion, use-site elaboration, match-type matching) in a new combination.
