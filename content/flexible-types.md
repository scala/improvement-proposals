---
layout: sip
permalink: /sips/:title.html
stage: implementation
status: waiting-for-implementation
title: SIP-XX - Flexible Types for TASTy Format
---

**By: Scala 3 Compiler Team**

## History

| Date          | Version       |
|---------------|---------------|
| Aug 22nd 2025 | Initial SIP   |

## Summary

This proposal specifies the encoding of Flexible Types in the TASTy (Typed Abstract Syntax Tree) format. Flexible Types are a compiler-internal mechanism introduced to improve Java and legacy Scala code (compiled without explicit nulls) interoperability under explicit nulls (`-Yexplicit-nulls`). They allow reference types from Java libraries and legacy Scala codeto be treated as either nullable or non-nullable depending on the usage.

Flexible Types provide a type-safe bridge between implicit nullability and Scala's explicit null system, enabling smoother interoperation while maintaining safety guarantees where possible. This SIP formalizes their representation in TASTy to ensure consistent serialization and deserialization across compiler versions.

This is not a SIP for explicit nulls itself, but only for standardizing the representation of flexible types in the TASTy format.

## Motivation

### Background: Explicit Nulls and Java/Legacy Scala Interoperability

When explicit nulls are enabled (`-Yexplicit-nulls`), Scala's type system changes so that `Null` is no longer a subtype of reference types. Instead, nullable types must be explicitly declared as union types like `String | Null`. This creates a safer type system but introduces friction when interoperating with:

1. **Java libraries**, where all reference types are implicitly nullable
2. **Legacy Scala code**, compiled without explicit nulls, where reference types could historically contain `null` values

### The Problem

Consider a Java method with signature `String getName()` or a legacy Scala method `def getName(): String` compiled without explicit nulls. Under explicit nulls,

1. **If we type it as `String`**: We lose safety because the Java method or legacy Scala method might actually return `null`
2. **If we type it as `String | Null`**: We burden users with constant null checks even when the method is known to never return null in practice

### Current Workarounds and Their Limitations

Before Flexible Types, the compiler would either:
- Force all Java and legacy Scala reference types to be nullable (`String | Null`), leading to excessive null-checking
- Provide unsafe nulls mode (`-language:unsafeNulls`) which disables safety checks entirely

Both approaches are suboptimal for large codebases that want gradual migration to explicit nulls.

## Proposed solution

### High-level overview

We introduce Flexible Types as a compiler-internal representation that allows a type to be treated as both nullable and non-nullable depending on the context. A Flexible Type `T?` (notation borrowed from Kotlin's platform types) has bounds `T | Null <: T? <: T`, meaning:

- It can accept both `T` and `T | Null` values
- It can be used where either `T` or `T | Null` is expected
- It can be called with member functions of `T`, but may throw `NullPointerException` at runtime if the value is actually null

Flexible Types are **non-denotable** - users cannot write them explicitly in source code. Only the compiler creates them during Java interoperability and when consuming legacy Scala code.

They may appear in type signatures because of type inference.
Due to their non-denotable nature, we do not recommend exposing Flexible Types in public APIs or library interfaces.
We will implement a mechanism to warn users when Flexible Types are exposed at field or method boundaries.

### Specification

#### TASTy Format Extension

We extend the TASTy format with a new type tag (`193`):

```
FLEXIBLEtype   Length underlying_Type                            -- (underlying)?
```

The tag is followed by the length of the underlying type and the underlying type itself.

The underlying type `T` represents the upper bound of the flexible type, and the lower bound is implicitly `T | Null`.

#### Subtyping Rules

Flexible Types are designed to introduce a controlled soundness hole to enable practical interoperability. Their subtyping rules differ from regular types:

The subtyping relationships for Flexible Type `T?` are:

1. **Lower bound**: `T | Null <: T?`
2. **Upper bound**: `T? <: T`

Implementation in `TypeComparer.scala`:

```scala
// In firstTry method (line ~901)
case tp2: FlexibleType =>
  recur(tp1, tp2.lo)  // tp1 <: FlexibleType.lo (which is T | Null)

// In thirdTry method (line ~1098)
case tp1: FlexibleType =>
  recur(tp1.hi, tp2)  // FlexibleType.hi (which is T) <: tp2
```

#### Member Selection

All members of the underlying type `T` are considered to be members of the flexible type `T?`.
Selecting a member from `T?` may throw `NullPointerException` at runtime if the actual value is `null`.

#### Erasure

The erased type of `T?` is the erased type of the underlying type `T`.

### Compatibility

Flexible Types preserve binary compatibility because:

1. **Erasure compatibility**: Flexible Types erase to their underlying types, producing identical bytecode
2. **Forward compatibility**: Compilers not using explicit nulls will treat flexible types as their underlying types
3. **Backward compatibility**: Older compilers that don't recognize the `FLEXIBLEtype` tag will treat it as the underlying type

## Implementation

Flexible Types have already been implemented in the latest Scala 3 compiler. The current implementation includes:

### Core Implementation Status

1. **Type System Integration**: The `FlexibleType` case class and its core subtyping rules have been implemented in `Types.scala`
2. **Subtyping Logic**: The subtyping algorithms in `TypeComparer.scala` handle flexible types according to the specification
3. **TASTy Serialization**: The `FLEXIBLEtype` tag (`193`) is fully implemented in `TastyFormat.scala` and supports serialization/deserialization
4. **Nullification Rules**: Both Java classes and legacy Scala code are processed with flexible type nullification when `-Yexplicit-nulls` is enabled

### Planned Improvements

The following enhancements are planned for upcoming releases:

1. Refined nullification rules for edge cases.

2. Stronger TASTy forward/backward compatibility guarantees, including updating tasty-mima and tasty-query.

3. A compiler warning when flexible types appear in public API boundaries.

## Related information

- [**Explicit Nulls**](https://docs.scala-lang.org/scala3/reference/experimental/explicit-nulls.html): The experimental explicit nulls feature that motivated the need for flexible types.
- [**Kotlin Platform Types**](https://kotlinlang.org/docs/java-interop.html#null-safety-and-platform-types): Direct inspiration for the flexible types concept, providing similar interoperability between Kotlin's null safety and Java's implicit nullability.

## FAQ

### Why are Flexible Types not user-denotable?

Making flexible types non-denotable prevents users from depending on them in API boundaries. This ensures:

1. APIs remain clean and explicit about nullability
2. Flexible types serve only as an interop mechanism, not a permanent type system feature
3. Migration path remains clear - eventually all types should be either `T` or `T | Null`

### Can Flexible Types be nested?

No, flexible types cannot be nested. `FlexibleType(FlexibleType(T))` is normalized to `FlexibleType(T)`.
This prevents unnecessarily complex type representations.

### What happens with generic wildcards?

Generic wildcards are handled specially:
- `List<?>` becomes `List[?]?` (flexible type with a wildcard bounds)
- `List<? extends String>` becomes `List[? <: String?]?`
- The outer container is made flexible to handle implicit nullability
