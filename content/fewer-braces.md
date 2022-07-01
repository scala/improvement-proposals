---
layout: sip
permalink: /sips/:title.html
stage: ready for review
status: implemented
title: SIP-NN Fewer Braces
---

**By: Martin Odersky**

## History

| Date          | Version            |
|---------------|--------------------|
| July 1st 2022 | Initial Draft      |

## Summary

The current state of Scala 3 makes braces optional around blocks and template definitions (i.e. bodies of classes, objects, traits, enums, or givens). This SIP proposes to allow optional braces also for function arguments.
The advantages of doing so is that the language feels more systematic, and programs become typographically cleaner.
The changes have been implemented and and made available under the language import `language.experimental.fewerBraces`. The proposal here is to make them available without a language import instead.


## Motivation

After extensive experience with the current indentation rules I conclude that they are overall a big success.
However. they still feel incomplete and a bit unsystematic since we can replace `{...}` in the majority of situations, but there are also important classes of situations where braces remain mandatory. In particular, braces are currently needed around blocks as function arguments.

It seems very natural to generalize the current class syntax indentation syntax to function arguments. In both cases, an indentation block is started by a colon at the end of a line.


## Proposed solution

The proposed solution is described in detail in https://dotty.epfl.ch/docs/reference/other-new-features/indentation.html#variant-indentation-marker--for-arguments. I inline the relevant sections here:

First, here is the spec for colons at ends of lines for template bodies. This is part of official Scala 3. I cited it here for context.

> A template body can alternatively consist of a colon followed by one or more indented statements. To this purpose we introduce a new `<colon>` token that reads as
the standard colon "`:`" but is generated instead of it where `<colon>`
is legal according to the context free syntax, but only if the previous token
is an alphanumeric identifier, a backticked identifier, or one of the tokens `this`, `super`, "`)`", and "`]`".

> An indentation region can start after a `<colon>`. A template body may be either enclosed in braces, or it may start with
`<colon> <indent>` and end with `<outdent>`.
Analogous rules apply for enum bodies, type refinements, and local packages containing nested definitions.

Generally, the possible indentation regions coincide with those regions where braces `{...}` are also legal, no matter whether the braces enclose an expression or a set of definitions. There is so far one exception, though: Arguments to functions can be enclosed in braces but they cannot be simply indented instead. Making indentation always significant for function arguments would be too restrictive and fragile.

To allow such arguments to be written without braces, a variant of the indentation scheme is implemented under language import
```scala
import language.experimental.fewerBraces
```
This SIP proposes to make this variant the default, so no language import is needed to enable it.
In this variant, a `<colon>` token is also recognized where function argument would be expected. Examples:

```scala
times(10):
  println("ah")
  println("ha")
```

or

```scala
credentials `++`:
  val file = Path.userHome / ".credentials"
  if file.exists
  then Seq(Credentials(file))
  else Seq()
```

or

```scala
xs.map:
  x =>
    val y = x - 1
    y * y
```
What's more, a `:` in these settings can also be followed on the same line by the parameter part and arrow of a lambda. So the last example could be compressed to this:

```scala
xs.map: x =>
  val y = x - 1
  y * y
```
and the following would also be legal:
```scala
xs.foldLeft(0): (x, y) =>
  x + y
```

The grammar changes for this variant are as follows.

```
SimpleExpr       ::=  ...
                   |  SimpleExpr ColonArgument
InfixExpr        ::=  ...
                   |  InfixExpr id ColonArgument
ColonArgument    ::=  colon [LambdaStart]
                      indent (CaseClauses | Block) outdent
LambdaStart      ::=  FunParams (‘=>’ | ‘?=>’)
                   |  HkTypeParamClause ‘=>’
```
### Compatibility

The proposed solution changes the meaning of the following code fragments:
```scala
  val x = y:
    Int

  val y = (xs.map: (Int => Int) =>
    Int)
```
In the first case, we have a type ascription where the type comes after the `:`. In the second case, we have
a type ascription in parentheses where the ascribing function type is split by a newline. Note that we have not found examples like this in the dotty codebase or in the community build. We verified this by compiling everything with success with `fewerBraces` enabled. So we conclude that incompatibilities like these would be very rare.
If there would be code using these idioms, it can be rewritten quite simply to avoid the problem. For instance, the following fragments would be legal (among many other possible variations):
```scala
  val x = y
    : Int

  val y = (xs.map: (Int => Int)
    => Int)
```

### Other concerns

Since this affects parsing, the scalameta parser and any other parser used in an IDE will also need to be updated. The necessary changes to the Scala 3 parser were made here: https://github.com/lampepfl/dotty/pull/15273/commits. The commit that embodies the core change set is here: https://github.com/lampepfl/dotty/pull/15273/commits/421bdd660b0456c2ff1ae386f032c41bb1e0212a.

### Open questions

None for me.

## Alternatives

I considered two variants:

The first variant would allow lambda parameters without preceding colons. E.g.
```scala
xs.foldLeft(z)(a, b) =>
  a + b
```
We concluded that this was visually less good since it looks too much like a function call `xs.foldLeft(z)(a, b)`.

The second variant would always require `(...)` around function types in ascriptions (which is in fact what the official syntax requires). That would have completely eliminated the second ambiguity above since
```scala
val y = (xs.map: (Int => Int) =>
    Int)
```
would then not be legal anyway. But it turned out that there were several community projects that were using function types in ascriptions without enclosing parentheses, so this change was deemed to break too much code.


## Related work

 - Doc page for proposed change: https://dotty.epfl.ch/docs/reference/other-new-features/indentation.html#variant-indentation-marker--for-arguments

 - Merged PR implementing the proposal under experimental flag: https://github.com/lampepfl/dotty/pull/15273/commits/421bdd660b0456c2ff1ae386f032c41bb1e0212a

 - Latest discussion on contributors (there were several before when we discussed indentation in general): https://contributors.scala-lang.org/t/make-fewerbraces-available-outside-snapshot-releases/5024/166

## FAQ

