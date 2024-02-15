---
layout: sip
permalink: /sips/:title.html
stage: completed
status: under-review
title: SIP-61 - Unroll Default Arguments for Binary Compatibility
---

**By: Li Haoyi**

## History

| Date          | Version            |
|---------------|--------------------|
| Feb 14th 2024 | Initial Draft      |

## Summary

This SIP proposes an `@unroll` annotation lets you add additional parameters
to method `def`s,`class` construtors, or `case class`es, without breaking binary 
compatibility. `@unroll` works by generating "unrolled" or "telescoping" forwarders:

```scala
// Original
def foo(s: String, n: Int = 1, @unroll b: Boolean = true, l: Long = 0) = s + n + b + l

// Generated
def foo(s: String, n: Int, b: Boolean) = foo(s, n, b, 0)
def foo(s: String, n: Int) = foo(s, n, true, 0)
```

In contrast to most existing or proposed alternatives that require you to contort your
code to become binary compatible (see [Major Alternatives](#major-alternatives)),
`@unroll` allows you to write Scala in the most straightforward way. You add 
a single annotation, and your `def`/`class`/`case class` will maintain binary 
compatibility as new default parameters and fields are added over time. 

`@unroll`'s only constraints are that: 

1. New parameters need to have a default value 
2. New parameters can only be added on the right

These are both existing industry-wide standard when dealing with data and schema evolution
(e.g. [Schema evolution in Avro, Protocol Buffers and Thrift — Martin Kleppmann’s blog](https://martin.kleppmann.com/2012/12/05/schema-evolution-in-avro-protocol-buffers-thrift.html)),
and are also the way the new parameters interact with _source compatibility_ in 
the Scala language. Thus these constraints should be immediately familiar to any
experienced programmers, and would be easy to follow without confusion.

Prior Discussion can be found [here](https://contributors.scala-lang.org/t/can-we-make-adding-a-parameter-with-a-default-value-binary-compatible/6132)

## Motivation

Maintaining binary compatibility of Scala libraries as they evolve over time is
difficult. Although tools like https://github.com/lightbend/mima help _surface_
issues, actually _resolving_ those issues is a different challenge. 

Some kinds of library changes are fundamentally impossible to make compatible, 
e.g. removing methods or classes. But there is one big class of binary compatibility 
issues that are "spurious": adding default parameters to methods, `class` constructors,
or `case class`es.

Adding a default parameter is source-compatible, but not binary compatible: a user
downstream of a library that adds a default parameter does not need to make any 
changes to their code, but _does_ need to re-compile it. This is "spurious" because
there is no _fundamental_ incompatibility here: semantically, a new default parameter
is meant to be optional! Old code invoking that method without a new default parameter
is exactly the user intent, and works just fine if the downstream code is re-compiled.

Other languages, such as Python, have the same default parameter language feature but face 
no such compatibility issues with their use. Even Scala codebases compiled from source 
(e.g. in a mono-repo setup) do not suffer these restrictions: adding a default parameter
to the right side of a parameter list is for all intents and purposes backwards compatible.
The fact that such addition is binary incompatible is purely an implementation restriction
of Scala's binary artifact format and distribution strategy.

**Binary compatibility is generally more important than Source compatibility**. When
you hit a source compatibility issue, you can always change the source code you are 
compiling, whether manually or via your build tool. In contrast, when you hit binary
compatibility issues, it can come in the form of diamond dependencies that would require
_re-compiling all of your transitive dependencies_, a task that is far more difficult and
often impractical.

There are many approaches to resolving these "spurious" binary compatibility issues, 
but most of them involve either tremendous amounts of boilerplate writing 
binary-compatibility forwarders, giving up on core language features like Case Classes 
or Default Parameters, or both. Consider the following code snippet 
([link](https://github.com/com-lihaoyi/mainargs/blob/1d04a6bd19aaca401d11fe26da31615a8bc9213c/mainargs/src/Parser.scala))
from the [com-lihaoyi/mainargs](https://github.com/com-lihaoyi/mainargs) library, which
duplicates the parameters of `def constructEither` no less than five times in
order to maintain binary compatibility as the library evolves and more default
parameters are added to `def constructEither`:

```scala
  def constructEither(
      args: Seq[String],
      allowPositional: Boolean,
      allowRepeats: Boolean,
      totalWidth: Int,
      printHelpOnExit: Boolean,
      docsOnNewLine: Boolean,
      autoPrintHelpAndExit: Option[(Int, PrintStream)],
      customName: String,
      customDoc: String,
      sorted: Boolean,
  ): Either[String, T] = constructEither(
    args,
    allowPositional,
    allowRepeats,
    totalWidth,
    printHelpOnExit,
    docsOnNewLine,
    autoPrintHelpAndExit,
    customName,
    customDoc,
    sorted,
  )

  def constructEither(
      args: Seq[String],
      allowPositional: Boolean = false,
      allowRepeats: Boolean = false,
      totalWidth: Int = 100,
      printHelpOnExit: Boolean = true,
      docsOnNewLine: Boolean = false,
      autoPrintHelpAndExit: Option[(Int, PrintStream)] = Some((0, System.out)),
      customName: String = null,
      customDoc: String = null,
      sorted: Boolean = true,
      nameMapper: String => Option[String] = Util.kebabCaseNameMapper
  ): Either[String, T] = ???

  /** binary compatibility shim. */
  private[mainargs] def constructEither(
      args: Seq[String],
      allowPositional: Boolean,
      allowRepeats: Boolean,
      totalWidth: Int,
      printHelpOnExit: Boolean,
      docsOnNewLine: Boolean,
      autoPrintHelpAndExit: Option[(Int, PrintStream)],
      customName: String,
      customDoc: String,
      nameMapper: String => Option[String]
  ): Either[String, T] = constructEither(
    args,
    allowPositional,
    allowRepeats,
    totalWidth,
    printHelpOnExit,
    docsOnNewLine,
    autoPrintHelpAndExit,
    customName,
    customDoc,
    sorted = true,
    nameMapper = nameMapper
  )
```

Apart from being extremely verbose and full of boilerplate, like any boilerplate this is
also extremely error-prone. Bugs like [com-lihaoyi/mainargs#106](https://github.com/com-lihaoyi/mainargs/issues/106)
slip through when a mistake is made in that boilerplate. These bugs are impossible to catch 
using a normal test suite, as they only appear in the presence of version skew. The above code 
snippet actually _does_ have such a bug, that the test suite _did not_ catch. See if you can 
spot it! 

Sebastien Doraene's talk [Designing Libraries for Source and Binary Compatibility](https://www.youtube.com/watch?v=2wkEX6MCxJs)
explores some of the challenges, and discusses the workarounds. 

## Proposed solution


The proposed solution is to provide a `scala.annotation.unroll` annotation, that
can be applied to methods `def`s, `class` constructors, or `case class`es to generate
"unrolled" or "telescoping" versions of a method that forward to the primary implementation:

```scala
  def constructEither(
      args: Seq[String],
      allowPositional: Boolean = false,
      allowRepeats: Boolean = false,
      totalWidth: Int = 100,
      printHelpOnExit: Boolean = true,
      docsOnNewLine: Boolean = false,
      autoPrintHelpAndExit: Option[(Int, PrintStream)] = Some((0, System.out)),
      customName: String = null,
      customDoc: String = null,
      @unroll sorted: Boolean = true,
      nameMapper: String => Option[String] = Util.kebabCaseNameMapper
  ): Either[String, T] = ???
```

This allows the developer to write the minimal amount of code they _want_ to write,
and add a single annotation to allow binary compatibility to old versions. In this
case, we annotated `sorted` with `@unroll`, which generates forwarders that make
`def constructEither` binary compatible with older versions that have fewer parameters,
up to a version before `sorted` was added. Any existing method `def`, `class`, or
`case class` can be evolved in this way, by addition of `@unroll` the first time
a default argument is added to their signature after its initial definition.

### Unrolling `def`s

Consider a library that is written as follows:

```scala
object Unrolled{
   def foo(s: String, n: Int = 1) = s + n + b + l
}
```

If over time a new default parameter is added:

```scala
object Unrolled{
   def foo(s: String, n: Int = 1, b: Boolean = true) = s + n + b + l
}
```

And another

```scala
object Unrolled{
   def foo(s: String, n: Int = 1, b: Boolean = true, l: Long = 0) = s + n + b + l
}
```

This is a source-compatible change, but not binary-compatible: JVM bytecode compiled against an 
earlier version of the library would be expecting to call `def foo(String, Int)`, but will fail
because the signature is now `def foo(String, Int, Boolean)` or `def foo(String, Int, Boolean, Long)`.
On the JVM this will result in a `MethodNotFoundError` at runtime, a common experience for anyone
who upgrading the versions of their dependencies. Similar concerns are present with Scala.js and
Scala-Native, albeit the failure happens at link-time rather than run-time

`@unroll` is an annotation that can be applied as follows, to the first "additional" default
parameter that was added (in this case, `b: Boolean = true`)


```scala
import scala.annotation.unroll

object Unrolled{
   def foo(s: String, n: Int = 1, @unroll b: Boolean = true, l: Long = 0) = s + n + b + l
}
```

The `@unroll` annotation takes `def foo` and generates synthetic forwarders for the purpose
of maintaining binary compatibility for old callers who may be expecting the previous signature.
These forwarders do nothing but forward the call to the current implementation, using the
given default parameter values:

```scala
import scala.annotation.unroll

object Unrolled{
   def foo(s: String, n: Int = 1, @unroll b: Boolean = true, l: Long = 0) = s + n + b + l

   def foo(s: String, n: Int, b: Boolean) = foo(s, n, b, 0)
   def foo(s: String, n: Int) = foo(s, n, true, 0)
}
```

As a result, old callers who expect `def foo(String, Int, Boolean)` or `def foo(String, Int, Boolean, Long)`
can continue to work, even as new parameters are added to `def foo`. The only restriction is that 
new parameters can only be added on the right, and they must be provided with a default value.

If there are multiple parameter lists (e.g. for curried methods or methods taking implicits) only one
parameter list can be unrolled (though it does not need to be the first one). e.g. this works:

```scala
object Unrolled{
   def foo(s: String, 
           n: Int = 1, 
           @unroll b: Boolean = true, 
           l: Long = 0)
          (implicit blah: Blah) = s + n + b + l
}
```

As does this

```scala
object Unrolled{
   def foo(blah: Blah)
          (s: String, 
           n: Int = 1, 
           @unroll b: Boolean = true, 
           l: Long = 0) = s + n + b + l
}
```

`@unroll`ed methods can be defined in `object`s, `class`es, or `trait`s. Other cases are shown below.

### Unrolling `class`es

Class constructors and secondary constructors are treated by `@unroll` just like any 
other method:

```scala
import scala.annotation.unroll

class Unrolled(s: String, n: Int = 1, @unroll b: Boolean = true, l: Long = 0){
   def foo = s + n + b + l
}
```

Unrolls to:

```scala
import scala.annotation.unroll

class Unrolled(s: String, n: Int = 1, @unroll b: Boolean = true, l: Long = 0){
   def foo = s + n + b + l

   def this(s: String, n: Int, b: Boolean) = this(s, n, b, 0)
   def this(s: String, n: Int) = this(s, n, true, 0)
}
```

### Unrolling `class` secondary constructors

```scala
import scala.annotation.unroll

class Unrolled() {
   var foo = ""

   def this(s: String, n: Int = 1, @unroll b: Boolean = true, l: Long = 0) = {
      this()
      foo = s + n + b + l
   }
}
```

Unrolls to:

```scala
import scala.annotation.unroll

class Unrolled() {
   var foo = ""

   def this(s: String, n: Int = 1, @unroll b: Boolean = true, l: Long = 0) = {
      this()
      foo = s + n + b + l
   }

   def this(s: String, n: Int, b: Boolean) = this(s, n, b, 0)
   def this(s: String, n: Int) = this(s, n, true, 0)
}
```

### Case Classes

`case class`es can also be `@unroll`ed. Unlike normal `class` constructors
and method `def`s, `case class`es have several generated methods (`apply`, `copy`)
that need to be kept in sync with their primary constructor. `@unroll` thus
generates forwarders for those methods as well, based on the presence of the
`@unroll` annotation in the primary constructor:

```scala
import scala.annotation.unroll

case class Unrolled(s: String, n: Int = 1, @unroll b: Boolean = true){
  def foo = s + n + b
}
```

Unrolls to:

```scala
import scala.annotation.unroll

case class Unrolled(s: String, n: Int = 1, @unroll b: Boolean = true, l: Long = 0L){
   def this(s: String, n: Int) = this(s, n, true, 0L)
   def this(s: String, n: Int, b: Boolean) = this(s, n, b, 0L)
   
   def copy(s: String, n: Int) = copy(s, n, true, 0L)
   def copy(s: String, n: Int, b: Boolean) = copy(s, n, b, 0L)
   
   def foo = s + n + b
}
object Unrolled{
   def apply(s: String, n: Int) = apply(s, n, true, 0L)
   def apply(s: String, n: Int, b: Boolean) = apply(s, n, b, 0L)
}
```

Notes:

1. `.unapply` does not need to be duplicated in Scala 3.x, as its signature
   `def unapply(x: Unrolled): Unrolled` does not change when new `case class` fields are
   added.

2. Even in Scala 2.x, where `def unapply(x: Unrolled): Option[TupleN]` is not
   binary compatible, pattern matching on `case class`es is already binary compatible 
   to addition of new fields due to 
   [Option-less Pattern Matching](https://docs.scala-lang.org/scala3/reference/changed-features/pattern-matching.html).
   Thus, only direct calls to `.unapply` on an unrolled `case class` in Scala 2.x (shown below)
   will cause a crash if additional fields were added.

```scala
def foo(t: (String, Int)) = println(t)
Unrolled.unapply(unrolled).map(foo)
```

In Scala 3, `@unroll`ing a `case class` also needs to generate a `fromProduct` 
implementation in the companion object, as shown below:

```scala
def fromProduct(p: Product): CaseClass = p.productArity match
  case 2 =>
    CaseClass(
      p.productElement(0).asInstanceOf[...],
      p.productElement(1).asInstanceOf[...],
    )
  case 3 =>
    CaseClass(
      p.productElement(0).asInstanceOf[...],
      p.productElement(1).asInstanceOf[...],
      p.productElement(2).asInstanceOf[...],
    )
  ...
```

This is not necessary for preserving binary compatibility - the method signature of
`def fromProduct` does not change depending on the number of fields - but it is
necessary to preserve semantic compatibility. `fromProduct` by default does not
take into account field default values, and this change is necessary to make it
use them when the given `p: Product` has a smaller `productArity` than the current
`CaseClass` implementation


## Limitations

1. Only the one parameter list of multi-parameter list methods (i.e. curried or taking
   implicits) can be `@unroll`ed. Unrolling multiple parameter lists would generate a number
   of forwarder methods exponential with regard to the number of parameter lists unrolled,
   and the generated forwarders may begin to conflict with each other. We can choose to spec
   this out and implement it later if necessary, but for 99% of use cases `@unroll`ing one
   parameter list should be enough. Typically, only one parameter list in a method has default
   arguments, with other parameter lists being `implicit`s or a single callback/blocks, neither
   of which usually has default values.

2. As unrolling generates synthetic forwarder methods for binary compatibility, it is
   possible for them to collide if your unrolled method has manually-defined overloads

3. As mentioned earlier, `@unroll`ed case classes are only fully binary compatible in Scala 3, 
   though they are _almost_ binary compatible in Scala 2. Direct calls to `unapply` are binary 
   incompatible, but most common pattern matching of `case class`es goes through a different 
   code path that _is_ binary compatible. In practice this should be sufficient for 99% of use 
   cases, but it does mean that it is possible for code written as below to fail in Scala 2
   if a new unrolled parameter is added to the case class and `.unapply` is called directly.

4. While `@unroll`ed `case class`es are fully binary compatible, they are *not* fully
   _source_ compatible, due to the fact that pattern matching requires all arguments to
   be specified. This proposal does not change that. Future improvements related to
   [Pattern Matching on Named Fields](https://github.com/scala/improvement-proposals/pull/44)
   may bring improvements here. But as we discussed earlier, binary compatibility is generally
   more important than source compatibility, and so we do not need to wait for any source
   compatibility improvements to land before proceeding with these binary compatibility
   improvements.

5. This proposal does not address how macros will derive typeclasses for `case class`es, and
   whether or not those will be binary/source/semantically compatible. That is up to the
   individual macro implementations to decide. e.g., [uPickle](https://github.com/com-lihaoyi/upickle)
   has a very similar rule about adding `case class` fields, except that field ordering
   does not matter. Trying to standardize this across all possible macros and all possible
   typeclasses is out of scope

6. `@unroll` generates a quadratic amount of generated bytecode as more default parameters
   are added: each forwarder has `O(num-params)` size, and there are `O(num-default-params)`
   forwarders. We do not expect this to be a problem in practice, as the small size of the
   generated forwarder methods means the constant factor is small, but one could imagine
   the `O(n^2)` asymptotic complexity becoming a problem if a method accumulates hundreds of
   default parameters over time. In such extreme scenarios, some kind of builder pattern
   (such as those listed in [Major Alternatives](#major-alternatives)) may be preferable.

7. `@unroll` only supports `final` methods. `object` methods and constructors are naturally
   final, but `class` or `trait` methods that are `@unroll`ed need to be explicitly marked `final`.
   It has proved difficult to implement the semantics of `@unroll` in the presence of downstream 
   overrides, `super`, etc. where the downstream overrides can be compiled against by different 
   versions of the upstream code. If we can come up with some implementation that works, we can
   lift this restriction later, but for now I have not managed to do so and so this restriction
   stays.

### Challenges of Non-Final Methods and Overriding

To elaborate a bit on the issues with non-final methods and overriding, consider the following 
case with four classes, `Upstream`, `Downstream`, `Main1` and `Main2`, each of which is compiled
against different versions of each other (hence the varying number of parameters for `foo`):

```scala
class Upstream{ // V2
   def foo(s: String, n: Int = 1, @unroll b: Boolean = true, l: Long = 0) = s + n + b + l
}
```

```scala
class Downstream extends Upstream{ // compiled against Upstream V1
   override def foo(s: String, n: Int = 1) = super.foo(s, n) + s + n
}
```

```scala
object Main1 { // compiled against Upstream V2
   def main(args: Array[String]): Unit = {
      new Downstream().foo("hello", 123, false, 456L)
   }
}
```

```scala
object Main2 { // compiled against Upstream V1
   def main(args: Array[String]): Unit = {
      new Downstream().foo("hello", 123)
   }
}
```


The challenge here is: how do we make sure that `Main1` and `Main2`, who call
`new Downstream().foo`, correctly pick up the version of `def foo` that is 
provided by `Downstream`? 

With the current implementation, the `override def foo` inside `Downstream` would only
override one of `Upstream`'s synthetic forwarders, but would not override the actual
primary implementation. As a result, we would see `Main1` calling the implementation
of `foo` from `Upstream`, while `Main2` calls the implementation of `foo` from 
`Downstream`. So even though both `Main1` and `Main2` have the same 
`Upstream` and `Downstream` code on the classpath, they end up calling different
implementations based on what they were compiled against.

We cannot perform the method search and dispatch _within_ the `def foo` methods,
because it depends on exactly _how_ `foo` is called: the `InvokeVirtual` call from
`Main1` is meant to resolve to `Downstream#foo`, while the `InvokeSpecial` call
from `Downstream#foo`'s `super.foo` is meant to resolve to `Upstream#foo`. But a
method implementation cannot know how it was called, and thus it is impossible
for `def foo` to forward the call to the right place.

This issue only arises in the presence of version skew between `Upstream` and 
`Downstream` code, but that scenario is precisely where `@unroll` is meant to
provide benefits. Thus, unless we can find some solution, we cannot properly support
virtual methods and overrides in `@unroll`.

It may be possible to loosen this restriction to also allow abstract methods that
are implemented only once by a final method. See the section about 
[Abstract Methods](#abstract-methods) for details.

## Major Alternatives

The major alternatives to `@unroll` are listed below:

1. [data-class](https://index.scala-lang.org/alexarchambault/data-class)
2. [SBT Datatype](https://www.scala-sbt.org/1.x/docs/Datatype.html)
3. [Structural Data Structures](https://contributors.scala-lang.org/t/pre-sip-structural-data-structures-that-can-evolve-in-a-binary-compatible-way/5684)
4. Avoiding language features like `case class`es or default parameters, as suggested by the
   [Binary Compatibility for Library Authors](https://docs.scala-lang.org/overviews/core/binary-compatibility-for-library-authors.html) documentation.

While those alternate approaches _do work_ - `data-class` and `SBT Datatype` are used heavily
in various open-source projects - I believe they are inferior to the approach that `@unroll`
takes:

### Case Class v.s. not-a-Case-Class

The first major difference between `@unroll` and the above alternatives is that these alternatives
all introduce something new: some kind of _not-a-case-class_ `class` that is to be used
when binary compatibility is desired. This _not-a-case-class_ has different syntax from 
`case class`es, different semantics, different methods, and so on.

In contrast, `@unroll` does not introduce any new language-level or library-level constructs.
The `@unroll` annotation is purely a compiler-backend concern for maintaining binary 
compatibility. At a language level, `@unroll` allows you to keep using normal method `def`s,
`class`es and `case class`es with exactly the same syntax and semantics you have been using
all along.

Having people be constantly choosing between _case-class_ and _not-a-case-class_ when
designing their data types, is inferior to simply using `case class`es all the time


### Scala Syntax v.s. Java-esque Syntax


The alternatives linked above all build a
Java-esque "[inner platform](https://en.wikipedia.org/wiki/Inner-platform_effect)"
on top of the Scala language, with its own conventions like `.withFoo` methods. 

In contrast, `@unroll` makes use of the existing Scala language's default parameters
to achieve the same effect. 

If we think Scala is nicer to write then Java due to its language
features, then `@unroll`'s approach of leveraging those language features is nicer
to use than the alternative's Java-esque syntax.

Having implementation-level problems - which is what binary compatibility across version
skew is - bleed into the syntax and semantics of the language is also inferior to having it
be controlled by an annotation. Martin Odersky has said that annotations are intended for
things that do not affect typechecking, and `@unroll` fits the bill perfectly.


### Evolving Any Class v.s. Evolving Pre-determined Classes

The alternatives given require that the developer has to decide _up front_ whether their
data type needs to be evolved while maintaining binary compatibility. 

In contrast, `@unroll` allows you to evolve any existing `class` or `case class`.

In general, trying to decide which classes _will need to evolve later on_ is a difficult
task that is easy to get wrong. `@unroll` totally removes that requirement, allowing
you to take _any_ `class` or `case class` and evolve it later in a binary compatible way.


### Binary Compatibility for Methods and Classes

Lastly, the above alternatives only solve _half_ the problem: how to evolve `case class`es.

In contrast, `@unroll` allows the evolution of `def`s and normal `class`es, in addition 
to `case class`es, all using the same approach.

Binary compatility is not just a problem for `case class`es adding new fields: normal
`class` constructors, instance method `def`s, static method `def`s, etc. have default 
parameters added all the time as well. `@unroll` solves all these problems at once,
using the same implementation and same user-facing semantics.


## Minor Alternatives:


### `@unrollOnly`

Currently, `@unroll` generates forwarders for every default parameter to the right of the one
annotated. This is not always necessary, e.g. if multiple parameters are added at once, we 
should only need a single forwarder for the entire set. This can be supported by requiring
supporting an `@unrollOnly` is provided for every default parameter that needs a forwarder generated. That
would mean generating two forwarders would look like this:

```scala
object Unrolled{
   def foo(s: String, n: Int = 1, @unrollOnly b: Boolean = true, @unrollOnly l: Long = 0) = s + n + b + l
}
```
```scala
object Unrolled{
   def foo(s: String, n: Int = 1, @unrollOnly b: Boolean = true, @unrollOnly l: Long = 0) = s + n + b + l

   def foo(s: String, n: Int, b: Boolean) = foo(s, n, b, 0)
   def foo(s: String, n: Int) = foo(s, n, true, 0)
}
```

And generating one forwarder would look like this:

```scala
object Unrolled{
   def foo(s: String, n: Int = 1, @unrollOnly b: Boolean = true, l: Long = 0) = s + n + b + l
}
```
```scala
object Unrolled{
   def foo(s: String, n: Int = 1, @unrollOnly b: Boolean = true, l: Long = 0) = s + n + b + l

   def foo(s: String, n: Int) = foo(s, n, true, 0)
}
```

`@unrollOnly` would provide a more granular replacement for `@unroll`. This probably does not
make a huge difference in most cases, but it could be useful in scenarios where there
are a large number of default parameters being added every version, as it would minimize the
amount of generated code.


### Abstract Methods

In [Limitations](#limitations), I mentioned that `@unroll` only supports `final` methods. 
It is likely possible for abstract methods which are `@unrolled` to have concrete forwarder
methods generated on their behalf.

```scala
import scala.annotation.unroll

trait Unrolled{
  def foo(s: String, n: Int = 1, @unroll b: Boolean = true): String
}

object Unrolled extends Unrolled{
  def foo(s: String, n: Int = 1, b: Boolean = true) = s + n + b
}
```

Unrolls to:

```scala
trait Unrolled{
  def foo(s: String, n: Int = 1, @unroll b: Boolean = true): String = foo(s, n)
  def foo(s: String, n: Int = 1): String = foo(s, n, true)
}

object Unrolled extends Unrolled{
  def foo(s: String, n: Int = 1, b: Boolean = true) = s + n + b
}
```

As the forwarders are concrete, the implementor of the abstract method does
not need to `@unroll` the implementation: they only need to provide the implementation
for the primary method `def` and not the forwarders.

One thing to note is that the `@unroll`ed abstract method needs to _itself_ become a
forwarder method, despite originally being abstract! That is because downstream code
compiled against an old version may define classes which `extends Unrolled` and
define a concrete `def foo(s: String, n: Int = 1): String`, while other downstream code compiled
against a newer version may define a concrete
`def foo(s: String, n: Int = 1, b: Boolean = true): String`. Thus, we need both overloads
of `foo` to be forwarders, so that downstream code can override either version and still work.

This handling for abstract methods is not fully fleshed out or implemented, so I'm
not sure if it can truly be made to work.

### Should the Generated Methods be Deprecated or Invisible?

It is not clear to me if we should discourage usage of the generated forwarders 
methods, or hide them:

1. On one hand, downstream code should not be compiling against these methods: they are 
   purely for binary compatibility

2. On the other hand, downstream code does not control which overload of the method is 
   selected: that is up to the Scala compiler and how overloading interacts with default
   parameter values. I have encountered scenarios with manually-written forwarders where
   selected over the "primary" implementation

3. The forwarders are meant to have the exact same semantics as the "primary" implementation.
   Thus it _does not matter_ whether you call the primary and pass it a default value,
   or you call a forwarder which then calls the primary and passes it the same default value.

For now, I have left the generated methods "as is", though choosing to hide them or deprecate
them or something else is definitely an option

### Generating Forwarders For Parameter Type Widening or Result Type Narrowing

While this proposal focuses on generating forwarders for addition of default parameters,
you can also imagine similar forwarders being generated if method parameter types
are widened or if result types are narrowed:

```scala
// Before
def foo(s: String, n: Int = 1, b: Boolean = true) = s + n + b + l

// After
def foo(@unrollType[String] s: Object, n: Int = 1, b: Boolean = true) = s.toString + n + b + l

// Generated
def foo(s: Object, n: Int = 1, b: Boolean = true) = s.toString + n + b + l
def foo(s: String, n: Int = 1, b: Boolean = true) = foo(s, n, b)
```

This would follow the precedence of how Java's and Scala's covariant method return 
type overrides are implemented: when a class overrides a method with a new 
implementation with a narrower return type, a forwarder method is generated to 
allow anyone calling the original signature \to be forwarded to the narrower signature.

This is not currently implemented in `@unroll`, but would be a straightforward addition.

### Incremental Forwarders or Direct Forwarders

Given this:

```scala
def foo(s: String, n: Int = 1, @unroll b: Boolean = true, l: Long = 0) = s + n + b + l
```

There are two ways to do the forwarders. First option, which I used in above, is
to have each forwarder directly call the primary method:

```scala
def foo(s: String, n: Int, b: Boolean) = foo(s, n, b, 0)
def foo(s: String, n: Int) = foo(s, n, true, 0)
```

Second option is to have each forwarder incrementally call the next forwarder, which
will eventually end up calling the primary method:

```scala
def foo(s: String, n: Int, b: Boolean) = foo(s, n, b, 0)
def foo(s: String, n: Int) = foo(s, n, true)
```

The first option results in shorter stack traces, while the second option results in
roughly half as much generated bytecode in the method bodies (though it's still `O(n^2)`).

For now I chose the first option.


## Implementation & Testing

This SIP has a full implementation for Scala {2.12, 2.13, 3} X {JVM, JS, Native}
in the following repository, as a compiler plugin:

- https://github.com/com-lihaoyi/unroll

As the `@unroll` annotation is purely a compile-time construct and does not need to exist
at runtime, `@unroll` can be added to Scala 2.13.x without breaking forwards compatibility.

The linked repo also contains an extensive test suite that uses both MIMA as well
as classpath-mangling to validate that it provides both the binary and semantic
compatibility benefits claimed in this document. In fact, it has even discovered
bugs in the upstream Scala implementation related to binary compatibility, e.g.
[scala-native/scala-native#3747](https://github.com/scala-native/scala-native/issues/3747)

I have also opened pull requests to a number of popular OSS Scala libraries,
using `@unroll` as a replacement for manually writing binary compatibility stubs,
and the 100s of lines of boilerplate reduction can be seen in the links below:

- https://github.com/com-lihaoyi/mainargs/pull/113/files
- https://github.com/com-lihaoyi/mill/pull/3008/files
- https://github.com/com-lihaoyi/upickle/pull/555/files

These pull requests all pass both the test suite as well as the MIMA
`check-binary-compatibility` job, demonstrating that this approach does work
in real-world codebases. 
