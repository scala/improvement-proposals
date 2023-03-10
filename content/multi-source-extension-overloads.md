---
layout: sip
permalink: /sips/:title.html
stage: implementation
status: waiting-for-implementation
title: SIP-54 - Multi-Source Extension Overloads
---

**By: Sébastien Doeraene and Martin Odersky**

## History

| Date          | Version            |
|---------------|--------------------|
| Mar 10th 2023 | Initial Draft      |

## Summary

We propose to allow overload resolution of `extension` methods with the same name but imported from several sources.
For example, given the following definitions:

```scala
class Foo
class Bar

object A:
  extension (foo: Foo) def meth(): Foo = foo
  def normalMeth(foo: Foo): Foo = foo

object B:
  extension (bar: Bar) def meth(): Bar = bar
  def normalMeth(bar: Bar): Bar = bar
```

and the following use site:

```scala
import A.*
import B.*

val foo: Foo = ???
foo.meth() // works with this SIP; "ambiguous import" without it

// unchanged:
meth(foo)() // always ambiguous, just like
normalMeth(foo) // always ambiguous
```

## Motivation

Extension methods are a great, straightforward way to extend external classes with additional methods.
One classical example is to add a `/` operation to `Path`:

```scala
import java.nio.file.*

object PathExtensions:
  extension (path: Path)
    def /(child: String): Path = path.resolve(child).nn

def app1(): Unit =
  import PathExtensions.*
  val projectDir = Paths.get(".") / "project"
```

However, as currently specified, they do not compose, and effectively live in a single flat namespace.
This is understandable from the spec--the *mechanism**, which says that they are just regular methods, but is problematic from an intuitive point of view--the *intent*.

For example, if we also use another extension that provides `/` for `URI`s, we can use it in a separate scope as follows:

```scala
import java.net.URI

object URIExtensions:
  extension (uri: URI)
    def /(child: String): URI = uri.resolve(child)

def app2(): Unit =
  import URIExtensions.*
  val rootURI = new URI("https://www.example.com/")
  val projectURI = rootURI / "project/"
```

The above does not work anymore if we need to use *both* extensions in the same scope.
The code below does not compile:

```scala
def app(): Unit =
  import PathExtensions.*
  import URIExtensions.*

  val projectDir = Paths.get(".") / "project"
  val rootURI = new URI("https://www.example.com/")
  val projectURI = rootURI / "project/"
  println(s"$projectDir -> $projectURI")
end app
```

*Both* attempts to use `/` result in error messages of the form

```
Reference to / is ambiguous,
it is both imported by import PathExtensions._
and imported subsequently by import URIExtensions._
```

### Workarounds

The only workarounds that exist are unsatisfactory.

We can avoid using extensions with the same name in the same scope.
In the above example, that would be annoying enough to defeat the purpose of the extensions in the first place.

The only other possibility is to *define* all extension methods of the same name in the same `object` (or as top-level definitions in the same file).
This is possible, although cumbersome, if they all come from the same library.
However, it is impossible to combine extension methods coming from separate libraries in this way.

### Problem for migrating off of implicit classes

Scala 2 implicit classes did not suffer from the above issues, because they were disambiguated by the name of the implicit class (not the name of the method).
This means that there are libraries that cannot migrate off of implicit classes to use `extension` methods without significantly degrading their usability.

## Proposed solution

We propose to relax the resolution of extension methods, so that they can be resolved from multiple imported sources.
Instead of rejecting the `/` call outright because of ambiguous imports, the compiler should try the resolution from all the imports, and keep the only one (if any) for which the receiver type matches.

Practically speaking, this means that the above `app()` example would compile and behave as expected.

### Non-goals

It is *not* a goal of this proposal to allow resolution of arbitrary overloads of regular methods coming from multiple imports.
Only `extension` method calls are concerned by this proposal.
The complexity budget of relaxing *all* overloads in this way is deemed too high, whereas it is acceptable for `extension` method calls.

For the same reason, we do not propose to change regular calls of methods that happen to be `extension` methods.

### Specification

From the [specification of extension methods](https://docs.scala-lang.org/scala3/reference/contextual/extension-methods.html#translation-of-calls-to-extension-methods), we amend step 1. of "The precise rules for resolving a selection to an extension method are as follows."

Previously:

> Assume a selection `e.m[Ts]` where `m` is not a member of `e`, where the type arguments `[Ts]` are optional, and where `T` is the expected type.
> The following two rewritings are tried in order:
>
> 1. The selection is rewritten to `m[Ts](e)`.

With this SIP:

> 1. The selection is rewritten to `m[Ts](e)` and typechecked, using the following slight modification of the name resolution rules:
>
>    - If `m` is imported by several imports which are all on the same nesting level, try each import as an extension method instead of failing with an ambiguity.
>      If only one import leads to an expansion that typechecks without errors, pick that expansion.
>      If there are several such imports, but only one import which is not a wildcard import, pick the expansion from that import.
>      Otherwise, report an ambiguous reference error.

### Compatibility

The proposal only alters situations where the previous specification would reject the program with an ambiguous import.
Therefore, we expect it to be backward source compatible.

The resolved calls could previously be spelled out by hand (with fully-qualified names), so binary and TASTy compatibility are not affected.

## Alternatives

A number of alternatives were mentioned in [the Contributors thread](https://contributors.scala-lang.org/t/change-shadowing-mechanism-of-extension-methods-for-on-par-implicit-class-behavior/5831), but none that passed the bar of "we think this is actually implementable".

## Related work

This section should list prior work related to the proposal, notably:

- [Contributors thread acting as de facto Pre-SIP](https://contributors.scala-lang.org/t/change-shadowing-mechanism-of-extension-methods-for-on-par-implicit-class-behavior/5831)
- [Pull Request in dotty](https://github.com/lampepfl/dotty/pull/17050) to support it under an experimental import

## FAQ

This section will probably initially be empty. As discussions on the proposal progress, it is likely that some questions will come repeatedly. They should be listed here, with appropriate answers.
