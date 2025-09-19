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

The above syntax desugars to an enum tree with subtype relationships:

```scala
sealed trait Animal

object Animal:
  sealed abstract class Mammal extends Animal
  object Mammal:
    case object Dog extends Mammal 
    case object Cat extends Mammal
  
  sealed trait Bird extends Animal
  object Bird:
   case object Sparrow extends Bird
   case object Pinguin extends Bird
  case object FIsh extends Animal
```

Results in:

- `Mammal` and `Bird` are singleton enum cases of `Animal`
- `Dog`, `Cat`, `Sparrow`, and `Pinguin` are **also** values of `Animal`, and they belong to `Mammal` and `Bird` respectively
- Type relationships:
  - `Dog <: Mammal <: Animal`
  - `Cat <: Mammal <: Animal`
  - `Fish <: Animal`
  - etc.

All leaf cases are usable as values of `Animal`, and the nested grouping allows matching at any level of the hierarchy.


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

Matching on intermediate enums is also allowed:

```scala
def isWarmBlooded(a: Animal): Boolean = a match
  case _: Mammal => true     // Covers Dog, Cat
  case Bird   => true     // Covers Sparrow, Pinguin
  case Fish   => false
```

Matching on a **supercase type** (e.g., `m: Mammal`) is shorthand for matching all its subcases.

#### `values`, `ordinal`, `valueOf`

- `Animal.values` returns all **leaf cases**: `[Dog, Cat, Sparrow, Pinguin, Fish]`
- `Mammal.values` returns `[Dog, Cat]`
- `Mammal.ordinal` and `Mammal.valueOf(...)` are also available
- `Mammal` and `Bird` are usable as enum **values**, but excluded from `values` (unless explicitly included)


#### Sealed-ness and Exhaustivity

- The parent enum and all nested enums are sealed.
- Pattern matching at any level (e.g. on `Mammal`) is **exhaustive** at that level.
- At the top level (`Animal`), exhaustivity means all leaf cases must be covered.

#### Reflection / Enum APIs

- `Animal.values`: All leaf values (`Dog`, `Cat`, `Sparrow`, etc.)
- Each nested `case enum` (e.g., `Mammal`) gets its own `.values`, `.ordinal`, and `.valueOf` API.
- `ordinal` value of leaves are global to the supercase  (e.g., `Dog.ordinal == 0`, `Cat.ordinal == 1`, etc.)

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


#### Examples

#### Example 1: Basic Structure

```scala
enum Shape:
  case enum Polygon:
    case Triangle, Square

  case enum Curve:
    case Circle

  case Point
```

#### Example 2: Moddeling size information

```
enum SizeInfo {
  case Bounded(bound: Int)
  case enum Atomic {
    case Infinite
    case Precise(n: Int)
  }
}
```

#### Example 3: Generalized `Either`

```
enum AndOr[+A, +B] {
  case Both[+A, +B](left: A, right: B) extends AndOr[A, B]
  case enum Either[+A, +B] extends AndOr[A, B] {
    case Left[+A, +B](value: A) extends Either[A, B]
    case Right[+A, +B](value: B) extends Either[A, B]
  }
}

```

#### Example 4:
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
```




### Compatibility
- Fully backwards compatible: does not affect existing flat enums.
- Adds optional expressiveness.
- Libraries using `enum` APIs (e.g., `values`) will continue to function with leaf-only views.
- Mirrors and macros

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


