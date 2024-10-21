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

| Date          | Version                                                   |
|---------------|-----------------------------------------------------------|
| Oct 3rd 2024  | Initial Draft                                             |
| Oct 3rd 2024  | Related Work                                              |
| Oct 4th 2024  | Add paragraph about using a type check instead of equals  |
| Oct 7th 2024  | Add paragraph about using `unapply` instead of equals     |
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

The proposed solution is to not require a `CanEqual` instance during  pattern matching when:
 - the scrutinee's type is a `sealed` type and the pattern is a `case object` that extends the scrutinee's type, or
 - the scrutinee's type is an `enum` type and the pattern is one of the enum's cases without a parameter list (e. g. `Nat.Z`)

### Compatibility

This change creates no new compatibility issues and improves the compatibility of the `strictEquality` feature with existing code bases.

## Alternatives

1. It was proposed to instead change the `enum` feature so that it always includes an implicit `derives CanEqual` clause. This is unsatisfactory for many reasons:
   - doesn't work for sealed types
   - doesn't work for 3rd party libraries compiled with an older compiler
   - `CanEqual` might be unwanted for that type – just because I want to perform pattern matching against an `enum` type doesn't mean I want to allow usage of `==`

1. It was proposed to change the behaviour of pattern matching from an `==` comparison to a type check, i. e. make `case Foo =>` equivalent to `case _: Foo.type =>`.
   - pro: the behaviour would be more consistent between `case class` and `case object` matching as matching against a `case class` also does a type check
   - contra: it is a backward incompatible change. A prominent example is `Nil`, whose `equals` method is overridden to return true for empty collections, even if these collections aren't of type `List`. Changing the behaviour would break such code
     - we could mostly avoid this by only doing the type check behaviour in the cases outlined above (i. e. scrutinee is `sealed` or `enum` and pattern is one of the `case`s or `case object`s), while retaining the equality check behaviour for cases like matching a `Vector` against `Nil`. But then pattern matching behaviour would be inconsistent depending on the types involved and we would only replace one inconsistency with another
   - the author's opinion is that, while this is an approach that he might have chosen in a new language, the practical benefits over the existing behaviour are marginal and that therefore the compatibility concerns outweigh them in this case
1. It was proposed to change the behaviour of `case object` so that it adds a suitable `def unapply(n: Nat): Boolean` method and to have `case Foo =>` invoke the `unapply` method (like `case Foo() =>` does today) if one exists, falling back to `==` otherwise
   - pro: more consistent behaviour between `case object` and `case class` as `unapply` would be used in both cases
   - contra: behaviour of `match` statements now depends on *both* the version of the compiler that you're using *and* the compiler used to compile the ADT.
   - contra: incompatible change. If your `case object` has an overridden `equals` method (like e. g. `Nil` does), you now need to define an `unapply` method that delegates to `equals`, otherwise your code will break. 
   - authors opinion: same as for 2. Fine if this was a new language, but the benefits aren't huge and practical compatibility concerns matter more.
 
## Related Work
 - https://contributors.scala-lang.org/t/pre-sip-better-strictequality-support-in-pattern-matching/6781
 - https://contributors.scala-lang.org/t/how-to-improve-strictequality/6722
 - https://contributors.scala-lang.org/t/enumeration-does-not-derive-canequal-for-strictequality/5280
