---
layout: sip
permalink: /sips/:title.html
stage: implementation
status: waiting-for-implementation
presip-thread: https://contributors.scala-lang.org/t/pre-sip-better-strictequality-support-in-pattern-matching/6781
title: SIP-NN - Strict-Equality pattern matching
---

**By: Matthias Berndt**

## History

| Date          | Version            |
|---------------|--------------------|
| Oct 3rd  2024 | Initial Draft      |

## Summary

This proposal aims to make the `strictEquality` feature easier to adopt by avoiding the need for a `CanEqual` instance
when matching a `sealed` or `enum` type against singleton cases (e. g. `Nil` or `None`).

## Motivation

The `strictEquality` feature is important to improve type safety. However due to the way that pattern matching in
Scala works, it often requires `CanEqual` instances where they conceptually don't really make sense, as evidenced
by the fact that in e. g. Haskell, an `Eq` instance is never required to perform a pattern matching.
It also seems arbitrary that a `CanEqual` instance is required to match on types such as `Option` or `List` but
not for e. g. `Either`.


A simple example is this code:

```scala
import scala.language.strictEquality

enum Nat:
  case Zero
  case Succ(n: Nat)

extension(l: Nat) def +(r: Nat): Nat =
  l match
    case Nat.Zero => r
    case Nat.Succ(x) => Nat.Succ(x + r)
```
This fails to compile with the following error message:

```
[error] ./nat.scala:9:10
[error] Values of types Nat and Nat cannot be compared with == or !=
[error]     case Nat.Zero => r
[error]          ^^^^^^^^
```
### Possible fixes today
 - add a `derives CanEqual` clause to the ADT definition. This is unsatisfactory for multiple reasons:
   - it is additional boilerplate code that needs to be added in potentially many places when enabling this option, thus hindering adoption
   - the ADT might not be under the user's control, e. g. defined in a 3rd party library
   - one might not *want* a `CanEqual` instance to be available for this type because one doesn't want this type to be compared with the `==`
     operator. For example, when one of the fields in the `enum` is a function, it actually isn't possible to perform a meaningful equality check.
 - turn the no-argument-list cases into empty-argument-list cases:
   ```scala
   enum Nat:
     case Zero() // notice the parens
     case Succ(n: Nat)
   ```
   The downsides are similar to the previous point:
   - doesn't work for ADTs defined in a library
   - hinders adoption in existing code bases by requiring new syntax (even more so, because now you not only need to change the `enum` definition but also every `match` and `PartialFunction` literal)
   - uglier than before
   - pointless overhead: can have more than one `Zero()` object at run-time
 - perform a type check instead:
   ```scala
    l match
      case _: Nat.Zero.type => r
      case Nat.Succ(x) => Nat.Succ(x + r)
   ```
   But like the previous solutions:
   - hinders adoption in existing code bases by requiring new syntax
   - looks uglier than before (even more so than the empty-argument-list thing)
     
For these reasons the current state of affairs is unsatisfactory and needs to improve in order to encourage adoption of `strictEquality` in existing code bases.
## Proposed solution

### Specification

The proposed solution is to perform an equality check without requiring a `CanEqual` instance when pattern matching when:
 - the scrutinee's type is a `sealed` type and the pattern is a `case object` that extends the scrutinee's type, or
 - the scrutinee's type is an `enum` type and the pattern is one of the enum's cases without a parameter list (e. g. `Nat.Z`)

### Compatibility

This change creates no new compatibility issues and improves the compatibility of the `strictEquality` feature with existing code bases.

## Alternatives

It was proposed to instead change the `enum` feature so that it always includes an implicit `derives CanEqual` clause. This is unsatisfactory for many reasons:
 - doesn't work for sealed types
 - doesn't work for 3rd party libraries compiled with an older compiler
 - `CanEqual` might be unwanted for that type – just because I want to perform pattern matching against an `enum` type doesn't mean I want to allow usage of `==`
 
## Related Work
 - https://contributors.scala-lang.org/t/pre-sip-better-strictequality-support-in-pattern-matching/6781
 - https://contributors.scala-lang.org/t/how-to-improve-strictequality/6722
 - https://contributors.scala-lang.org/t/enumeration-does-not-derive-canequal-for-strictequality/5280

## FAQ

