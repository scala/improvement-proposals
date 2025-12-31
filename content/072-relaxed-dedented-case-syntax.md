---
layout: sip
number: 72
permalink: /sips/:number.html
redirect_from:
  - /sips/:number
  - /sips/:title.html
stage: pre-sip
status: under-review
title: Relaxed Dedented Case Syntax
---

**By: Li Haoyi**

## History

| Date              | Version            |
|-------------------|--------------------|
| December 31st 2025| Initial Draft      |

## Summary

This SIP proposes to allow non-indented `case` clauses in non-match contexts, such
as partial function literals and catch blocks:

```scala
xs.map:
case (a, b) => a + b
case (a, b, c) => a + b + c

val pf: PartialFunction[String, Int] =
case "foo" => 1
case "bar" => 2
```

## Motivation

Currently, when using `case` clauses
outside of `match` expressions, the `case` keywords must be indented relative to the
construct that introduces them. This proposal relaxes that restriction, allowing
`case` clauses to appear at the same indentation level as the preceding line,
reducing unnecessary nesting and making the required syntax more consistent with 
`match` and `catch` blocks that already support non-indented `case` statements

Consider the following example using the current syntax:

```scala
xs.map:
  case (a, b) => a + b
  case (a, b, c) => a + b + c
```

Trying to de-dent the `case`s results in an error:

```scala
xs.map:
case (a, b) => a + b
case (a, b, c) => a + b + c
// Error: indented definitions expected, eof found
```

The `case` keywords must be indented one level from the colon. For deeply nested
code, this adds unnecessary indentation that makes code harder to read and wastes
horizontal space. Furthermore, this is inconsistent with `case` statements in other
contexts where non-indented syntax is already allowed, such as following a `match`:

```scala
xs match // OK
case (a, b) => a + b
case (a, b, c) => a + b + c
```

Or `catch`

```scala
try ???
catch // OK
case (a, b) => a + b
case (a, b, c) => a + b + c
```

This means using `case` statements can be confusing: in some scenarios you can de-dent them, in
other scenarios you cannot, and it is not obvious which is which.

De-dented `case` statements are valuable because they reduce indentation and act as 
a generalization of `if-then-else`. `if-then-else` has a single discriminator expression
and two hardcoded branches:

```scala
if foo
then bar
else qux
```

While `match`-`case` has a single discriminator and N branches

```scala
foo match
case bar =>
case qux =>
case baz =>
case cow =>
```

Since we already allow de-dented `match`/`case` and `catch`/`case` pairs, we cannot enforce
consistency by requiring indentation. And so the only way forward is to relax the indentation
requirements for other contexts as well.

## Proposed Solution

This proposal would generalize the ability to have a non-indented `case` from just `try`/`catch`
to other contexts where indentation is currently necessary:

**Before (current syntax):**

```scala
// Partial function with required indentation
xs.collect:
  case Some(x) => x
  case None => 0

// Nested usage
def process(data: List[Option[Int]]) =
  data.flatMap:
    case Some(x) if x > 0 =>
      List(x, x * 2)
    case _ =>
      Nil

// Partial function literal assigned to typed variable
val pf: PartialFunction[String, Int] =
  case "foo" => 1
  case "bar" => 2
```

**After (with relaxed syntax):**

```scala
// Partial function without extra indentation
xs.collect:
case Some(x) => x
case None => 0

// Nested usage with reduced indentation
def process(data: List[Option[Int]]) =
  data.flatMap:
  case Some(x) if x > 0 =>
    List(x, x * 2)
  case _ =>
    Nil

// Partial function literal assigned to typed variable (no colon needed)
val pf: PartialFunction[String, Int] =
case "foo" => 1
case "bar" => 2
```

This makes the syntax of `case` statements more consistent across the language,
so users do not need to remember special rules about whether indentation is required or not

Note that the indented form remains valid. This proposal only adds additional valid
syntax; it does not remove the existing indented form.

## Compatibility

There are no backwards compatibility considerations

## Implementation

The proposed change has been implemented in Scala 3 PR
[#24841](https://github.com/scala/scala3/pull/24841). The implementation involves a
minor adjustment to the parser that permits case clauses to appear without
additional indentation after a colon. As noted in the PR, the change introduces no
parsing ambiguity and requires minimal code changes.

## References

- Contributors forum discussion:
  https://contributors.scala-lang.org/t/can-we-allow-non-indented-cases-for-non-match-case-blocks/7326
- Implementation PR: https://github.com/scala/scala3/pull/24841
- Scala 3 syntax reference: https://docs.scala-lang.org/scala3/reference/syntax.html
- SIP-44 (Fewer Braces): https://docs.scala-lang.org/sips/fewer-braces.html
