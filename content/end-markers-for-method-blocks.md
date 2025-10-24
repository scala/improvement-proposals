---
layout: sip
permalink: /sips/:title.html
stage: design
status: proposal
presip-thread: https://contributors.scala-lang.org/t/end-markers-for-fewer-braces-blocks/6358
title: SIP-77 - End Markers for Method Blocks
---

**By: Oron Port (and AI agent)**

## History

| Date          | Version            |
|---------------|--------------------|
| Aug 26th 2025 | Initial Draft      |
| Oct 24th 2025 | Removed anonymous classes end markers which already exist |

## Summary

This SIP proposes to generalize end markers in Scala 3's indentation-based syntax to cover end markers for blocks that are arguments to method applications, written as `end <methodName>`.

The goal is to improve readability of long braceless blocks, align with existing end markers for definitions, and enable formatter/tooling support without forcing braces. The end marker is optional and may be inserted by tools (e.g., formatters) once a block exceeds a configured length, without changing semantics.

## Motivation

With braceless syntax, large blocks passed as by-name or lambda arguments can be harder to visually pair with their starts, particularly inside nested DSLs, tests, or direct-style code. Contributors have requested optional end markers for such blocks to mirror end markers for definitions and to improve readability and navigation, especially in long, nested structures.

Prior discussions highlight the need to:

- Allow an end marker that refers to the invoked method, e.g., `end test`, `end locally`, which communicates the intent and closes the application site rather than a definition. See the discussion and viewpoints in `End markers for fewer braces blocks` and follow-on threads [link1] and `More indentation syntax end marker variations` [link2].

Illustrative example:

```scala
def foo(arg: Int)(block: => Unit): Unit = ???

foo(42):
  // do something
  // more nested blocks...
end foo
```

These markers make it explicit which construct is being closed, aiding readability in long files and easing formatter/tooling support.

## Proposed solution

### High-level overview

Extend end markers to support method-argument blocks, so a block is the last argument of a method application written with a trailing colon, allow an optional end marker `end <methodName>` to close that block.

Examples:

```scala
test("very long test"):
  locally:
    // setup
  end locally
  // assertions...
end test
```

```scala
elements.foreach: elem =>
  // use elem
end foreach
```

```scala
//the end marker for vals/defs takes precedence.
val myLocal1 = locally:
end myLocal1
def myLocal2 = locally:
end myLocal2
def myLocal3 = 
  locally:
  end locally
end myLocal3
```

### Specification

This proposal adds an optional end markers in indentation-based syntax for method-argument blocks:
- Allowed for a block that is syntactically the last argument of a method application introduced by `:` and indentation (i.e., a "fewer braces" block).
- Syntax: `end id` where `id` must equal the simple method name at the application site (after potential infix desugaring). For chained selections, the name is the rightmost term name (e.g., `db.transaction:` → `end transaction`).
- The marker must be aligned with the indentation level that closes the block (same as other `end` markers for definitions).
- The marker closes only the immediately preceding application block; nested application blocks can each have their own end markers.
- If the block corresponds to an anonymous function argument (`f: x => ...`), the marker uses the method being applied (`end f`), not the parameter name.
- Not permitted when the block is not associated with a named method (e.g., a pure paren-less lambda literal not tied to an application name). In such cases, no end marker for the block is allowed.

Name resolution and validity

- The identifier in `end id` must match the invoked method name as it appears after syntax normalization (e.g., infix `xs map:` becomes `end map`). Overloaded resolution does not affect the spelling requirement; only the surface name matters.
- For extension methods and operator-named methods, the same spelling rules apply. For symbolic names, the symbol itself is used (e.g., `end >>`).
- If the identifier does not match, a compile-time error is reported indicating the expected method name.

Parsing and precedence

- The new markers integrate with existing `end` parsing. Where an `end <id>` could close either a definition or an application block at the same indentation, the innermost construct whose start matches the marker takes precedence. Existing valid programs must continue to parse the same.

Errors and edge cases

- Misplaced or unmatched `end id` yields an error with a suggestion of the nearest enclosing applicable construct.
- `end` alone remains invalid for closing application blocks in this proposal.

#### Grammar changes

The proposal builds on the Scala 3 syntax summary and the fewer-braces variant (ColonArgument) as specified in the Scala 3 docs and SIP-44 [dotty syntax summary] and [fewer-braces]. We extend those productions as follows (EBNF-like, deltas only):

```
SimpleExpr       ::=  ...
                   |  SimpleExpr ColonArgument [AppEndMarker]

InfixExpr        ::=  ...
                   |  InfixExpr id ColonArgument [AppEndMarker]

AppEndMarker     ::=  nl? 'end' id           -- when followed by EOL
```

Constraints and disambiguation rules:

- For `AppEndMarker`, `id` must equal the invoked method name associated with the immediately preceding `ColonArgument` block after normalization (e.g., `xs map:` ⇒ `end map`).
- `AppEndMarker` closes only that `ColonArgument` block and must appear at the indentation level where the block is closed. Nested blocks may each have their own `AppEndMarker`s.
- `AppEndMarker` is part of expression syntax and does not close definitions; existing `EndMarker` for definitions remains unchanged.

Notes:

- The above keeps `ColonArgument` identical to the fewer-braces grammar from SIP-44 and only augments the application sites with an optional `AppEndMarker`.
- The general `EndMarker` production from the Scala 3 syntax remains valid; this SIP does not introduce a bare `end` for application blocks.

#### Apply method handling

When dealing with `apply` methods, the end marker follows the explicit method name used in the call:

- **Explicit `apply` calls**: Use `end apply` when the method is called explicitly with `.apply`.

```scala
object Foo:
  def apply(block: => Unit): Unit = ()

Foo.apply:
  // do something
end apply
```

- **Implicit `apply` calls**: Use the name of the object/class instance that owns the `apply` method when it's called implicitly.

```scala
object Foo:
  def apply(block: => Unit): Unit = ()

Foo:
  // do something
end Foo
```

```scala
class Foo:
  def apply(block: => Unit): Unit = ()

val foo = new Foo

foo:
  // do something
end foo
```

This rule ensures that the end marker always corresponds to the syntactically visible method name, making the code self-documenting and consistent with the principle that end markers should match the surface syntax.

References: Scala 3 Syntax Summary ([dotty syntax summary]) and SIP-44 Fewer Braces ([fewer-braces]).

#### Misaligned End Markers
As the specification defines, the end marker must align with the beginning of the method call. Here is an example where misalignment yields an error:
```scala
def foo = bar:
  ...
end bar //error: misaligned end marker
end foo
```

#### Curried Method End Markers
Nothing in this spec prohibits the following examples from compiling successfully as well:
```scala
def foo(bar: String)(baz: String) = ???

foo("abc"):
  "xyz"
end foo
```

```scala
class CurriedFoo(bar: String):
  def apply(baz: String) = ???
  
def foo(bar: String) = CurriedFoo(bar)

foo("abc"):
  "xyz"
end foo
```

### Compatibility

- Binary and TASTy: No impact. End markers are purely syntactic sugar for braceless blocks and do not affect emitted bytecode or TASTy semantics.
- Source: Largely backward compatible. The only change is accepting additional `end <id>` tokens where previously only a dedentation closed the block. Name-checked markers that do not match will report errors as they do today for definition end markers.
- Tooling: Formatters and IDEs may optionally insert or display these markers; existing code without markers remains valid.

### Feature Interactions

Fewer braces / optional braces: Complements existing end markers for definitions by covering application blocks and anonymous classes.

## Alternatives

- Use braces for long blocks: Always available, but loses the succinctness and uniformity of braceless style.
- `end` alone: Simpler to type but less informative; conflicts with existing treatment of bare `end` as an identifier in some contexts and reduces error checking.
- `end apply` / `end block`: Generic markers are less informative than `end <methodName>` and provide weaker validation. See suggestions in the discussion [link1].
- Tooling-only solutions: Inlay hints or code lenses to show artificial end markers in editors [link2] improve readability but do not help code reviews or non-enhanced environments and cannot be enforced or formatted in source.

## Related work

- End markers for fewer-braces blocks discussion: see `End markers for fewer braces blocks` on Scala Contributors [link1].
- Follow-up variations and IDE support: `More indentation syntax end marker variations` [link2].

[link1]: https://contributors.scala-lang.org/t/end-markers-for-fewer-braces-blocks/6358
[link2]: https://contributors.scala-lang.org/t/more-identation-syntax-end-marker-variations/7113

