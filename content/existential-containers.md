---
layout: sip
permalink: /sips/:title.html
stage: design
status: submitted
presip-thread: n/a
title: SIP-NN - Existential Containers
---

**By: Dimi Racordon and Eugene Flesselle and Matt Bovel**

## History

| Date          | Version            |
|---------------|--------------------|
| Nov 25th 2024 | Initial Draft      |

## Summary

Type classes have become a well-established feature in the Scala ecosystem to escape some of the shortcomings of subtyping with respect to extensibility.
Unfortunately, type classes do not support run-time polymorphism and dynamic dispatch, two features typically taken for granted in Scala.

This SIP proposes a feature called *existential containers* to address this problem.
An existential container wraps a value together with a witness of its conformance to one or several type classes into an object exposing the API defined by these type classes.

## Motivation

Type classes can address some of the well-known limitations of subtyping with respect to extensibility, such as the ability to extend existing data types with new behaviors [1].
A type class describes the interface of a generic _concept_ as a set of requirements, expressed in the form of operations and associated types.
These requirements can be implemented for a specific type, thereby specifying how this type _models_ the concept.
The following illustrates:

```scala
import shapes.{Square, Hexagon}

trait TypeClass:
  type Self

trait Polygon extends TypeClass:
  extension (self: Self)
    def area: Double

given Square is Polygon: ...
given Hexagon is Polygon: ...
```

Defining `Polygon` as a type class rather than an abstract class to be inherited allows us to retroactively state that squares are polygons without modifying the definition of `Square`.
Sticking to subtyping would require the definition of an inneficient and verbose wrapper class.

Alas, type classes offer limited support for type erasure–the eliding of some type information at compile-time.
Hence, it is difficult to manipulate heterogeneous collections or write procedures returning arbitrary values known to model a particular concept.
The following illustrates:

```scala
def largest[T: Polygon](xs: Seq[T]): Option[T] =
  xs.maxByOption(_.area)

largest(List(Square(), Hexagon()))
// error: No given instance of type Polygon{type Self = Square | Hex} was found for a context parameter of method largest
```

The call to `largest` is illegal because, although there exist witnesses of the `Polygon` and `Hexagon`'s conformance to `Polygon`, no such witness exists for their least common supertype.
In other words, it is impossible to call `largest` with an heterogeneous sequence of polygons.

## Proposed solution

The problems raised above can be worked around if, instead of using generic parameters with a context bound, we use pairs bundling a value with its conformance witness.
In broad strokes, our solution generalizes the following possible implementation of `largest`:

```scala
trait AnyPolygon:
  type Value
  val value: Value
  val witness: Polygon { type Self = Value }

def largest(xs: Seq[AnyPolygon]): Option[AnyPolygon] =
  xs.maxByOption((a) => a.witness.area(a.value))
```

The type `AnyPolygon` conceptually represents a type-erased polygon.
It consists of a pair containing some arbitrary value as well as a witness of that value's type being a polygon.
We call this pair an _existential container_, as a nod to a similar feature in Swift, and the remainder of this SIP explains how to express this idea in a single, type-safe abstraction.

### Specification

Existential containers are encoded as follows:

```scala
import language.experimental.{clauseInterleaving, modularity}

/** A type class. */
trait TypeClass:
  type Self

/** A value together with an evidence of its type conforming to some type class. */
sealed trait Containing[Concept <: TypeClass]:
  /** The type of the contained value. */
  type Value: Concept as witness
  /** The contained value. */
  val value: Value

object Containing:
  /** A `Containing[C]` whose value is known to have type `V`. */
  type Precisely[C <: TypeClass, V] =
    Containing[C] { type Value >: V <: V }
  /** Wraps a value of type `V` into a `Containing[C]` provided a witness that `V is C`. */
  def apply[C <: TypeClass](v: Any)[V >: v.type](using V is C) =
    new Precisely[C, V] { val value: Value = v }
  /** An implicit constructor for `Containing.Precisely[C, V]` from `V`. */
  given constructor[C <: TypeClass, V : C]: Conversion[V, Precisely[C, V]] =
    apply
```

Given a type class `C`, an instance `Containing[C]` is an existential container, similar to `AnyPolygon` shown before.
The context bound on the definition of the `Value` member provides a witness of `Value`'s conformance to `C` during implicit resolution when a method of the `value` field is selected.
The companion object of `Containing` provides basic support to create containers ergonomically.
For instance:

```scala
def largest(xs: Seq[Containing[Polygon]]): Option[Containing[Polygon]] =
  xs.maxByOption(_.value.area)
```

To further improve usability, we propose to let the compiler inject the selection of the `value` field implicitly when a method of `Containing[C]` is selected.
That way, one can simply write `xs.maxByOption(_.area)` in the above example, resulting in quite idiomatic scala.

```scala
// Version with subtyping:
trait Polygon1:
  def area: Double
def largest1(xs: Seq[Polygon1]): Option[Polygon1] =
  xs.maxByOption(_.area)

// Version with existential containers:
trait Polygon2 extends TypeClass:
  extension (self: Self) def area: Double
def largest2(xs: Seq[Containing[Polygon2]]): Option[Containing[Polygon2]] =
  xs.maxByOption(_.area)
```

### Compatibility

The change in the syntax does not affect any existing code and therefore this proposal has no impact on source compatibility.

The semantics of the proposed feature is fully expressible in Scala.
Save for the implicit addition of `.value` on method selection when the receiver is an instance of `Containing[C]`, this proposal requires no change in the language.
As a result, it has no backward binary or TASTy compatibility consequences.

### Feature Interactions

The proposed feature is meant to interact with implicit search, as currently implemented by the language.
More specifically, given an existential container `c`, accessing `c.value` _opens_ the existential while retaining its type `c.Value`, effectively keeping an _anchor_ (i.e., the path to the scope of the witness) to the interface of the type class.

Since no change in implicit resolution is needed, this proposal cannot create unforeseen negative interactions with existing features.

### Open questions

One problem not addressed by the proposed encoding is the support of multiple type classes to form the interface of a specific container.
For example, one may desire to create a container of values whose types conform to both `Polygon` _and_ `Show`.
We have explored possible encodings for such a feature but decided to remove them from this proposal, as support for multiple type classes can most likely be achieved without any additional language change.

Another open question relates to possible language support for shortening the expression of a container type and/or value.

## Related work

Swift support existential containers.
For instance, `largest` can be written as follows in Swift:

```swift
func largest(_ xs: [any Polygon]) -> (any Polygon)? {
  xs.max { (a, b) in a.area < b.area }
}
```

Unlike in this proposal, existential containers in Swift are built-in and have a dedicated syntax (i.e., `any P`).
One advantage of Swift's design is that the type system can treat an existential container as supertype of types conforming to that container's interface.
For example, `any Polygon` is supertype of `Square` (assuming the latter conforms to `Polygon`):

```swift
print(largest([Square(), Hexagon()]))
```

In contrast, to avoid possible undesirable complications, this proposal does not suggest any change to the subtyping relation of Scala.

Rust also supports existential containers in a similar way, writing `dyn P` to denote a container bundling some value of a type conforming to `P`.
Similar to Swift, existential containers in Rust are considered supertypes of the types conforming to their bound.


A more formal exploration of the state of the art as been documented in a research paper presented prior to this SIP [2].

## FAQ

#### Is there any significant performance overhead in using existential containers?

On micro benchmarks testing method dispatch specifcally, we have measured that dispatching through existential containers in Scala was about twice as slow as traditional virtual method dispatch, which is explained by the extra pointer indirection introduced by an existential container.
This overhead drops below 10% on larger, more realistic benchmarks [2].

## References

1. Stefan Wehr and Peter Thiemann. 2011. JavaGI: The Interaction of Type Classes with Interfaces and Inheritance. ACM Transactions on Programming Languages and Systems 33, 4 (2011), 12:1–12:83. https://doi.org/10.1145/1985342.1985343
2. Dimi Racordon and Eugene Flesselle and Matt Bovel. 2024. Existential Containers in Scala. ACM SIGPLAN International Conference on Managed Programming Languages and Runtimes, pp. 55-64. https://doi.org/10.1145/3679007.3685056

