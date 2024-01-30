---
layout: sip
permalink: /sips/:title.html
stage: implementation
status: waiting-for-implementation
presip-thread: https://contributors.scala-lang.org/t/pre-sip-foo-bar/9999
title: SIP-NN - Scala 3 Macro Annotations
---

**By: Nicolas Stucki**

## History

| Date          | Version            |
|---------------|--------------------|
| Feb 19th 2024 | Initial Draft      |

## Summary

Macro annotations are a metaprogramming feature that allows compile-time transformation of annotated definitions.
These annotations extend the existing macro system, they use the reflection API directly and allow integration with the multi-stage programming abstractions (quoted expressions  `'{...}`).
They provide the same level of safety guarantees as macros.

The design of these macro annotations prioritizes soundness over expressivity.
Therefore they will be less expressive than the experimental macro annotations in Scala 2, but will avoid unsoundness pitfalls.

This proposal introduces the concrete trait interface to define macro annotations and how these are evaluated by the compiler. The details on multi-stage programming and the reflection API are taken as a given and unmodified by this proposal.

## Motivation

The goal of macro annotation is to provide a way to transform the code of annotated definitions. This can be used to automate code generation, to implement optimizations, or to enforce coding standards. Below we will see a few examples of macro annotations that could be implemented using the proposed interface.

### Example 1: Data class
Many users of Scala use case classes to define data classes. They usually only care about the access to the fields, `equals`, `hashCode`, and `toString` methods. Case classes add methods for pattern matching that might be considered bloat code in these situations.

We could define a data class as follows:

```scala
@data class User(val name: String, val id: Int)
```

The `@data` would override the implementations of `equals`, `hashCode`, and `toString`. The fields are accessible because they are marked as `val`. After the transformation of the `@data` macro annotation, the definition would be equivalent to:

```scala
class User(val name: String, val id: Int) {
  override def equals(that: Any): Boolean = that match
    case that: User => this.name == that.name && this.id == that.id
    case _ => false
  override def hashCode(): Int = ...
  override def toString(): Int = s"User($name, $id)"
}
```


### Example 2: Memoize

Memoization is a common optimization technique that stores the results of expensive function calls and returns the cached result when the same inputs occur again. This optimization can be implemented using a macro annotation, let us call it `@memoize`. For example, we could define a memoized version of the Fibonacci function as follows:

```scala
@memoize
def fib(n: Int): Int =
  if n <= 1 then n else fib(n - 1) + fib(n - 2)
```

The `@memoize` will add a new `val` outside the annotated definition to store the cache. This cache will then be used to
in the modified definition of `fib`.


```scala
private val fibCache$1: scala.collection.mutable.Map[Int, Int] =
  scala.collection.mutable.Map.empty[Int, Int]

def fib(n: Int): Int =
  val cache = fibCache$1
  if !cache.contains(n) then
    cache(n) = if n <= 1 then n else fib(n - 1) + fib(n - 2)
  cache(n)
```

### Example 3: Main annotation

In this example, we define an `@httpMain` that will generate a `main` method in a similar way to the `@main` method in Scala 3.


```scala
@httpMain def httpGet(url: URL) = println(http.get(url))
```

The `@httpMain` will generate a new class named `httpMain` with a `main` method that will call the annotated definition with the parsed arguments. Note that the syntax below is not valid Scala, but a representation of the TASTy code that would be generated. In particular, the top-level definition is moved into its package object `Example3$package`, and the
`main` method is generated in a class as a static method rather than an object.

```scala
object Example3$package {
  def httpGet(url: URL) = println(http.get(url))
}

class httpGet {
  <static> def main(args: Array[String]): Unit =
    args match
      case Array(urlString) => Example3$package.httpGet(URL.parse(url))
      case _ => println("Usage: httpGet <url>")
}
```

## Design constraints

Macro annotations must integrate with existing language features.

* Integrate with existing macro system and safety guarantees
* Separate/incremental compilation
* IDE support

### Integrate with existing macro system and safety guarantees
We want seamless integration with the existing macro system. This means that macro annotations should be able to use the reflection API and the multi-stage programming abstractions (quoted expressions  `'{...}`). This also implies that we do not need to invent yet another new metaprogramming API for macro annotations.

### Separate/incremental compilation
Supporting incremental compilation implies that macro annotations must not affect the typing process.
Otherwise, the order in which files are compiled/typed would introduce non-deterministic typing outcomes.

### IDE/tools support
For this, we rely on the representation of the program in TASTy. The IDEs or other tools should not get confused by changes in definitions caused by macro annotations. In other words, the TASTy representation of the program must contain all the information of the original definitions.


## Proposed solution

The library will define a macro annotation trait that will define a tree transformation operation using the `scala.quotes.Quotes` API.
A macro implementation is a class that extends this trait and implements the `transform` method.
Then a definition annotated with the concrete macro annotation will be transformed by the macro implementation at compile time.

### High-level overview

The `MacroAnnotation` annotation trait would be defined as follows:

```scala
package scala.annotation
import scala.quoted.*

/** Annotation classes that extend this trait will be able to transform the annotated definition. */
trait MacroAnnotation extends StaticAnnotation:
  /** Transforms a tree `definition` and optionally adds new definitions.
   *
   *  This method takes as an argument the `definition` that will be transformed by the macro annotation.
   *  It returns a non-empty list containing the modified version of the annotated definition.
   *  The new tree for the definition must use the original symbol.
   *  New definitions can be added to the list before or after the transformed definitions, this order
   *  will be retained. New definitions will not be visible from outside the macro expansion.
   *
   *  For classes or objects annotated with a macro annotation, we also provide an optional `companion`
   *  containing the class or object companion. It is `None` if the definition does not have a companion.
   *  If `definition` represents the class, then the companion is the module class of the object; if the
   *  `definition` is a module class, then the companion is the class itself. This companion can be
   *  transformed in the same way as the `definition`. If transformed it should be added to the resulting
   *  list of definitions.
   *
   *  If the definition is a `def` with a macro annotated parameter, the `companion` will contain the macro
   *  annotated parameter.
   *  ...
   */
  def transform(using Quotes)(
    definition: quotes.reflect.Definition,
    companion: Option[quotes.reflect.Definition]
  ): List[quotes.reflect.Definition]
```

The definition of the `@memoize` macro annotation of _Example 2_ would be defined as follows:


```scala
import scala.quoted.*

class memoize extends MacroAnnotation:
  def transform(using Quotes)(
    definition: quotes.reflect.Definition,
    companion: Option[quotes.reflect.Definition]
  ): List[quotes.reflect.Definition] =
    ... // Returns 2 trees: the cache definition and the modified `tree` definition
```
A full implementation of a `@memoize` macro annotation can be found on the [MacroAnnotation documentation][MemoizeMacro].


### Specification

The specification of macro annotations consists of 3 parts: the definition of the macro annotation trait, the evaluation of macro annotations, and the restrictions of macro annotations


#### Additions to the Standard Library

The only addition we need is the interface definition for the macro annotation.

```scala
package scala.annotation
import scala.quoted.*

trait MacroAnnotation extends StaticAnnotation:
  def transform(using Quotes)(
    definition: quotes.reflect.Definition,
    companion: Option[quotes.reflect.Definition]
  ): List[quotes.reflect.Definition]
```

The other library definition we need is `Quotes`, which is already part of the standard library for inline macro definitions.
This interface can be used as it is, but we might need to add some new methods to it.
For example, we need to extend the symbol constructors to allow the creation of any kind of definition.
Some of these were not implemented as they were not useful in the context of inline macros (local definitions).

#### Evaluation of Macro Annotations

##### Macro Implementation Definition
Compiling a macro annotation definition does not have any special requirements.

These are just regular Scala definitions that implement the `MacroAnnotation` trait.
This is similar to a macro implementation of an inline macro, in that case, implementation is
just a `def` that returns an `Expr`, in our case it returns a `List[Definition]`.

##### Macro Expansion

The expansion of macro annotations should be performed at the same time as inlining.
Macro annotations might generate code that needs to be inlined, for example: a reference to the `assert` method.
Inlined code could also generate code that contains a macro annotation that needs to be expanded, for example: `'{ @memoize def f(x: T): U = ...; f }`.
Macro annotations in quoted code will not be expanded until that code is inlined.
However, the reflection API currently restricts the addition of annotations to new symbols.

The inlining phase is located after the pickler phases, where the tree representation aligns with the TASTy.
This is important because the macro annotation implementation will need to manipulate the TASTy representation of the annotated definition.
The `Inlining` follows just after the `Pickler` phase, and therefore TASTy does not contain the expanded representation of the trees.
By making this expansion after pickling we guarantee that the TASTy representation does not contain any generated definition.
This is important to make the typing process deterministic, a new definition would affect the typing of the rest of the program.

The process of expanding a macro annotation is similar to the process of evaluating the top-level splice of an inline macro.
The macro annotation `@myMacroAnnot(x, y, z)` is evaluated/interpreted by the compiler,
with the same interpreter used to interpret the contents of a top-level splice.
This implies that all arguments to the macro annotation must be constant values.
In this case result of the interpretation is an instance of `MacroAnnotation`.
We then call the `transform` method of this instance with the `tree` of the annotated definition.
The result of this call is a list of definitions that will replace the original definition.

Once we have the list of definitions that the macro annotation has generated we need to insert them into the current context.
If it was a local definition we will just insert all generated definitions in order.
Otherwise, if the definition was in a class, we need to enter them into that class.
Note that the reflection API is prohibited from modifying symbols and therefore cannot enter the new definitions into the class.
The symbols created with reflection will just state its the owner; it is up to the compiler to perform sanity checks and then enter the new definitions into the class.
As we do not want to allow a macro annotation to see new definitions added by another macro annotation, we will enter the symbol at the end of the inlining phase.


##### Expansion order

A definition might be annotated with multiple macro annotations, for example: `@log @memoize def fib(n: Int) = ...`.
We need to expand them in some reliable order, either left to right or right to left.
These two orders are equivalently acceptable, as a tiebreaker we use the right to left that Scala 2 macro annotations chose.
It is not encouraged to write one macro annotation relying on another that annotates the same definition.
If so, the user could write a new macro annotation that does this transformation calling into the implementation of the other macro annotations.
If we have macro annotated parameters we start from the last one, to follow the same order as the annotations of the definition itself.

We can also have a macro annotation on a definition that is nested in a macro annotated definition.
We can also follow the Scala 2 design and expand the outer macro annotation first, then the inner ones.

#### Restrictions

The `MacroAnnotation.transform` methods imposed some restrictions on what can be returned in the list of definitions.

* The list must contain the transformed definition (a definition with the same symbol as the original definition).
* If a companion is modified, it must be returned in the list of definitions and must have the original symbol.
* All definitions in the result must have the same owner. The owner can be recovered from `Symbol.spliceOwner`.
* Special case: an annotated top-level `def`, `val`, `var`, `lazy val` can return a `class`/`object`
                definition that is owned by the package or package object.
* Can not return a `type`. At this abstraction level, they are not really useful.
* Annotated top-level `class`/`object` can not return top-level `def`, `val`, `var`, `lazy val`.
* Trees must be well-formed TASTy definition. `-Xcheck-macros` and `-Ycheck` will help to enforce this.



### Compatibility
The introduction of the `MacroAnnotation` trait does not affect binary nor TASTy compatibility.

Crucially, macro annotations cannot break TASTy compatibility because they are evaluated after pickling.
They should also not break binary compatibility as newly added definitions by macro annotations are assumed to only be used from within the macro expanded code.

Macro annotations can be used to patch binary incompatibilities by generating an old definition that is not generated anymore.


### Feature Interactions


#### Typer
As we transform the definitions after pickling, the typer only needs to be able to type the reference to the macro annotation.

#### Separate/incremental compilation
As we transform the definitions after pickling, separate/incremental compilation is not affected by the macro annotations.

#### Compilation suspension
Inline macros introduced the concept of compilation suspension to be able to define and use a macro in the same project.
If a call to a macro discovers statically or dynamically that it cannot expand because the macro implementation has not been compiled yet, then the compiler will suspend the compilation of the file that contains the macro call.
Once it finishes the compilation of other files, the compiler will retry the compilation of the suspended file.
This is repeated until all files are compiled or no progress is made due to a cyclic macro dependency.

This same idea applies to macro annotations. The macro annotation `transform` method must be compiled before we can expand the macro annotation in another file.

#### Inlining and macros
Given that macro annotations can generate code that contains inline calls and that inline code can contain macro annotations,
we need to ensure that after any expansion of a macro annotation or inline call we check for further macro expansions.
This is already the case with inline call, we just need to make sure the same happens for macro annotations.

Transparent inline method calls generated by macro annotations will expand in the same way as a transparent inline method call inside a non-transparent inline method definition.
This implies that the type refinement of the transparent inline method will be limited and will not affect the typing process.


#### IDE and other tools

Given that we only generate code after pickling, the IDEs that consume TASTy will be agnostic to these changes. All
definitions will align with source and typer as it does for non-annotated classes.


## Alternatives

### Change the order of evaluation of nested macro annotations
There might be reasons to evaluate the inner macro annotation first, then the outer one.
In that case, we have two options: we reverse the order of evaluation, or we add the ability to specify the order of evaluation of each macro annotation.

### Allow new definitions to be macro annotated
This would complicate the expansion process. The traversal of macro annotations and expansion order would need to be refined.
The motivation to not add this feature is performance and simplicity.
If users are allowed to do this they would use it as a simple crutch in all their implementation.
But they can do the same by just instantiating the macro annotation and calling the `transform` method themselves.

### Expand macro annotations while typing

I a way, these would be equivalent to transparent macro annotations.
The issue is that they should not expose new APIs to avoid problems with compilation order.
This would defeat the purpose of adding transparent macro annotation.


## Related work

* The initial design space exploration was done by Zhendong Ang as part of his Master's Thesis at EPFL. His work was reported in [Macro Annotations for Scala 3][Zhendong].
* The design of the Scala 3 inlining, multi-stage programming, and reflection can be found in [Scalable Metaprogramming in Scala 3][Stucki].
* An experimental version of `MacroAnnotation` is already available in 3.3 and 3.4. This version does not yet include the `companion` parameter (see [#19676][Companion]).
* Scala 3 macro annotations were motivated by the [Scala 2 macro annotations][Scala2Macros]. Detailed description of Scala 2 macros can be found in [Ch 5.3 of Unification of Compile-Time and Runtime Metaprogramming in Scala][Burmako]


## FAQ


[Zhendong]: https://infoscience.epfl.ch/record/294615?ln=en
[Stucki]: https://infoscience.epfl.ch/record/299370?ln=en
[Scala2Macros]: https://docs.scala-lang.org/overviews/macros/annotations.html
[Burmako]: https://infoscience.epfl.ch/record/226166?ln=en
[MemoizeMacro]: https://github.com/lampepfl/dotty/blob/cba1cfc3191835dd6821ef5bcd204fc4459697da/library/src/scala/annotation/MacroAnnotation.scala#L43-L71
[EqualsMacro]: https://github.com/lampepfl/dotty/blob/cba1cfc3191835dd6821ef5bcd204fc4459697da/library/src/scala/annotation/MacroAnnotation.scala#L99-L184
[Companion]: https://github.com/lampepfl/dotty/issues/19676