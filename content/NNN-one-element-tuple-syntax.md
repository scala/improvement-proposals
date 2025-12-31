---
layout: sip
title: Trailing Comma Syntax for Tuple Types and Values
stage: pre-sip
status: withdrawn
---

# SIP-NN: Trailing Comma Syntax for Tuple Types and Values

**By: [Ruslan Shevchenko]**

**Status: WITHDRAWN** - This proposal is not backward compatible due to conflict with SIP-27 (Trailing Comma Tolerance). It may be reconsidered at the start of the Scala 4 development cycle when breaking changes can be evaluated.

For a backward-compatible solution to uniform tuple construction, see **SIP-NNN: Uniform Tuple Construction and Extraction** which provides `Tuple(...)` syntax via library-level macros.

## History

| Date          | Version       |
|---------------|---------------|
| Dec 31st 2025 | **Withdrawn**: not backward compatible due to SIP-27 conflict |
| Dec 31st 2025 | Expanded SIP-27 conflict analysis (backward incompatibility); added Alternative 3 (library-level solution) |
| Nov 30th 2025 | Expanded grammar specification and SIP-27 interaction |
| Nov 29th 2025 | Initial Draft |

## Reason for Withdrawal

The `(1,)` syntax fundamentally conflicts with SIP-27 (Trailing Comma Tolerance). Currently, this code is valid:

```scala
val x = (1,
        )       // Valid today: trailing comma is ignored, this equals 1
```

Implementing `(1,)` as single-element tuple syntax requires one of two choices:

1. **Keep SIP-27 behavior for multi-line**: `(1,)` and `(1,\n)` would have different meanings - whitespace would change semantics.

2. **Always treat trailing comma as tuple**: Existing code `(expr,\n)` would silently change from returning `expr` to returning `Tuple1(expr)` - a breaking change.

Neither option is acceptable for Scala 3.x. This proposal should be reconsidered for Scala 4 when breaking changes can be properly evaluated.

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

Notably, the REPL currently displays single-element tuples using the proposed syntax:

```scala
scala> val t = Tuple1(3)
val t: Tuple1[Int] = (3,)
```

This creates an inconsistency: the REPL prints `(3,)` but this syntax is currently invalid in source code. Note: Alternative 3 (library-level solution) also proposes changing `Tuple1.toString` to `Tuple1(3)` to eliminate this confusing display of impossible syntax.

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

**The `(1,)` syntax fundamentally conflicts with SIP-27 (Trailing Comma Tolerance).**

SIP-27 allows trailing commas in multi-line contexts, where a trailing comma followed by a newline and closing delimiter is silently ignored:

```scala
val list = List(
  1,
  2,
)  // The trailing comma after 2 is ignored
```

This applies to parenthesized expressions as well:

```scala
val x = (1,
        )       // Currently valid: trailing comma is ignored, this is just 1
```

This creates a fundamental problem for the `(1,)` proposal:

**Option A: Keep SIP-27 behavior for multi-line** - Whitespace changes semantics:
```scala
val x = (1,)    // Single-element tuple Tuple1(1)
val y = (1,
        )       // Parenthesized expression 1
```
This violates the principle that inserting whitespace should not change program meaning.

**Option B: Always treat trailing comma as tuple** - Backward incompatible:
```scala
val y = (1,
        )       // Currently: 1, would become: Tuple1(1)
```
Existing code that relies on SIP-27 trailing comma tolerance in parenthesized expressions would silently change meaning.

The same issue affects type position: `(Int,)` vs `(Int,\n)`.

**Possible resolutions:**
1. **Option A: Accept whitespace sensitivity** - Poor language design, confusing for users
2. **Option B: Break backward compatibility** - Existing `(expr,\n)` code changes meaning silently
3. **Disable SIP-27 for parentheses only** - Complex, inconsistent with other delimiters
4. **Use library-level solution instead** - See Alternative 3, avoids the problem entirely

The implementation in proof-of-concept branches attempts to handle this by modifying scanner/parser interaction, but the fundamental tension remains a significant concern.

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

### Alternative 3: Library-Level Solution with `Tuple.apply` Macro

Instead of syntax changes, provide uniform tuple construction via a transparent inline macro in the `Tuple` companion object:

```scala
object Tuple:
  // Keep existing methods for binary compatibility
  def apply(): EmptyTuple = EmptyTuple
  def apply[T](x: T): T *: EmptyTuple = Tuple1(x)

  // New: Uniform apply for any arity with type preservation
  transparent inline def apply(inline args: Any*): Tuple = ${ applyImpl('args) }

  // Typed pattern matching - returns the tuple itself for Product-based extraction
  def unapply[T <: Tuple](t: T): T = t
  def unapply(t: EmptyTuple): true = true
```

This enables:

```scala
// Value construction (uniform for all arities)
Tuple()              // EmptyTuple
Tuple(1)             // Tuple1[Int]
Tuple(1, "a")        // (Int, String)
Tuple(1, "a", 2.0)   // (Int, String, Double)

// Pattern matching
t match {
  case Tuple() => ...           // empty tuple
  case Tuple(a) => ...          // a: Int (for Tuple1[Int])
  case Tuple(a, b) => ...       // a: Int, b: String
  case Tuple(a, b, c) => ...    // typed extraction
}
```

Key implementation techniques:
- `transparent inline` ensures return type is refined at call site
- `inline args` makes arguments available as AST at compile time
- Macro extracts `term.tpe.widen` to preserve original types (avoids `Any` erasure)
- For arities 1-22: builds `TupleN` with correct type parameters
- For arities > 22: uses `scala.runtime.Tuples.fromArray`
- Pattern matching works via Product's `_1`, `_2`, etc. methods

**Pros:**
- No SIP-27 conflict (no syntax changes)
- 100% backward compatible (purely additive)
- No compiler modifications required
- Works uniformly for all arities (0 to unbounded)
- Provides both construction and typed extraction

**Cons:**
- Does not address type syntax (`Tuple1[Int]` is still verbose vs desired `(Int,)`)
- More verbose than hypothetical `(1,)` syntax
- Requires explicit `Tuple(...)` wrapper

**Comparison:**

| Aspect | `(1,)` Syntax | `Tuple(1)` Macro |
|--------|---------------|------------------|
| SIP-27 conflict | Yes | No |
| Backward compatible | Requires careful handling | 100% additive |
| Compiler changes | Required | None |
| Type syntax | `(Int,)` | `Tuple1[Int]` (unchanged) |
| Value syntax | `(1,)` | `Tuple(1)` |
| Pattern matching | `case (a,)` | `case Tuple(a)` |
| Works for all arities | Yes | Yes |

A proof-of-concept implementation is available in the brainstorm directory and as a dotty branch:
- Local: `brainstorom/one-element-tuple/test1/` (run with `scala-cli`)
- Dotty branch: [`uniform-tuple-apply-unapply`](https://github.com/rssh/dotty/tree/uniform-tuple-apply-unapply)

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
