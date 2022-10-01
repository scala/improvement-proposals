---
layout: sip
permalink: /sips/:title.html
stage: implementation
status: waiting-for-implementation
title: SIP-NN Wildcard Context BOunds
---

**By: Adam Pauls**

## History

| Date          | Version            |
|---------------|--------------------|
| Sep 30th 2022 | Initial Draft      |

## Summary

This SIP proposes new syntactic sugar for context bounds in the case where type context-bounded type argument need not be named. For example, 

```scala
// new sugar from this SIP
def showAll(xs: List[? : Showable]): Unit
// existing sugar
def showAll[S: Showable](xs: List[S]): Unit
// unsugared
def showAll[S](xs: List[S])(using Showable[S]): Unit
```
## Motivation

Scala 3 has reduced cases where names must be provided even if they are never used, as with `using` and `given` clauses. Separately, Scala 3 has significantly reduced the boilerplate required to dot-call methods defined on a type class with `extension` methods. However, there is still a common case where type parameters must be named, even when that name is never referenced (outside the signature). Consider the simple `Showable` typeclass:

~~~ scala
trait Showable[A]:
  extension (a: A) def show: String
~~~

Using [context bound syntax](https://docs.scala-lang.org/scala3/book/ca-context-bounds.html), one can define a method that accepts a variable with any type for which a `Showable` `given` instance is defined:

~~~ scala
def showAll[A: Showable](as: Set[A]): String = as.map(_.show).join("\n")
~~~

Note that the type parameter name `A` is a nuisance: it only needs a name because the context bound must be defined on the type parameter and not on the parameter type declaration. In contrast, if `Showable` were a simple (non-typeclass) trait with a `def show: String` member, then `showAll` does need this nuisance name:

~~~ scala
def showAll(as: Set[? <: Showable]): String = as.map(_.show).join("\n")
~~~

## Proposed Solution

This SIP aims to bring typeclass-defined methods closer to ergonomic parity withtraditional member methods by permitting parameter type declarations to use context-bounded wildcard types (`?`) as well:

~~~ scala
def showAll(as: Set[? : Showable]): String = as.map(_.show).join("\n")
// sugar for
def showAll[T: Showable](as: Set[T]): String = as.map(_.show).join("\n")
// which is in turn sugar for
def showAll[T](as: Set[T])(using Showable[T]): String = as.map(_.show).join("\n")
~~~

### High-level overview

One motivating use case for this feature is possibility to mimic Rust's [Into](xxx) typeclass, which provides for a more narrowly scoped implicit conversion mechanism than Scala's existing implicit conversions. Rust uses a (mostly non-privileged) `Into` typeclass to declare that a parameter can take any type convertible to a particular type:

~~~ rust
fn foo<T: Into<Int>>(arg: T): Int {
  return arg.into();
}
~~~

Rust's existing `impl` [parameters](https://doc.rust-lang.org/reference/types/impl-trait.html) allow for this declaration to be sugared to 

~~~ rust
fn foo(arg: impl Into[Int]): Int {
  return arg.into();
}
~~~

Scala 3 can already do a good job of mimicking the first declaration:
~~~ scala
type Into[-T, +U] = Conversion[T, U]
extension(t: T) def into()(using conv: Conversion[T, U]): U = conv(t)

def foo[T: Into[Int])(arg: T): Int = arg.into()
~~~

With the feature in this SIP, one could write
~~~ scala
def foo(arg: ? : Into[Int]): Int = arg.into()
~~~

There is [active discussion](xxx) about limiting the scope of implicit conversions using a language-level `into` keyword in a similar way. The fact that this prposal can accomplish much of that discussed feature, while also benefitting other typeclass use cases, is an indicator of the general applicability of this feature. Note that this proposal does not attempt to replace the `into` mechanism ununder discussion; it is only mentioned here for motivation. 

### Specification

The feature requires a change to the parser to permit context bound syntax for parameter types. Currently, context bound syntax is only allowed for type parameter declarations. 

It is easy to modify the parser to permit context bound syntax for all types, but this change is too permissive, since many contexts should not allow context bounds (e.g. `type T : Showable`). It is a much larger change to restrict the parser to only allow context bound syntax for function parameters, but it is entirely mechanical if the committee prefers it. Otherwise, it is a relatively small change to check for inappropriate context bounds in the typer phase. This proposal does not take a strong position on this implementation detail, but recommends checking in the typer phases to make the size of the change smaller. 

This feature also requires modifying the context bound desugaring to handle the new wildcard context bounds. The natural thing to do would be to follow the existing implementation for `using` parameters, which prepends the desugared synthetic parameters to the last `using` parameter list of the function, or creates a new `using` parameter list if none exists. 

However, as discussed in the *Open Questions* section, the synthetic type parameters are likely to be a bigger surprise to callers of the fuction than the synthetic `using` parameters. If [multiple type parameter lists](xxx) are implemented, it would be better to always append a new type parameter list when desugaring wildcard type parameters. 


### Compatibility

Since this change introduces new syntactic sugar, there are no worries about backwards compatbility. 

### Other concerns

In the common case where type variables are a single character, this new sugar will typically save only two characters per context-bounded type variable. That savings alone hardly justifies the change, but this proposal argues that the new syntax is considerably more readable than the syntax it replaces for the same reason new `using` and `given` clauses are more readable: removing nuisance type parameters and their names requires less cognitive load from the reader, since they don't need to consider the possibility that the name will be used elsewhere.

### Open questions

The proposal interacts with [multiple parameter lists](xxx): if that feature is not implemented, this SIP would still be implemented by appending (or prepending) synthetic type parameters to an existing type parameter list, but that would significantly increase the leakiness of the syntactic abstraction. In cases where explicit type parameters are mixed with wildcard context bounds, specifying an explicit type argument list would need to (surprisingly) specify the wildcard type:

~~~ scala
def foo[T](t: T, x: ? : Showable): Unit
foo[Int](1, "x") // wouldn't compile with error indicating missing type argument
foo[String, Int](1, "x") // compiles
~~~

Of course, this invocation wouldn't compile without the sugar either, but it would be more obvious that a type argument was missing becuase the declaration of `foo` would explicitly list the type parameters. This is less of an issue with the synthetic `using` parameters generated from existing context bounds because, in the cases where one must specify an explicit `using` argument list, the programmer can fill in inferrable arguments with `summon`/`implicitly` without having to think about their correct values. 

## Alternatives

An [earlier version](https://contributors.scala-lang.org/t/pre-sip-additional-syntactic-sugar-for-typeclasses/5675) suggested (ab)using type lambda syntax:
~~~ scala
def showAll(as: Set[Showable[_]]): String
~~~

Allowing type lambdas in parameter types is not well-typed. The only reason for this proposal was my ignorance of the fact thatthat the right-hand side of a context bound could already be a type lambda, so it is already natural to allow
~~~ scala
def foo(as: Set[SomeTwoArgTypeclass[_, Int]): Unit
// as sugar for
def foo[T: SomeTwoArgTypeclass[_, Int]](as: Set[T]): Unit
~~~

Another suggestion from the [pre-SIP discussion](https://contributors.scala-lang.org/t/pre-sip-additional-syntactic-sugar-for-typeclasses/5675) was to use a keyword, in particular, borrowing Rust's `impl` or reusing `using` or `given`:
~~~ scala
def showAll(as: Set[using Showable]): String
~~~

Although the searchability of keywords is a big win, this proposal argues that:
- the existing syntax for context bounds and wildcard upper- and lower-type bounds argues strongly for this proposal's syntax
- this alternative syntax makes `Showable` appear to be an unparamerized type as in `def foo(using Showable)`, while the proposed syntax clearly evokes context bounds
- existing (soft) keywords would naturally lead to an expected alternative syntax for context bounds:

~~~ scala
def showAll[T using Showable](as: Set[T]): String
~~~

Unfortunately, this syntax would not be backwards compatible because of infix type syntax: `T using Showable` is infix syntax for `using[T, Showable]`. 

## Related work

- The [pre-SIP discussion](https://contributors.scala-lang.org/t/pre-sip-additional-syntactic-sugar-for-typeclasses/5675/17)
- A [PR](https://github.com/lampepfl/dotty/pull/15162) implementing the proposal.  
- A [description](https://doc.rust-lang.org/reference/types/impl-trait.html) of Rust's `impl` parameters, which inspired this proposal.

## FAQ

- (initially empty)
