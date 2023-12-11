---
layout: sip
permalink: /sips/:title.html
stage: implementation
status: waiting-for-implementation
presip-thread: https://contributors.scala-lang.org/t/pre-sip-replace-non-sensical-unchecked-annotations/6342
title: SIP-57 - Replace non-sensical @unchecked annotations
---

**By: Martin Odersky and Jamie Thompson**

## History

| Date          | Version            |
|---------------|--------------------|
| Dec 8th 2023  | Initial Draft      |

## Summary

We propose to replace the mechanism to silence warnings for "unchecked" patterns, in the cases where silencing the warning will still result in the pattern being checked at runtime.

Currently, a user can silence warnings that a scrutinee may not be matched by a pattern, by annotating the scrutinee with the `@unchecked` annotation. This SIP proposes to use a new annotation `@RuntimeCheck` to replace `@unchecked` for this purpose. For convenience, an extension method will be added to `Predef` that marks the receiver with the annotation (used as follows: `foo.runtimeCheck`). Functionally it behaves the same as the old annotation, but improves readability at the callsite.

## Motivation

As described in [Scala 3 Reference: Pattern Bindings](https://docs.scala-lang.org/scala3/reference/changed-features/pattern-bindings.html), under `-source:future` it is an error for a pattern definition to be refutable. For instance, consider:
```scala
def xs: List[Any] = ???
val y :: ys = xs
```

This compiled without warning in 3.0, became a warning in 3.2, and we would like to make it an error by default in a future 3.x version.
As an escape hatch we recommend to use `@unchecked`:
```
-- Warning: ../../new/test.scala:6:16 ------------------------------------------
6 |  val y :: ys = xs
  |                ^^
  |pattern's type ::[Any] is more specialized than the right hand side expression's type List[Any]
  |
  |If the narrowing is intentional, this can be communicated by adding `: @unchecked` after the expression,
  |which may result in a MatchError at runtime.
```
Similarly for non-exhaustive `match` expressions, where we also recommend to put `@unchecked` on the scrutinee.

But `@unchecked` has several problems. First, it is ergonomically bad. For instance to fix the exhaustivity warning in
```scala
xs match
  case y :: ys => ...
```
we'd have to write
```
(xs: @unchecked) match
  case y :: ys => ...
```
Having to wrap the `@unchecked` in parentheses requires editing in two places, and arguably harms readability: both due to the churn in extra symbols, and because in this use case the `@unchecked` annotation poorly communicates intent.

Nominally, the purpose of the annotation is to silence warnings (_from the [API docs](https://www.scala-lang.org/api/3.3.1/scala/unchecked.html#)_):
> An annotation to designate that the annotated entity should not be considered for additional compiler checks.



In the following code however, the word `unchecked` is a misnomer, so could be confused for another meaning by an inexperienced user:

```scala
def xs: List[Any] = ???
val y :: ys = xs: @unchecked
```
 After all, the pattern `y :: ys` _is_ checked, but it is done at runtime (by looking at the runtime class), rather than statically.

As a direct contradiction, in the following usage of `unchecked`, the meaning is the opposite:
```scala
xs match
  case ints: List[Int @unchecked] =>
```
Here, `@unchecked` means that the `Int` parameter will _not_ be checked at runtime: The compiler instead trusts the user that `ints` is a `List[Int]`. This could lead to a `ClassCastException` in an unrelated piece of code that uses `ints`, possibly without leaving a clear breadcrumb trail of where the faulty cast originally occurred.

## Proposed solution

### High-level overview

This SIP proposes to fix the ergnomics and readability of `@unchecked` in the usage where it means "checked at runtime", by instead adding a new annotation `scala.internal.RuntimeCheck`.

```scala
package scala.annotation.internal

final class RuntimeCheck extends Annotation
```

In all usages where the compiler looks for `@unchecked` for this purpose, we instead change to look for `@RuntimeCheck`.

By placing the annotation in the `internal` package, we communicate that the user is not meant to directly use the annotation.

Instead, for convenience, we provide an extension method `Predef.runtimeCheck`, which can be applied to any expression.

The new usage to assert that a pattern is checked at runtime then becomes as follows:
```scala
def xs: List[Any] = ???
val y :: ys = xs.runtimeCheck
```

We also make `runtimeCheck` a transparent inline method. This ensures that the elaboration of the method defines its semantics. (i.e. `runtimeCheck` is not meaningful because it is immediately inlined at type-checking).

### Specification

The addition of a new `scala.Predef` method:

```scala
package scala

import scala.annotation.internal.RuntimeCheck

object Predef:
  extension [T](x: T)
    transparent inline def runtimeCheck: x.type =
      x: @RuntimeCheck
```

### Compatibility

This change carries the usual backward binary and TASTy compatibility concerns as any other standard library addition to the Scala 3 only library.

Considering backwards source compatibility, the following situation will change:

```scala
// source A.scala
package example

extension (predef: scala.Predef.type)
  transparent inline def runtimeCheck[T](x: T): x.type =
    println("fake runtimeCheck")
    x
```
```scala
// source B.scala
package example

@main def Test =
  val xs = List[Any](1,2,3)
  val y :: ys = Predef.runtimeCheck(xs)
  assert(ys == List(2, 3))
```

Previously this code would print `fake runtimeCheck`, however with the proposed change then recompiling this code will _succeed_ and no longer will print.

Potentially we could mitigate this if necessary with a migration warning when the new method is resolved (`@experimental` annotation would be a start)


In general however, the new `runtimeCheck` method will not change any previously linking method without causing an ambiguity compilation error.

### Other concerns

In 3.3 we already require the user to put `@unchecked` to avoid warnings, there is likely a significant amount of existing code that will need to migrate to the new mechanism. (We can leverage already exisiting mechanisms help migrate code automatically).

### Open questions

1) A large question was should the method or annotation carry semantic weight in the language. In this proposal we weigh towards the annotation being the significant element.
The new method elaborates to an annotated expression before the associated pattern exhaustivity checks occur.
2) Another point, where should the helper method go? In Predef it requires no import, but another possible location was the `compiletime` package. Requiring the extra import could discourage usage without consideration - however if the method remains in `Predef` the name itself (and documentation) should signal danger, like with `asInstanceOf`.

3) Should the `RuntimeCheck` annotation be in the `scala.annotation.internal` package?

## Alternatives

1) make `runtimeCheck` a method on `Any` that returns the receiver (not inline). The compiler would check for presence of a call to this method when deciding to perform static checking of pattern exhaustivity. This idea was criticised for being brittle with respect to refactoring, or automatic code transformations via macro.

2) `runtimeCheck` should elaborate to code that matches the expected type, e.g. to heal `t: Any` to `Int` when the expected type is `Int`. The problem is that this is not useful for patterns that can not be runtime checked by type alone. Also, it implies a greater change to the spec, because now `runtimeCheck` would have to be specially treated.

## Related work

- [Pre SIP thread](https://contributors.scala-lang.org/t/pre-sip-replace-non-sensical-unchecked-annotations/6342)
- [Scala 3 Reference: Pattern Bindings](https://docs.scala-lang.org/scala3/reference/changed-features/pattern-bindings.html),
- None of OCaml, Rust, Swift, or Java offer explicit escape hatches for non-exhaustive pattern matches (Haskell does not even warn by default). Instead the user must add a default case, (making it exhaustive) or use the equivalent of `@nowarn` when they exist.

## FAQ

N/A so far.
