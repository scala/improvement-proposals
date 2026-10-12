---
layout: sip
permalink: /sips/:number.html
redirect_from:
  - /sips/:number
  - /sips/:title.html
stage: design
status: under-review
number: 80
presip-thread: https://contributors.scala-lang.org/t/relative-scoping-for-hierarchical-adt-arguments/4136
title: Companion Scope Inference
---

**By: Oron Port**

## History

| Date           | Version                                       |
|----------------|-----------------------------------------------|
| May 5th 2026   | First draft: leading `.` sigil (`.X`)         |
| May 7th 2026   | Second draft: leading `#` sigil (`#X`)        |
| May 12th 2026  | Third draft: sigil-free companion scope inference |
| May 21th 2026  | Final draft: companion scope inference |

## Summary

We propose **companion scope inference**: when an identifier `X` cannot be resolved through normal name lookup, *and* the surrounding position has a known expected type `T`, the compiler searches `T`'s companion module for a term-level member named `X`. If such a member is found, the use site is treated as if the user had written `T.X` explicitly.

```scala
final case class Shape(geometry: Shape.Geometry, color: Shape.Color)
object Shape:
  sealed trait Geometry
  object Geometry:
    case object Triangle, Rectangle, Circle extends Geometry
  sealed trait Color
  object Color:
    case object Red, Green, Blue extends Color

val s = Shape(Circle, Red) // Geometry.Circle, Color.Red
                           // inferred via companion lookup

shape match
  // Geometry.Triangle / Rectangle
  case Shape(Triangle | Rectangle, _) => true  
  case _                              => false
```

The mechanism is uniform: a bare identifier whose ordinary resolution fails is retried against the principal class of the expected type's companion. The proposal is *not* limited to enums; it covers any companion-object member (enum case, sealed-trait case object, `val`, `object`, factory `def`, etc.) and any expected type with a companion.

This concept is somewhat similar to implicit scope priorities in Scala that favor external scope imports and have companion implicit scope as a fallback.

## Motivation

### Scala example

When an algebraic data type is organised hierarchically to keep the top-level namespace clean and group related cases, every construction and pattern match becomes verbose. The running example is:

```scala
final case class Shape(geometry: Shape.Geometry, color: Shape.Color)
object Shape:
  sealed trait Geometry
  object Geometry:
    case object Triangle, Rectangle, Circle extends Geometry
  sealed trait Color
  object Color:
    case object Red, Green, Blue extends Color
```

Today, building or matching a `Shape` requires either:

1. Use fully qualified paths which lead to verbosity:

```scala
val redCircle = Shape(Shape.Geometry.Circle, Shape.Color.Red)

shape match
  case Shape(Shape.Geometry.Triangle | Shape.Geometry.Rectangle, _) => true
  case _                                                            => false
```

2. Use wildcard imports, which lead to namespace pollution that we tried to avoid by using hierarchies to begin with:
```scala
import Shape.Geometry.*
import Shape.Color.*
val redCircle = Shape(Circle, Red)

shape match
  case Shape(Triangle | Rectangle, _) => true
  case _                              => false
```

3. Use shorthand imports, which do not scale with more complex structures:
```scala
import Shape.Geometry as g
import Shape.Color as c
val redCircle = Shape(g.Circle, c.Red)

shape match
  case Shape(g.Triangle | g.Rectangle, _) => true
  case _                                  => false
```

The same pain appears beyond ADTs whenever a companion holds factory members (e.g., in DSL builders) or when opaque types wrap existing enums. In every such case the call site already knows the expected type; what it lacks is a way to *reuse* that knowledge to look up the member by its short name.

### Empirical analysis: how often would companion scope inference fire?

#### Methodology

A [TASTy-based scanner](https://github.com/soronpo/scala3/tree/claude/scala-repo-scanner-script-FXi3I/sip80-scanner) walks compiled `.tasty` files using `scala.tasty.inspector.TastyInspector`. For each `Select(qual, name)` and `Ident(name)` tree it asks the same question: *is this position target-typed by a context whose principal class (after the target-type reduction specified below) has a companion that owns this symbol?* Using compiled TASTy rather than source regexes catches the patterns a syntactic scan misses (bare identifiers reached through wildcard imports, ascriptions, context-typed `if`/`match` branches, nested extractors) while rejecting false positives where the surface `T.X` shape does not actually match the parameter type.

The target-typed positions walked are:

- `typed_decl`: `val`/`var`/`def` with an explicit declared type, on the RHS;
- `default_arg`: parameter defaults;
- `call_arg`: method and constructor arguments;
- `ascription`: `(expr : T)`;
- `if_branch`: branches typed by the enclosing context;
- `match_case`: `case T.X` where the scrutinee type is known;
- `nested_pattern`: `Some(T.X)`, tuples, extractor signatures.

For each incident the scanner records the source location, whether it is *prefix-based* (the user already wrote `T.X` explicitly) or *import-based* (a bare `X` made visible by an open wildcard import), and `chars_saved`: `text.length - (memberName.length + 1)` for prefix incidents, and `0` for import incidents (the use site is already short, so the *file* shrinks instead, once the now-unnecessary wildcard import is removed). The scanner ships with a 39-incident self-test fixture pinning counts per category and file.

#### Results

**Scala 3 itself (3.8.3)**, a ~100 kLOC codebase with extensive ADT pattern matching, `List.empty`/`Set.empty` factory calls, and flag enums:

| Module                                | Incidents | Chars saved | Import-based |
|----------------------------------------|----------:|------------:|-------------:|
| `scala-library:3.8.3`                  |       290 |       1,688 |           98 |
| `scala3-compiler_3:3.8.3`              |     1,010 |      12,295 |          343 |
| `scala3-presentation-compiler_3:3.8.3` |       177 |       1,817 |            2 |
| `scaladoc_3:3.8.3`                     |        89 |         630 |            1 |
| `scala3-staging_3:3.8.3`               |         2 |           4 |            1 |
| `tasty-core_3:3.8.3`                   |         1 |          15 |            0 |
| **Scala 3 total**                      | **1,569** |  **16,449** |      **445** |

**DFiantHDL**, a hardware-description language whose intermediate representation is a large, deeply nested ADT, i.e. exactly this proposal's design target:

| Module                    | Version | Incidents | Chars saved | Import-based |
|---------------------------|---------|----------:|------------:|-------------:|
| `dfhdl-core_3`            | 0.17.0  |       223 |       1,823 |           44 |
| `dfhdl-internals_3`       | 0.17.0  |        17 |         126 |            0 |
| `dfhdl-compiler-ir_3`     | 0.17.0  |       296 |       2,295 |           39 |
| `dfhdl-compiler-stages_3` | 0.17.0  |       216 |       2,138 |            2 |
| `dfhdl-platforms_3`       | 0.17.0  |        55 |         357 |           13 |
| `dfhdl-devices_3`         | 0.12.0  |         5 |          79 |            0 |
| **DFiantHDL total**       |         |   **812** |   **6,818** |       **98** |

**Curated Scala 3 community build**, 49 active projects across 62 modules, in three size categories:

| Category            | Active projects | Modules | Incidents | Chars saved | Import-based |
|---------------------|----------------:|--------:|----------:|------------:|-------------:|
| A (small)           |               4 |      11 |       335 |       2,111 |           54 |
| B (medium)          |              15 |      18 |     1,798 |       9,836 |          499 |
| C (large/framework) |              30 |      33 |     3,826 |      31,128 |        1,613 |
| **Total**           |          **49** |  **62** | **5,959** |  **43,075** |    **2,166** |

Approximately 6,000 positions across the curated community build where the proposal would fire, ~36 % of them via bare identifiers visible only because a wildcard import is currently open. That is exactly the namespace-pollution pattern this SIP lets users avoid. Top contributors include `scalaz-core` (1,483 incidents, 904 import-based, from pervasive `import Foo._`), `scalapb-runtime` (571 incidents, in generated protobuf code), `scalacheck` (353), `sconfig` (232), and `libretto-core` (219).

#### Caveats

- **`chars_saved` is a lower bound.** It counts only bytes removed at the use site; for the ~36 % import-based incidents the recorded saving is `0`, but the file still shrinks when the wildcard import line is dropped.
- **Volume vs. density.** These are absolute incident counts. No single reader meets all ~6,000 at once; each reads one code block. The relevant measure of perceived noise is density within hot files (DSL builders, ADT pattern-matchers, configuration enums): the compiler's 1,010 incidents are spread thin across hundreds of files, whereas DFiantHDL concentrates 296 of them into a single module.
- **TASTy-version coverage.** The scanner auto-detects each jar's TASTy version; transitive dependencies not resolvable on Maven Central in TASTy-walkable form are skipped or run with extended classpaths.

### Several other languages have a similar feature

Several languages like Swift and Dart (see Related Work) encountered the same problem and added a similar feature. 

## Proposed solution

### High-level overview

The rule is a single addition to name resolution:

1. **Try normal name lookup.** If the identifier resolves through any existing rule (lexical scope, imports, exports, package members, etc.), that resolution wins. Companion scope inference does not change the meaning of any program that compiles today.
2. **If normal lookup fails and the position has a known expected type `T`**, reduce `T` to a target type `T'` (strip prototypes, dealias, drop refinements, take the principal class, carve out `T | Null`; the full reduction is specified under *Resolution rule* below). Look up the identifier as a *term-level* member of `T'`'s companion.
3. **If found**, use the member as if the user had written `T'.X` explicitly. The resolved expression is then re-typed by the language's normal `Select` machinery, including implicit conversions.
4. **If still not found**, emit a "not found" error, possibly enriched with the companions searched (see *Diagnostics*).

The mental model is uniform: *"the bare `X` of whatever type is expected here, if `X` is not otherwise in scope."*

The feature applies to:

- **Enum cases**: `val c: Color = Red`.
- **Plain companion-object members** (`val`s, `object`s, factory `def`s, anything term-level): `val o: Option[Int] = empty`.
- **Factory methods with arguments**: `render(color("red"))`.
- **Pattern matching** at every position where the scrutinee type is known: `case Red => ...`.
- **Transparent type aliases, `import`s, and `export`s**: resolution dealiases before looking up the companion, as in `val c: MyColor = Red` (where `type MyColor = Color`).
- **Opaque type aliases** (from outside the defining module): `val l: Level = Info`.
- **`using` clauses**: `f(using Red)`.

It does *not* apply to:

- Positions where the expected type is unknown (a fresh, unconstrained type variable, or a bare expression statement).
- Expected types whose principal class component has no companion module: general union types `A | B` (where neither side is `Null`), function types `A => B`, or bare traits without companions. The form `T | Null` is the one supported union: it reduces to `T`.
- Type-argument positions.
- Anonymous given instances on the companion (these remain reachable through normal given resolution).

### Specification

#### Resolution rule

Given an identifier `X` and a use site whose expected type is `T`, the compiler resolves `X` in this order:

1. **Normal resolution.** Apply the existing Scala name-resolution rules (lexical scope, imports, exports, package members, inherited members, etc.). If a unique result is found, use it. If an *ambiguity* is found, raise the existing ambiguity error; companion scope inference does not bypass ambiguity diagnostics.
2. **Target-type reduction** (only if step 1 found no candidate). Compute `T'` from `T`:
   1. Strip prototype layers.
   2. Dealias transparent type aliases.
   3. Drop dependent refinements.
   4. Take the principal class component, if `T` is a refined or intersection type.
   5. Drop `Null` arms from `T | Null` / `Null | T` unions (recursively).
   If `T'` has no companion module (for example an unconstrained abstract type, a type parameter, a bare trait without a companion, a function/SAM type, or a non-`Null` union type `A | B`), companion scope inference does not fire. The position is then a "not found" error.
3. **Companion lookup.** Look up `X` as a *term-level* member of `T'`'s companion object. For an opaque type alias, the alias's *own* companion is searched (the underlying type's companion is *not* consulted from outside the module that defines the alias). Anonymous givens are not eligible candidates; named givens are.
4. **Re-typing.** The desugared form `T'.X` is then re-typed by the language's regular `Select` machinery, so:
   - implicit conversions are inserted to bridge the candidate's type to the surrounding expected type;
   - if `X` is overloaded in the companion, normal overload resolution applies at the use site after the desugaring.

   The rule does not pre-filter by conformance; the resolved member's type may differ from `T` and a `given Conversion` (or other conversion) is expected to bridge the gap.

Only the static expected type's companion is searched, not its supertypes' companions. A user who needs a member from a supertype writes the chained form (see the *Animal* worked example), or qualifies explicitly.

##### Nullable expected types

The `T | Null` carve-out is the only union form for which companion scope inference fires. `T | Null` is the canonical representation of nullable references under explicit-nulls, and there is no ambiguity about which side carries the companion: `Null` has none.

```scala
val c1: Color | Null   = Red // OK — reduces to Color
val c2: Null | Color   = Red // OK — order does not matter
val u:  Color | Int    = Red // ERROR — no principal class
```

#### Pattern matching

In a pattern position, `X` is resolved the same way: try normal pattern resolution first (constants in scope, stable identifiers, lower-case binders, etc.); if it fails *and* the pattern's expected type is statically known, look up `X` as a member of that type's companion.

```scala
val c: Color = ???
c match
  case Red | Blue => "primary"
  case Green      => "secondary"
```

The lower-case-binder vs. constant-reference rule of current Scala is preserved. `case red` still binds a fresh variable; only capitalised identifiers (or back-ticked ones) are subject to companion scope inference, because only those reference rather than bind.

#### Application form: `X(args)`

When the inferred member is callable, the apply-form follows naturally. `color("red")` in a `Frag`-typed position resolves `color` against `Frag`'s companion and applies the resulting `def`:

```scala
trait Frag
object Frag:
  def color(name: String): Frag = ???
  def text(s: String): Frag     = ???

def render(frag: Frag): String = ???

render(color("red"))    // companion scope inference: color → Frag.color
render(text("hi"))      // companion scope inference: text → Frag.text
```

The companion's `apply` method is not given special status. `Color(20, 5, 100)` already works under existing Scala rules because `Color` is a type in scope, so its companion's `apply` is reached by the standard apply-method dispatch. Inside an enclosing scope where bare `apply` resolves to *some other* `apply` (for example a containing `object Foo`), companion scope inference does not silently rescue the call site; the user qualifies the call as `Color.apply(...)` or `Color(...)` explicitly. This is the same disambiguation step the language already requires when two methods named `apply` are visible.

#### Chaining: `Mammal.Dog`

Companion scope inference applies to the *leftmost* identifier in a chained selection. Subsequent segments are ordinary path selection on the resulting value:

```scala
sealed trait Animal
object Animal:
  sealed trait Mammal extends Animal
  sealed trait Bird   extends Animal
  object Mammal:
    case object Dog, Cat extends Mammal
  object Bird:
    case object Parrot, Eagle extends Bird

def describe(a: Animal): String = ???

describe(Mammal.Dog)
// `Mammal` is not in scope → fallback finds Animal.Mammal (member of
// Animal's companion). `.Dog` is then plain selection on Animal.Mammal.
// Final: Animal.Mammal.Dog.

val a: Animal = ???
a match
  case Mammal.Dog => "woof"
  case Bird.Eagle => "screech"
  case _          => "other"
```

If the leftmost identifier *is* in scope as something else (a local `Mammal`, an unrelated import), normal resolution wins and companion scope inference does not fire. The can user can qualify explicitly with `Animal.Mammal.Dog`. See *Compatibility → Silent shadowing*.

#### `using` clauses

For consistency with regular argument clauses, companion scope inference is allowed inside `using` argument clauses:

```scala
def f(using c: Color): Unit = ???
f(using Red)  // Red resolves against the using parameter's
              // expected type Color.
```

#### Overload resolution

After overload arity narrowing, which may leave multiple candidates when default arguments are involved, if all remaining candidates have the **same parameter type** at the position where the unresolved identifier sits, companion scope inference fires against that shared type and normal overload selection disambiguates using the other arguments. If the candidates have **different** parameter types at that position, the use site is a "not found" or ambiguity error; the user disambiguates with the qualified name, a type ascription, or a non-overloaded wrapper.

This mirrors the same flavour of rule that already governs target-typed `_` in Scala.

```scala
def bar(a: Animal): Unit                           = ???
def bar(a: Animal, b: Animal = Animal.Dog): Unit   = ???

bar(Cat)          // OK — both candidates have parameter 0 type Animal;
                  //      Cat resolves via Animal's companion to Animal.Cat.

def foo(a: Animal): Unit = ???
def foo(a: Color):  Unit = ???

foo(Red)          // ERROR: candidates differ at parameter 0;
                  //        companion scope inference cannot decide
                  //        which expected type drives lookup.
foo((Red: Color)) // OK — ascription pins expected type.

def fc(c: Color): Unit = foo(c)
fc(Red)           // OK — non-overloaded wrapper.
```

#### Varargs

```scala
def palette(colors: Color*): Unit = ???
palette(Red, Green)               // each position has expected
                                  // type Color.

def labelled(name: String, colors: Color*): Unit = ???
labelled("primaries", Red, Green) // first argument binds the
                                  // first parameter; varargs are
                                  // typed as Color.
```

In pattern position, varargs extractors carry the element type:

```scala
val cs: List[Color] = ???
cs match
  case List(Red, _*)         => "starts with red"
  case List(Red, Green, _*)  => "red then green"
  case _                     => "other"
```

#### Polymorphic inference

Companion scope inference **does not** contribute back to type-parameter inference, but it does *consume* a type parameter that has already been fixed by other means:

```scala
Seq[Color](Red, Green)                       // OK — explicit type argument.
val cs: Seq[Color]    = Seq(Red, Green)      // OK — outer expected type pins A.
val opt: Option[Color] = Some(Red)           // OK — Some.apply[A] picks A.
val e:   Either[String, Color] = Right(Red)  // OK — same.

Seq(Red, Green)                              // ERROR — Seq.apply[A](xs: A*) has
                                             //         no other source of A.
```

#### Aliases, imports, and exports

Dealiasing fires before companion lookup, so transparent type aliases, `import`-introduced type names, and `export`-introduced type names all participate:

```scala
object Lib:
  sealed trait Color
  object Color:
    case object Red, Blue extends Color

object AliasUser:
  type MyColor = Lib.Color
  def foo(c: MyColor): Unit = ()

  val c1: MyColor = Red // dealias MyColor → Lib.Color → Lib.Color.Red
  foo(Blue)
  foo(c = Blue)         // named argument; expected type is MyColor
```

For *opaque* type aliases the alias's own companion is searched; the underlying type's companion is not consulted from outside the module that defines the alias.

#### Implicit-conversion bridging

The resolution rule does not require the candidate's declared result type to equal the expected type; existing implicit-conversion machinery bridges the gap. The motivating example with opaque types:

```scala
enum LogLevel:
  case INFO, WARN, ERROR

opaque type ParserLogLevel = LogLevel
object ParserLogLevel:
  export LogLevel.{INFO, WARN, ERROR}
  given Conversion[LogLevel, ParserLogLevel] = identity

def parse(level: ParserLogLevel): Unit = ???

parse(INFO)
// `INFO` is not in scope → fallback finds ParserLogLevel.INFO
// (declared type LogLevel) → given Conversion bridges to ParserLogLevel.
```

#### Equality and inequality

`==` and `!=` are defined on `Any`, so the right operand's expected type is `Any`. The companion of `Any` has no member named `Red`, `Blue`, etc., so companion scope inference does *not* help with equality comparisons. Users continue to write the fully qualified form for equality, or to use a typed comparison method:

```scala
c == Red               // ERROR — expected type of RHS is Any.
c == Color.Red         // OK — the standard form.

extension (c: Color) infix def matches(other: Color): Boolean = c == other
c matches Red          // OK — RHS expected type is Color.
```

#### Diagnostics

Diagnostic quality is the central correctness concern of this proposal. The compiler **must** produce error messages that distinguish three failure modes:

1. **Member not in the expected type's companion.** When normal resolution fails and companion lookup also fails, the error names the companion that was searched and lists the closest available members:
   ```
   Not found: Triagle
     Searched expected type Shape.Geometry's companion.
     Did you mean Triangle?
   ```
2. **Member exists but the expected type's companion is the wrong place.** When the user's intent is plausibly companion-scoped but the chosen expected type does not own the member (e.g. mixing up argument order):
   ```
   Not found: Red in scope or in companion of expected type Shape.Geometry.
     Note: Red is a member of Shape.Color's companion.
     Did you intend a different parameter position?
   ```
3. **Silent shadowing: a local of the same name resolves to a wrong type.** When normal resolution succeeds but the result conforms only by accident (or fails to conform), and the *would-have-been* companion candidate would conform, the error message points at both:
   ```
   Type mismatch.
     Expected: Shape.Geometry
     Found:    Triangle (of type Triangle in scope at line N)
     Note: Shape.Geometry's companion also has a member named Triangle;
           if you meant that, qualify it as Shape.Geometry.Triangle.
   ```

The expected-type anchor is what makes diagnostics tractable: the compiler always knows the type at the position, even when the name resolves elsewhere, so it can always evaluate "would the fallback have produced a conforming candidate?" and surface that hint.

These diagnostic requirements are *normative*: an implementation of this SIP that does not provide hints (1)–(3) is considered incomplete.

### Compatibility

#### Source compatibility

The proposal is **monotonic with respect to existing code**:

- A program that currently compiles compiles unchanged. Companion scope inference fires only when normal resolution fails, so every previously-resolved identifier resolves the same way.
- A program that currently errors with "not found" may now compile, if the expected type's companion has a member of the unresolved name. This direction is the intended behaviour of the SIP.

A consequence: **adding a member to a companion can make previously-erroring code compile** at a downstream call site. This is unusual but not unprecedented (adding a member to a wildcard-imported scope has a similar effect today). It cannot make a previously-passing program fail to compile.

#### Binary and TASTy compatibility

The feature is a pure resolution-pass extension. After resolution, every site is a `Select` on a fully qualified path; emitted bytecode and TASTy are identical to writing the qualified form by hand. No new AST nodes; no new TASTy nodes.

### Feature interactions

- **Implicit / given resolution.** Companion scope inference runs *before* implicit search and applies only to named members. Anonymous givens remain reachable through normal given resolution.
- **Named arguments.** `f(color = Red)` works because the named argument fixes the expected type to that parameter's type, and companion scope inference runs against that type.
- **Default arguments.** Standard default-argument resolution is unchanged; the position's expected type is the parameter's declared type, against which inference fires.
- **Polymorphic methods and type-parameter inference.** Companion scope inference consumes an established expected type but does not contribute back to type inference. If a type parameter is otherwise unconstrained, the user supplies an explicit type argument.
- **Opaque types.** Explicitly supported.
- **Transparent type aliases, `import`, `export`.** Resolution dealiases before companion lookup.
- **Union types.** Only `T | Null` reduces to `T`; other unions are hard errors.
- **SAM and function types.** `Function1`'s companion has no useful members; the diagnostic is the standard "no member named `X`" error.

### Other concerns

- **Reference implementation.** A working implementation is available as scala/scala3 PR [#26056](https://github.com/scala/scala3/pull/26056). The implementation surface is small: a single resolution-pass extension that, on an unresolved `Ident` with a known expected type, applies the target-type reduction and retries the lookup against the resulting companion.
- **Tooling.** Completions follow the same rule: when the user types a bare identifier at a target-typed position, the presentation compiler offers candidates from both lexical scope and the expected type's companion, with the companion-sourced candidates marked. Hover / go-to-definition operates on the desugared `T.X` form.
- **Cross-platform.** Pure desugaring; no JVM, JS, or Native specifics.

## Alternatives

The three workarounds in current Scala (fully qualified names; wildcard imports; shorthand imports) are all unsatisfactory: verbose, polluting, and lacking composition inside argument-only / pattern-only positions. They are shown side by side in *Motivation* above.

### Why a sigil-free design?

Although other languages that solve this problem use a leading sigil (dot `.`) to explicitly reference the companion objects, it was decided to push for a sigil-free design companion scope in Scala. This aligns well with Scala's implicit scope semantics and clean syntax that does not require writing `this.term` to reference a class value.

The trade-off this proposal makes:

- **Wins**
  - Zero new syntax; no sigil to teach, parse, or document.
  - Single style of use across all libraries; there is no "qualified form" vs "shorthand form" split that style guides have to legislate.
  - No grammar change: no parser work, no lexer-level trigger, no interaction with infix, chain-continuation, or any other existing rule.
  - Libraries using older Scala 2.12 cross-compile targets participate immediately; nothing about the call site changes.

- **Costs (acknowledged)**
  - The reader cannot tell at a glance whether a bare `Red` was resolved in lexical scope or via the companion. Under the `#X` draft the leading sigil provides that anchor; this draft does not.
  - *Silent shadowing* is possible: when a bare `Triangle` happens to resolve to a local of an unrelated type, the user receives a type-mismatch error rather than the "look in the companion" hint they would have received under a sigilled form.
  - Adding a member to a companion can make previously-erroring code compile (it cannot, however, change the meaning of code that already compiled; see *Compatibility*).

The proposal's task is to make those costs as small as practical via the diagnostic-quality requirements set out in *Specification → Diagnostics*.

### `#X` companion shorthand (the sibling draft of SIP-80)

Same semantics with an explicit leading-`#` sigil at the use site.

| Dimension | `#X` draft | Companion-inference draft |
|-----------|------------|---------------------------|
| New syntax | Yes (`#X`, optional `#(args)`) | No |
| Reader anchor | Explicit ("this is companion-scoped") | None (relies on context) |
| Silent shadowing | Impossible — sigil forces companion lookup | Possible — mitigated by diagnostics |
| Diagnostic specificity | Sigil tells compiler "look only in companion"; errors are direct | Compiler must infer intent; errors need expected-type-aware hints |
| Grammar impact | One new production each in expression and pattern position | None |
| Use-site uniformity | Two styles coexist (`Foo.X` vs `#X`) | One style |
| Apply shorthand | `#(args)` for `T.apply(args)` (optional extension) | Not available; user writes `T(args)` or `T.apply(args)` |
| Chaining | `#Mammal.Dog` is explicit | `Mammal.Dog` works via fallback; only the leftmost segment uses inference |

Neither draft dominates the other on every axis. The `#X` draft trades verbosity savings against new syntax; companion scope inference trades visual-anchor clarity against zero syntax.

### `.X` companion shorthand (the original draft of SIP-80)

The leading-dot form has a chain-continuation collision with Scala 3's existing rule (`a` ↵ `  .b`). Withdrawn in favour of `#X` during PR-134 review.

### `relative import T.*`

A proposed new kind of import that brings names from `T`'s companion into a *target-typed-only* scope: the imported names resolve only at positions whose expected type is `T`. Discussed in the PR-134 thread as a compromise between sigil-based and inference-based approaches.

```scala
relative import Color.*
val c: Color = Red          // works — Red is target-typed-scoped to Color
val x = Red                 // fails — no expected type
```

This proposal rejects `relative import` as the primary form because it re-introduces the boilerplate the SIP is meant to eliminate (one import line per file per type), and because it does not compose inside argument-only or pattern-only positions any better than ordinary imports do. A `relative import` form may be useful as an opt-in *escape hatch* (e.g. to widen the search across supertype companions), but it is not the default mechanism this SIP proposes.

### Restrict to enums only

A scheme in which companion scope inference fires only when the expected type is an `enum`, leaving non-enum companion members (factory `def`s, sealed-trait case objects in hand-rolled hierarchies, opaque-type members) unaffected.

Rejected because the motivation (DSL builders, factory methods, opaque-type idioms) extends well beyond enums. Restricting to enums would privilege one specific ADT encoding over the others Scala supports.

### Opt-in modifier on the library side

A scheme in which library authors mark companion members as eligible for the shorthand (`relative def …`, `export-like` flag, etc.).

Rejected because it fragments the ecosystem, locks out libraries cross-compiling to Scala 2.12, and shifts the cost from the library author (who pays once) to every consumer (who has to remember which members are eligible).

### Status quo (do nothing)

Verbose but safe: the ~6,000 positions in the curated community build remain fully qualified. Reviewers weighing this option should also weigh the workaround patterns it forces on library users (wildcard imports for one-shot ADT construction, `_.foo` lambda-syntax abuse for keying into companion-scoped members in DSLs, etc.); those workarounds also have costs.

## Related work

Several modern statically-typed languages have shipped a feature in the same design space: a use-site shorthand that resolves a bare member name against the expected type. The survey below is self-contained.

| Language     | Form                       | Status                         |
|--------------|----------------------------|--------------------------------|
| Swift        | `.case` / `.factory(args)` | Production (since Swift 1.0)   |
| Dart 3.x     | `.case` / `.factory(args)` | Production (Dart 3.0+)         |
| Zig          | `.case` / `.{ … }`         | Production                     |
| C# 12+       | `[ … ]`                    | Production                     |
| Kotlin       | `[ … ]`                    | Proposed (KEEP-0416)           |
| Rust         | `.case` / `.case(args)`    | Proposed (RFC #3444)           |
| OCaml        | `` `Tag x ``               | Production                     |
| Java         | (none)                     | —                              |

A specific cross-language note for this draft: every language above that has shipped a similar feature uses an explicit sigil at the use site. The sigil-free design proposed here has no direct precedent in this family. The closest analogue is **OCaml's polymorphic variants**, where the tag itself (`` `Red ``) carries no namespace at all and is unified structurally; but OCaml's mechanism is enabled by structural typing, not by a name-resolution fallback. Companion scope inference is therefore the most ambitious design point in the cross-language landscape: it asks the type checker to do work that other languages have chosen not to ask of theirs. The reward, if it works, is that Scala ends up with the lowest-ceremony form of this feature available in any modern statically-typed language.

A `#`-as-companion-placeholder design was previously floated within Scala's own pre-SIP discussions in 2024: see [aggregate-literals pre-SIP, post #98](https://contributors.scala-lang.org/t/pre-sip-a-syntax-for-aggregate-literals/6697/98). That discussion is in progress and no official SIP has been submitted as of yet. Companion scope inference takes the opposite direction, eliminating the sigil entirely rather than refining its placement.

## FAQ

**Won't this cause silent shadowing bugs?**
Yes, in the specific case where a local of unrelated type shares a name with a desired companion member. The mitigation is the normative diagnostic-quality requirement: every type-mismatch error must check whether the expected type's companion has a same-named member and, if so, surface that fact in the error message. The cost is a slightly less direct user experience than the `#X` draft's sigil form, in exchange for zero new syntax.

**Won't I have to write `Color.Red` anyway when there's a name clash?**
Yes, and that is the *only* time you have to qualify. In current Scala you qualify *every* time. Companion scope inference turns "always qualify" into "qualify when needed." The break-even point comes well below 50 % shadowing rate; in practice the shadowing rate in measured codebases is far lower (see *Empirical analysis*; most positions resolve cleanly).

**Why not also look up `given` instances?**
Anonymous givens are out of scope; this proposal is about static name lookup of *explicitly named* companion members. Named givens, like any other named term-level member, are eligible.

**What about library evolution?**
Adding a member to a companion can make previously-erroring code compile. It cannot change the meaning of code that already compiled. Removing a member is the usual API-break. The behaviour is monotonic on the source-compatibility axis.

**Does this work with opaque types?**
Yes. The alias's own companion is searched (from outside the defining module), with implicit conversions bridging where needed.

**Does this work in `using` clauses?**
Yes. `f(using Red)` resolves `Red` against the expected type of the `using` parameter.

**How does this interact with the sibling `#X` draft?**
The two drafts are competing approaches to the same underlying problem on the same SIP number; they share the target-type reduction machinery and most worked examples. They differ on whether the call site carries an explicit sigil. The committee may accept one and reject the other. If it sees the value in both, it could accept companion scope inference as the default mechanism and the `#X` form as an opt-in escape hatch when the user wants to *force* companion lookup despite a shadow. Both drafts have been written so that this combined acceptance is technically possible.

**Why not just teach people to use wildcard imports?**
That is the status quo, and it produces the namespace pollution this SIP is designed to avoid. Wildcard imports bring names into the *entire* enclosing scope; companion scope inference brings names into only the positions where the expected type matches. The two mechanisms are not interchangeable.
