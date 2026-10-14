---
layout: sip
number: 79
permalink: /sips/:number.html
redirect_from:
  - /sips/:title.html
  - /sips/:number
stage: design
status: under-review
presip-thread: https://contributors.scala-lang.org/t/strictequality-with-explicit-nulls-do-not-work-well-together/7050
title: Relaxed Null checks under `strictEquality`
---

**By: Matthias Berndt**

## History

| Date          | Version            |
|---------------|--------------------|
| Apr 11th 2026 | Initial Draft      |

## Summary

Under `strictEquality`, I propose to not require a `CanEqual` instance when an expression is tested for (in-)equality with `null` and its type is a supertype of `Null`
This affects the `==` and `!=` operators as well as `case null` clauses in pattern matching.

## Motivation

With the explicit-nulls feature, Scala currently supports a form of flow typing where the type of a nullable variable is refined to a non-nullable type when a suitable non-null check is performed. However this interacts poorly with `strictEquality`, because there is currently no good way to perform a null check for certain types when `strictEquality` is enabled. The type `Int | Null` cannot be compared to `null` using `==` or `!=` because a `CanEqual[Int | Null, Null]` instance is not available. `eq` cannot be used either because that is only available on `AnyRef`, which is not a supertype of `Int | Null`.
The currently discussed proposal to make `Null` a subtype of `AnyVal` makes `eq` an even less viable alternative.

```scala
//> using option -Yexplicit-nulls -language:strictEquality
val x: Int | Null = ...
if x != null then // Error: Values of types Int | Null and Null cannot be compared
  val y: Int = x // would be safe

x match
  case null => () // currently doesn't compile
  case _ =>
    val y: Int = x // would be safe
```

## Proposed solution

Do not require a `CanEqual` instance for the `==` and `!=` operators when either operand is `null` and the other operand's type is a supertype of `Null`. Similarly, don't require a `CanEqual` instance during pattern matching when the pattern is `null` and the scrutinee's type is a supertype of `Null`.

### Compatibility

No compatibility implications because it only allows things that are already allowed when compiling without `strictEquality`.

### Feature Interactions

As described above, this feature corrects an unfortunate interaction between `explicit-nulls` and `strictEquality`.  Scala implements a form of flow typing: when `x` has type `Int | Null`, its type changes to `Int` inside an `if x != null` block. It is currently difficult to make use of this feature when `strictEquality` is enabled because an explicit `CanEqual` instance needs to be provided to make the null check compile for types like `Int | Null`.

## Alternatives

It was proposed to add a universally-available `CanEqual[A | Null, Null]` instance. However there is a downside to this: due to contravariance, such an instance allows everything to be compared to `null` because `A` is a subtype of `A | Null`. This would adversely affect the goal of `strictEquality` to prevent nonsensical comparisons.

## Related work

 - Implementation: https://github.com/scala/scala3/compare/main...mberndt123:scala3:better-equals
   - single line (excepting tests)
 - Discussion: https://contributors.scala-lang.org/t/strictequality-with-explicit-nulls-do-not-work-well-together/7050
 - This was discussed on the Scala Discord, where users BalmungSan and whowatchlist have expressed support
 https://discord.com/channels/632150470000902164/632150470000902166/1472075245149098016

## FAQ
 - What about expressions other than `null` that have type `Null`? E. g. `val n: Null = null`
   - These aren't special-cased by `explicit-nulls` either, so let's stick with that.
 - Is it really worth adding another special case to the compiler for this?
   - Comparing to `null` is already a special case in the compiler as it enables the flow typing behaviour described above. Therefore, this SIP shouldn't be thought of as a new special case; rather, it is the completion of an already existing special behaviour.
