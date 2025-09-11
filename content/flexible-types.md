---
layout: sip
permalink: /sips/:title.html
stage: implementation
status: under-review
title: SIP-XX - Adding Flexible Types as Internal Type to Scala 3 Spec
---

## History

| Date          | Version       |
|---------------|---------------|
| Aug 22nd 2025 | Initial SIP   |

## Summary

This proposal specifies the representation of Flexible Types and encoding in the TASTy (Typed Abstract Syntax Tree) format. Flexible Types are an Internal Type (see §3.1 of the Scala 3 language specification) introduced to improve interoperability with Java and legacy Scala code (compiled without explicit nulls) under explicit nulls (`-Yexplicit-nulls`). They allow reference types from Java libraries and legacy Scala code to be treated as either nullable or non-nullable depending on the usage.

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

We introduce Flexible Types as an Internal Type that allows a type to be treated as both nullable and non-nullable depending on the context. Informally, we write a flexible type as `T?` (notation inspired by Kotlin platform types). The following subtyping relationships hold: `T | Null <: T?` and `T? <: T`, meaning:

- It can accept both `T` and `T | Null` values
- It can be used where either `T` or `T | Null` is expected
- It can be used as the prefix in accesses to members of `T`, but may throw `NullPointerException` at runtime if the value is actually null

Flexible Types are **non-denotable** - users cannot write them explicitly in source code. Only the compiler creates them during Java interoperability and when consuming legacy Scala code.

They may appear in type signatures because of type inference.
Due to their non-denotable nature, we do not recommend exposing Flexible Types in public APIs or library interfaces.
We have implemented a mechanism to warn users when Flexible Types are exposed at public (or protected) field or method boundaries.

### Specification

#### Abstract Syntax (Spec Addendum)

We extend the abstract syntax of (internal) types with a new form:

```
InternalType ::= ... | FlexibleType
FlexibleType ::= Type ‘?’
```

`T?` (rendered informally in spec; there is no concrete syntax) designates a flexible type whose underlying type is `T`.

Normalization: `(T?)? = T?` (flexible types do not nest).

#### Conformance (Extension to §3.6.1)

We extend the conformance relation (<:) with the following two derivation rules:

1. `S = U` and `T = U?`
2. `S = Null` and `T = U?`
3. `S = U?` and `T = U`

We can also equivalence: `U =:= U?` and `U | Null =:= U?`, 
even though `U | Null` and `U` may be not equivalent under explicit nulls.

#### Member Selection

Member selection is treated as if `T?` were `T` (so `memberType(T?, m, p)` delegates to `memberType(T, m, p)`).

#### TASTy Format Extension

We reserve a new TASTy type tag (`193`) to encode flexible types:

```
FLEXIBLEtype   Length underlying_Type
```

Decoders that do not recognize `FLEXIBLEtype` may safely treat it as its underlying type `T` (erasure compatibility is preserved).

#### Subtyping Rules in Compiler

For implementors: the two conformance rules above are implemented in `TypeComparer.scala` as follows:

```scala
// In firstTry method 
case tp2: FlexibleType =>
  recur(tp1, tp2.lo)  // tp1 <: FlexibleType.lo (which is T | Null)

// In thirdTry method
case tp1: FlexibleType =>
  recur(tp1.hi, tp2)  // FlexibleType.hi (which is T) <: tp2
```

#### Type Erasure (Extension to §3.8)

Erasure is extended with: `|T?| = |T|` (i.e., identical to the erasure of its underlying type).

### Compatibility

Flexible Types preserve binary compatibility because:

1. **Erasure compatibility**: Flexible Types erase to their underlying types, producing identical bytecode
2. **Forward compatibility**: Compilers not using explicit nulls will treat flexible types as their underlying types
3. **Backward compatibility**: Older compilers that don't recognize the `FLEXIBLEtype` tag will treat it as the underlying type

## Implementation

Flexible Types have already been implemented in the latest Scala 3 compiler. The current implementation includes:

### Core Implementation Status

1. **New Type and Subtyping Rules**: The `FlexibleType` case class and its subtyping rules have been implemented
2. **TASTy Serialization**: The `FLEXIBLEtype` tag (`193`) is fully implemented in `TastyFormat.scala` and supports serialization/deserialization
3. **Nullification Rules**: Both Java classes and legacy Scala code are processed with flexible type nullification when `-Yexplicit-nulls` is enabled
4. **Public API Warnings**: A warning mechanism is in place to alert users when flexible types appear in public or protected API boundaries

### Planned Improvements

The following enhancements are planned for upcoming releases:

1. Refined nullification rules for edge cases.
2. Stronger TASTy forward/backward compatibility guarantees, including updating tasty-mima and tasty-query.

## Related information

- [**Explicit Nulls**](https://docs.scala-lang.org/scala3/reference/experimental/explicit-nulls.html): The experimental explicit nulls feature that motivated the need for flexible types.
- [**Kotlin Platform Types**](https://kotlinlang.org/docs/java-interop.html#null-safety-and-platform-types): Direct inspiration for the flexible types concept, providing similar interoperability between Kotlin's null safety and Java's implicit nullability.