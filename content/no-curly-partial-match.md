---
layout: sip
permalink: /sips/:title.html
stage: implementation
status: under-review
title: SIP-XX - No-Curly Partial Functions and Matches
---

**By: Li Haoyi**

## History

| Date          | Version            |
|---------------|--------------------|
| Aug 22nd 2025 | Initial Draft      |

## Summary

This proposal is to allow parens `(...)` to be used instead of curly braces `{...}`
when defining partial functions which have only one `case`, and eliding the `case` keyword:


```scala
Seq((1, 2), (3, 4)).collect(case (a, b) if b > 2 => a) // 3
```

Currently this syntax is disallowed:

```scala
scala> Seq((1, 2), (3, 4)).collect(case (a, b) if b > 2 => a)
-- [E018] Syntax Error: --------------------------------------------------------
1 |Seq((1, 2), (3, 4)).collect(case (a, b) if b > 2 => a)
  |                            ^^^^
  |                            expression expected but case found
  |
  | longer explanation available when compiling with `-explain`
```

## Motivation

Allowing partial functions with only a single-expression to be defined using parentheses makes 
the language more regular, and removes a common friction of converting a function to a partial
function and having to change all the parens to curlies:

```scala
Seq((1, 2), (3, 4)).map((a, b) => a) // OK
Seq((1, 2), (3, 4)).collect(case (a, b) if b > 2 => a) // BAD
Seq((1, 2), (3, 4)).collect{ case (a, b) if b > 2 => a } // OK
```

This also currently causes visual messiness in method call chains where some single-line 
methods use parens and others use curlies:

```scala
Seq((1, 2), (3, 4), (5, 6))
  .filter(_._1 < 5) // PARENS
  .collect{ case (a, b) if b > 2 => a } // CURLIES
  .reduce(_ + _) // PARENS
```

Partial functions and `match` statements are currently the only expressions where curly braces are
required and parentheses are disallowed. In the syntax of other expressions, curly braces are only
necessary to define "blocks" with multiple statements: `if`-`else`, `try`-`catch`-`finally`,
`for`-`yield`, `do`-`while`, method calls like `foo()` etc. all allow you to replace curly braces
with parentheses (or elide them altogether) when there is only a single expression present.
This is unlike other languages like Java that mandate curly braces in these syntactic constructs

Unlike `match` statements, partial functions are very commonly written in a single line with
a single expression as the result, and so having to put curlies around them is tedious and
irregular. Allowing them to use parentheses would make the language syntax more regular,
reduce friction of people converting between normal and partial functions, and make code
more regular and easier to read by associating curlies more strongly with multi-statement blocks
rather than multi-statement-blocks-and-also-partial-functions.

## Limitations

If not using the Brace-free/Fewer-braces syntax, partial function literals with multiple 
statements in one `case` block will still require curly braces, in line with how curly 
braces are used to define multi-statement blocks in other contexts: 

```scala
Seq((1, 2), (3, 4)).collect {
  case (a, b) if b > 2 =>
    println(b)  
    a
}
```

Partial functions with multiple `case` statements will also require braces: 

```scala
Seq((1, 2), (3, 4)).collect {
  case (a, b) if b > 2 => a
  case _ => ???
}
```

Although with Scala 3's [Optional Braces](https://docs.scala-lang.org/scala3/reference/other-new-features/indentation.html),
we expect that most of these more-complex constructs would be written without braces as well.