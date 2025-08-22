---
layout: sip
permalink: /sips/:title.html
stage: implementation
status: under-review
title: SIP-XX Allow Partial Function Literals to be defined with Parentheses
---

**By: Li Haoyi**

## History

| Date          | Version            |
|---------------|--------------------|
| Aug 22nd 2025 | Initial Draft      |

## Summary

This proposal is to allow parens `(...)` to be used instead of curly braces `{...}`
when defining partial functions which have only one `case`:


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

Partial function literals in other non-function-call contexts can be defined with parens as well,
as long as they have a single `case` block with a single expression on the right:

```scala
val partial: PartialFunction[(Int, Int), Int] = (case (a, b) if b > 2 => a)
```

## Motivation

With Scala 3's [Optional Braces](https://docs.scala-lang.org/scala3/reference/other-new-features/indentation.html),
and [SIP-44's Fewer Braces](https://docs.scala-lang.org/sips/fewer-braces.html), single-line
partial functions are one of the only remaining places where curly braces are mandatory in Scala
syntax.

Needing to swap between parens and curlies a common friction of converting a function to 
a partial function:

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

In the syntax of other expressions, curly braces are only
necessary to define "blocks" with multiple statements: `if`-`else`, `try`-`catch`-`finally`,
`for`-`yield`, `do`-`while`, method calls like `foo()` etc. all allow you to replace curly braces
with parentheses (or elide them altogether) when there is only a single expression present.
This is unlike other languages like Java that mandate curly braces in these syntactic constructs.
Furthermore, in most expressions, Optional Braces means you do not have to write the curlies
if you do not want to.

This proposal brings partial functions in-line with the rest of Scala syntax, with the curly
braces only being mandatory for multi-statement blocks, and made optional with Scala 3's
Optional Braces.

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
we expect that most of these multi-statement constructs would be written without braces as well:

```scala
Seq((1, 2), (3, 4)).collect:
  case (a, b) if b > 2 =>
    println(b)  
    a
```


```scala
Seq((1, 2), (3, 4)).collect:
  case (a, b) if b > 2 => a
  case _ => ???
```

This proposal does not affect `match` blocks, which typically have multiple lines, nor does
it affect `catch` blocks which already allow a curly-free `catch case e: Throwable =>` syntax.
These also can be written without braces in most multi-line scenarios


