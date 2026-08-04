---
layout: sip
number: 85
permalink: /sips/:number.html
redirect_from:
  - /sips/:title.html
  - /sips/:number
stage: implementation
status: under-review
presip-thread: https://contributors.scala-lang.org/t/pre-sip-compile-time-specialization/7524/1
title: Compile-time Specialization
---

**By: James You, Hamish Starling, and Timothée Andres**

**Supervisors and advisors: Solal Pirelli and Oliver Bracevac**

## History

| Date          | Version                    |
|---------------|----------------------------|
| Aug 4th  2026 | Initial Draft              |
| Sep 22nd 2026 | Revised Draft              |
| Oct 19th 2026 | Draft with SIP \#          |
| Nov 18th 2026 | Detailed Semantics         |
| Nov 18th 2026 | Support Variance           |


## Summary

<!--
A summary of the proposed changes. This should be no longer than 3 paragraphs. It is intended to serve in two ways:

- For a first-time reader, a high-level overview of what they should expect to see in the proposal.
- For returning readers, a quick reminder of what the proposal is about.
-->

This proposal presents a pair of complementary features, _inline_ traits and _specialized_ traits, 
to provide compile-time specialization for Scala 3. 
This is presented as the following features:

1. Permit the `inline` modifier for traits and new semantics to accompany it.
2. The `Specialized` type class, intended to be use in compile-time erased context bounds to generate specializations. 

## Motivation

<!--
A high-level overview of the proposal with:

- An explanation of the problems or limitations that it aims to solve,
- A presentation of one or more use cases as running examples, with code showing how they would be addressed *using the status quo* (without the feature), and why that is not good enough.

This section should clearly express the scope of the proposal. It should make it clear what are the goals of the proposal, and what is out of the scope of the proposal.
-->

Specialization is one of the few remaining desirable features from Scala 2 that is still missing from Scala 3. 
The approach described in this proposal falls somewhere in between the selective specialization of Scala 2 and full monomorphization.
Our approach addresses several pain points in the implementation of Specialization for Scala 2:

### 1. Multiple inheritance 
Specialization in Scala 2 often ran into cases where 
multiple inheritance was required in order to fully implement the inheritance relationship between multiple specializations. 
Consider the following example:
~~~scala
class B[T]
class A[T] extends B[T]

/* Would require the following inheritance hierarchy when specializing for Int
     B
   /   \
 A     B_Int
   \   /
   A_Int
*/
~~~
This relationship is impossible to fully express on the JVM so some specialization is lost. 
Our approach encapsulates specializable semantics inside traits, which permit multiple inheritance as traits are not super types.

### 2. Unused class fields

Consider the following example:
~~~scala
class A[T](val x: T)

class A_Int(override val x: Int) extends A[Int](x)
~~~

This would roughly compile to:
~~~scala
class A extends Object {
  private val x: Object
  def x(): Object = this.x
}

class A_Int extends A {
  private val x: Int
  override def x(): Int = this.x
  override def x(): Object = Int.box(this.x())
}
~~~

There are two fields named `x` in this class hierarchy, as `A_Int` could be used interchangeably with `A`. 
Furthermore, bridge methods have to be introduced so that `A_Int` can be used in polymorphic contexts.
By extension, specialization based on classes, as they are in Scala 2, means these same problems exist.
Using traits for specialization avoids this issue altogether.

### 3. Possible code explosion 
Marking a class as `@specialized` in Scala 2 meant that a specialization for that class was generated for every primitive and reference type combination (`9^n where n is # of type parameters`). Users could manually restrict which type parameters to specialize as workaround. [Miniboxing](http://scala-miniboxing.org/) also provided automated solution to reusing specialization between similar types. However it had complications when specializing classes with array members.

Our approach specializes on-demand for type arguments that are present in the current compilation.

## Proposed solution 

<!--
This is the meat of your proposal.
--> 

<!--
A high-level overview of the proposed changes, and how they allow to better solve the running examples. This section should be example-heavy, and not dive into corner cases.

Example:

~~~ scala
// This is an @main method
@main def foo(x: Int): Unit =
  println(x)
~~~
-->

### Inline Traits

An `inline` trait is a trait defined with a `inline` modifier.

~~~scala
inline trait Vec[T: Numeric](elems: Array[T]):
  private val num = summon[Numeric[T]]

  def length = elems.length

  def apply(i: Int): T = elems(i)

  def scalarProduct(other: Vec[T]): T =
    require(this.length == other.length)
    var result = num.fromInt(0)
    for i <- 0 until length do
      result = num.plus(result, num.times(this(i), other(i)))
    result
~~~
 
Inline traits may be extended by: 
  - objects
  - classes 
  - other inline traits. 
  - ordinary traits, only if the inline trait does not take parameters

Inline traits may extend:
  - other inline traits
  - ordinary traits, without the benefit of inlining

When an inline trait is extended, all its definitions are inlined, and adapted to the context of the extending type. 
This means that:
- references to type parameters of the inline trait are specialized to the type arguments provided during extension
- `this` calls are updated to refer to the inline receiver.

For example, a concrete implementation of `Vec[T]` might look like:

~~~scala
class IntVec(elems: Array[Int])(using Numeric[Int]) extends Vec[Int](elems):
  override def length = elems.length

  override def apply(i: Int): Int = elems(i)

  override def scalarProduct(other: Vec[Int]): Int =
    require(this.length == other.length)
    var result = 0
    for i <- 0 until length do
      result = this.num.plus(result, this.num.times(this(i), other(i)))
    result
~~~

However, inline traits are not able to fully specialize code where interfaces are required. 
Consider the following code:

~~~scala
def first(v: Vec[Int]): Int = v(0)
~~~

The call to `v.apply` will refer to the version of `apply` typed `apply: T` which becomes `apply: Object` after the erasure phase, 
because we accessed `apply` on an object of declared type `Vec` (even though `v`'s runtime type may be `IntVec`). 
This in turn requires the insertion of additional bridge methods into subclasses of inline traits:

~~~scala
class IntVec(elems: Array[Int])(using Numeric[Int]) extends Vec[Int](elems):
  // as before
  
  override def apply(i: Int): Object = Int.box(this.apply(i))
  override def scalarProduct(other: Vec): Object = 
    Int.box(this.scalarProduct(other))

def first(v: Vec[Int]): Int = Int.unbox(v(0))
~~~

### Specialization of Inline Traits

We introduce the `Specialized` typeclass as a optional context bound on inline traits and inline methods to address the specific shortcoming with interfaces. 

~~~scala
package scala.specialize

sealed trait Specialized[T] extends compiletime.Erased
~~~

The `Specialized` context bound indicates specializations of the inline traits should be generated.
References to specific instantiations of inline traits are replaced by references to their specializations.
Consider our previous example with the `Specialized` context bound:

~~~scala
inline trait Vec[T: {Specialized, Numeric}](elems: Array[T]):
  // as before

inline trait Vec$sp$Int extends Vec[Int]:
  def length: Int
  def apply(i: Int): Int
  def scalarProduct(other: Vec$sp$Int): Int

class IntVec(elems: Array[Int])(using Numeric[Int]) 
  extends Vec[Int](elems), Vec$sp$Int: 
  // as before
~~~ 

The generation of a new specialized interface resolves our previous interface issue with invoking bridge methods because we relied on interfaces after erasure.

~~~scala
def first(v: Vec$sp$Int): Int = v(0)
~~~

Inline traits and specialized traits have an experimental implementation. 
A more in-depth overview of how they are implemented is given in the documentation provided as part of the [pull request](https://github.com/scala/scala3/pull/26156).

### Specification

<!--
A specification for the proposed changes, as precise as possible. This section should address difficult interactions with other language features, possible error conditions, and corner cases as much as the good behavior.

For example, if the syntax of the language is changed, this section should list the differences in the grammar of the language. If it affects the type system, the section should explain how the feature interacts with it.
-->

<!--
A discussion of how the proposal interacts with other language features. Think about the following questions:

- When envisioning the application of your proposal, what features come to mind as most likely to interact with it?
- Can you imagine scenarios where such interactions might go wrong?
- How would you solve such negative scenarios? Any limitations/checks/restrictions on syntax/semantics to prevent them from happening? Include such solutions in your proposal.
-->

Our proposal does not require any changes to existing syntax.
Inline traits have restricted sets of semantics when compared to normal traits.
In this section, we will provide a high-level overview of those semantics as well as how they fit into Scala as a whole.

#### Inlining Rules of Inline Traits

This set of rules govern how members are inlined into their respective subclasses.

```scala
inline trait A[T](val p: T) {} ; class C extends A[U](p = e) {}
⟶ inline trait A[T](val p: T) {} ; class C extends A[U] { private val p : U = e } (Inline-Parameters)


inline trait A[T] { def m: T = e } ; class C extends A[U] {}
⟶ inline trait A[T] { def m: T }; class C extends A[U] { override def m: U = e } (Inline-Method-Def)


inline trait A[T] { def m: T = e } ;  class C extends A[U] { override def m: T }
⟶ inline trait A[T] { def m: T } ;  class C extends A[U] { override def m: T ; private def m$: U = e } (Inline-Override-Method-Def)


inline trait A[T] { private val x: T = e } ; class C extends A[U] {} 
⟶ inline trait A[T] {} ; class C extends A[U] { private val x$: U = e }(Inline-Private-Val)
     
     
inline trait A { inline def m = e } ; class C extends A { m() ; ??? }
⟶ inline trait A {} ; class C extends A { e ; ??? } (Inline-Inline-Def)
 

inline trait A { inline def v = e } ; class C extends A { m() ; ??? }
⟶ inline trait A {} ; class C extends A { e ; ??? } (Inline-Inline-Val)
```

#### Rules of Specialized Traits

A specialization is any applied type $A[T_1, ..., T_n]$ where $T_1 ...T_n $ are constant types and $A$ is a specialized trait.
The specialization is chosen by $$Specialization(A[T_1, ..., T_n]) = A[SpecializingType(T_1), ..., SpecializingType(T_n)]$$

Where `SpecializingType` is given as:

```

—————————————————————————————————————————————————————————— (Specialzing-Type-Nothing)
           SpecializedType(Nothing) = Nothing

  T <: C, C extends one of {Any, AnyVal, AnyRef, Object}
—————————————————————————————————————————————————————————— (Specializing-Type-General)
                SpecializingType(T) = C

```

While the actual code generation of specializations is handled by our implementation,
the lowering of references to specializations is implemented as an extension to type erasure.

We propose the following extensions to the existing erasure rules given in the Scala specification.
We use the type `A` without any type parameters to denote the original erased type without specialization.

$$|A[T_1, ..., T_n]| = A\ \text{if}\ \exist i, T_i\ \text{has variance}\ \text{(variance specialization)}$$
$$|A[T_1, ..., T_n]| = A\ \text{if}\ \forall i, T_i \in \{Any, AnyVal, AnyRef, Object\} \text{(erasure equivalent)}$$
$$|A[T_1, ..., T_n]| = Specialization(A[T_1, ..., A_n])\ \text{(specialized erasure)}$$


#### Mixins & Inline Traits

Types may mix in multiple inline traits with colliding member names. 
This follows the same rules as normal traits and can be disambiguated with an override. 
```scala
inline trait A:
    def foo = "Hello World"

inline trait B:
    def foo = "Bonjour"

class C extends A, B // error: C inherits conflicting members A.foo and B.foo
```
A typical way to disambiguate would be using `super`. For example:
```scala
class C extends A, B:
    override def foo = super[A].foo 
```
It should be noted that it is not possible to make a direct call to the
method on A or B.  
Therefore, overridden methods are inlined into the extending type with a mangled name,
`A$$foo`, `B$$foo` and the `override def foo` in `C` will delegate to one of these methods. 
Super calls to non-overridden methods are transformed to point directly to the corresponding inlined methods.

Further, inline traits may not contain `super` references to classes or non-inline traits. 
`super` references in scala may only reference direct parents.
After inlining, references to direct parents in the original inline traits would no longer reference direct parents.

#### Private Members of Inline Traits
Inline traits may define private members.
Private fields in the inline trait are inlined as private fields with a mangled name in the inheriting type.
Similar to overriden methods described in the prior subsection, the name mangling follows the same format.
In general, this mangling should be opaque to client code but we expand upon it here to clarify that there is disambiguation.
It is otherwise an implementation detail and ensures there is no name collision from other inline traits.
Private fields should not be accessible in the inline trait itself, so they are deleted after trait inlining.

```scala
inline trait A(b: Boolean):
    private val x: Int = 1
    def foo(): Int = if b then x + 1 else 0

class B extends A(true)
```
Is inlined to:

```scala
inline trait A(b: Boolean):
    def foo(): Int

class B extends A(true):
    private val A$$b: Boolean = true
    private val A$$x: Int = 1
    override def foo(): Int = if this.A$$b then this.A$$x.+(1) else 0
```

#### Inheriting Inline Traits
Inline traits with parameters cannot be extended by ordinary traits.
Consider the following example:
~~~ scala
inline trait A[T](x: T):
    val y = x
trait B extends A[Int]
class C extends A[Int](10), B
~~~

After inlining, `B.y` is defined in terms of `B.A$$x` (the inlined copy of the parameter accessor of `x`), 
but this is undefined as we don't have the parameter value in `B` due to the trait parameter passing rules that insist that `C` passes the parameters directly.

#### Inline Members & Inline Traits
Inline traits may define inline members (e.g. `inline def`, `inline val`). 
These defs are inlined as the body of the trait is inlined into the inheriting type, 
but the members themselves are not inlined and they are deleted from the parent trait.
~~~scala
  inline trait A:
    inline val x = 1

  class B extends A:
    def f = x
~~~
After inlining and inline trait resolution:    
~~~scala
  inline trait A
  class B extends A:
    def f = 1
~~~

`inline val` members must have constant value types. 
They may not take the value of a parameter to the inline trait, 
even if these be known at inlining time:

~~~scala
inline trait A[T](x: T):
  inline val y = 1
  inline val a = y // ok
  inline val z = x // Not ok
~~~

#### Anonymous Specialized Classes

~~~scala
val v = new Vec[Int] {}
~~~

Anonymous class instances acting as instances of specialized traits are subject to the following restrictions:
  - can extend a single specialized trait
  - cannot mix in further classes or traits
  - cannot contain member definitions.
  
These restrictions ensure that all anonymous class instances of the same specialized trait use the same specializations.

#### `sealed` Specialized Traits

While specialized traits may be `sealed`, the generated `$sp$` traits and implementation classes extending the `sealed` specialized trait may potentially be from another compilation unit. 

We want to allow for example:
~~~scala
// (1)
// A.scala
sealed inline trait Foo[T: Specialized]

// B.scala
def foo(x: Foo[String])
~~~
This would normally be allowed with ordinary traits, but the process of specialization creates `Foo[String]` in the compilation unit of `B.scala` which is an illegal child of the sealed `Foo[String]`.

We are also faced with the question of whether we should allow the following:
```scala
// (2)
// A.scala
sealed inline trait Foo[T: Specialized]
val x = new Foo[Int]() {}

// B.scala
val y = new Foo[Int]() {}
```
In the bytecode `x` and `y` may or may not point to the same `Foo[Int]` depending on if we share the generated specialized classes, but in the source code `y` extends `Foo[Int]` illegally.

In both cases we essentially opt for the "source code" interpretation as this seems clearest for users. 
In particular we allow example (1) but not example (2). 
This happens automatically because `sealed` trait inheritance checking is done before specialized trait desugaring / erasure, so it is unaware of generated specializations and implementation classes. 

In terms of exhaustivity checking, we also want this to work within a single file. 
Consider:
```scala
sealed inline trait List[+T: Specialized]
sealed inline trait Nil[T: Specialized] extends List[T]
sealed inline trait :+:[T: Specialized](h: T, t: List[T]) extends List[T]

val xs: List[Double] = new Nil[Double]() {}

def foo(x: List[Double]): Unit = x match {
  case xs: :+:[_] => println("Cons case")
  case _: Nil[_] =>  println("Nil case")
  // warning: non-exhaustive pattern match, missing case _: List[Double]
}
```

The anonymous class `Nil[Double]` also extends the `List[Double]` interface because anonymous class instances mixin all ancestor traits if there are parameters to pass. 
Therefore pattern match exhaustivity checking on `List[Double]` requires `_: List[Double]` because the List trait has anonymous class children and so we are unable to decompose `_: List[Double]` to children in space checking. 
This occurs because we don't do specialized class replacement until erasure so the anonymous class is still around. 
For that reason we exempt anonymous classes extending specialized traits from being treated as children for pattern match exhaustivity checking, but we do treat the concrete classes as children because the anonymous classes will not exist at runtime when the pattern matches run, rather having been replaced by the concrete specialized classes.

Furthermore, the generated `List$sp$Double` trait also interferes.

```scala
def foo(x: List[Double]): Unit = x match {
  case xs: :+:[_] => println("Cons case")
  case _: Nil[_] =>  println("Nil case")
  // warning: non-exhaustive pattern match, missing case _: List[Double] & List$sp$Double
}
```

We don't register the compiler generated specialized traits as children of the original specialized trait for exhaustivity checking. 
This is safe because these traits are synthetic and the following invariant `T <:< Foo[Int] <=> T <:< Foo$sp$Int`, so users cannot match on `Foo$sp$Int])` or `Foo[Int] minus Specialized(Foo[Int])`

#### Variance

If an `inline trait A[+T: Specialized]` has variant specialized type parameters, `A[+T]` erases to `A`.
because a value of type `A[Int]` could then be interpreted as:
1. an unspecialized `A[Any]` (contravariance) 
2. `A[Nothing]` (covariance). 

However, the actual object itself may still be specialized. 
To keep the benefit of specialization when the value is specialized, we perform the following transformation:
```
q is an inline trait
q has specialized type parameters
q has variant type parameters
————————————————————————————————————
q.m(args) 
⟶ { 
  val q' = q ;
  val args' = args ;
  if q'.isInstanceOf[A[Int]] then q'.asInstanceOf[A[Int]].m(args') 
  else q'.m(args') 
}
```

### Compatibility

<!--
A justification of why the proposal will preserve backward binary and TASTy compatibility. Changes are backward binary compatible if the bytecode produced by a newer compiler can link against library bytecode produced by an older compiler. Changes are backward TASTy compatible if the TASTy files produced by older compilers can be read, with equivalent semantics, by the newer compilers.

If it doesn't do so "by construction", this section should present the ideas of how this could be fixed (through deserialization-time patches and/or alternative binary encodings). It is OK to say here that you don't know how binary and TASTy compatibility will be affected at the time of submitting the proposal. However, by the time it is accepted, those issues will need to be resolved.

This section should also argue to what extent backward source compatibility is preserved. In particular, it should show that it doesn't alter the semantics of existing valid programs.
-->

While inline traits are compiled to pure interfaces, libraries compiled with older versions of the compiler cannot take advantages of the benefits 
offered by inline traits. 
Binary and TASTy linking compatibility may be maintained by maintaining the appropriate bridge methods in specialized classes. 
However, this is still up for discussion.

### Other concerns

<!--
If you think of anything else that is worth discussing about the proposal, this is where it should go. Examples include interoperability concerns, cross-platform concerns, implementation challenges.
-->

### Open questions

<!--
If some design aspects are not settled yet, this section can present the open questions, with possible alternatives. By the time the proposal is accepted, all the open questions will have to be resolved.
-->

## Alternatives

<!--
This section should present alternative proposals that were considered. It should evaluate the pros and cons of each alternative, and contrast them to the main proposal above.

Having alternatives is not a strict requirement for a proposal, but having at least one with carefully exposed pros and cons gives much more weight to the proposal as a whole.
-->

1. Revive the `@specialized` annotation: see [discussion on reviving `@specialized`](https://github.com/scala/scala3/issues/15532)

## Related work

<!--
This section should list prior work related to the proposal, notably:

- A link to the Pre-SIP discussion that led to this proposal,
- Any other previous proposal (accepted or rejected) covering something similar as the current proposal,
- Whether the proposal is similar to something already existing in other languages,
- If there is already a proof-of-concept implementation, a link to it will be welcome here.
-->

1. [Discussion on reviving `@specialized`](https://github.com/scala/scala3/issues/15532)
2. [Discussion on specialization for Scala 3](https://github.com/scala/scala3/discussions/18044)
3. [Pull request for experimental implementation](https://github.com/scala/scala3/pull/26156)

## FAQ
