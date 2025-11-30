---
layout: sip
title: Trailing Comma Syntax for Tuple Types and Values
stage: pre-sip
status: draft
---

# SIP-NN: Trailing Comma Syntax for Tuple Types and Values

**By: [Ruslan Shevchenko]**

## History

| Date          | Version       |
|---------------|---------------|
| Nov 30th 2025 | Expanded grammar specification and SIP-27 interaction |
| Nov 29th 2025 | Initial Draft |

## Summary

This proposal introduces trailing comma syntax for tuples, allowing `(A,)` to represent a single-element tuple type and `(a,)` to represent a single-element tuple value. This addresses the long-standing ambiguity where `(A)` is parsed as a parenthesized expression rather than a tuple, making it impossible to express single-element tuples using the concise parenthesized notation.

The proposal also allows `(,)` for empty tuples (equivalent to `EmptyTuple`) and trailing commas in multi-element tuples like `(A, B,)`, providing consistency with other Scala constructs that already support trailing commas.

This change enables more ergonomic syntax when working with tuple-based APIs, particularly those using `*:` and `EmptyTuple` for type-level programming, where the current syntax `A *: EmptyTuple` is verbose compared to the proposed `(A,)`.

## Motivation

### Single-Element Tuples Are Cumbersome

Currently, there is no concise way to express a single-element tuple in Scala 3. The parenthesized notation `(A)` is parsed as a simple parenthesized expression, not a tuple:

```scala
// Current behavior
val x: (Int) = 42        // This is just Int, not Tuple1[Int]
val y: Int = x           // Compiles fine - (Int) is just Int

// To get a single-element tuple, you must write:
val t1: Int *: EmptyTuple = 1 *: EmptyTuple
val t2: Tuple1[Int] = Tuple1(1)
```

Notably, `Tuple1.toString` already uses the proposed syntax:

```scala
scala> val t = Tuple1(3)
val t: Tuple1[Int] = (3,)
```

This creates an inconsistency: the REPL prints `(3,)` but you cannot write this syntax in code.

This is particularly problematic when working with tuple-based APIs:

```scala
// Verbose current syntax
class Component(using AppContextProvider[Dependency *: EmptyTuple])

// Desired concise syntax (enabled by this proposal)
class Component(using AppContextProvider[(Dependency,)])
```

### No Concise Syntax for Empty Tuple Type

There is also no concise syntax for empty tuple types - `EmptyTuple` must be written explicitly:

```scala
val empty: EmptyTuple = EmptyTuple  // No shorthand syntax available
```

Scala supports trailing commas in multi-line contexts (parameters, arguments, imports, tuples, etc.) when the closing delimiter is on its own line (SIP-27). However, there is no way to use a trailing comma to disambiguate single-element tuples from parenthesized expressions, since `(a,)` is a syntax error.

## Proposed Solution

### High-Level Overview

Allow a trailing comma in tuple syntax to force tuple interpretation:

```scala
// Single-element type tuple
type T1 = (Int,)                    // Equivalent to Int *: EmptyTuple or Tuple1[Int]

// Single-element value tuple
val v1: (Int,) = (1,)               // A tuple containing just 1

// Empty tuple (optional, for symmetry)
type T0 = (,)                       // Equivalent to EmptyTuple (not Unit!)
val v0: (,) = (,)                   // Equivalent to EmptyTuple (not Unit!)

// Multi-element tuples with trailing comma (for consistency)
type T2 = (Int, String,)            // Same as (Int, String)
val v2 = (1, "hello",)              // Same as (1, "hello")

// Pattern matching
def process(x: Any) = x match
  case (a,) => s"single: $a"        // Matches single-element tuple
  case (a, b) => s"pair: $a, $b"    // Matches two-element tuple
  case _ => "other"
```

### Specification

#### Grammar Changes

The grammar for tuple types and tuple expressions is extended to allow a trailing comma:

```
TupleType     ::= '(' ')' | '(' ',' ')' | '(' Type ',' ')' | '(' Type {',' Type} ','? ')'
TupleExpr     ::= '(' ')' | '(' ',' ')' | '(' Expr ',' ')' | '(' Expr {',' Expr} ','? ')'
TuplePattern  ::= '(' ')' | '(' ',' ')' | '(' Pattern ',' ')' | '(' Pattern {',' Pattern} ','? ')'
```

The new/changed productions are:
- `'(' ',' ')'` - empty tuple with explicit comma (equivalent to `EmptyTuple`)
- `'(' Type ',' ')'` / `'(' Expr ',' ')'` / `'(' Pattern ',' ')'` - single-element tuple
- `','?` at the end of multi-element tuples - optional trailing comma

Note: Multi-line trailing commas are already supported by SIP-27 at the scanner level. This proposal extends trailing comma support to single-line contexts for tuples, where the trailing comma serves a semantic purpose (disambiguating single-element tuples from parenthesized expressions).

#### Parsing Rules

1. **`(expr)`** - Parsed as `Parens(expr)` (parenthesized expression), unchanged from current behavior
2. **`(expr,)`** - Parsed as `Tuple(List(expr))` (single-element tuple)
3. **`(expr1, expr2, ...)`** - Parsed as `Tuple(List(expr1, expr2, ...))`, unchanged
4. **`(expr1, expr2, ...,)`** - Parsed as `Tuple(List(expr1, expr2, ...))` (trailing comma allowed)
5. **`()`** - Parsed as `Tuple(Nil)` (empty tuple), unchanged
6. **`(,)`** - Parsed as `Tuple(Nil)` (empty tuple with explicit comma)

The same rules apply to type tuples and pattern tuples.

#### Type Representation

Single-element tuples with trailing comma are represented as:
- Type: `elem *: EmptyTuple` (or equivalently `Tuple1[elem]`)
- Value: The runtime `Tuple1` class

```scala
val t: (Int,) = (42,)
// t has type Int *: EmptyTuple
// t has runtime class Tuple1

t match
  case (x,) => x  // x: Int = 42
```

### Compatibility

#### Source Compatibility

This change is fully source compatible:
- All existing code continues to work unchanged
- `(A)` still means parenthesized expression `A`
- New syntax `(A,)` is currently a syntax error, so no existing code uses it

#### Binary Compatibility

Fully binary compatible:
- Single-element tuples are represented as `Tuple1` at runtime, same as `Tuple1(a)`
- No changes to class file format or method signatures

#### TASTy Compatibility

Fully TASTy compatible:
- Tuples are represented the same way regardless of source syntax
- The trailing comma is a parsing-only distinction, not preserved in TASTy

### Feature Interactions

#### Interaction with Trailing Comma Tolerance (SIP-27)

Scala supports trailing commas in multi-line contexts via SIP-27, where a trailing comma followed by a newline and closing delimiter is silently ignored. This proposal requires special handling to ensure trailing commas in tuple contexts are preserved rather than ignored.

Without this handling, the following code would be ambiguous:

```scala
val x = (1,
)
```

Should this be a single-element tuple `(1,)` or a parenthesized expression `(1)` with an ignored trailing comma? This proposal ensures it is consistently parsed as a single-element tuple, regardless of whitespace.

Similarly, for multi-element tuples:

```scala
val y = (1, 2,
)
```

The trailing comma is preserved and parsed as part of the tuple syntax.

The implementation requires slightly more complex interaction between the scanner and parser to preserve trailing commas in tuple contexts while maintaining existing behavior elsewhere.

#### Interaction with Pattern Matching

Pattern matching with trailing comma matches single-element tuples:

```scala
(42,) match
  case (x,) => x      // Matches as single-element tuple, x: Int = 42
  case (x) => x       // Also matches (any value), but x: (Int,) = (42,), not unwrapped
```

#### Interaction with Named Tuples

Named tuples already support single-element syntax via `(name: Type)` because the colon disambiguates from parenthesized expressions. The trailing comma syntax provides an alternative:

```scala
// Existing named tuple syntax (already works)
type Person = (name: String)
val p: Person = (name = "Alice")

// With trailing comma (also valid with this proposal)
type Person2 = (name: String,)
val p2: Person2 = (name = "Alice",)

// Multi-element with trailing comma
type Record = (id: Int, name: String,)
```

### Other Concerns

#### IDE Support

IDEs will need to update syntax highlighting and code completion to recognize the trailing comma tuple syntax. This is a minor change as trailing commas are already supported in other contexts.

#### Error Messages

Parser error messages should be updated to suggest the trailing comma syntax when users attempt invalid single-element tuple syntax.

### Open Questions

1. **Should trailing comma in multi-element tuples be restricted to multi-line format?**
   - Option A (permissive): Allow `(1, 2,)` on single line - simpler, matches Python/Rust
   - Option B (restrictive): Only allow trailing comma when meaningful (single-element) or multi-line (SIP-27 style)
   - Pro for B: More consistent with SIP-27, prevents pointless trailing commas
   - Pro for A: Simpler mental model, easier for code generation/macros
   - Note: If Option A is chosen, a separate proposal could extend single-line trailing commas to other contexts (parameter lists, arguments, etc.) for consistency - but that's outside the scope of this SIP

2. **Should trailing comma be allowed in single-element named tuples?**
   - Named tuples already support single-element syntax: `(name: String)` is unambiguous
   - Allowing `(name: String,)` adds redundant syntax but maintains consistency with unnamed tuples
   - Pro: Uniform treatment of all tuple forms
   - Con: Unnecessary since named tuples don't need disambiguation

## Alternatives

### Alternative 1: Different Delimiter

Use a different delimiter for tuples, like `{|` and `|}`:

```scala
type T = {| Int |}      // Single-element tuple
val v = {| 42 |}
```

**Pros:**
- No ambiguity with parentheses

**Cons:**
- Major departure from established syntax
- Inconsistent with other languages
- Harder to type

### Alternative 2: Keep Current Syntax

Continue requiring `*: EmptyTuple` or `Tuple1[A]` for single-element tuples.

**Pros:**
- No language change needed
- Explicit about tuple nature

**Cons:**
- Verbose and inconvenient
- Inconsistent with multi-element tuple syntax
- Poor ergonomics for tuple-heavy code

## Related Work

### Prior Discussions

- [Syntax for type tuple with one element](https://contributors.scala-lang.org/t/syntax-for-type-tuple-with-one-element/6974/50) - Scala Contributors discussion
- [SIP-27 - Trailing Commas](https://docs.scala-lang.org/sips/trailing-commas.html)

### Similar Features in Other Languages

- **Python**: Uses `(a,)` for single-element tuples - this proposal follows Python's approach
- **Rust**: Uses `(a,)` for single-element tuples
- **Haskell**: Does not have single-element tuples (uses newtype wrappers instead)
- **TypeScript**: Uses `[T]` for single-element tuple types (e.g., `[number]`); note that `[a]` in value context is array destructuring

### Proof of Concept

Two proof-of-concept implementations have been developed for the Scala 3 compiler, available in the [rssh/dotty fork](https://github.com/rssh/dotty):

| Variant | Branch | Description |
|---------|--------|-------------|
| **Full** | [`feat/one-element-tuple-syntax`](https://github.com/rssh/dotty/tree/feat/one-element-tuple-syntax) | Allows trailing comma for all tuple sizes: `(a,)`, `(a, b,)`, `(,)` |
| **Minimal** | [`feat/one-element-tuple-syntax-min`](https://github.com/rssh/dotty/tree/feat/one-element-tuple-syntax-min) | Only allows trailing comma where semantically meaningful: `(a,)`, `(,)` (multi-element `(a, b,)` is rejected) |

- **Related PR (full variant)**: https://github.com/scala/scala3/pull/24591

The implementations pass all existing tuple-related tests and include new tests for:
- Single-element tuples: `(a,)`, `(Int,)`
- Multi-element tuples with trailing comma: `(a, b,)`, `(Int, String,)` (full variant only)
- Empty tuples: `(,)`
- Multiline variants: `(a,<newline>)`, `(a, b,<newline>)`, `(,<newline>)`
