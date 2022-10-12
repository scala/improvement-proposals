---
layout: sip
title: SIP-49 - Polymorphic Eta-Expansion
stage: implementation
permalink: /sips/:title.html
---

**By: Quentin Bernet and Guillaume Martres** <!-- and Martin and Seb ?-->

## History

| Date          | Version            |
|---------------|--------------------|
| Sep 23th 2022 | Initial Draft      |

## Summary

<!-- A summary of the proposed changes. This should be no longer than 3 paragraphs. It is intended to serve in two ways:

- For a first-time reader, a high-level overview of what they should expect to see in the proposal.
- For returning readers, a quick reminder of what the proposal is about. -->

We propose to extend eta-expansion to polymorphic methods. 
This means automatically transforming polymorphic methods into corresponding polymorphic functions, for example:

~~~ scala
def f1[A](x: A): A = ???
val v1_1: [B] => B => B = f1 // f1 becomes [B'] => (y: B') => f1[B'](y)
val v1_2                = f1 // f1 becomes [A'] => (x': A') => f1[A'](x')
~~~

Returning readers, for a quick glance at a wide array of examples illustrated like the above, go to [High-level overview](#high-level-overview).

In the following, "Note" never introduces new concepts, it points out a non-obvious consequence, and/or reminds the reader of a pertinent fact about Scala.

## Motivation

<!-- A high-level overview of the proposal with:

- An explanation of the problems or limitations that it aims to solve,
- A presentation of one or more use cases as running examples, with code showing how they would be addressed *using the status quo* (without the feature), and why that is not good enough.

This section should clearly express the scope of the proposal. It should make it clear what are the goals of the proposal, and what is out of the scope of the proposal. -->

Regular eta-expansion is so ubiquitous that most users are not aware of it, for them it is intuitive and obvious that methods can be passed where functions are expected.

When manipulating polymorphic methods, we wager that most users find it confusing not to be able to do the same. 
This is the main motivation of this proposal.

It however remains to be demonstrated that such cases appear often enough for time and maintenance to be devoted to fixing it. 
To this end, the remainder of this section will show a manufactured example with tuples, as well as real-world examples taken from the [Shapeless-3](https://index.scala-lang.org/typelevel/shapeless-3) and [kittens](https://index.scala-lang.org/typelevel/kittens) libraries.


#### Tuples

~~~ scala
List(1, 2, 3).map(Some.apply) // works

("Hello", 2, 'u').map(Some.apply) // error:
// Found: Any => Some[Any], Required: [t] => (t) => Nothing
~~~

As tuples are becoming a powerful part of metaprogramming through `Mirror` instances, we expect these kinds of cases to become more and more frequent.

#### Shapeless ([source](https://github.com/typelevel/shapeless-3/blob/8b1bbc651618e77e0bd7c2433b79e46adafa4506/modules/deriving/src/test/scala/shapeless3/deriving/type-classes.scala#L651-L665))


In the shapeless library, polymorphic functions are used relatively often, but they are usually small and unique, making them not very suitable for refactoring.
There is however the following case, where a function is very large:

~~~ scala
...
  def readElems(s: String): Option[(T, String)] = {
    type Acc = (String, Seq[String], Boolean)
    inst.unfold[Acc]((s, labelling.elemLabels, true))(
      [t] => (acc: Acc, rt: Read[t]) => {
        val (s, labels, first) = acc
        (for {
          (_, tl0) <- if(first) Some(("", s)) else head(s, "(,)(.*)".r)
          (_, tl1) <- head(tl0, s"(${labels.head}):(.*)".r)
          (t, tl2) <- rt.read(tl1)
          } yield (t, tl2)) match {
            case Some(t, tl2) => ((tl2, labels.tail, false), Some(t))
            case None => ((s, labels, first), None)
          }
      }
      ) match {
        case (s, None) => None
        case (acc, Some(t)) => Some((t, acc._1))
      }
  }
~~~ 

By factoring out the function, it is possible to make the code more readable:

~~~ scala
...
  def readElems(s: String): Option[(T, String)] = {
    type Acc = (String, Seq[String], Boolean)
    val unfolder = [t] => (acc: Acc, rt: Read[t]) => {
      val (s, labels, first) = acc
      (for {
        (_, tl0) <- if(first) Some(("", s)) else head(s, "(,)(.*)".r)
        (_, tl1) <- head(tl0, s"(${labels.head}):(.*)".r)
        (t, tl2) <- rt.read(tl1)
        } yield (t, tl2)) match {
          case Some(t, tl2) => ((tl2, labels.tail, false), Some(t))
          case None => ((s, labels, first), None)
        }
    }
    inst.unfold[Acc]((s, labelling.elemLabels, true))(unfolder) match {
        case (s, None) => None
        case (acc, Some(t)) => Some((t, acc._1))
      }
  }
~~~ 

It is natural at this point to want to transform the function into a method, as the syntax for the latter is more familiar, and more readable:

~~~ scala
...
  def readElems(s: String): Option[(T, String)] = {
    type Acc = (String, Seq[String], Boolean)
    def unfolder[T](acc: Acc, rt: Read[T]): Acc = {
      val (s, labels, first) = acc
      (for {
        (_, tl0) <- if(first) Some(("", s)) else head(s, "(,)(.*)".r)
        (_, tl1) <- head(tl0, s"(${labels.head}):(.*)".r)
        (t, tl2) <- rt.read(tl1)
        } yield (t, tl2)) match {
          case Some(t, tl2) => ((tl2, labels.tail, false), Some(t))
          case None => ((s, labels, first), None)
        }
    }
    inst.unfold[Acc]((s, labelling.elemLabels, true))(unfolder) match {
      case (s, None) => None
      case (acc, Some(t)) => Some((t, acc._1))
    }
  }
~~~

However, this does not compile. 
Only monomorphic eta-expansion is applied, leading to the same issue as with our previous `Tuple.map` example.

#### Kittens ([source](https://github.com/typelevel/kittens/blob/e10a03455ac3dd52096a1edf0fe6d4196a8e2cad/core/src/main/scala-3/cats/derived/DerivedTraverse.scala#L44-L48))

In `Kittens`, there is a case of particularly obvious eta-expansion done by hand (comments by me):

~~~ scala
...
  final override def traverse[G[_], A, B](fa: F[A])(f: A => G[B])
      (using G: Applicative[G]): G[F[B]] =
    val pure = [a] => (x: a) => G.pure(x)                        //  eta-expansion
    val map = [a, b] => (ga: G[a], f: a => b) => G.map(ga)(f)    // ~eta-expansion
    val ap = [a, b] => (gf: G[a => b], ga: G[a]) => G.ap(gf)(ga) // ~eta-expansion
    inst.traverse[A, G, B](fa)(map)(pure)(ap)([f[_]] => (tf: T[f], fa: f[A]) => tf.traverse(fa)(f))

~~~

Sadly since `map` and `ap` are curried, assuming this proposal, only `pure` can be eliminated:

~~~ scala
...
  final override def traverse[G[_], A, B](fa: F[A])(f: A => G[B])
      (using G: Applicative[G]): G[F[B]] =
    val map = [a, b] => (ga: G[a], f: a => b) => G.map(ga)(f)
    val ap = [a, b] => (gf: G[a => b], ga: G[a]) => G.ap(gf)(ga)
    inst.traverse[A, G, B](fa)(map)(G.pure)(ap)([f[_]] => (tf: T[f], fa: f[A]) => tf.traverse(fa)(f))

~~~

This already helps with readability, but we can postulate that given cases like this, an uncurried variant of `map` and `ap` would be implemented, allowing us to write:

~~~ scala
...
  final override def traverse[G[_], A, B](fa: F[A])(f: A => G[B])
      (using G: Applicative[G]): G[F[B]] =
    inst.traverse[A, G, B](fa)(G.map)(G.pure)(G.ap)([f[_]] => (tf: T[f], fa: f[A]) => tf.traverse(fa)(f))
~~~

If wanted, we can then factor the function into a method:

~~~ scala
...
  final override def traverse[G[_], A, B](fa: F[A])(f: A => G[B])
      (using G: Applicative[G]): G[F[B]] =
    def traverser[F[_]](tf: T[F], fa: F[A]) = tf.traverse(fa)(f)
    inst.traverse[A, G, B](fa)(G.map)(G.pure)(G.ap)(traverser)
~~~

## Proposed solution

<!-- This is the meat of your proposal. -->

### High-level overview

As the previous section already describes how polymorphic eta-expansion affects the examples, we will use this section to give quantity of small, illustrative, examples, that should cover most of the range of this proposal.

In the following `id'` means a copy of `id` with a fresh name, and `//reminder:` sections are unchanged by this proposal.
#### Explicit parameters:
~~~ scala
def f1[A](x: A): A = ???
val v1_1: [B] => B => B = f1 // f1 becomes [B'] => (y: B') => f1[B'](y)
val v1_2                = f1 // f1 becomes [A'] => (x': A') => f1[A'](x')

def f2[A]: A => A = ???
val v2_1: [B] => B => B = f2 // f2 becomes [B'] => (y: B') => f2[B'](y)
val v2_2                = f2 // f2 becomes f2[Any]

type F[C] = C => C
def f3[A]: F[A] = ???
val v3_1: [B] => B => B = f3 // f3 becomes [B'] => (y: B') => f3[B'](y)
val v3_1:               = f3 // f3 becomes f3[Any]

//reminder:
val vErr: [B] => B    = ??? // error: polymorphic function types must have a value parameter
~~~

#### Extension/Interleaved method:
~~~ scala
extension (x: Int)
  def extf1[A](x: A): A = ???

val extv1_1: [B] => B => B = extf1(4) // extf1(4) becomes [B'] => (y: B')  => extf1(4)[B'](y)
val extv1_2                = extf1(4) // extf1(4) becomes [A'] => (x': A') => extf1(4)[A'](x')

val extv1_3: Int => [B] => B => B = extf1 // extf1 becomes (i: Int) => [B'] => (y: B')  => extf1(i)[B'](y)
val extv1_4                       = extf1 // extf1 becomes (i: Int) => [A'] => (x': A') => extf1(i)[A'](x')
~~~

#### Implicit parameters:
~~~ scala
def uf1[A](using x: A): A = ???
val vuf1_1: [B] => B ?=> B = uf1 // uf1 becomes [B'] => (y: B') ?=> uf1[B']
val vuf1_1:                = uf1 // uf1 becomes uf1[X](using x) where X and x are given by type and term inference


def uf2[A]: A = ???
val vuf2: [B] => B ?=> B = uf2 // uf2 becomes [B'] => (y: B') ?=> uf2[B']
val vuf2:                = uf2 // uf2 becomes uf2[X] where X is given by type inference

//reminder:
val get: (String) ?=> Int = 22 // 22 becomes (s: String) ?=> 22
val err: () ?=> Int       = ?? // error: context functions require at least one parameter
~~~

### Specification

<!-- A specification for the proposed changes, as precise as possible. This section should address difficult interactions with other language features, possible error conditions, and corner cases as much as the good behavior.

For example, if the syntax of the language is changed, this section should list the differences in the grammar of the language. If it affects the type system, the section should explain how the feature interacts with it. -->

Before we go on, it is important to clarify what we mean by "polymorphic method", we do not mean, as one would expect, "a method taking at least one type parameter clause", but rather "a (potentially partially applied) method whose next clause is a type clause", here is an example to illustrate:

~~~ scala 
extension (x: Int)
  def poly[T](x: T): T = x
// signature: (Int)[T](T): T

poly(4) // polymorphic method: takes a  [T]
poly    // monomorphic method: takes an (Int)
~~~

Note: Since typechecking is recursive, eta-expansion of a monomorphic method like `poly` can still trigger polymorphic eta-expansion, for example:

~~~ scala
val voly: Int => [T] => T => T = poly
// poly expands to: (x: Int) => [T] => (y: T) => poly(x)[T](y)
~~~

As this feature only provides a shortcut to express already definable objects, the only impacted area is the type system.

When typing a polymorphic method `m` there are three cases to consider:

#### With expected type
If the expected type is a polymorphic function taking `[T_1 <: U_1 >: L_1, ..., T_n <: U_n >: L_n]` as type parameters, `(A_1, ..., A_k)` as term parameters and returning `R`, we proceed as follows:

Note: Polymorphic functions always take term parameters (but `k` can equal zero if the clause is explicit: `[T] => () => T`).

1. Copies of `T_i`s are created, and replaced in `U_i`s, `L_i`s, `A_i`s and `R`, noted respectively `T'_i`, `U'_i`, `L'_i`, `A'_i` and `R'`.

2. Is the expected type a polymorphic context function ?
* 1. If yes then `m` is replaced by the following: 
~~~ scala
[T'_1 <: U'_1 >: L'_1, ... , T'_n <: U'_n >: L'_n] 
  => (a_1: A'_1 ..., a_k: A'_k)
  ?=> m[T'_1, ..., T'_n]
~~~
* 2. If no then `m` is replaced by the following: 
~~~ scala
[T'_1 <: U'_1 >: L'_1, ... , T'_n <: U'_n >: L'_n] 
  => (a_1: A'_1 ..., a_k: A'_k)
  => m[T'_1, ..., T'_n](a_1, ..., a_k)
~~~

3. the application of `m` is type-checked with expected type `R'`
* 1. If it succeeds, the above is the created tree.
* 2. If it fails, go to [Default](#Default).

At 3.2. if the cause of the error is such that [Default](#Default) will never succeed, we might return that error directly, this is at the discretion of the implementation, to make errors as clear as possible.

Note: Type checking will be in charge of overloading resolution, as well as term inference, so the following will work:
~~~ scala
def f[A](using Int)(x: A)(using String): A
def f[B](x: B, y: B): B

given i: Int = ???
given s: String = ???
val v: [T] => T => T = f
// f expands to: [T'] => (t: T') => f[T'](t)
// and then to:  [T'] => (t: T') => f[T'](using i)(t)(using s)

def g[C](using C): C
val vg: [T] => T ?=> T = g
// g expands to: [T'] => (t: T') ?=> g[T']
// and then to:  [T'] => (t: T') ?=> g[T'](using t)
~~~

Note: Type checking at 3. will have to recursively typecheck `m[T'_1, ..., T'_n](a_1, ..., a_k)` with expected type `R`, this can lead to further eta-expansion:
~~~ scala
extension [A](x: A)
  def foo[B](y: B) = (x, y)

val voo: [T] => T => [U] => U => (T, U) = foo
// foo expands to: 
// [T'] => (t: T') => ( foo[T'](t) with expected type [U] => U => (T', U) )
// [T'] => (t: T') => [U'] => (u: U') => foo[T'](t)[U'](u)
~~~

#### Without expected type

<!-- TODO: Think more about overloading

If there is no expected type, let `ms` be all the overloaded variants of `m` which take at least one explicit parameter clause.

If `ms` contains no variants, go to [Default](#Default).
If it contains more than one variant, throw an overloading error.

If `ms` contains exactly one variant, then it is expanded as follows:
-->

`m` has to contain at least one explicit term clause before the return type or the next type clause.
<details>
  <summary>Note</summary>
  Since the only way to create multiple type clause is with extension methods, and since they force an explicit clause, this condition is currently equivalent to "`m` contains explicit term parameters".
  The stricter wording was chosen to already accommodate [SIP-47 - Clause Interleaving](https://github.com/scala/improvement-proposals/pull/47).
</details>

If `m` doesn't satisfy the above condition, go to [Default](#Default), otherwise:

Assuming in all generality that `m` takes `[T_1 <: U_1 >: L_1, ..., T_n <: U_n >: L_n]` as type parameters and `(A_1, ..., A_k)` as the first clause of (explicit or implicit) term parameters, then `m` is expanded as follows:

1. Copies of `T_i`s are created, and replaced in `U_i`s, `L_i`s, `A_i`s and `R`, noted respectively `T'_i`, `U'_i`, `L'_i` and `A'_i`.

2. `m` is replaced by the following: 
~~~ scala
[T'_1 <: U'_1 >: L'_1, ... , T'_n <: U'_n >: L'_n] 
  => (a_1: A'_1 ..., a_k: A'_k) 
  => m[T'_1, ..., T'_n](a_1, ..., a_k)
~~~

3. `m[T'_1, ..., T'_n](a_1, ..., a_k)` is type-checked (with no expected type).

Note: Again, type checking takes care of term inference, and there is no overloading resolution necessary, as there is only one option by assumption.

#### Default

No polymorphic eta-expansion is performed, this corresponds to the old behaviour, written here as a reminder:

Fresh variables are applied to `m`, typing constraints are generated, and typing continues, for example:

~~~ scala
def ident[T](x: T): T = x

val idInt: Int => Int = ident
// ident becomes:
// ident[X] with expected type Int => Int
// (x: X) => ident[X](x) of type X => X with expected type Int => Int
// therefore X := Int
// (x: Int) => ident[Int](x)
~~~

### Compatibility

<!-- A justification of why the proposal will preserve backward binary and TASTy compatibility. Changes are backward binary compatible if the bytecode produced by a newer compiler can link against library bytecode produced by an older compiler. Changes are backward TASTy compatible if the TASTy files produced by older compilers can be read, with equivalent semantics, by the newer compilers.

If it doesn't do so "by construction", this section should present the ideas of how this could be fixed (through deserialization-time patches and/or alternative binary encodings). It is OK to say here that you don't know how binary and TASTy compatibility will be affected at the time of submitting the proposal. However, by the time it is accepted, those issues will need to be resolved.

This section should also argue to what extent backward source compatibility is preserved. In particular, it should show that it doesn't alter the semantics of existing valid programs. -->

#### Binary and TASTy

As this proposal never generates code that couldn't have been written before, these changes are binary and TASTy compatible.

#### Source

This proposal conserves source compatibility when an expected type is present, since either the behaviour is the same, or the code did not compile before.

This proposal breaks source compatibility only when there is no expected type, in this regard it is essentially a change in type inference, and therefore has all the usual repercussions of changing type inference, for example:

```scala
def foo[T](x: T): T = x
val voo = foo // was Any => Any is now [T] => T => T
val y = voo(5) // was Any is now Int

def lookFor[T](x: T)(using u: T): T = u
lookFor(foo) // was searching for Any => Any, is now searching for [T] => T => T
// This change in search can find different instances, and thus potentially wildly different behaviour !
```

While these examples might seem damming, this is the case every time we change type inference.

**Note:** All of this disappears if we change `val voo` to `val voo: Any => Any`, this is the reason it is recommended for library authors to always provide explicit types for all public members.

### Restrictions

Not included in this proposal are:

* Expanding `[T] => T => T` to `[T] => T => Id[T]` to make `tuple.map(identity)` work (might work out of the box anyways, but not guaranteed)
* Expanding `x => x` to `[T] => (x: T) => x` if necessary (and generalizations)
* Expanding `_` to `[T] => (x: T) => x` if necessary (and generalizations)
* Polymorphic SAM conversion
* Polymorphic functions from wildcard: `foo[_](_)`

While all of the above could be argued to be valuable, we deem they are out of the scope of this proposal. 

We encourage the creation of follow-up proposals to motivate their inclusion.

<!--
### Other concerns

If you think of anything else that is worth discussing about the proposal, this is where it should go. Examples include interoperability concerns, cross-platform concerns, implementation challenges. -->

### Open questions

<!-- If some design aspects are not settled yet, this section can present the open questions, with possible alternatives. By the time the proposal is accepted, all the open questions will have to be resolved. -->

#### Overloaded methods and no expected type

In the case where there is no expected type, there is nothing to guide overloading resolution, and it becomes complicated to find a good metric for the "best" variant, for example where we compare signatures in pairs:

~~~ scala
(A)(B): R
(A)(using C): R // currently more specific

(A)(B): R    // currently more specific
(A)[T](T): R

(A)(B): R          // currently more specific
(A)[T](using T): R // intuitively more specific ?
~~~

As we have not yet been able to find a good universal and intuitive heuristic, we propose to let this question be settled on during the implementation phase, as the ease of implementation can help to choose between equivalently good imperfect metrics.
<!--
## Alternatives
-->
<!-- This section should present alternative proposals that were considered. It should evaluate the pros and cons of each alternative, and contrast them to the main proposal above.

Having alternatives is not a strict requirement for a proposal, but having at least one with carefully exposed pros and cons gives much more weight to the proposal as a whole. -->
<!--
This feature adds no additional expressivity to the language, it is purely a shortcut.
Therefore the main alternative is doing it by hand, every time.
We hope this document already thoroughly presented the differences of both approaches.

No other alternatives have been imagined, the similarity between this proposal and already existing features making it difficult to conceptualise a different way forward.
-->
## Related work

<!-- This section should list prior work related to the proposal, notably:

- A link to the Pre-SIP discussion that led to this proposal,
- Any other previous proposal (accepted or rejected) covering something similar as the current proposal,
- Whether the proposal is similar to something already existing in other languages,
- If there is already a proof-of-concept implementation, a link to it will be welcome here. -->

* Pre-SIP: https://contributors.scala-lang.org/t/polymorphic-eta-expansion/5516
* A naive implementation can be found at https://github.com/lampepfl/dotty/pull/14015 (it is more general than this proposal and thus breaks compatibility)
* A compatibility-preserving implementation is in development.


## FAQ

<!-- This section will probably initially be empty. As discussions on the proposal progress, it is likely that some questions will come repeatedly. They should be listed here, with appropriate answers. -->
