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
Sticking to subtyping only would require the definition of an inneficient and verbose wrapper class.

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
For example, we can rewrite `largest` as follows:

```scala
def largest(xs: Seq[(Any, PolygonWitness)]): Option[(Any, PolygonWitness)] =
  xs.maxByOption((a) => a(1).area(a(0)))
```

A pair `(Any, PolygonWitness)` conceptually represents a type-erased polygon.
We call this pair an _existential container_ and the remainder of this SIP explains how to express this idea in a single, type-safe abstraction by leveraging Scala 3 features.

### Specification

As mentioned above, an existential container is merely a pair containing a value and a witness of its conformance to some concept(s).
Expressing such a value in Scala is easy: just write `(Square(1) : Any, summon[Square is Polygon] : Any)`.
This encoding, however, does not allow the selection of any method defined by `Polygon` without an unsafe cast due to the widening applied on the witness.
Fortunately, this issue can be addressed with path dependent types:

```scala
/** A value together with an evidence of its type conforming to some type class. */
trait Container[Concept <: TypeClass]:
  /** The type of the contained value. */
  type Value : Concept as witness
  /** The contained value. */
  val value: Value

object Container:
  /** Wraps a value of type `V` into a `Container[C]` provided a witness that `V is C`. */
  def apply[C <: TypeClass](v: Any)[V >: v.type](using V is C) =
    new Container[C]:
      type Value >: V <: V
      val value: Value = v
```

### Compatibility

A justification of why the proposal will preserve backward binary and TASTy compatibility. Changes are backward binary compatible if the bytecode produced by a newer compiler can link against library bytecode produced by an older compiler. Changes are backward TASTy compatible if the TASTy files produced by older compilers can be read, with equivalent semantics, by the newer compilers.

If it doesn't do so "by construction", this section should present the ideas of how this could be fixed (through deserialization-time patches and/or alternative binary encodings). It is OK to say here that you don't know how binary and TASTy compatibility will be affected at the time of submitting the proposal. However, by the time it is accepted, those issues will need to be resolved.

This section should also argue to what extent backward source compatibility is preserved. In particular, it should show that it doesn't alter the semantics of existing valid programs.

### Feature Interactions

A discussion of how the proposal interacts with other language features. Think about the following questions:

- When envisioning the application of your proposal, what features come to mind as most likely to interact with it?
- Can you imagine scenarios where such interactions might go wrong?
- How would you solve such negative scenarios? Any limitations/checks/restrictions on syntax/semantics to prevent them from happening? Include such solutions in your proposal.

### Other concerns

If you think of anything else that is worth discussing about the proposal, this is where it should go. Examples include interoperability concerns, cross-platform concerns, implementation challenges.

### Open questions

If some design aspects are not settled yet, this section can present the open questions, with possible alternatives. By the time the proposal is accepted, all the open questions will have to be resolved.

## Alternatives

This section should present alternative proposals that were considered. It should evaluate the pros and cons of each alternative, and contrast them to the main proposal above.

Having alternatives is not a strict requirement for a proposal, but having at least one with carefully exposed pros and cons gives much more weight to the proposal as a whole.

## Related work

This section should list prior work related to the proposal, notably:

- A link to the Pre-SIP discussion that led to this proposal,
- Any other previous proposal (accepted or rejected) covering something similar as the current proposal,
- Whether the proposal is similar to something already existing in other languages,
- If there is already a proof-of-concept implementation, a link to it will be welcome here.

## FAQ

This section will probably initially be empty. As discussions on the proposal progress, it is likely that some questions will come repeatedly. They should be listed here, with appropriate answers.

## References

1. Stefan Wehr and Peter Thiemann. 2011. JavaGI: The Interaction of Type Classes with Interfaces and Inheritance. ACM Transactions on Programming Languages and Systems 33, 4 (2011), 12:1–12:83. https://doi.org/10.1145/1985342.1985343
2.
