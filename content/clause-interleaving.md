---
layout: sip
title: SIP-NN Clause Interleaving
stage: design
permalink: /sips/:title.html
---

**By: Quentin Bernet and Guillaume Martres and Sébastien Doeraene**

## History

| Date          | Version            |
|---------------|--------------------|
| May 5th 2022  | Initial Draft      |
| Aug 17th 2022 | Formatting         |

## Summary

We propose to generalize method signatures to allow any number of type parameter lists, interleaved with term parameter lists and using parameter lists. As a simple example, it would allow to define
~~~ scala
def pair[A](a: A)[B](b: B): (A, B) = (a, b)
~~~
Here is also a more complicated and contrived example that highlights all the possible interactions:
~~~ scala
def foo[A][B <: A](using a: A)(b: B)[C <: a.type, D](cd: (C, D))[E]: Foo[A, B, C, D, E]
~~~


## Motivation

We motivate the feature with two use cases:

* a `getOrElse` method for a heterogeneous key-value store, which is an occurrence of wanting a type parameter whose bounds are path-dependent on a term parameter, and

* a `fill` method for a statically sized collection, where we want to explicitly pass one type parameter, but leave the other one to be inferred.

### Heterogeneous key-value store
Consider an API for a heterogenous key-value store, where keys know what type of value they must be associated to:
~~~ scala
trait Key:
  type Value

class Store:
  def get(key: Key): key.Value = …
  def put(key: Key)(value: => key.Value): Unit = …
~~~
We want to provide a method `getOrElse`, taking a default value to be used if the key is not present. Such a method could look like
~~~ scala
def getOrElse(key: Key)(default: => key.Value): key.Value = …
~~~
However, at call site, it would prevent from using as default value a value that is not a valid `key.Value`. This is a limitation compared to other `getOrElse`-style methods such as that of `Option`, which allow passing any supertype of the element type.

In current Scala, there is no way to define `Store.getOrElse` in a way that supports this use case. We may try to define it as
~~~ scala
def getOrElse[V >: key.Value](key: Key)(default: => V): V = …
~~~
but that is not valid because the declaration of `V` needs to refer to the path-dependent type `key.Value`, which is defined in a later parameter list.

We might also try to move the type parameter list after `key` to avoid that problem, as
~~~ scala
def getOrElse(key: Key)[V >: key.Value](default: => V): V = …
~~~
but that is also invalid because type parameter lists must always come first.

A workaround is to return an intermediate object with an `apply` method, as follows:
~~~ scala
class Store:
  final class StoreGetOrElse[K <: Key](val key: K):
    def apply[V >: key.Value](default: => V): V = …
  def getOrElse(key: Key): StoreGetOrElse[key.type] = StoreGetOrElse(key)
~~~
This definition provides the expected source API at call site, but it has two issues:
* It is more complex than expected, forcing a user looking at the API to navigate to the definition of `StoreGetOrElse` to make sense of it.
* It is inefficient, as an intermediate instance of `StoreGetOrElse` must be created for each call to `getOrElse`.
* Overloading resolution looks at clauses after the first one, but only in methods, the above is ambiguous with any `def getOrElse(k:Key): ...`, whereas the proposed signature is not ambiguous with for example `def getOrElse(k:Key)[A,B](x: A, y: B)`

Another workaround is to return a polymorphic function, for example:
~~~scala
def getOrElse(k:Key): [V >: k.Value] => (default: V) => V = 
    [V] => (default: V) => ???
~~~
While again, this provides the expected API at call site, it also has issues:
* The behavior is not the same, as `default` has to be a by-value parameter
* The definition is hard to visually parse, as users are more used to methods (and it is our opinion this should remain so)
* The definition is cumbersome to write, especially if there are a lot of term parameters
* Methods containing curried type clauses like `def foo[A][B](x: B)` cannot be represented in this way, as polymorphic methods always have to have a term parameter right after.
* It is inefficient, as many closures must be created for each call to `getOrElse` (one per term clause to the right of the first non-initial type clause).
* Same problem as above with overloading

### Statically sized collection
Let us suppose we have defined a statically sized collection `Vec[N <: Int, +A]`, which contains exactly `N` elements of type `A`. Its API could look like the following:
~~~ scala
final class Vec[N <: Int, +A] private (...):
  def dot(that: Vec[N, A])(using Numeric[A]): Vec[N, A] = …
~~~
We would like to define a `fill` method for its companion object, as follows:
~~~ scala
object Vec:
  def fill[N <: Int, A](value: A)(using ValueOf[N]): Vec[N, A] = …
~~~
The implicit `ValueOf[N]` parameter would allow the implementation of `fill` to get hold of the run-time representation of `N` as an `Int` value, which is required to actually create a `Vec` of the right length.

We would like to use `fill` as follows:
~~~ scala
val v = Vec.fill[3]("hello")
~~~
We want to explicitly specify the `N` parameter as `3`, since there is no way for the compiler to infer it for us. However, we would like it to infer `A` as `String`. This is unfortunately not possible in current Scala, as either all or none of the type parameters for any given method must be provided.

The typical workaround would be to explicitly provide both type parameters:
~~~ scala
val v = Vec.fill[3, String]("hello")
~~~
If we feel strongly about inferring `A`, we can use a similar indirection with an intermediate class, as follows:
~~~ scala
object Vec:
  final class Fill[N <: Int]()(using ValueOf[N]):
    def apply[A](value: A): Vec[N, A] = …
  def fill[N <: Int](using ValueOf[N]): Fill[N] = Fill[N]()
~~~
which enables the desired call site API, with the same drawbacks that we already saw with the heterogeneous key-value store.

## Proposed solution
### High-level overview

To solve the above problems, we propose to generalize method signatures so that they can have multiple type parameter lists, interleaved with term parameter lists and using parameter lists. There can be multiple consecutive type parameter lists.

For the heterogeneous key-value store example, this allows to define `getOrElse` as follows:
~~~ scala
def getOrElse(key: Key)[V >: key.Value](default: => V): V = …
~~~
It provides the best of all worlds:
* A convenient API at call site
* A single point of documentation
* Efficiency, since the method erases to a single JVM method with signature `getOrElse(Object,Object)Object`

The `Vec.fill` method can be defined as follows:
~~~ scala
def fill[N <: Int][A](value: A)(using ValueOf[N]): Vec[N, A] = …
~~~
Since `N` and `A` are in two separate type parameter lists, it is possible to call it as
~~~ scala
val v = Vec.fill[3][String]("hello")
~~~
or, letting the second type parameter list be inferred by the compiler, as
~~~ scala
val c = Vec.fill[3]("hello")
~~~

### Specification
We amend the syntax of def parameter clauses as follows:
~~~
DefDcl            ::=  DefSig ‘:’ Type
DefDef            ::=  DefSig [‘:’ Type] ‘=’ Expr
DefSig            ::=  id [DefParamClauses] [DefImplicitClause]
DefParamClauses   ::=  DefParamClause { DefParamClause }
DefParamClause    ::=  DefTypeParamClause
                    |  DefTermParamClause
                    |  UsingParamClause
DefTypeParamClause::=  [nl] ‘[’ DefTypeParam {‘,’ DefTypeParam} ‘]’
DefTypeParam      ::=  {Annotation} id [HkTypeParamClause] TypeParamBounds
DefTermParamClause::=  [nl] ‘(’ [DefTermParams] ‘)’
UsingParamClause  ::=  [nl] ‘(’ ‘using’ (DefTermParams | FunArgTypes) ‘)’
DefImplicitClause ::=  [nl] ‘(’ ‘implicit’ DefTermParams ‘)’
DefTermParams     ::=  DefTermParam {‘,’ DefTermParam}
DefTermParam      ::=  {Annotation} [‘inline’] Param
Param             ::=  id ‘:’ ParamType [‘=’ Expr]
~~~
The main rules of interest are `DefParamClauses` and `DefParamClause`, which now allow any number of type parameter clauses, term parameter clauses and using parameter clauses, in any order.

Note that these are also used for the right-hand side of extension methods, clause interleaving thus also applies to them.

It is worth pointing out that there can still only be at most one implicit parameter clause, which, if present, must be at the end.

The type system and semantics naturally generalize to these new method signatures.

When a method poly type consists of _n_ consecutive type parameter lists, and _m < n_ lists are specified at call site, they always correspond to the left-most _m_ type parameter lists of the signature. The right-most _(n-m)_ type parameter lists are then inferred.

### Restrictions
Class signatures are unchanged. Classes can still only have at most one type parameter list, which must come first. For example, the following definition is still invalid:
~~~ scala
class Pair[+A](val a: A)[+B](val b: B)
~~~
Class signatures already have limitations compared to def signatures. For example, they must have at least one term parameter list. There is therefore precedent for limiting their expressiveness compared to def parameter lists.

The rationale for this restriction is that classes also define associated types. It is unclear what the type of an instance of `Pair` with `A` and `B` should be. It could be defined as `Foo[A][B]`. That still leaves holes in terms of path-dependent types, as `B`'s definition could not depend on the path `a`. Allowing interleaved type parameters for class definitions is therefore restricted for now. It could be allowed with a follow-up proposal.

Note: As `apply` is a normal method, it is totally possible to define a method `def apply[A](a: A)[B](b: B)` on `Pair`'s companion object, allowing to create instances with `Pair[Int](4)[Char]('c')`.

The left hand side of extension methods remains unchanged, since they only have one explicit term clause, and since the type parameters are very rarely passed explicitly, it is not as necessary to have multiple type clauses there.

### Compatibility
The proposal is expected to be backward source compatible. New signatures currently do not parse, and typing rules are unchanged for existing signatures.

Backward binary compatibility is straightforward.

Backward TASTy compatibility should be straightforward. The TASTy format is such that we can extend it to support interleaved type parameter lists without added complexity. If necessary, a version check can decide whether to read signatures in the new or old format. For typechecking, like for source compatibility, the typing rules are unchanged for signatures that were valid before.

Of course, libraries that choose to evolve their public API to take advantage of the new signatures may expose incompatibilities.

## Alternatives
The proposal is a natural generalization of method signatures. We could have decomposed the proposal in two: a) multiple type parameter lists (allowing partial type inference) and b) interleaved-but-single type parameter lists (allowing path dependencies). However, specifying those independently would not be simpler than the combined, generalized proposal.

As discussed above, we may want to consider generalizing class parameter lists as well. However, we feel it is better to leave that extension to a follow-up proposal, if required.

The second use case (partial type inference) was also the topic of a previous proposal for Named Type Arguments, which was rejected before the release of Scala 3. Even if accepted, it would have required writing `Vec.fill[N = 3]` instead of the shorter form `Vec.fill[3]`.

## Related work
* Pre-SIP: https://contributors.scala-lang.org/t/clause-interweaving-allowing-def-f-t-x-t-u-y-u/5525
* An implementation of the proposal is available as a pull request at https://github.com/lampepfl/dotty/pull/14019

## FAQ
Currently empty.
