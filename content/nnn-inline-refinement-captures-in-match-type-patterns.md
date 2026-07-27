---
layout: sip
number: NN
permalink: /sips/:number.html
redirect_from:
  - /sips/:title.html
  - /sips/:number
stage: implementation
status: waiting-for-implementation
presip-thread: https://contributors.scala-lang.org/t/pre-sip-inline-refinement-captures-in-match-type-patterns/7481
title: Inline Refinement Captures in Match Type Patterns
---

**By: Bartłomiej Kozak**

## History

| Date          | Version       |
|---------------|---------------|
| Jul 27th 2026 | Initial Draft |

## Summary

This SIP proposes allowing match type case patterns to capture the abstract type members of a refined type directly, without going through an intermediate type alias.

Today, capturing an abstract type member requires introducing a named extractor alias:

```scala
trait Box:
  type Content

type BoxOf[c] = Box { type Content = c }

type Unbox[B <: Box] = B match
  case BoxOf[x] => x
```

This proposal allows the refinement to be written inline in the pattern instead:

```scala
type Unbox[B <: Box] = B match
  case Box { type Content = x } => x
```

The change is a small, local desugaring, not a new pattern shape.
[SIP 56](056-match-types-spec.md) already treats `Base { type Y = t }` as legal whenever it arises from beta-reducing a type-lambda alias, which is exactly what the `BoxOf` workaround does above.
This proposal reaches the same, already-legal shape directly: the typer synthesizes the equivalent anonymous type lambda whenever a refinement in a case pattern captures a member.
It also allows several captured members, mixed freely with ordinary, non-captured refinements, in a single pattern.

## Motivation

Match types already support capturing type arguments through var-patterns: bare lowercase identifiers in type-argument position become pattern-bound type variables.

```scala
type Elem[X] = X match
  case List[a] => a // `a` is a pattern-bound type variable
```

However, capturing an *abstract type member* of a refined type is not supported directly.
The only way to do it today is to factor the refinement out into a standalone type alias and match against that alias applied to a var-pattern:

```scala
trait Box:
  type Content

type BoxOf[c] = Box { type Content = c }

type Unbox[B <: Box] = B match
  case BoxOf[x] => x
```

`BoxOf[x]` carries no information beyond what `Box { type Content = x }` already expresses on its own; it exists only so the pattern has an `Ident[Args]` shape the parser recognizes as applied.
This indirection is a pure syntactic tax, with concrete drawbacks:

- It pollutes the namespace of the enclosing type or object with a definition that exists only to be pattern-matched on.
- In a library, the alias leaks an implementation detail (the shape of the refinement) into the public API surface, since it must be at least as visible as the match type that uses it.
- It forces the reader of the match type to jump to a separate definition to understand what the pattern actually captures, rather than seeing the refinement in place.
- It gets worse as more members need to be captured: each additional captured member is either folded into the same alias (further obscuring what the match type itself is doing) or requires yet another alias.

This proposal removes the indirection by allowing the refinement to be written directly in the `case` pattern.

## Proposed solution

### High-level overview

A bare lowercase identifier on the right-hand side of a type-member refinement, written inside a match type case pattern, acts as a pattern-bound type variable, governed by the same rules as var-patterns already used in type-argument position: it must be a bare lowercase identifier not already bound to a named type in scope.

Basic extraction, without the auxiliary alias:

```scala
trait Box:
  type Content

type Unbox[B <: Box] = B match
  case Box { type Content = c } => c

class StringBox extends Box:
  type Content = String

val _: Unbox[StringBox] = "hello" // OK
```

Multiple members can be captured in a single refinement pattern:

```scala
trait KV:
  type Key
  type Value

type PairOf[K <: KV] = K match
  case KV { type Key = k; type Value = v } => (k, v)
```

Captured and fixed (ordinary, non-captured) refinements can be freely mixed:

```scala
type MT[x <: SomeTrait] = x match
  case SomeTrait { type Concrete = Int; type Abstract = a } => a
```

In the last example, `type Concrete = Int` behaves exactly as it does today: it is part of the structural type the scrutinee is checked against.
Only `type Abstract = a` introduces a pattern-bound type variable, because `a` is a bare lowercase identifier not already bound to a type.

### Specification

The [draft implementation](https://github.com/scala/scala3/pull/26381) does not add a new pattern shape to [SIP 56](056-match-types-spec.md)'s grammar of legal match type patterns.
It reuses a shape SIP 56 already treats as legal.

SIP 56 already allows a `TyconWithoutCapture` to be a type alias to a type lambda, applied to the captures.
That is exactly how today's `BoxOf[c]` workaround works, spelled out in full:

```scala
// The alias:
type BoxOf[c] = Box { type Content = c }
// is just a name for this anonymous type lambda:
type BoxOf = [c] =>> Box { type Content = c }

// So the case pattern:
case BoxOf[x] => x
// really means:
case ([c] =>> Box { type Content = c })[x] => x
```

`c` is the alias's own type parameter; `x` is the name the caller happens to capture with, and the two are independent, exactly as they are for any type alias applied to an argument.

This proposal produces that same final shape directly, without a `BoxOf` alias ever being written.
Since there is no alias here, there is no separate internal name either: the one name the user writes fills both roles.
`case Box { type Content = x } => x` desugars to `case ([x] =>> Box { type Content = x })[x] => x`.
The typer does the work the user would otherwise do by hand: wrap the refinement in a type lambda over its captured members, then apply that lambda straight back to them.

Formally: whenever the typer sees a refined type `Base { R_1; ...; R_n }` in a match type case pattern, it looks for members of the form `type Y = a` where `a` is a bare, unbound lowercase identifier, a var-pattern, exactly as already recognized for `case List[a] => ...`.
Given at least one such member, it rewrites

```scala
Base { ...; type Y_1 = a_1; ...; type Y_k = a_k; ... }
```

to

```scala
([a_1, ..., a_k] =>> Base { ...; type Y_1 = a_1; ...; type Y_k = a_k; ... })[a_1, ..., a_k]
```

before typing continues.
Fixed, non-capturing members such as `type Concrete = Int` are left untouched inside the refinement; only members whose right-hand side is a bare, unbound identifier take part in the rewrite.

From here on, nothing new happens.
The result is a legal `TyconWithoutCapture` applied to captures, so typing proceeds exactly as it already does for `BoxOf[c]`, with bounds inferred the same way.
Because the desugared tree is indistinguishable from what a user would write with a named alias, **the matching algorithm, the `provablyDisjoint` relation, and the reduction algorithm specified in SIP 56 need no change**: all three already handle it.

The user-visible rule, matching the pre-SIP, is:

- A bare lowercase identifier `a` on the right-hand side of a type-member refinement, written inside a match type case pattern, is a pattern-bound type variable if it is not already bound to a named type in scope.
- Any number of such captured members may appear in the same refinement, freely mixed with ordinary, non-captured refinement members.
- As with any other pattern-bound type variable, each captured name must appear exactly once in the whole case pattern.

### Compatibility

This is a strictly additive change: it has no effect on the meaning of any currently valid program.

The only case where old and new behavior could differ is a bare lowercase identifier on the right-hand side of a refinement inside a case pattern.
Today, the compiler looks it up as an ordinary type reference:

- If no type of that name is in scope, the program fails to compile today with "not found: type t".
  Under this proposal it compiles instead, with `t` bound as a pattern-bound type variable.
  A compile error turning into a successful compile can't break any program that worked before.
- If a type of that name *is* in scope, the compiler already treats it as an ordinary, non-capturing refinement member today, exactly as it does for var-patterns in type-argument position (`case List[c] => ...` does not capture `c` if `c` is already a type in scope).
  This proposal keeps that rule unchanged: a capture is introduced only when the identifier is not already bound to a named type.

SIP 56 already specifies the matching semantics for a captured refinement member; only the concrete syntax to reach them changes.
No new TASTy encoding is required: the compiler already represents and serializes the refined-tycon-with-capture shape today, for match types that reach it through a type-lambda alias.
This proposal only adds a second, direct source-level path to the same internal representation, through an anonymous rather than a named type lambda.
Binary and TASTy compatibility hold by construction.

### Feature Interactions

?

### Other concerns

?

### Open questions

?

## Alternatives

- **Keep the status quo** (the type-alias indirection). Rejected because of the drawbacks listed under Motivation: namespace pollution, API leakage, and loss of locality between the pattern and what it captures.
- **Allow inline bound annotations on captured members** (e.g. `type Content = c <: String`) as part of this proposal.
  Deferred as an open question rather than folded into the proposal, to keep the initial change minimal and consistent with how var-patterns behave elsewhere in match types.

## Related work

- [Pre-SIP discussion](https://contributors.scala-lang.org/t/pre-sip-inline-refinement-captures-in-match-type-patterns/7481)
- [SIP 56: Proper Specification for Match Types](056-match-types-spec.md), whose legality clause for refined-tycon type-lambda aliases this proposal reuses
- [SIP 53: Quote pattern explicit type variable syntax](053-quote-pattern-type-variable-syntax.md), prior art for extending var-pattern capture syntax elsewhere in the language
- [Draft implementation](https://github.com/scala/scala3/pull/26381)

## FAQ

