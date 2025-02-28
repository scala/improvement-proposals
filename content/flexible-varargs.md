---
layout: sip
permalink: /sips/:title.html
stage: implementation
status: under-review
title: SIP-XX - Flexible Varargs
---

**By: Li Haoyi**

## History

| Date          | Version            |
|---------------|--------------------|
| Feb 28th 2025 | Initial Draft      |

## Summary

This SIP proposes an extension of the Scala Vararg unpacking syntax `*`. Currently,
vararg unpacking can only be used for a single `Seq`, both in expressions and in patterns:

```scala
def sum(x: Int*) = x.sum

val numbers = Seq(1, 2, 3)

val total = sum(numbers*)  // 6

numbers match{
  case Seq(numbers2*) => ???
}
```

We propose to extend this to allow mixing of `*` unpackings and raw values in expressions and patterns:


```scala
val numbers = Seq(1, 2, 3)

val total = sum(0, numbers*, 4) // 10

numbers match {
  case Seq(1, numbers2*, 3) => println(numbers2) // Seq(2)
}
```

Allow multiple `*`s to be unpacked into the same varargs:

```scala
val numbers1 = Seq(1, 2, 3)
val numbers2 = Seq(4, 5, 6)

val total = sum(numbers1*, numbers2*) // 21
```

And allow `Option`s to be unpacked:

```scala
val numberOpt1: Option[Int] = Some(1)
val numberOpt2: Option[Int] = None

val total = sum(numberOpt1*, numberOpt2*) // 1
```

## Motivation

Vararg unpacking with `*` is convenient but very limited. In particular, this proposal 
streamlines two scenarios that were previously awkward.

### Passing Multiple Things to Varargs

The first scenario that this proposal streamlines is when you want to combine a bunch of
different variables into a single varargs unpacking, e.g. two single values and two sequences.
This can be done as shown below, but is terribly ugly: lots of extra parens, constructing
inline `Seq`s with `++`:

```scala
val numbers1 = Seq(1, 2, 3)
val numbers2 = Seq(4, 5, 6)

// BEFORE
val total = sum((Seq(0) ++ numbers1 ++ numbers2 ++ Seq(7))*) // 28

val total = sum((0 +: numbers1 ++ numbers2 :+ 7)*)
// (console):1: left- and right-associative operators with same precedence may not be mixed

// AFTER
val total = sum(0, numbers1*, numbers2*, 4) // 10
```

As you can see, the version using inline `Seq()`s and `++` is super verbose, and the "obvious"
cleanup using `+:` and `:+` doesn't actually work due to weird associativity problems.
With this proposal, you can write what you mean and have it just work.

### Constucting Sequences

The second scenarios that this streamlines is constructing `Seq`s and other collections.
For example, a common scenario is constructing a collection from values:


```scala
val foo = 1
val bar = 2

val coll = Seq(foo, bar) 
// Seq(1, 2)
```

This works great even as the collection grows:

```scala
val foo = 1
val bar = 2
val qux = 3
val baz = 4

val coll = Seq(foo, bar, qux, baz) 
// Seq(1, 2, 3, 4)
```

This looks fine, until one of the values is sequence or optional:

```scala
val foo = 1
val bar = Seq(2)
val qux = 3
val baz = Some(4)

val coll = Seq(foo) ++ bar ++ Seq(qux) ++ baz 
// Seq(1, 2, 3, 4)
```

Even worse, if the first value is optional, it needs to be explicitly turned into a `Seq`
otherwise you get an inferred type of `Iterable` which is unexpected:

```scala
val foo = Some(1)
val bar = Seq(2)
val qux = 3
val baz = 4

val coll = foo.toSeq ++ bar ++ Seq(qux, baz) 
val coll = Seq() ++ foo ++ bar ++ Seq(qux, baz) // alternative syntax 
// Seq(1, 2, 3, 4)
```

As you can see, we end up having to refactor our code significantly for what is 
logically a very similar operation: constructing a sequence from values. Depending
on what those values are, the shape of the code varies a lot, and you are open to
get surprising inferred types if you forget to call `toSeq` on the first entry (sometimes!)

```scala
val coll = Seq(foo, bar, qux, baz)
val coll = Seq(foo) ++ bar ++ Seq(qux) ++ baz
val coll = foo.toSeq ++ bar ++ Seq(qux, baz)
val coll = Seq() ++ foo ++ bar ++ Seq(qux, baz)
```

With those proposal, all three scenarios would look almost the same - reflecting
the fact that they are really doing the same thing - and you are no longer prone to weird
type inference issues depending on the type of the left-most value:


```scala
val coll = Seq(foo, bar, qux, baz)
val coll = Seq(foo, bar*, qux, baz*)
val coll = Seq(foo*, bar*, qux, baz)
```

## Implementation

The proposed implementation is to basically desugar the multiple `*`s into the manual 
`Seq`-construction code you would have written without it:

```scala
// User Code
val total = sum(0, numbers1*, numbers2*, 4) // 10

// Desugaring
val total = sum((IArray(0) ++ numbers1 ++ numbers2 ++ IArray(4))*) // 10
```

The implementation for patterns could be something like

```scala
// User Code
numbers match {
  case Seq(1, numbers2*, 3) => println(numbers2) // Seq(2)
}

// Desugaring Helper
class VarargsMatchHelper(beforeCount: Int, afterCount: Int) {
  def unapply[T](values: Seq[T]): Option[(Seq[T], Seq[T], Seq[T])] = {
    Option.when (values.length >= beforeCount + afterCount){
      (
        values.take(beforeCount),
        values.drop(beforeCount).dropRight(afterCount), 
        values.takeRight(afterCount)
      )
    }
  }
}

// Desugaring Helper
val VarargsMatcher = new VarargsMatchHelper(1, 1)
numbers match {
  case VarargsMatcher(Seq(1), numbers2, Seq(3)) => println(numbers2) // Seq(2)
}
```

## Limitations


### Single `*` in pattern matching

One major limitation is that while expressions support unpacking multiple `*`s in a varargs,
pattern matching can only support a single `*`. That is because a varargs pattern with
multiple `*` sub-patterns may have multiple possible ways of assigning the individual
elements to each sub-pattern, and depending on the sub-patterns not all such assignments
may be valid. Thus there is no way to implement it efficiently in general, as it would 
require an expensive (`O(2^n)`) backtracking search to try and find a valid assignment 
of the elements that satisfies all sub-patterns. 

Python's in [PEP634: Structural Pattern Matching](https://peps.python.org/pep-0634)
has the same limitation of only allowing one`*` unpacking in its
[Sequence Patterns](https://peps.python.org/pep-0634/#sequence-patterns)

### No Specific Performance Optimizations

As proposed, the desugaring just relies on `IArray()` and `++` to construct the final 
sequence that will be passed to varargs. We don't want to use `Seq` because it returns
a `List`, and `List#++` is very inefficient. But we do not do any further optimizations,
and anyone who hits performance issues with flexible vararg unpacking can always rewrite
it themselves manually constructing an `mutable.ArrayBuffer` or `mutable.ArrayDeque`.

### Not supporting unpacking arbitrary `Iterable`s

Given we propose to allow unpacking `Seq` and `Option`, its worth asking if we should 
support unpacking `Iterable` or `IterableOnce`. This proposal avoids those types for now,
but if anyone wants to unpack them it's trivial to call `.toSeq`. For `Option`, we think
that the use case of calling a vararg method with optional values is frequent enough that 
it deserves special support, whereas calling a vararg method with a `Set` or a `Map`
doesn't happen enough to be worth special casing.

## Alternatives

Apart from the manual workflows described above, one alternative is to use implicit conversions
to a target type, i.e. the "magnet pattern". For example:

```scala
case class Summable(value: Seq[Int])
implicit def seqSummable(value: Seq[Int]) = Summable(value) 
implicit def singleSummable(value: Int) = Summable(Seq(value))
implicit def optionSummable(value: Option[Int]) = Summable(value.toSeq)

def sum(x: Int*) = x.sum

val numbers1 = Seq(1, 2, 3)
val numbers2 = Seq(4, 5, 6)

val total = sum(0, numbers1, numbers2, 7)
```

We can see this done repeatedly in the wild:

* OS-Lib's `os.call` and `os.spawn` methods, which use 
  `os.Shellable` as the target type, with [several implicit conversions](https://github.com/com-lihaoyi/os-lib/blob/ff52a8bc4873d9c01e085cc18780845ecea0f8a2/os/src/Model.scala#L217-L237)
  both for normal constructors as well as for `Seq[T]` and `Option[T]` cases 

* SBT's `settings` lists, which uses `SettingsDefinition` as the target type, with
  [two implicit conversions](https://github.com/sbt/sbt/blob/1d16ca95106a11ad4ef0e3c5a1637c17189600da/internal/util-collection/src/main/scala/sbt/internal/util/Settings.scala#L691-L695) 
  for single and sequence entries

This approach works, but relies on you controlling the 
target type, and adds considerable boilerplate defining implicit conversions for 
every such target type. It thus can sometimes be found in libraries where that 
overhead can be amortized over many callsites, but it not a general
replacement for the more flexible `*` proposed in this document.