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

| Date          | Version                                                                                   |
|---------------|-------------------------------------------------------------------------------------------|
| Jul 27th 2026 | Initial Draft                                                                             |
| Jul 28th 2026 | Scoped down to single-member capture per refinement; corrected the Compatibility section  |

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
A captured member can be freely mixed with ordinary, non-captured refinements in the same pattern.
Capturing more than one member of the same refinement is not yet supported by the draft implementation; see [Open questions](#open-questions).

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

A bare lowercase identifier on the right-hand side of a type-member refinement, written inside a match type case pattern, acts as a pattern-bound type variable, governed by the same rules as var-patterns already used in type-argument position: it must be a bare lowercase identifier, and it must not already be captured elsewhere in the same case pattern.
As with any other pattern-bound type variable, it shadows any type of the same name visible in the enclosing scope for the extent of the case; it does not refer to that outer type.

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

Captured and fixed (ordinary, non-captured) refinements can be freely mixed:

```scala
type MT[x <: SomeTrait] = x match
  case SomeTrait { type Concrete = Int; type Abstract = a } => a
```

Here, `type Concrete = Int` behaves exactly as it does today: it is part of the structural type the scrutinee is checked against.
Only `type Abstract = a` introduces a pattern-bound type variable, because `a` is a bare lowercase identifier that is not itself already captured elsewhere in the pattern.

Capturing more than one member of the *same* refinement, e.g.

```scala
trait KV:
  type Key
  type Value

type PairOf[K <: KV] = K match
  case KV { type Key = k; type Value = v } => (k, v)
```

is not supported by the draft implementation as it stands; see [Open questions](#open-questions).

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

Formally: whenever the typer sees a refined type `Base { R_1; ...; R_n }` in a match type case pattern, it looks for members of the form `type Y = a` where `a` is a bare lowercase identifier, a var-pattern, exactly as already recognized for `case List[a] => ...`.
Given exactly one such member, it rewrites

```scala
Base { ...; type Y = a; ... }
```

to

```scala
([a] =>> Base { ...; type Y = a; ... })[a]
```

before typing continues.
Fixed, non-capturing members such as `type Concrete = Int` are left untouched inside the refinement; only the member whose right-hand side is a bare identifier takes part in the rewrite.

From here on, nothing new happens for the single-capture case.
The result is a legal `TyconWithoutCapture` applied to a capture, so typing proceeds exactly as it already does for `BoxOf[c]`, with bounds inferred the same way.
Because the desugared tree is indistinguishable from what a user would write with a named, single-parameter alias, **the matching algorithm and the reduction algorithm specified in SIP 56 need no change** for this case: both already handle it.

The `provablyDisjoint` relation is the one place where the single-capture restriction actually matters, not just an implementation convenience.
Its recognition of a captured refinement member (`core/Types.scala`, `tryConvertToSpecPattern`) is what lets a match type reduce over an *abstract*, bounded scrutinee such as `K <: KV` — not just over concrete instantiations — by proving the case pattern and any sibling cases are disjoint without knowing `K` concretely.
Extending that recognition from one captured member to several is exactly the part of SIP 56's algorithm that would need to change, contrary to the claim above; see [Open questions](#open-questions) for why this proposal does not attempt it yet.

The user-visible rule, matching the pre-SIP, is:

- A bare lowercase identifier `a` on the right-hand side of a type-member refinement, written inside a match type case pattern, is a pattern-bound type variable, provided it is not already captured elsewhere in the same case pattern. Like any other pattern-bound type variable, it shadows a type of the same name from the enclosing scope, if one exists, rather than referring to it.
- At most one such captured member may appear per refinement in this proposal; captured members may still be freely mixed with ordinary, non-captured refinement members.
- As with any other pattern-bound type variable, each captured name must appear exactly once in the whole case pattern.

### Compatibility

This is a strictly additive change: it has no effect on the meaning of any currently valid program.

The only case where old and new behavior could differ is a bare lowercase identifier on the right-hand side of a refinement inside a case pattern.
Today, without this proposal, the compiler looks it up as an ordinary type reference: if no type of that name is in scope, the program fails to compile today with "not found: type t".
Under this proposal it compiles instead, with `t` bound as a pattern-bound type variable.
A compile error turning into a successful compile can't break any program that worked before.

If a type of that same name happens to be visible in the enclosing scope, this proposal does *not* fall back to treating the identifier as a reference to that outer type.
It is captured regardless, shadowing the outer type for the extent of the case, exactly as the pre-existing var-pattern mechanism for type arguments already does (`case List[c] => ...` captures a fresh `c` even when a type named `c` is visible in the enclosing scope; it does not type-test against it).
This is consistent with ordinary pattern-matching scoping elsewhere in Scala, where a pattern-bound name always shadows an outer binding of the same name rather than referring to it, and it does not change the meaning of the outer definition itself, only what the name resolves to for the extent of the case.

SIP 56 already specifies the matching semantics for a captured refinement member; only the concrete syntax to reach them changes.
No new TASTy encoding is required: the compiler already represents and serializes the refined-tycon-with-capture shape today, for match types that reach it through a type-lambda alias.
This proposal only adds a second, direct source-level path to the same internal representation, through an anonymous rather than a named type lambda.
Binary and TASTy compatibility hold by construction.

### Feature Interactions

?

### Other concerns

?

### Open questions

- **Capturing more than one member of the same refinement** (e.g. `KV { type Key = k; type Value = v }`) is not supported by the draft implementation.
  `tryConvertToSpecPattern` in `core/Types.scala` only recognizes a single captured refinement member: its `RefinedType` case (`parent.member(refinedName)`) never recurses into `parent` when `parent` is itself a refinement carrying another capture, and its type-lambda-alias case is restricted to `HKTypeLambda(onlyParam :: Nil, ...)`, i.e. exactly one type parameter.
  A case with two or more captured members in one refinement currently fails to compile with "the pattern contains an unaccounted type parameter".
  Two rewrites were considered to route around this in the typer instead of touching `tryConvertToSpecPattern`:
  - Desugaring into nested match types, each capturing one member (`case KV { type Key = k } => K match { case KV { type Value = v } => (k, v) }`). This compiles, since each level only needs the already-supported single-capture path, but it changes what `provablyDisjoint` sees: the outer case's derived pattern would only reflect the `Key` capture, silently dropping the `Value` constraint from the shape used for disjointness/reduction reasoning over an abstract scrutinee. For a match type with a single case this is likely harmless, but for a match type with several cases it risks the compiler treating this case as disjoint from a sibling case it does not actually appear disjoint from once `Value` is taken into account.
  - Extracting each captured member through its own single-capture helper and substituting it into the body (a "tuple of extractors" encoding). This avoids the disjointness risk but requires substituting pattern-variable references through an arbitrary body expression, not just a tuple literal, which is substantially more general than the single-capture case.
  Properly supporting multiple captures per refinement therefore looks like it needs `tryConvertToSpecPattern` itself to recognize an arbitrary chain of nested `RefinedType` captures (and the alias case to accept an N-ary `HKTypeLambda`), rather than a purely syntactic desugaring in the typer. This is deferred to a follow-up.

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

