---
layout: sip
permalink: /sips/:title.html
stage: implementation
status: waiting-for-implementation
presip-thread: https://contributors.scala-lang.org/t/pre-sip-foo-bar/9999
title: SIP-NN - Multi-Level Enums
---

# SIP: Multi-Level Enums

**By: Raphael Bosshard**

## History

| Date       | Version            |
| ---------- | ------------------ |
| 2025-09-15 | Initial Draft      |
| 2026-02-17 | Revised Draft      |



## Summary

This proposal adds minimal syntax to allow enums with nested enumerations, maintaining full exhaustivity checking while keeping syntax clean and intuitive.

## Motivation

Scala 3 introduced `enum` as a concise and type-safe way to define algebraic data types. However, it currently supports only flat enums. Many real-world use cases, such as domain modeling or UI state machines, naturally require **hierarchical enums**, where cases are grouped into logical families or categories.

Consider modeling animals:

```scala
enum Animal:
  case Dog, Cat, Sparrow, Penguin
```

This flat structure works, but grouping `Dog` and `Cat` under `Mammal`, and `Sparrow` and `Penguin` under `Bird` is more expressive and enables cleaner abstraction.


## Proposed solution

### High-level overview

Allow `enum` cases to contain **nested `enum` definitions**, using a consistent indentation-based syntax.

```scala
enum Animal:
  case enum Mammal:
    case Dog, Cat
  case enum Bird:
    case Sparrow, Pinguin
```

Each nested `enum` case defines a group of related subcases. The **nested enum is itself a valid subtype** of the parent enum, and its members are **valid cases** of the parent enum, allowing full exhaustivity and pattern matching.

### Specification

#### Enum Definition:

```scala
enum Animal:
  case enum Mammal:
    case Dog, Cat
  case enum Bird:
    case Sparrow, Pinguin
  case Fish
```

- `case enum Mammal:` introduces a **sub-enum case**.
- Nested cases (`Dog`, `Cat`) are **automatically part of the parent enum** (`Animal`), as well as part of the sub-enum (`Mammal`).

#### Desugaring / Type Relationships



Recursive desugaring:

1. For `enum E` emit `sealed abstract class E` and `object E` (the companion).
2. For each `case Ids` directly under the current enum/enum-case:
  - if `Id` has no nested subcases, emit `case object`/`case class` `Id` extending the current sealed class.
  - if `Id` is `case enum S: ...`, emit `sealed abstract class S extends Current` and `object S` in the current companion, then recursively lower `S`'s body with `S` as the current sealed class.
3. If a case has an explicit `extends T`, use `T` as the immediate supertype instead of the current sealed class (subject to normal Scala `extends` rules).
4. Preserve declared type parameters and variance for parameterized cases and nested enums; companions are singletons (`.type`).
5. Populate each companion's `.values` from the companion's leaf members in source-order (depth-first, left-to-right traversal).

Example lowered shape (conceptual):

```scala
sealed abstract class Animal
object Animal:
  sealed abstract class Mammal extends Animal
  object Mammal:
    case object Dog extends Mammal
    case object Cat extends Mammal

  sealed abstract class Bird extends Animal
  object Bird:
    case object Sparrow extends Bird
    case object Pinguin extends Bird

  case object Fish extends Animal
```

Notes / rules:

- Enums are modeled as `sealed abstract class`; all subtype relations asserted by nested `case enum` follow that model.
- `extends` clauses on cases follow normal Scala rules: a case may extend an accessible abstract class or trait; when it names the enclosing supercase class (the immediately enclosing `case enum`), that creates the intended parent/child enum subtype relation.
- Constructor expressions of nested leaf cases are "widened" to the immediately enclosing enum type (the nearest enclosing `enum` or `case enum` under which the case is declared). In other words, the declared parent is the target widening type.
- A nested `case enum` that contains subcases is not simultaneously a distinct term-case value of its parent. If it has subcases it represents a namespace/type; to have both a singleton term and subcases you must declare them separately with distinct names.

- Type relationships: for a nested declaration `case enum S` inside `enum E`, every leaf `L` declared under `S` satisfies `L <: S <: E`. Parameterized cases keep their declared type parameters and variance; companion objects are singletons whose `.type` is a subtype of the declared class type.


```scala
enum Animal:
  case enum Mammal:
    case Dog, Cat
  case Fish
```

// Lowered/type relations
// Dog <: Mammal <: Animal
// Cat <: Mammal <: Animal
// Fish <: Animal

// Widening (constructor expression widens to the nearest enclosing enum):
val d: Mammal = Animal.Mammal.Dog
val a: Animal = Animal.Mammal.Dog

// `.values` and `ordinal` (per-enum view):
// Each enum's ordinals are relative to its own `.values`
```scala
enum Animal:        // No ordinal (non-leaf container)
  case enum Mammal: // No ordinal (non-leaf container)
    case Dog        // Ordinal 0 in Mammal, Ordinal 0 in Animal
    case Cat        // Ordinal 1 in Mammal, Ordinal 1 in Animal
  case enum Bird:   // No ordinal (non-leaf container)
    case Sparrow    // Ordinal 0 in Bird, Ordinal 2 in Animal
    case Pinguin    // Ordinal 1 in Bird, Ordinal 3 in Animal
  case Fish         // Ordinal 2 in Animal
```

// Matching semantics:
a match
  case _: Mammal => // matches Dog | Cat
  case Mammal  => // matches singleton Mammal only when declared as a leaf

// Inferred term-subcase type:
val x = Animal.Mammal.Dog // inferred: Animal.Mammal.Dog.type

// Prohibited ambiguity (example):
enum Bad:
  case Mammal           // term
  case enum Mammal:     // error: duplicate/ambiguous name
    case Dog
```


#### Pattern Matching

Exhaustive pattern matching on `Animal` must cover all leaf cases:

```scala
def classify(a: Animal): String = a match
  case Dog     => "a dog"
  case Cat     => "a cat"
  case Sparrow => "a bird"
  case Pinguin => "a penguin"
  case Fish    => "a fish"
```

Matching on intermediate enums is allowed, but the pattern form matters:

```scala
def isWarmBlooded(a: Animal): Boolean = a match
  case _: Mammal => true     // matches any value whose runtime type conforms to Mammal (covers Dog, Cat)
  case Bird     => true     // matches the singleton term `Bird` (only if `Bird` is declared as a term value)
  case Fish     => false
```

Semantics:

- `case _: Mammal` matches any leaf whose type is a subtype of `Mammal`.
- `case Mammal` (no `_:`) matches the singleton term `Mammal` only when `Mammal` is itself a declared term-case (i.e. a leaf); it is not shorthand for all subcases.


#### `values`, `ordinal`, `valueOf`

- `.values` for an enum returns the leaf cases that belong to that enum (the enumeration's view of leaves). Example: `Animal.values == [Dog, Cat, Sparrow, Pinguin, Fish]` and `Mammal.values == [Dog, Cat]`.
- `ordinal` is defined relative to the enum whose `.values` is considered: a leaf's `ordinal` in `E` is its index in `E.values`.
- Non-leaf groupings (i.e. a `case enum` that contains subcases) are not themselves entries of their parent `.values`; therefore they do not receive ordinals in the parent's ordering. A name that is both a singleton value and a container is disallowed to avoid ambiguity.
- `valueOf` works against the `.values` set of the enum being queried.

Proposal for term-subcase typing:

- For expressions like `val x = Animal.Mammal.Dog` the inferred type should be the singleton type `Animal.Mammal.Dog.type` (the most precise static type), which also conforms to `Mammal` and `Animal`.


#### Sealed-ness and Exhaustivity

- The parent enum and all nested enums are sealed.
- Pattern matching at any level (e.g. on `Mammal`) is **exhaustive** at that level.
- At the top level (`Animal`), exhaustivity means all leaf cases must be covered.

#### Reflection / Enum APIs

- `Animal.values`: All leaf values (`Dog`, `Cat`, `Sparrow`, `Pinguin`, `Fish`) in source-order (depth-first, left-to-right).
- Each nested `case enum` (e.g., `Mammal`) gets its own `.values`, `.ordinal`, and `.valueOf` API.
- Each enum's `.values` contains only its direct leaf members, and `.ordinal` is the index within that enum's `.values`.
- A leaf's ordinal depends on which enum it is queried from: `Dog.ordinal` in `Mammal` is 0; in `Animal` it is also 0.

#### Syntax Specification (EBNF-like)

```
EnumDef         ::= 'enum' Id ':' EnumBody
EnumBody        ::= { EnumCase }
EnumCase        ::= 'case' EnumCaseDef
EnumCaseDef     ::= Ids
                 | 'enum' Id ':' EnumBody
Ids             ::= Id {',' Id}
```


#### Compiler

- The Scala compiler must treat nested enums inside `case enum` as part of the parent enum’s namespace.
- Exhaustivity checking logic must recursively analyze nested enums to extract leaf cases.
- Type relationships must be modeled to reflect subtyping: e.g. `Dog <: Mammal <: Animal`.


#### Examples (with desugaring)


##### Basic Structure

```scala
enum Shape:
  case enum Polygon:
    case Triangle, Square

  case enum Curve:
    case Circle

  case Point


// Desugaring

```

#### Moddeling size information

```
enum SizeInfo {
  case Bounded(bound: Int)
  case enum Atomic {
    case Infinite
    case Precise(n: Int)
  }
}
```

#### Generalized `Either`

```
enum AndOr[+A, +B] {
  case Both[+A, +B](left: A, right: B) extends AndOr[A, B]
  case enum Either[+A, +B] extends AndOr[A, B] {
    case Left[+A, +B](value: A) extends Either[A, B]
    case Right[+A, +B](value: B) extends Either[A, B]
  }
}


// Desugaring

```

#### JSON datataype
Grouping JSON values into primitives and non-primitives

```
enum JsValue {
  case Obj(fields: Map[String, JsValue])
  case Arr(elems: ArraySeq[JsValue])
  case enum Primitive {
    case Str(str: String)
    case Num(bigDecimal: BigDecimal)
    case JsNull
    case enum Bool(boolean: Boolean) {
      case True extends Bool(true)
      case False extends Bool(false)
    }
  }
}

// Desugaring

```

##### Parameterized nested enums and `extends` clause

```scala
enum Animal:
  case enum Bird(sound: String):
    case Sparrow extends Bird("chirp")
    case Pinguin extends Bird("honk")

  case enum Mammal:
    case Dog(sound: String) extends Mammal
    case Cat(sound: String) extends Mammal

  case Fish

// Desugaring:
sealed abstract class Animal
object Animal:
  sealed abstract class Bird(val sound: String) extends Animal
  object Bird:
    case object Sparrow extends Bird("chirp")
    case object Pinguin extends Bird("honk")

  sealed abstract class Mammal extends Animal
  object Mammal:
    case class Dog(sound: String) extends Mammal
    case class Cat(sound: String) extends Mammal

  case object Fish extends Animal
```



### Compatibility
- Fully backwards compatible: does not affect existing flat enums.
- Adds optional expressiveness.
- Libraries using `enum` APIs (e.g., `values`) will continue to function with leaf-only views.

### Other Concerns

#### Macro / Tooling Support

- IDEs and macros need to understand the nested structure.
- Pattern matching hints and auto-completion should support matching on intermediate cases.


### Feature Interactions


### Alternatives

- **Flat enums with traits**: more verbose, less exhaustivity checking, more boilerplate.
- **Nested cases with `extends`**: heavier syntax, harder to teach/read.
- **DSLs or macros**: non-standard, cannot integrate with Scala's `enum` semantics cleanly.


## Related Work


## Faq


