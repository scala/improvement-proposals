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

### Constructing Sequences

The second scenario that this streamlines is constructing `Seq`s and other collections.
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

With this proposal, all three scenarios would look almost the same - reflecting
the fact that they are really doing the same thing - and you are no longer prone to weird
type inference issues depending on the type of the left-most value:


```scala
val coll = Seq(foo, bar, qux, baz)
val coll = Seq(foo, bar*, qux, baz*)
val coll = Seq(foo*, bar*, qux, baz)
```

## Reference Implementation

A reference implementation of this is shown below, but the language is allowed to implement
it in any way that returns a `Seq` that has the same elements as the reference implementation:
```scala
// User Code
val total = sum(0, numbers1*, numbers2*, 4) // 10

// Desugaring
val total = sum(IArray.newBuilder.addOne(0).addAll(numbers1).addAll(numbers2).addOne(4).result()*) // 10
```

We don't want to hard-code too much deep integration with the Scala collections library,
but at the same time do not want to do this too naively, since this may be used in some
performance critical APIs (e.g. Scalatags templates). It seems reasonable to assume that 
any possible implementation of `IArray` or `Seq` will have an API like `newBuilder` that
allows you to construct the collection efficiently without excessive copying.

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
      val (first, rest) = values.splitAt(beforeCount)
      val (middle, last) = rest.splitAt(rest.length - afterCount)
      (first, middle, last)
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

### Not Support unpacking `Option`s


We considered allowing `Option`s to be unpacked, as it is very common to have some of the values
you want to pass to a varargs be optional:

```scala
val number1: Int = 1
val number2: Option[Int] = Some(2)
val number3: Int = 3

val total = sum(number1, number2*, number3) // 6
```

For now we left it as out of scope, though that doesn't rule out some design and implementation 
in future.

### Not supporting unpacking arbitrary `Iterable`s

Given we propose to allow unpacking `Seq`, its worth asking if we should 
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

def sum(x: Summable*) = x.flatMap(_.value).sum

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

* Scalatags' HTML templates, which use `Frag` as the target type and provide 
  [an implicit conversion](https://github.com/com-lihaoyi/scalatags/blob/762ab37d0addc614bfd65bbeabeb5f123caf4395/scalatags/src/scalatags/Text.scala#L59-L63) from any `Seq[T]` with an implicit `T => Frag`

This approach works, but relies on you controlling the 
target type, and adds considerable boilerplate defining implicit conversions for 
every such target type. It thus can sometimes be found in libraries where that 
overhead can be amortized over many callsites, but it not a general
replacement for the more flexible `*` proposed in this document. Note that while the
"manual" approach of doing `foo ++ Seq(bar) ++ qux ++ Seq(baz)` could be applied to
any of the three use cases above, all three libraries found it painful enough that
adding implicit conversions was worthwhile.

# Prior Art

### Python

Python's `*` syntax works identically to this proposal. In Python, you can mix
single values with one or more `*` unpackings when calling a function:

```python
>>> a = [1, 2, 3]
>>> b = [4, 5, 6]

>>> print(*a, 0, *b)
1 2 3 0, 4 5 6
```

Python's [PEP634: Structural Pattern Matching](https://peps.python.org/pep-0634)
has the same limitation of only allowing one `*` unpacking in its
[Sequence Patterns](https://peps.python.org/pep-0634/#sequence-patterns), with
an arbitrary number of non-`*` patterns on the left and right, and follows the
[same pattern matching strategy](https://docs.python.org/3/reference/compound_stmts.html#sequence-patterns)
that I sketched above.

```python
match command:
    case ["drop", *objects]:
        for obj in objects:
            ...
```

### Javascript
Javascript's expression `...`  syntax works identically to this proposal. In Javascript, you can mix
single values with one or more `...` unpackings when calling a function:

```javascript
a = [1, 2, 3]
b = [4, 5, 6]

console.log(...a, 0, ...b)
// 1 1 2 3 0 4 5 6
```

Javascript has a stricter limitation when destructuring an array, as it only allows
single values to the _left_ of the `...rest` pattern, and does not allow anything to be
to the right of it.

```javascript
[a, b, ...rest] = [10, 20, 30, 40, 50];
// a = 10
// b = 20
// rest = [30, 40, 50]

[a, b, ...rest, c] = [10, 20, 30, 40, 50];
// Uncaught SyntaxError: Rest element must be last element
```

### PHP

PHP's work in progress [Pattern Matching RFC](https://wiki.php.net/rfc/pattern-matching)
allows for a `...` "rest" pattern to be added to a sequence pattern, but does not allow
it to be bound to a local variable:

```php
// Array sequence patterns
$list is [1, 2, 3, 4];   // Exact match.
$list is [1, 2, 3, ...]; // Begins with 1, 2, 3, but may have other entries.
$list is [1, 2, mixed, 4];   // Allows any value in the 3rd position.
$list is [1, 2, 3|4, 5]; // 3rd value may be 3 or 4.
```

### Dart

Dart allows pattern matching on lists with a single `...` [Rest Element](https://dart.dev/language/pattern-types#rest-element)
that can be anywhere in the list, similar to Python:

```dart
var [a, b, ...rest, c, d] = [1, 2, 3, 4, 5, 6, 7];
// Prints "1 2 [3, 4, 5] 6 7".
print('$a $b $rest $c $d');
```

### C#

C# also has structural pattern matching, and supports 
[List Patterns](https://learn.microsoft.com/en-us/dotnet/csharp/language-reference/operators/patterns#list-patterns),
which allows a single `..` "slice" to be placed anywhere in the list:

```csharp
Console.WriteLine(new[] { 1, 2, 3, 4, 5 } is [> 0, > 0, ..]);  // True
Console.WriteLine(new[] { 1, 1 } is [_, _, ..]);  // True
Console.WriteLine(new[] { 0, 1, 2, 3, 4 } is [> 0, > 0, ..]);  // False
Console.WriteLine(new[] { 1 } is [1, 2, ..]);  // False

Console.WriteLine(new[] { 1, 2, 3, 4 } is [.., > 0, > 0]);  // True
Console.WriteLine(new[] { 2, 4 } is [.., > 0, 2, 4]);  // False
Console.WriteLine(new[] { 2, 4 } is [.., 2, 4]);  // True

Console.WriteLine(new[] { 1, 2, 3, 4 } is [>= 0, .., 2 or 4]);  // True
Console.WriteLine(new[] { 1, 0, 0, 1 } is [1, 0, .., 0, 1]);  // True
Console.WriteLine(new[] { 1, 0, 1 } is [1, 0, .., 0, 1]);  // False
```

## Ruby

Ruby does something interesting with its pattern matching: rather than only allowing a single
`*` vararg in the middle with single values to the left and right, 
Ruby's [Find Patterns](https://docs.ruby-lang.org/en/3.0/syntax/pattern_matching_rdoc.html#label-Patterns)
allow the `*` only as the _left-most and right-most_ entry in the sequence, with the
single values in the _middle_


```ruby
case ["a", 1, "b", "c", 2]
in [*, String, String, *]
  "matched"
else
  "not matched"
end
```

Implementing this requires a `O(n^2)` scan over the input sequence attempting to
match the pattern starting at every index, hence the name `Find Patterns`. Although better
than the `O(2^n)` exponential backtracking search that would be required for arbitrary 
placement of `*` patterns, it is still much worst than the `O(n)` cost of the 
single-`*` pattern that most languages do, and so we are leaving it out of this proposal.
