---
layout: sip
permalink: /sips/:title.html
stage: pre-sip
status: submitted
presip-thread: https://contributors.scala-lang.org/t/pre-sip-equivalent-of-inline-implicit-def/6590
title: SIP-65 - Implicit macro conversions
---

**By: Raphaël FROMENTIN**

## History

| Date          | Version            |
|---------------|--------------------|
| Jun 24th 2024 | Initial Draft      |

## Summary

Implicit conversions are both liked and hated in the community because of the expressiveness they give and how easy it
is to make the code confusing. Scala 3 made implicit conversions more ergonomic and less dangerous by introducing the
`Conversion` type class instead.

```scala
//Scala 2
implicit def fooToBar(foo: Foo): Bar = ???

//Scala 3
given Conversion[Foo, Bar] = ???
```

However, this does not cover the use case of compile-time (using the `inline` keyword in Scala 3) implicit conversions.

This SIP tries to address this missing feature which existed in Scala 2 conversions by introducing a new type class
`MacroConversion`, similar to `Conversion`.

## Motivation

Despite being controversial, compile-time/macro implicit conversions are useful in many cases and is adopted in several
libraries (see
[this thread](https://contributors.scala-lang.org/t/use-cases-for-implicit-conversion-blackbox-macros/6386)):
- [SourceCode](https://github.com/com-lihaoyi/sourcecode)
- [Mill](https://mill-build.com)
- [Iron](https://github.com/Iltotore/iron)
- [Refined](https://github.com/fthomas/refined)
- Others, including [new projects]
<!-- Add Discord Link -->

They often use it for metaprogramming capabilities (Sourcecode, Mill...) and/or increased safety with compile-time
inspections (Iron, Refined...).

When the old-fashioned `implicit def` will be removed, these libraries will have to make major refactors or worst case:
to give up some features due to their inability to be expressed using `Conversion`. In both cases, this will result in
major compatibility breaks among the ecosystem.

## Proposed solution

### High-level overview

The proposed solution is to add a new type class behaving similarly to `scala.Conversion` but with an inline version of
`apply`:

```scala
abstract class MacroConversion[-T, +U]:

  inline def apply(inline x: T): U
```

#### Example 1: Simple conversion

```scala
implicit inline def fooToBar(inline foo: Foo): Bar = fooToBarMacro(foo)
```

would become

```scala
inline given fooToBar: MacroConversion[Foo, Bar] with

  override inline def apply(inline foo: Foo): Bar = fooToBarMacro(foo)
```

or alternatively:

```scala
inline given MacroConversion[Foo, Bar] with

  override inline def apply(inline foo: Foo): Bar = fooToBarMacro(foo)
```

Note: While the given instance does theoretically not have to be `inline`, it would do in most cases to avoid a
"Deferred inline method" error.

#### Example 2: Migrating Iron's autoRefine conversion

[Iron](https://github.com/Iltotore/iron) uses an
[implicit inline conversion](https://github.com/Iltotore/iron/blob/1af717cf4af61b35abba11b060fde03bc161dacf/main/src/io/github/iltotore/iron/conversion.scala#L20)
to safely cast an unconstrained value to its refined version if it satisfies the given constraint. This involves:
- Generic parameters `A` and `C`
- A macro `assertCondition`
- A `using` parameter `Constraint[A, C]`

```scala
implicit inline def autoRefine[A, C](inline value: A)(using inline constraint: Constraint[A, C]): A :| C =
  macros.assertCondition(value, constraint.test(value), constraint.message)
  IronType(value)
```

Similarly to the first example or a non-inline `implicit def`, it is migrated to the following given instance:

```scala
inline given autoRefine[A, C](using inline constraint: Constraint[A, C]): Conversion[A, A :| C] with
  
  override inline def apply(inline value: A): A :| C =
    macros.assertCondition(value, constraint.test(value), constraint.message)
    IronType(value)
```

### Specification

#### Definition

The implementation of `MacroConversion` should be similar to
[scala.Conversion](https://github.com/scala/scala3/blob/3.4.2/library/src/scala/Conversion.scala#L25): an
`abstract class` with a special treatment in the implicit resolution algorithm.

```scala
@FunctionalInterface
abstract class MacroConversion[-T, +U]

  inline def apply(inline x: T): U
```

#### Compiler treatment

`MacroConversion` should be treated by the compiler the same way it does with `Conversion`:

> A class for implicit values that can serve as implicit conversions. The implicit resolution algorithm will act as if
> there existed the additional implicit definition:
>
> ```scala
> def $implicitConversion[T, U](x: T)(c: Conversion[T, U]): U = c(x)
> ```
>
>However, the presence of this definition would slow down implicit search since its outermost type matches any pair of
> types. Therefore, implicit search contains a special case in `Implicits#discardForView` which emulates the conversion
> in a more efficient way.
> 
> [scala.Conversion - Scaladoc](https://www.scala-lang.org/api/current/scala/Conversion.html)

#### Support `using Conversion`

It is not possible to make this class inherit from `Conversion` because `x` is not defined as inline in the parent.
Defining `apply` like this:

```scala
@FunctionalInterface
abstract class MacroConversion[-T, +U]

  def apply(x: T): U = applyInline(x)

  inline def applyInline(inline x: T): U
```

results in a "deferred inline" error. To support `MacroConversion` being passed to methods with a `using Conversion`
parameter, a "proxy given" can be defined:

```scala
inline given [T, U](using macroConv: MacroConversion[T, U]): Conversion[T, U] = new:

  override def apply(x: T): U = macroConv(x)
```

### Compatibility

This proposal only adds new definitions and a special case in compiler that only targets the new `MacroConversion`. It
should not break API nor binary compatibility.

### Interaction with Conversion

There are two cases where `MacroConversion` with `Conversion` if both are given for the same types.

```scala
given Conversion[Foo, Bar] = ???

given MacroConversion[Foo, Bar] = ???
```

#### As a `using` parameter

Taking this example method:

```scala
def myMethod(using Conversion[Foo, Bar]) = ???
```

In this case, according to implicit rules and the `given` instance [previously defined](#support-using-conversion), the
`Conversion` instance would have priority over `MacroConversion`. See
[Changes in implicit resolution](https://docs.scala-lang.org/scala3/reference/changed-features/implicit-resolution.html).

#### Invoking an implicit conversion

```scala
val x: Foo = ???
val y: Bar = x
```

There are three different ways to handle implicit clash:
- Prioritize `Conversion` over `MacroConversion` when in the same scope
- Prioritize `MacroConversion` over `Conversion` when in the same scope
- Do not apply conversion due to ambiguity

The first solution is more analogous to the way we handle `using Conversion`. The third one behaves similarly to
ambiguity between two given `Conversion` or two `implicit def`:

```scala
given a: Conversion[Foo, Bar] = ???
given b: Conversion[Foo, Bar] = ???

//Found:    Playground.Foo
//Required: Playground.Bar
//Note that implicit conversions cannot be applied because they are ambiguous;
val x: Bar = Foo()
```

```scala
implicit def a(x: Foo): Bar = ???
implicit inline def b(inline x: Foo): Bar = ???

//Found:    Playground.Foo
//Required: Playground.Bar
//Note that implicit conversions cannot be applied because they are ambiguous;
val x: Bar = Foo()
```

Therefore, the third option seems to be the most suited.

### Abuse concerns

Implicit conversions, especially in Scala 2, were criticized for being prone to abuse, making code more confusing and
hard to reason about. @odersky
[pointed out](https://contributors.scala-lang.org/t/pre-sip-equivalent-of-inline-implicit-def/6590/5) that macro
conversions would allow to mix two abusable features which can eventually lead to obscure code.

However, other users argue that feature misuse is not a problem but backward compatibility is:
- [jducoeur - Pre-SIP discussion](https://contributors.scala-lang.org/t/pre-sip-equivalent-of-inline-implicit-def/6590/7)
- [Li Haoyi - Pre-SIP discussion](https://contributors.scala-lang.org/t/pre-sip-equivalent-of-inline-implicit-def/6590/10)

Furthermore, these features have been vastly improved in Scala 3 which leads to less misuse opportunities:
- [Implicit keyword separation](https://docs.scala-lang.org/scala3/reference/contextual/)
- [Orphan givens (including `Conversion`) needing to be imported explicitly](https://docs.scala-lang.org/scala3/reference/contextual/given-imports.html)
- [More sound and safe macros](https://docs.scala-lang.org/scala3/guides/macros/macros.html)

@Sporarum suggested solutions to make implicit macro conversions less prone to abuse:
- Previously, the type class was named `InlineConversion` but changed to `MacroConversion` to indicate that it should be
used wisely.
- Lock `MacroConversion` behind an import different from `Conversion`'s like `scala.language.macroConversions`.

@odersky's proposal to
[turn the "missing implicit feature flag" warning to an error](https://contributors.scala-lang.org/t/pre-sip-equivalent-of-inline-implicit-def/6590/5)
would further reduce the abuse potential.

## Alternatives

### Make `scala.Conversion#apply` inline

An alternative is to change the signature of the `apply` method of `Conversion` to allow inline methods/macros but this
path contains many culprits:
- It would break binary and source compatibility
- It could cause "deferred inline" errors at call-site
- It prevents from adding further "danger" signs in the name or as a different import
  (see [Abuse concerns](#abuse-concerns))

### Do not change anything and forbid implicit inline conversions

Another alternative is to simply not do anything and let `implicit inline def` be deprecated then removed, [as suggested
by @odersky](https://contributors.scala-lang.org/t/pre-sip-equivalent-of-inline-implicit-def/6590/5). This would
prevent people from abusing this feature, but it would break backward compatibility forcing many codebases, including libraries and companies' to be
rewritten. According to the [Scala Survey 2023](https://scalasurvey2023.virtuslab.com/), binary compatibility is among
the top of Scala features for 15% of the participants while implicit conversions are not mentioned. Metaprogramming is
mentioned by 10% of the respondents. Abuse concerns have been addressed in a [previous section](#abuse-concerns).

## Related work

This section should list prior work related to the proposal, notably:

1. Pre-SIP thread, Scala Contributors: https://contributors.scala-lang.org/t/pre-sip-equivalent-of-inline-implicit-def/6590
2. Use Cases for Implicit Conversion Blackbox Macros, Scala Contributors: https://contributors.scala-lang.org/t/use-cases-for-implicit-conversion-blackbox-macros/6386
3. Before deprecating old-style implicit conversions, we need this, Scala Contributors: https://contributors.scala-lang.org/t/use-cases-for-implicit-conversion-blackbox-macros/6386