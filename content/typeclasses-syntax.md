---
layout: sip
stage: implementation
status: implemented
presip-thread: https://contributors.scala-lang.org/t/pre-sip-improve-syntax-for-context-bounds-and-givens/6576/97
title: SIP-64 - Improve Syntax for Context Bounds and Givens
---

**By: Martin Odersky**

## History

| Date          | Version            |
|---------------|--------------------|
| March 11, 2024| Initial Draft      |

## Summary

We propose some syntactic improvements that makes context bounds and given clauses more
expressive and easier to read. The proposed additions and changes comprise:

 - naming context bounds, as in `A: Monoid as a`,
 - a new syntax for multiple context bounds, as in `A: {Monoid, Ord}`,
 - context bounds for type members,
 - a cleaner syntax for given definitions that eliminates some syntactic warts,
 - replacing abstract givens with a more powerful and convenient mechanism.

## Motivation

This SIP is part of an effort to get state-of-the art typeclasses and generic in Scala. It fixes several existing pain points:

 - The inability to name context points causes awkward and obscure workarounds in practice.
 - The syntax for multiple context bounds is not very clear or readable.
 - The existing syntax for givens is unfortunate, which hinders learning and adoption.
 - Abstract givens are hard to specify and implement and their syntax is easily confused
   with simple concrete givens.

These pain points are worth fixing on their own, independently of any other proposed improvements to typeclass support. What's more, the changes
are time sensitive since they affect existing syntax that was introduced in 3.0, so it's better to make the change at a time when not that much code using the new syntax is written yet.

## Proposed Solution

### 1. Naming Context Bounds

Context bounds are a convenient and legible abbreviation. A problem so far is that they are always anonymous, one cannot name the implicit parameter to which a context bound expands. For instance, consider the classical pair of type classes
```scala
  trait SemiGroup[A]:
    extension (x: A) def combine(y: A): A

  trait Monoid[A] extends SemiGroup[A]:
    def unit: A
```
and a `reduce` method defined like this:
```scala
def reduce[A : Monoid](xs: List[A]): A = ???
```
Since we don't have a name for the `Monoid` instance of `A`, we need to resort to `summon` in the body of `reduce`:
```scala
def reduce[A : Monoid](xs: List[A]): A =
  xs.foldLeft(summon[Monoid[A]].unit)(_ `combine` _)
```
That's generally considered too painful to write and read, hence people usually adopt one of two alternatives. Either, eschew context bounds and switch to using clauses:
```scala
def reduce[A](xs: List[A])(using m: Monoid[A]): A =
  xs.foldLeft(m.unit)(_ `combine` _)
```
Or, plan ahead and define a "trampoline" method in `Monoid`'s companion object:
```scala
  trait Monoid[A] extends SemiGroup[A]:
    def unit: A
  object Monoid:
    def unit[A](using m: Monoid[A]): A = m.unit
  ...
  def reduce[A : Monoid](xs: List[A]): A =
    xs.foldLeft(Monoid.unit)(_ `combine` _)
```
This is all accidental complexity which can be avoided by the following proposal.

**Proposal:** Allow to name a context bound, like this:
```scala
  def reduce[A : Monoid as m](xs: List[A]): A =
    xs.foldLeft(m.unit)(_ `combine` _)
```

We use `as x` after the type to bind the instance to `x`. This is analogous to import renaming, which also introduces a new name for something that comes before.

**Benefits:** The new syntax is simple and clear. It avoids the awkward choice between concise context bounds that can't be named and verbose using clauses that can.

### 2. New Syntax for Aggregate Context Bounds

Aggregate context bounds like `A : X : Y` are not obvious to read, and it becomes worse when we add names, e.g. `A : X as x : Y as y`.

**Proposal:** Allow to combine several context bounds inside `{...}`, analogous
to import clauses. Example:

```scala
  trait:
    def showMax[X : {Ordering, Show}](x: X, y: X): String
  class B extends A:
    def showMax[X : {Ordering as ordering, Show as show}](x: X, y: X): String =
      show.asString(ordering.max(x, y))
```

The old syntax with multiple `:` should be phased out over time. There's more about migration at the end of this SIP.


### 3. Expansion of Context Bounds

With named context bounds, we need a revision to how the witness parameters of such bounds are added. Context bounds are currently translated to implicit parameters in the last parameter list of a method or class. This is a problem if a context bound is mentioned in one of the preceding parameter types. For example, consider a type class of parsers with associated type members `Input` and `Result` describing the input type on which the parsers operate and the type of results they produce:
```scala
trait Parser[P]:
  type Input
  type Result
```
Here is a method `run` that runs a parser on an input of the required type:
```scala
def run[P : Parser as p](in: p.Input): p.Result
```
With the current translation this does not work since it would be expanded to:
```scala
  def run[P](x: p.Input)(using p: Parser[P]): p.Result
```
Note that the `p` in `p.Input` refers to the `p` introduced in the using clause, which comes later. So this is ill-formed.

This problem would be fixed by changing the translation of context bounds so that they expand to using clauses immediately after the type parameter. But such a change is infeasible, for two reasons:

 1. It would be a source- and binary-incompatible change. We cannot simply change the expansion of existing using clauses because
    then clients that pass explicit using arguments would no longer work.
 2. Putting using clauses earlier can impair type inference. A type in
    a using clause can be constrained by term arguments coming before that
    clause. Moving the using clause first would miss those constraints, which could cause ambiguities in implicit search.

But there is an alternative which is feasible:

**Proposal:** Map the context bounds of a method or class as follows:

 1. If one of the bounds is referred to by its term name in a subsequent parameter clause, the context bounds are mapped to a using clause immediately preceding the first such parameter clause.
 2. Otherwise, if the last parameter clause is a using (or implicit) clause, merge all parameters arising from context bounds in front of that clause, creating a single using clause.
 3. Otherwise, let the parameters arising from context bounds form a new using clause at the end.

Rules (2) and (3) are the status quo, and match Scala 2's rules. Rule (1) is new but since context bounds so far could not be referred to, it does not apply to legacy code. Therefore, binary compatibility is maintained.

**Discussion** More refined rules could be envisaged where context bounds are spread over different using clauses so that each comes as late as possible. But it would make matters more complicated and the gain in expressiveness is not clear to me.


### 4. Context Bounds for Type Members, Deferred Givens

It's not very orthogonal to allow subtype bounds for both type parameters and abstract type members, but context bounds only for type parameters. What's more, we don't even have the fallback of an explicit using clause for type members. The only alternative is to also introduce a set of abstract givens that get implemented in each subclass. This is extremely heavyweight and opaque to newcomers.

**Proposal**: Allow context bounds for type members. Example:

```scala
  class Collection:
    type Element : Ord
```

The question is how these bounds are expanded. Context bounds on type parameters
are expanded into using clauses. But for type members this does not work, since we cannot refer to a member type of a class in a parameter type of that class. What we are after is an equivalent of using parameter clauses but represented as class members.

**Proposal:**
Introduce a new way to implement a given definition in a trait like this:
```scala
given T = deferred
```
`deferred` is a new method in the `scala.compiletime` package, which can appear only as the right hand side of a given defined in a trait. Any class implementing that trait will provide an implementation of this given. If a definition is not provided explicitly, it will be synthesized by searching for a given of type `T` in the scope of the inheriting class. Specifically, the scope in which this given will be searched is the environment of that class augmented by its parameters but not containing its members (since that would lead to recursive resolutions). If an implementation _is_ provided explicitly, it counts as an override of a concrete definition and needs an `override` modifier.

Deferred givens allow a clean implementation of context bounds in traits,
as in the following example:
```scala
trait Sorted:
  type Element : Ord

class SortedSet[A : Ord] extends Sorted:
  type Element = A
```
The compiler expands this to the following implementation.
```scala
trait Sorted:
  type Element
  given Ord[Element] = compiletime.deferred

class SortedSet[A](using evidence$0: Ord[A]) extends Sorted:
  type Element = A
  override given Ord[Element] = evidence$0
```

The using clause in class `SortedSet` provides an implementation for the deferred given in trait `Sorted`.

**Benefits:**

 - Better orthogonality, type parameters and abstract type members now accept the same kinds of bounds.
 - Better ergonomics, since deferred givens get naturally implemented in inheriting classes, no need for boilerplate to fill in definitions of abstract givens.

**Alternative:** It was suggested that we use a modifier for a deferred given instead of a `= deferred`. Something like `deferred given C[T]`. But a modifier does not suggest the concept that a deferred given will be implemented automatically in subclasses unless an explicit definition is written. In a sense, we can see `= deferred` as the invocation of a magic macro that is provided by the compiler. So from a user's point of view a given with `deferred` right hand side is not abstract.
It is a concrete definition where the compiler will provide the correct implementation.

### 5. Context Bounds for Polymorphic Functions

Currently, context bounds can be used in methods, but not in function types or function literals. It would be nice  propose to drop this irregularity and allow context bounds also in these places. Example:

```scala
type Comparer = [X: Ord] => (x: X, y: X) => Boolean
val less: Comparer = [X: Ord as ord] => (x: X, y: X) =>
  ord.compare(x, y) < 0
```

The expansion of such context bounds is analogous to the expansion in method types, except that instead of adding a using clause in a method, we insert a context function type.

For instance, type and val above would expand to
```scala
type Comparer = [X] => (x: X, y: X) => Ord[X] ?=> Boolean
val less: Comparer = [X] => (x: X, y: X) => (ord: Ord[X]) ?=>
  ord.compare(x, y) < 0
```

The expansion of using clauses does look inside alias types. For instance,
here is a variation of the previous example that uses a parameterized type alias:
```scala
type Cmp[X] = (x: X, y: X) => Ord[X] ?=> Boolean
type Comparer2 = [X: Ord] => Cmp[X]
```
The expansion of the right hand side of `Comparer2` expands the `Cmp[X]` alias
and then inserts the context function at the same place as what's done for `Comparer`.

### 6. Cleanup of Given Syntax

A good language syntax is like a Bach fugue: A small set of motifs is combined in a multitude of harmonic ways. Dissonances and irregularities should be avoided.

When designing Scala 3, I believe that, by and large, we achieved that goal, except in one area, which is the syntax of givens. There _are_ some glaring dissonances, as seen in this code for defining an ordering on lists:
```scala
given [A](using Ord[A]): Ord[List[A]] with
  def compare(x: List[A], y: List[A]) = ...
```
The `:` feels utterly foreign in this position. It's definitely not a type ascription, so what is its role? Just as bad is the trailing `with`. Everywhere else we use braces or trailing `:` to start a scope of nested definitions, so the need of `with` sticks out like a sore thumb.

We arrived at that syntax not because of a flight of fancy but because even after trying for about a year to find other solutions it seemed like the least bad alternative. The awkwardness of the given syntax arose because we insisted that givens could be named or anonymous, with the default on anonymous, that we would not use underscore for an anonymous given, and that the name, if present, had to come first, and have the form `name [parameters] :`. In retrospect, that last requirement showed a lack of creativity on our part.

Sometimes unconventional syntax grows on you and becomes natural after a while. But here it was unfortunately the opposite. The longer I used given definitions in this style the more awkward they felt, in particular since the rest of the language seemed so much better put together by comparison. And I believe many others agree with me on this. Since the current syntax is unnatural and esoteric, this means it's difficult to discover and very foreign even after that. This makes it much harder to learn and apply givens than it need be.

**Proposal:** Things become much simpler if we introduce the optional name instead with an `as name` clause at the end, just like we did for context bounds. We can then use a more intuitive syntax for givens like this:
```scala
given Ord[String]:
  def compare(x: String, y: String) = ...

given [A : Ord] => Ord[List[A]]:
  def compare(x: List[A], y: List[A]) = ...

given Monoid[Int]:
  extension (x: Int) def combine(y: Int) = x + y
  def unit = 0
```
If explicit names are desired, we add them with `as` clauses:
```scala
given Ord[String] as stringOrd:
  def compare(x: String, y: String) = ...

given [A : Ord] => Ord[List[A]] as listOrd:
  def compare(x: List[A], y: List[A]) = ...

given Monoid[Int] as intMonoid:
  extension (x: Int) def combine(y: Int) = x + y
  def unit = 0
```

The underlying principles are:

 - A `given` clause consists of the following elements:

    - An optional _precondition_, which introduces type parameters and/or using clauses and which ends in `=>`,
    - the implemented _type_,
    - an optional name binding using `as`,
    - an implementation which consists of either an `=` and an expression,
      or a template body.

 - Since there is no longer a middle `:` separating name and parameters from the implemented type, we can use a `:` to start the class body without looking unnatural, as is done everywhere else. That eliminates the special case where `with` was used before.

This will be a fairly significant change to the given syntax. I believe there's still a possibility to do this. Not so much code has migrated to new style givens yet, and code that was written can be changed fairly easily. Specifically, there are about a 900K definitions of `implicit def`s
in Scala code on Github and about 10K definitions of `given ... with`. So about 1% of all code uses the Scala 3 syntax, which would have to be changed again.

Changing something introduced just recently in Scala 3 is not fun,
but I believe these adjustments are preferable to let bad syntax
sit there and fester. The cost of changing should be amortized by improved developer experience over time, and better syntax would also help in migrating Scala 2 style implicits to Scala 3. But we should do it quickly before a lot more code
starts migrating.

Migration to the new syntax is straightforward, and can be supported by automatic rewrites. For a transition period we can support both the old and the new syntax. It would be a good idea to backport the new given syntax to the LTS version of Scala so that code written in this version can already use it. The current LTS would then support old and new-style givens indefinitely, whereas new Scala 3.x versions would phase out the old syntax over time.


### 7. Abolish Abstract Givens

Another simplification is possible. So far we have special syntax for abstract givens:
```scala
given x: T
```
The problem is that this syntax clashes with the quite common case where we want to establish a given without any nested definitions. For instance
consider a given that constructs a type tag:
```scala
class Tag[T]
```
Then this works:
```scala
given Tag[String]()
given Tag[String] with {}
```
But the following more natural syntax fails:
```scala
given Tag[String]
```
The last line gives a rather cryptic error:
```
1 |given Tag[String]
  |                 ^
  |                 anonymous given cannot be abstract
```
The problem is that the compiler thinks that the last given is intended to be abstract, and complains since abstract givens need to be named. This is another annoying dissonance. Nowhere else in Scala's syntax does adding a
`()` argument to a class cause a drastic change in meaning. And it's also a violation of the principle that it should be possible to define all givens without providing names for them.

Fortunately, abstract givens are no longer necessary since they are superseded by the new `deferred` scheme. So we can deprecate that syntax over time. Abstract givens are a highly specialized mechanism with a so far non-obvious syntax.
We have seen that this syntax clashes with reasonable expectations of Scala programmers. My estimate is that maybe a dozen people world-wide have used abstract givens in anger so far.

**Proposal** In the future, let the `= deferred` mechanism be the only way to deliver the functionality of abstract givens.

This is less of a disruption than it might appear at first:

 - `given T` was illegal before since abstract givens could not be anonymous.
   It now means a concrete given of class `T` with no member definitions.
 - `given x: T` is legacy syntax for an abstract given.
 - `given T as x = deferred` is the analogous new syntax, which is more powerful since
    it allows for automatic instantiation.
 - `given T = deferred` is the anonymous version in the new syntax, which was not expressible before.

**Benefits:**

 - Simplification of the language since a feature is dropped
 - Eliminate non-obvious and misleading syntax.

The only downside is that deferred givens are restricted to be used in traits, whereas abstract givens are also allowed in abstract classes. But I would be surprised if actual code relied on that difference, and such code could in any case be easily rewritten to accommodate the restriction.

## Summary of Syntax Changes

Here is the complete context-free syntax for all proposed features.
Overall the syntax for givens becomes a lot simpler than what it was before.

```
TmplDef           ::=  'given' GivenDef
GivenDef          ::=  [GivenConditional '=>'] GivenSig
GivenConditional  ::=  [DefTypeParamClause | UsingParamClause] {UsingParamClause}
GivenSig          ::=  GivenType ['as' id] ([‘=’ Expr] | TemplateBody)
                   |   ConstrApps ['as' id] TemplateBody
GivenType         ::=  AnnotType {id [nl] AnnotType}

TypeDef           ::=  id [TypeParamClause] TypeAndCtxBounds
TypeParamBounds   ::=  TypeAndCtxBounds
TypeAndCtxBounds  ::=  TypeBounds [‘:’ ContextBounds]
ContextBounds     ::=  ContextBound | '{' ContextBound {',' ContextBound} '}'
ContextBound      ::=  Type ['as' id]

FunType           ::=  FunTypeArgs (‘=>’ | ‘?=>’) Type
                    |  DefTypeParamClause '=>' Type
FunExpr           ::=  FunParams (‘=>’ | ‘?=>’) Expr
                    |  DefTypeParamClause ‘=>’ Expr
```
The syntax for function types `FunType` and function expressions `FunExpr` also gets simpler. We can now use a regular `DefTypeParamClause` that is also used for `def` definitions and allow context bounds in type parameters.

## Compatibility

All additions are fully compatible with existing Scala 3. The prototype implementation contains a parser that accepts both old and new idioms. That said, we would
want to deprecate and remove over time the following existing syntax:

 1. Multiple context bounds of the form `X : A : B : C`.
 2. The previous syntax for given clauses which required a `:` in front of the implemented type and a `with` after it.
 3. Abstract givens

The changes under (1) and (2) can be automated using existing rewrite technology in the compiler or Scalafix. The changes in (3) are more global in nature but are still straightforward.

## Alternatives

No syntactic alternatives were proposed or discussed in the Pre-SIP. One alternative put forward was to deprecate context bounds altogether and only promote using clauses. This would still be a workable system and arguably lead to a smaller language. On the other hand, dropping context bounds for using clauses worsens
some of the ergonomics of expressing type classes. First, it is longer. Second, it separates the introduction of a type name and the constraints on that type name. Typically, there can be many normal parameters between a type parameter and the using clause that characterized it. By contrast, context bounds follow the
general principle that an entity should be declared together with its type, and in a very concrete sense context bounds define types of types. So I think context bounds are here to stay, and improvements to the ergonomics of context bounds will be appreciated.

The Pre-SIP also contained a proposal for a default naming convention of context bounds. If no explicit `as` clause is given, the name of the witness for
`X : C` would be `X`, instead of a synthesized name as is the case now. This led to extensive discussions how to accommodate multiple context bounds.
I believe that a default naming convention for witnesses will be very beneficial in the long run, but as of today there are several possible candidate solutions, including:

 1. Use default naming for single bounds only.
 2. If there are multiple bounds, as in `X: {A, B, C}` create a synthetic companion object `X` where selections `X.m` translate into
    witness selections `A.m`, `B.m`, or `C.m`. Disallow any references to the companion that remain after that expansion.
 3. Like (2), but use the synthetic companion approach also for single bounds.
 4. Create real aggregate given objects that represent multiple bounds.

Since it is at present not clear what the best solution would be, I decided to defer the question of default names to a later SIP.

In the next section I explore one syntactic alternative: Use the new style of givens in general, but keep the current convention of naming them.

## Comparison

This section gives a systematic comparison of the current given syntax and the new proposed one. We show the following use cases:

 1. A simple typeclass instance, such as `Ord[Int]`.
 2. A parameterized type class instance, such as `Ord` for lists.
 3. A type class instance with a using clause.
 4. A simple given alias.
 5. A parameterized given alias
 6. A given alias with a using clause
 7. A simple given value, i.e. making a given from a concrete class instance
 8. An abstract or deferred given
 9. A by-name given, e.g. if we have a given alias of a mutable variable, and we
    want to make sure that it gets re-evaluated on each access.

**Why anonymous should be the default**

We show first the anonymous versions that implement these use cases, followed by the named versions. Arguably anonymous is the more important case. In general there should be
no need to name a given. Givens are like extends clauses. We state a fact, that a
type implements a type class, or that a value can be used implicitly. We don't need a name for that fact. It's analogous to extends clauses, where we state that a class is a subclass of some other class or trait. We would not think it useful to name an extends clause, it's simply a fact that is stated.

Even for contextual givens, where we do define a value or a function, I tend to prefer to split the definition from the given part. I.e.
```scala
  val foo: Foo = ...
  given Foo = foo
```
The given syntax is kind of unwieldy as a way to define values or functions. That was a complaint often voiced when coming from the Scala 2 implicits where this just requires an `implicit` modifier. So my reaction to that is, let's define entities as regular values or functions and inject them as givens separately. But then again, we don't need _another_ name for the given.

Two other arguments for anonymous:

 - Every other language that defines type classes uses anonymous syntax. Somehow, no other language found it necessary to name these instances.
 - When I define a regular val or def my first concern is: By which name should the defined entity be referenced? That's why the name comes first. For givens this is my least concern. In most cases I don't need a name at all. That's also a reason for the
 optional suffix names with `as`. Writer as well as readers of given clauses should focus
on the type that's implemented. That's why the type comes first, and if there is an additional name, we add it at the end with an `as` clause.

The possible variations are presented in the following.

**Current, Anonymous**

```scala
  // Simple typeclass
  given Ord[Int] with
    def compare(x: Int, y: Int) = ...

  // Parameterized typeclass
  given [A: Ord]: Ord[List[A]] with
    def compare(x: List[A], y: List[A]) = ...

  // Typeclass with using clause
  given [A](using Ord[A]): Ord[List[A]] with
    def compare(x: List[A], y: List[A]) = ...

  // Simple alias
  given Ord[Int] = IntOrd()

  // Parameterized alias
  given [A: Ord]: Ord[List[A]] =
    ListOrd[A]()

  // Alias with using clause
  given [A](using Ord[A]): Ord[List[A]] =
    ListOrd[A]()

  // Concrete class instance
  given Context with {}

  // Abstract or deferred given
  // given Context   // can't be expressed

  // By-name given
  given [DummySoItsADef]: Context = curCtx
```
**Notes:**

 - The `with` is an irregularity with relative to the rest of the language.
 - The infix `:` also feels strange. To a newcomer, it's not clear what it signifies.
 - Abstract anonymous givens are not supported.
 - Concrete class instances need an empty block `with {}`, which is also irregular and might come as a surprise.
 - By-name givens need an artificial dummy type parameter.

**Proposal, Anonymous**

```scala
  // Simple typeclass
  given Ord[Int]:
    def compare(x: Int, y: Int) = ...

  // Parameterized typeclass
  given [A: Ord] => Ord[List[A]]:
    def compare(x: List[A], y: List[A]) = ...

  // Typeclass with using clause
  given [A](using Ord[A]) => Ord[List[A]]:
    def compare(x: List[A], y: List[A]) = ...

  // Simple alias
  given Ord[Int] = IntOrd()

  // Parameterized alias
  given [A: Ord] => Ord[List[A]] =
    ListOrd[A]()

  // Alias with using clause
  given [A](using Ord[A]) => Ord[List[A]] =
    ListOrd[A]()

  // Concrete class instance
  given Context()

  // Abstract or deferred given
  given Context = deferred

  // By-name given
  given => Context = curCtx
```

**Notes:**

 - The `=>` is consistent with its other uses in Scala 3. It's very similar to the `=>` in case clauses. E.g. `[A: Ord] => Ord[List[A]]` reads as follows: _Assuming_ we have an `A: Ord` parameter, we can construct an instance of type `Ord[List[A]]`.
 - `=>` is also consistent with the use of `=>` for functions. We can read it as constructing a function from arguments to result type in the figurative sense: If we can construct a given instance of type `[A: Ord] => Ord[List[A]]` then, given an `A: Ord` we can construct a given instance of type `Ord[List[A]]`. This is really just _modus ponens_.
 - Of course,  one usually does not construct given instances for function types in the literal sense. But if one did,
one would have to put the function in parentheses, in both current and proposed new syntax.
 - Concrete class instances, deferred givens, and by-name givens are all naturally supported.


**Current, Named**

```scala
  // Simple typeclass
  given intOrd: Ord[Int] with
    def compare(x: Int, y: Int) = ...

  // Parameterized typeclass
  given listOrd[A: Ord]: Ord[List[A]] with
    def compare(x: List[A], y: List[A]) = ...

  // Typeclass with using clause
  given listOrd[A](using Ord[A]): Ord[List[A]] with
    def compare(x: List[A], y: List[A]) = ...

  // Simple alias
  given intOrd: Ord[Int] = IntOrd()

  // Parameterized alias
  given listOrd[A: Ord]: Ord[List[A]] =
    ListOrd[A]()

  // Alias with using clause
  given listOrd[A](using Ord[A]): Ord[List[A]] =
    ListOrd[A]()

  // Concrete class instance
  given context: Context with {}

  // Abstract or deferred given
  given context: Context

  // By-name given
  given context[DummySoItsADef]: Context = curCtx
```
**Notes:**

 - The interior `:` looks less out of place with names. One can clearly see the lineage where it came from.

**Proposal, Named**

```scala
  // Simple typeclass
  given Ord[Int] as intOrd:
    def compare(x: Int, y: Int) = ...

  // Parameterized typeclass
  given [A: Ord] => Ord[List[A]] as listOrd:
    def compare(x: List[A], y: List[A]) = ...

  // Typeclass with using clause
  given [A](using Ord[A]) => Ord[List[A]] as listOrd:
    def compare(x: List[A], y: List[A]) = ...

  // Simple alias
  given Ord[Int] as intOrd = IntOrd()

  // Parameterized alias
  given [A: Ord] => Ord[List[A]] as listOrd =
    ListOrd[A]()

  // Alias with using clause
  given [A](using Ord[A]) => Ord[List[A]] as listOrd =
    ListOrd[A]()

  // Concrete class instance
  given Context as context

  // Abstract or deferred given
  given Context as context = deferred

  // By-name given
  given => Context as context = curCtx
```
**Notes:**

 - The `as` takes some getting used to. However, it is in line with the concept that names
   should come last since the important thing here is the type hat's implemented.

**Proposal, Named in Current Style**

As an alternative, here is a version of new style given, but using the current `id:` for syntax for optional names.

```scala
  // Simple typeclass
  given intOrd: Ord[Int]:
    def compare(x: Int, y: Int) = ...

  // Parameterized typeclass
  given listOrd: [A: Ord] => Ord[List[A]]:
    def compare(x: List[A], y: List[A]) = ...

  // Typeclass with using clause
  given listOrd: [A](using Ord[A]) => Ord[List[A]]:
    def compare(x: List[A], y: List[A]) = ...

  // Simple alias
  given intOrd: Ord[Int] = IntOrd()

  // Parameterized alias
  given listOrd: [A: Ord] => Ord[List[A]] =
    ListOrd[A]()

  // Alias with using clause
  given listOrd: [A](using Ord[A]) => Ord[List[A]] =
    ListOrd[A]()

  // Concrete class instance
  given context: Context()

  // Abstract or deferred given
  given context: Context = deferred

  // By-name given
  given context: => Context = curCtx
```
**Notes:**

 - It's more conventional than with `as`.
 - The double `:` in the first two examples is a bit jarring.
 - The concrete class instance needs explicit parentheses `()` to distinguish it
   from an abstract given. Once abstract givens are deprecated the compiler can give a hint that `()` is missing.
 - Overall, I find this version a bit more cumbersome to the one with `as`, but i could live with it (since I don't usually recommend to write named givens anyway).

### Alternative: Reinforce Similarity with Function Types

A reservation against the new syntax that is sometimes brought up is that the `=>` feels strange. I personally find the `=>` quite natural since it means implication, which is exactly what we want to express when we write a conditional given. This also corresponds to the meaning of arrow in functions since by the Curry-Howard isomorphism function types correspond to implications in logic.
Besides `=>` is also used in other languages that support type classes (e.g.: Haskell).

As an example, the most natural reading of
```scala
given [A: Ord] => Ord[List[A]]
```
is _if `A` is `Ord` then `List[A]` is `Ord`_, or, equivalently, `A` is `Ord` _implies_ `List[A]` is `Ord`, hence the `=>`. Another way to see this is that
the given clause establishes a _context function_ of type `[A: Ord] ?=> Ord[List[A]]` that is automatically applied to evidence arguments of type `Ord[A]` and that yields instances of type `Ord[List[A]]`. Since givens are in any case applied automatically to all their arguments, we don't need to specify that separately with `?=>`, a simple `=>` arrow is sufficiently clear and is easier to read.

Once one has internalized the analogy with implications and functions, one
could argue the opposite, namely that the `=>` in a given clause is not sufficiently function-like. For instance, `given [A] => F[A]` looks like it implements a function type, but `given[A](using B[A]) => F[A]` looks like a mixture between a function type and a method signature.

A more radical and in some sense cleaner alternative is to decree that a given should always look like it implements a type. Conditional givens should look
like they implement function types. Examples:
```scala
  // Typeclass with context bound, as before
  given [A: Ord] => Ord[List[A]]:
    def compare(x: List[A], y: List[A]) = ...

  // Typeclass with context parameter, instead of using clause
  given [A] => Ord[A] => Ord[List[A]]:
    def compare(x: List[A], y: List[A]) = ...

  // Alias with context bound, as before
  given [A: Ord] => Ord[List[A]] =
    ListOrd[A]

  // Alias with with context parameter, instead of using clause
  given [A] => Ord[A] => Ord[List[A]] =
    ListOrd[A]()
```
For completeness I also show two cases where the given clause uses names for
both arguments and the clause as a whole (in the prefix style)

```scala
  // Named typeclass with named context parameter
  given listOrd: [A] => (ord: Ord[A]) => Ord[List[A]]:
    def compare(x: List[A], y: List[A]) = ...

  // Named alias with named context parameter
  given listOrd: [A] => (ord: Ord[A]) => Ord[List[A]] =
    ListOrd[A]()
```
The new syntax fits exactly the approach of seeing conditional givens as implications: For instance,
```scala
[A] => Ord[A] => Ord[List[A]]
```
can be read as:

> If A is a type, then if `A` is `Ord`, then `List[A]` is `Ord`.

I think this is overall the cleanest proposal. For completeness here is the delta
in the syntax description:
```
GivenDef          ::=  [id ':'] GivenSig
GivenSig          ::=  GivenType ([‘=’ Expr] | TemplateBody)
                   |   ConstrApps TemplateBody
                   |   GivenConditional '=>' GivenSig
GivenConditional  ::=  DefTypeParamClause | DefTermParamClause | '(' FunArgTypes ')'
GivenType         ::=  AnnotType {id [nl] AnnotType}
```
This would also give a more regular and familiar syntax to by-name givens:
```scala
var ctx = ...
given () => Context = ctx
```
Indeed, since we know `=>` means `?=>` in givens, this defines a value
of type `() ?=> Context`, which is exactly the same as a by-name parameter type.


**Possible ambiguities**

 - If one wants to define a given for an a actual function type (which is probably not advisable in practice), one needs to enclose the function type in parentheses, i.e. `given ([A] => F[A])`. This is true in the currently implemented syntax and stays true for all discussed change proposals.

 - The double meaning of `:` with optional prefix names is resolved as usual. A `:` at the end of a line starts a nested definition block. If for some obscure reason one wants to define
 a named given on multiple lines, one has to format it as follows:
   ```scala
     given intOrd
       : Ord = ...

     given intOrd
       : Ord:
       def concat(x: Int, y: Int) = ...
   ```

Finally, for systematic comparison, here is the listing of all 9x2 cases discussed previously with the proposed alternative syntax. Only the 3rd, 6th, and 9th case are different from what was shown before.

Unnamed:

```scala
  // Simple typeclass
  given Ord[Int]:
    def compare(x: Int, y: Int) = ...

  // Parameterized typeclass with context bound
  given [A: Ord] => Ord[List[A]]:
    def compare(x: List[A], y: List[A]) = ...

  // Parameterized typeclass with context parameter
  given [A] => Ord[A] => Ord[List[A]]:
    def compare(x: List[A], y: List[A]) = ...

  // Simple alias
  given Ord[Int] = IntOrd()

  // Parameterized alias with context bound
  given [A: Ord] => Ord[List[A]] =
    ListOrd[A]()

  // Parameterized alias with context parameter
  given [A] => Ord[A] => Ord[List[A]] =
    ListOrd[A]()

  // Concrete class instance
  given Context()

  // Abstract or deferred given
  given Context = deferred

  // By-name given
  given () => Context = curCtx
```
Named:

```scala
  // Simple typeclass
  given intOrd: Ord[Int]:
    def compare(x: Int, y: Int) = ...

  // Parameterized typeclass with context bound
  given listOrd: [A: Ord] => Ord[List[A]]:
    def compare(x: List[A], y: List[A]) = ...

  // Parameterized typeclass with context parameter
  given listOrd: [A] => Ord[A] => Ord[List[A]]:
    def compare(x: List[A], y: List[A]) = ...

  // Simple alias
  given intOrd: Ord[Int] = IntOrd()

  // Parameterized alias with context bound
  given listOrd: [A: Ord] => Ord[List[A]] =
    ListOrd[A]()

  // Parameterized alias with context parameter
  given listOrd: [A] => Ord[A] => Ord[List[A]] =
    ListOrd[A]()

  // Concrete class instance
  given context: Context()

  // Abstract or deferred given
  given context: Context = deferred

  // By-name given
  given context: () => Context = curCtx
```

## Summary

The proposed set of changes removes awkward syntax and makes dealing with context bounds and givens a lot more regular and pleasant. In summary, the proposed changes are:

 1. Allow to name context bounds with `as` clauses.
 3. Introduce a less cryptic syntax for multiple context bounds.
 4. Allow context bounds on type members which expand to deferred givens.
 5. Introduce a more regular and clearer syntax for givens.
 6. Eliminate abstract givens.

These changes were implemented as part of a  [draft PR](https://github.com/lampepfl/dotty/pulls/odersky)
which also covers the other prospective changes slated to be proposed in two future SIPs. The new system has proven to work well and to address several fundamental issues people were having with
existing implementation techniques for type classes.

The changes proposed in this SIP are time-sensitive since we would like to correct some awkward syntax choices in Scala 3 before more code migrates to the new constructs (so far, it seems most code still uses Scala 2 style implicits, which will eventually be phased out). It is easy to migrate to the new syntax and to support both old and new for a transition period.
