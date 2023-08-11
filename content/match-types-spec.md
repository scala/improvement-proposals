---
layout: sip
permalink: /sips/:title.html
stage: implementation
status: waiting-for-implementation
title: SIP-56 - Proper Specification for Match Types
---

**By: Sébastien Doeraene**

## History

| Date          | Version            |
|---------------|--------------------|
| Aug 11th 2023 | Initial Draft      |

## Summary

Currently, match type reduction is not specified, and its implementation is by nature not specifiable.
This is an issue because match type reduction spans across TASTy files (unlike, for example, type inference or GADTs), which can and will lead to old TASTy files not to be linked again in future versions of the compiler.

This SIP proposes a proper specification for match types, which does not involve type inference.
It is based on `baseType` computations and subtype tests involving only fully-defined types.
That is future-proof, because `baseType` and subtyping are defined in the specification of the language.

The proposed specification defines a subset of current match types that are considered legal.
Legal match types use the new, specified reduction rules.
Illegal match types are rejected, which is a breaking change, and can be recovered under `-source:3.3`.

## Motivation

Currently, match type reduction is implementation-defined.
Matching a scrutinee type `X` against a pattern `P` with captures `ts` works as follows:

1. we create new type variables for the captures `ts'` giving a pattern `P'`,
2. we ask the compiler's `TypeComparer` (the type inference black blox) to "try and make it so" that `X <:< P'`,
3. if it manages to do so, we get constraints for `ts'`; we then have a match, and we instantiate the body of the pattern with the received constraints for `ts`.

The problem with this approach is that, by essence, type inference is an unspecified black box.
There are *guidelines* about how it should behave in common cases, but no actual guarantee.
This is fine everywhere else in the language, because what type inference comes up with is stored once and for all in TASTy.
When we read TASTy files, we do not have to perform the work of type inference again; we reuse what was already computed.
When a new version of the compiler changes type inference, it does not change what was computed and stored in TASTy files by previous versions of the compiler.

For match types, this is a problem, because reduction spans across TASTy file.
In order to guarantee compatibility, we must ensure that, for any given match type:

* if it reduces in a given way in verion 1 of the compiler, it still reduces in the same way in version 2, and
* if it decides disjointness in version 1, it still decides disjointness in version 2.

By delegating reduction to the `TypeComparer` black box, it is in practice impossible to guarantee the former.

In order to solve this problem, this SIP provides a specification for match type reduction that is independent of the `TypeComparer` black box.
It defines a subset of match type cases that are considered legal.
Legal cases get a specification for when and how they should reduce for any scrutinee.
Illegal cases are rejected as being outside of the language.
For compatibility reasons, they can still be accepted with `-source:3.3`; in that case, they reduce using the existing, unspecified (and prone to breakage) implementation.

For legal cases, the proposed reduction specification should reduce in the same way as the current implementation for the majority of cases.
That is however not possible to guarantee, since the existing implementation is not specified in the first place.

## Proposed solution

### High-level overview

By its nature, this proposal only contains a specification, without any high level overview.

### Specification

#### Syntax

Syntactically, nothing changes.
The way that a pattern is parsed and type captures identified is kept as is.

Once type captures are identified, we can represent the *abstract* syntax of a pattern as follows:

```
MatchTypePattern ::= TypeWithoutCapture
                   | MatchTypeAppliedPattern

MatchTypeAppliedPattern ::= TyconWithoutCapture ‘[‘ MatchTypeSubPattern { ‘,‘ MatchTypeSubPattern } ‘]‘

MatchTypeSubPattern ::= TypeCapture
                      | TypeWithoutCapture
                      | MatchTypeAppliedPattern

TypeCapture ::= NamedTypeCapture
              | WildcardTypeCapture
```

The cases `MatchTypeAppliedPattern` are only chosen if they contain at least one `TypeCapture`.
Otherwise, they are considered `TypeWithoutCapture` instead.
Each named capture appears exactly once.

#### Legal patterns

A `MatchTypePattern` is legal if and only if one of the following is true:

* It is a `TypeWithoutCapture`, or
* It is a `MatchTypeAppliedPattern` with a legal `TyconWithoutCapture` and each of its arguments is either:
  * A `TypeCapture`, or
  * A `TypeWithoutCapture`, or
  * The type constructor is *covariant* in that parameter, and the argument is recursively a legal `MatchTypeAppliedPattern`.

A `TyconWithoutCapture` is legal if one of the following is true:

* It is a *class* type constructor, or
* It is the `scala.compiletime.ops.int.S` type constructor, or
* It is an *abstract* type constructor, or
* It is a refined type of the form `Base { type Y = t }` where:
  * `Base` is a `TypeWithoutCapture`,
  * There exists a type member `Y` in `Base`, and
  * `t` is a `TypeCapture`.
* It is a type alias to a type lambda such that:
  * Its bounds contain all possible values of its arguments, and
  * When applied to the type arguments, it beta-reduces to a new legal `MatchTypeAppliedPattern` that contains exactly one instance of every type capture present in the type arguments.

#### Examples of legal patterns

Given the following definitions:

```scala
class Inv[A]
class Cov[+A]
class Contra[-A]

class Base {
  type Y
}

type YExtractor[t] = Base { type Y = t }
type ZExtractor[t] = Base { type Z = t }

type IsSeq[t <: Seq[Any]] = t
```

Here are example of legal patterns:

```scala
// TypeWithoutCapture's
case Any => // also the desugaring of `case _ =>` when the _ is at the top-level
case Int =>
case List[Int] =>
case Array[String] =>

// Class type constructors with direct captures
case scala.collection.immutable.List[t] => // not Predef.List; it is a type alias
case Array[t] =>
case Contra[t] =>
case Either[s, t] =>
case Either[s, Contra[Int]] =>
case h *: t =>
case Int *: t =>

// The S type constructor
case S[n] =>

// An abstract type constructor
// given a [F[_]] or `type F[_] >: L <: H` in scope
case F[t] =>

// Nested captures in covariant position
case Cov[Inv[t]] =>
case Cov[Cov[t]] =>
case Cov[Contra[t]] =>
case Array[h] *: t => // sugar for *:[Array[h], t]
case g *: h *: EmptyTuple =>

// Type aliases
case List[t] => // which is Predef.List, itself defined as `type List[+A] = scala.collection.immutable.List[A]`

// Refinements (through a type alias)
case YExtractor[t] =>
```

The following patterns are *not* legal:

```scala
// Type capture nested two levels below a non-covariant type constructor
case Inv[Cov[t]] =>
case Inv[Inv[t]] =>
case Contra[Cov[t]] =>

// Type constructor with bounds that do not contain all possible instantiations
case IsSeq[t] =>

// Type refinement where the refined type member is not a member of the parent
case ZExtractor[t] =>
```

#### Matching

Given a scrutinee `X` and a match type case `case P => R` with type captures `ts`, matching proceeds in three steps:

1. Compute instantiations for the type captures `ts'`, and check that they are *specific* enough.
2. If successful, check that `X <:< [ts := ts']P`.
3. If successful, reduce to `[ts := ts']R`.

The instantiations are computed by the recursive function `matchPattern(X, P, variance, scrutIsWidenedAbstract)`.
At the top level, `variance = 1` and `scrutIsWidenedAbstract = false`.

`matchPattern` behaves according to what kind is `P`:

* If `P` is a `TypeWithoutCapture`:
  * Do nothing (always succeed).
* If `P` is a `WildcardCapture` `ti = _`:
  * If `X` is of the form `_ >: L <: H`, instantiate `ti := H` (anything between `L` and `H` would work here),
  * Otherwise, instantiate `ti := X`.
* If `P` is a `TypeCapture` `ti`:
  * If `X` is of the form `_ >: L <: H`,
    * If `scrutIsWidenedAbstract` is `true`, fail as not specific.
    * Otherwise, if `variance = 1`, instantiate `ti := H`.
    * Otherwise, if `variance = -1`, instantiate `ti := L`.
    * Otherwise, fail as not specific.
  * Otherwise, if `variance = 0` or `scrutIsWidenedAbstract` is `false`, instantiate `ti := X`.
  * Otherwise, fail as not specific.
* If `P` is a `MatchTypeAppliedPattern` of the form `T[Qs]`:
  * Assert: `variance = 1` (from the definition of legal patterns).
  * If `T` is a class type constructor of the form `p.C`:
    * If `baseType(X, C)` is not defined, fail as not matching.
    * Otherwise, it is of the form `q.C[Us]`.
    * If `p =:= q` is false, fail as not matching.
    * Let `innerScrutIsWidenedAbstract` be true if either `scrutIsWidenedAbstract` or `X` is not a concrete type.
    * For each pair of `(Ui, Qi)`, compute `matchPattern(Ui, Qi, vi, innerScrutIsWidenedAbstract)` where `vi` is the variance of the `i`th type parameter of `T`.
  * If `T` is `scala.compiletime.ops.int.S`:
    * If `n = natValue(X)` is undefined or is `Int.MinValue`, fail as not matching.
    * Otherwise, compute `matchPattern(n, Q1, 1, scrutIsWidenedAbstract)`.
  * If `T` is an abstract type constructor:
    * If `X` is not of the form `F[Us]` or `F =:= T` is false, fail as not matching.
    * Otherwise, for each pair of `(Ui, Qi)`, compute `matchPattern(Ui, Qi, vi, scrutIsWidenedAbstract)` where `vi` is the variance of the `i`th type parameter of `T`.
  * If `T` is a refined type of the form `Base { type Y = ti }`:
    * Let `q` be `X` if `X` is a stable type, or the skolem type `∃α:X` otherwise.
    * If `q` does not have a type member `Y`, fail as not matching (that implies that `X <:< Base` is false, because `Base` must have a type member `Y` for the pattern to be legal).
    * If `q.Y` is abstract or is a class definition, fail as not specific.
    * Otherwise, the underlying type definition of `q.Y` is of the form `= U`.
    * If `q` is a skolem type `∃α:X` and `U` refers to `α`, fail as not specific.
    * Compute `matchPattern(t, U, 0, scrutIsWidenedAbstract)`.
  * If `T` is a concrete type alias to a type lambda:
    * Let `P'` be the beta-reduction of `P`.
    * Compute `matchPattern(P', X, variance, scrutIsWidenedAbstract)`.

#### Disjointness

This proposal does not affect the check for provably disjoint types in match types.
If a case is legal and does not match, the existing disjointness check is used to decide whether we can move on to the next case.

### Compatibility

Compatibility is inherently tricky to evaluate for this proposal, and even to define.
One could argue that, from a pure specification point of view, it breaks nothing since it only specifies things that were unspecified before.
However, that is not very practical.
In practice, this proposal definitely breaks some code that compiled before, due to making some patterns illegal.
In exchange, it promises that all the patterns that are considered legal will keep working in the future; which is not the case with the current implementation, even for the legal subset.

In order to evaluate the practical impact of this proposal, we conducted a quantitative analysis of *all* the match types found in Scala 3 libraries published on Maven Central.
We used [Scaladex](https://index.scala-lang.org/) to list all Scala 3 libraries, [coursier](https://get-coursier.io/docs/api) to resolve their classpaths, and [tasty-query](https://github.com/scalacenter/tasty-query) to semantically analyze the patterns of all the match types they contain.

Out of 4,783 libraries that were found and analyzed, 49 contained at least one match type definition.
These 49 libraries contained a total of 779 match type `case`s.
Of those, there were 8 `case`s that would be flagged as not legal by the current proposal.

These can be categorized as follows:

* 2 libraries with 1 type member extractor each where the `Base` does not contain `Y`; they are both to extract `SomeEnumClass#Value` (from Scala 2 `scala.Enumeration`-based "enums").
  * https://github.com/iheartradio/ficus/blob/dcf39d6cd2dcde49b093ba5d1507ca478ec28dac/src/main/scala-3/net/ceedubs/ficus/util/EnumerationUtil.scala#L4-L8
  * https://github.com/json4s/json4s/blob/5e0b92a0ca59769f3130e081d0f53089a4785130/ext/src/main/scala-3/org/json4s/ext/package.scala#L4-L8
* 1 library used to have 2 cases of the form `case HKExtractor[f] =>` with `type KHExtractor[f[_, _]] = Base { type Y[a, b] = f[a, b] }`.
  * Those used to be at https://github.com/7mind/idealingua-v1/blob/48d35d53ce1c517f9f0d5341871e48749644c105/idealingua-v1/idealingua-v1-runtime-rpc-http4s/src/main/scala-3/izumi/idealingua/runtime/rpc/http4s/package.scala#L10-L15 but they do not exist in the latest version of the library.
* 1 library used to have 1 `&`-type extractor (which "worked" who knows how?):
  https://github.com/Katrix/perspective/blob/f1643ac7a4e6a0d8b43546bf7b9e6219cc680dde/dotty/derivation/src/main/scala/perspective/derivation/Helpers.scala#L15-L18
  but the author already accepted a change with a workaround at
  https://github.com/Katrix/perspective/pull/1
* 1 library has 3 occurrences of using an abstract type constructor too "concretely":
  https://github.com/kory33/s2mc-test/blob/d27c6e85ad292f8a96d7d51af7ddc87518915149/protocol-core/src/main/scala/io/github/kory33/s2mctest/core/generic/compiletime/Tuple.scala#L16
  defined at https://github.com/kory33/s2mc-test/blob/d27c6e85ad292f8a96d7d51af7ddc87518915149/protocol-core/src/main/scala/io/github/kory33/s2mctest/core/generic/compiletime/Generic.scala#L12
  It could be replaced by a concrete `class Lock[A](phantom: A)` instead.

The only case for which there exists no workaround that we know of is the extractor for `scala.Enumeration`-based `Value` classes.

### Other concerns

Ideally, this proposal would be first implemented as *warnings* about illegal cases, and only later made errors.
Unfortunately, the presence of the abstract type constructor case makes that impossible.
Indeed, because of it, a pattern that is legal at definition site may become illegal after some later substitution.

Consider for example the standard library's very own `Tuple.InverseMap`:

```scala
/** Converts a tuple `(F[T1], ..., F[Tn])` to `(T1,  ... Tn)` */
type InverseMap[X <: Tuple, F[_]] <: Tuple = X match {
  case F[x] *: t => x *: InverseMap[t, F]
  case EmptyTuple => EmptyTuple
}
```

If we instantiate `InverseMap` with a class type parameter, such as `InverseMap[X, List]`, the first case gets instantiated to
```scala
case List[x] *: t => x *: InverseMap[t, List]
```
which is legal.

However, nothing prevents us a priori to instantiate `InverseMap` with an illegal type constructor, for example
```scala
type IsSeq[t <: Seq[Any]] = t
InverseMap[X, IsSeq]
```
which gives
```scala
case IsSeq[x] *: t => x *: InverseMap[t, IsSeq]
```

These instantiatiations happen deep inside the type checker, during type computations.
Since types are cached, shared and reused in several parts of the program, by construction, we do not have any source code position information at that point.
That means that we cannot report *warnings*.

We can in fact report *errors* by reducing to a so-called `ErrorType`, which is aggressively propagated.
This is what we do in the proposed implementation (unless using `-source:3.3`).

### Open questions

None at this point.

## Alternatives

The specification is more complicated than we initially wanted.
At the beginning, we were hoping that we could restrict match cases to class type constructors only.
The quantitative study however revealed that we had to introduce support for abstract type constructors and for type member extractors.

As already mentioned, the standard library itself contains an occurrence of an abstract type constructor in a pattern.
Making that an error would mean declaring the standard library itself bankrupt, which was not a viable option.

We tried to restrict abstract type constructor to never match on their own.
Instead, we wanted them to stay *stuck* until they could be instantiated to a concrete type constructor.
However, that led some existing tests to fail even for match types that were declared legal, because they did not reduce anymore in some places where they reduced before.

Type member extractors are our biggest pain point.
Their specification is complicated, and the implementation as well.
Our quantitative study showed that they were however "often" used (10 occurrences spread over 4 libraries).
In each case, they seem to be a way to express what Scala 2 type projections (`A#T`) could express.
While not quite as powerful as type projections (which were shown to be unsound), match types with type member extractors delay things enough for actual use cases to be meaningful.

As far as we know, those use cases have no workaround if we make type member extractors illegal.

## Related work

This section should list prior work related to the proposal, notably:

- [Current reference page for Scala 3 match types](https://dotty.epfl.ch/docs/reference/new-types/match-types.html)
- ["Pre-Sip" discussion in the Contributors forum](https://contributors.scala-lang.org/t/pre-sip-proper-specification-for-match-types/6265) (submitted at the same time as this SIP document)
- [PR with the proposed implementation](https://github.com/lampepfl/dotty/pull/18262)

## FAQ

None at this point.
