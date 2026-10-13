---
layout: sip
number: NN
permalink: /sips/:number.html
redirect_from:
  - /sips/:title.html
  - /sips/:number
stage: design
status: submitted
presip-thread:
  - https://contributors.scala-lang.org/t/wip-scala-with-explicit-nulls/2761
  - https://contributors.scala-lang.org/t/make-null-a-subclass-of-anyval-under-yexplicit-nulls/7406
  - https://contributors.scala-lang.org/t/proposal-fixing-null-methodofany-under-explicit-nulls/7504
  - https://contributors.scala-lang.org/t/explicit-nulls-option-like-extensions-for-working-with-nullable-t-null/7229
  - https://contributors.scala-lang.org/t/yexplicit-nulls-strictequality-and-null-checking/7190
title: SIP-NN - Explicit Nulls
---

**By: Ondřej Lhoták, Yaoyu Zhao, Martin Odersky, Harris Lau, Sébastien Doeraene, Abel Nieto**

## History

| Date                  | Version            |
|-----------------------|--------------------|
| September 23rd, 2026  | Initial Draft      |

## Summary

Scala inherits from the JVM the property that every reference type is implicitly nullable: `null`
is a legal value of `String`, of `List[Int]`, and of every other subtype of `AnyRef`. The type
system therefore cannot rule out a common failure mode of JVM programs, the
`NullPointerException`. On the theoretical side, Amin and Tate showed that this implicit nullability makes Scala's
type system unsound, since `null` supplies a value for types that are otherwise uninhabited.
Since Scala 3.0, the compiler has shipped an opt-in *explicit nulls* mode
behind the `-Yexplicit-nulls` flag, in which reference types are non-nullable and nullability is
expressed with the union type `T | Null`. The feature has been developed, tested and used for
several years, and this proposal asks that it become the default behaviour of the Scala compiler.

Concretely, the proposal makes four changes:

1. `scala.Null` becomes a subclass of `scala.AnyVal` rather than of `scala.AnyRef`.
2. `scala.Null` is no longer a *subtype* of reference types, so `val s: String = null` and
   `(s: String | Null).length` are rejected. This is the default. Existing code that has not been
   ported keeps its current meaning under the existing `scala.language.unsafeNulls` import, which
   may be applied to a whole project, a file, or any smaller scope.
3. *Flow typing* for nullability is enabled: after a check such as `if s != null then ...`, a
   reference of type `String | Null` is given type `String` in the scopes where the check is known
   to hold.
4. Types that come from Java are given *flexible types* (written `T |? Null`), a compiler-internal type
   with the bounds `T | Null <: T |? Null <: T`, which can be used both as a nullable and as a
   non-nullable type.

Together, these make Scala a null-safe language by default, while leaving existing projects a
mechanical way to keep compiling: add `-language:unsafeNulls`, then remove it scope by scope as
the code is ported.

## Motivation

### The problem

`null` is the canonical example of what Tony Hoare called his "billion-dollar mistake". In Scala
today, the type `String` claims to describe strings, but it in fact describes strings *and*
`null`. Consequently:

```scala
def upper(s: String): String = s.toUpperCase   // compiles; throws at run time on `null`
upper(null)                                    // compiles
```

Every reference type in the language is really an implicit sum of the type the programmer wrote
and `Null`, and every member selection on a reference is an implicit, unchecked assumption that
the reference is not `null`. The type system offers no way to state, and no way to check, that a
particular value is never `null`. Occurrences of `null` cause `NullPointerException`s at run time.

The issue has a theoretical dimension for a (path-)dependently typed language such as Scala.
Amin and Tate showed that implicit nullability makes the
type systems of both Java and Scala **unsound**, in a way that has nothing to do with dereferencing
a null pointer. Their Scala counterexample defines a value of a type that cannot be inhabited:

```scala
trait LowerBound[T] { type M >: T }
trait UpperBound[U] { type M <: U }

def coerce[T, U](t: T): U =
  def upcast(lb: LowerBound[T], t: T): lb.M = t
  val bounded: LowerBound[T] with UpperBound[U] = null   // rejected under this proposal
  upcast(bounded, t)

val zero: String = coerce[Integer, String](0)            // no cast, no warning
```

Without `T <: U` there is no way to *construct* a `LowerBound[T] with UpperBound[U]`, and the
soundness argument for path-dependent types relies on that. `null` supplies one anyway, which
turns a type-level guarantee into a way to coerce any type to any other without a cast.

The nullable default is the wrong way round. Most idiomatic Scala code avoids the use of `null`
and intends most expressions to be non-nullable, preferring `Option` where a value is genuinely absent. Making
nullability the default therefore forces the overwhelmingly common case to go unstated and the
rare case to be indistinguishable from it. This is borne out by tests of this proposal on the
Open Community Build: 1074 of 1928 projects already satisfy the non-null-by-default discipline and
compile unchanged under it. For the remainder, `-language:unsafeNulls` is a one-line change that
brings the failures down to 16 (see
[Evidence](#evidence-the-open-community-build-and-the-community-build)).

### Goals

- Make it possible to write Scala in which `String` means "a string", not "a string or `null`",
  and in which the compiler enforces that.
- Make the transition incremental: a per-scope opt-out, with no forced recompilation of
  dependencies. An existing project should be able to keep compiling with a single compiler
  option, and then shrink the scope of that option as it ports.
- Keep Java interoperability practical. A Scala program that calls a Java library must not be
  forced to write `.nn` on every call.
- Make the resulting types visible in TASTy so that downstream users of a null-safe library see
  its null-safety.

### Non-goals

- Full soundness with respect to `null`. Uninitialized fields, `asInstanceOf`, arrays,
  deserialization and Java code can all produce a `null` where the type says otherwise. This
  proposal reduces the number of places where `null` can appear unnoticed; it does not eliminate
  them. (See [Unsoundness](#5-unsoundness).)
- Changing the runtime representation of `null`, or the bytecode Scala emits.
- Deprecating or removing `Option`.

## Proposed solution

### High-level overview

The proposed changes are grouped into four areas.

| Change | Scope |
|---|---|
| 1. `Null` extends `AnyVal` | global |
| 2. `Null` not a subtype of reference types | global default, lexically scoped opt-out |
| 3. Flow typing for nullability | global |
| 4. Flexible types for Java-defined signatures | global |

Of the four changes listed above, changes 1, 3 and 4 must be decided once for a whole compilation
run: 1 and 4 determine what a symbol's type is, and 3 is part of how expressions are typed.
Change 2 is a property of *subtyping*, which the
compiler already implements as a scoped mode (`Mode.SafeNulls`).
It applies by default; `scala.language.unsafeNulls` turns it off again for a scope of the
programmer's choosing, so that existing code has a migration path.

Here is the whole feature in one example.

```scala
def parse(s: String): Int = s.trim.toInt      // `s` cannot be null; no check needed

val a: String = null                          // error: Found: Null, Required: String
val b: String | Null = null                   // ok

b.length                                      // error: value length is not a member of String | Null
if b != null then b.length                    // ok: flow typing gives `b` type `String` here
b.nn.length                                   // ok: checked cast, throws NPE if `b` is null

// Java interop: `java.lang.System.getProperty` has Scala type `String |? Null => String |? Null`
val p: String = System.getProperty("user.dir")        // ok
val q: String | Null = System.getProperty("user.dir") // also ok
```

### Specification

Throughout, "reference type" means a type whose class derives from `AnyRef`.

Some parts of this section are stated as changes to the language specification; those changes are
implemented in [scala/scala3#26941](https://github.com/scala/scala3/pull/26941).

---

#### 1. `scala.Null` becomes a subclass of `AnyVal`

Today, the parent class of `scala.Null` is `scala.AnyRef`. Under this
proposal, it is changed to `scala.AnyVal`.

Since `AnyVal extends Any, Matchable`, the base classes of `Null` become `Null`, `AnyVal`,
`Matchable`, `Any`. `null` remains the only value of type `Null`.

This change is global (it changes the `baseClasses` of a symbol) and independent of the subtyping
change. It is *not* what makes `Null` fail to conform to `String`: that is the rule specified in
[§2](#2-null-is-not-a-subtype-of-reference-types) below, and today's compiler already keeps the
subtyping rule `Null <: String` alive inside `unsafeNulls` scopes even though `Null`'s declared
parent is `AnyVal`.

##### 1.1 Consequences for members of `null`

`null` no longer inherits the members of `java.lang.Object`. In particular `eq`, `ne`,
`synchronized`, `wait`, `notify`, `notifyAll`, `clone` and `finalize` are no longer members of
`Null`. The members it does have are those of `Any`: `==`, `!=`, `##`, `isInstanceOf`,
`asInstanceOf`, `equals`, `hashCode`, `toString`, `getClass`.

The specification of the `null` value (spec §6.3) is updated accordingly. `null` implements the
methods of `scala.Any` as follows:

- `eq(x)` and `==(x)` return `true` iff `x` is also `null`;
- `ne(x)` and `!=(x)` return `true` iff `x` is not `null`;
- `isInstanceOf[T]` always returns `false`;
- `asInstanceOf[T]` returns the default value of `T`;
- `##` returns `0`;
- `toString` returns `"null"`;
- `getClass` returns `classOf[scala.Null]`.

A reference to any other member of `null` throws a `NullPointerException`.

The last two entries are new: today `null.toString` and `null.getClass` throw. Because `toString`
and `getClass` are members of `Any` and `Null <: Any`, they should be total on `Null`. The compiler
rewrites a selection `q.toString` where `q`'s type admits `null` to `java.util.Objects.toString(q)`,
and `q.getClass` to `scala.runtime.ScalaRunTime.anyClass(q)`. Selections on receivers statically
known to be non-null are unchanged, so there is no cost in the common case. This is implemented in
`InterceptedMethods`.

`eq` and `ne` remain available on nullable references through extension methods on
`AnyRef | Null` added to `Predef`:

```scala
extension (inline x: AnyRef | Null)
  inline infix def eq(inline y: AnyRef | Null): Boolean = ...
  inline infix def ne(inline y: AnyRef | Null): Boolean = ...
```

so `(s: String | Null) eq null` continues to work.

##### 1.2 Deprecation of `Any.equals` and `Any.hashCode`

`null.equals(x)` and `null.hashCode` cannot be made total in a useful way, and calling them is
almost always a mistake. `Any.equals` and
`Any.hashCode` are therefore deprecated in favour of `==` and `##`, which do handle `null` (and,
unlike `equals`/`hashCode`, also handle equality of boxed primitive numbers correctly). The
deprecation message is:

```
Any.equals does not handle `null` nor equality of primitive numbers; use == instead
```

Calls on a receiver whose type is a reference type (`AnyRef`, `String`, a case class, …) are not
deprecated, since `equals` and `hashCode` are inherited from `Object` there.

##### 1.3 Removing nullability: `.nn`

Alongside the `eq` and `ne` extensions of §1.1, `Predef` provides a checked cast for removing
nullability:

```scala
extension [T](x: T | Null) inline def nn: x.type & T =
  if x.asInstanceOf[Any] == null then scala.runtime.Scala3RunTime.nnFail()
  x.asInstanceOf[x.type & T]
```

It throws a `NullPointerException` if `x` is `null`.

The result type intersects the non-null `T` with a singleton `x.type` to preserve
the identity of `x`.

The compiler warns when `.nn` is unnecessary — either because the qualifier is already known to be
non-null (for instance by flow typing) or because the expected type admits `null`.

Like the `eq` and `ne` extensions, `.nn` is an ordinary library method, available whatever the
enclosing scope. Inside an `unsafeNulls` scope one does not need it, since a `T | Null` may be
used as a `T` directly there.

##### 1.4 Erasure

`Null` continues to erase to a reference type, and after erasure `null` remains a value of every
reference type, as the JVM requires. All of the rules in this section apply only before erasure;
`isNullableClass` and `isBottomType` revert to their pre-erasure-agnostic definitions in
erased phases.

The specification of the erased LUB is currently missing any mention of `Nothing` or `Null`.
It is to be corrected as follows:

```
- if one argument is `scala.Nothing`, the other argument
- if one argument is `scala.Null` and the other derives from [`Object`](https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/lang/Object.html), the other argument
```

---

#### 2. `Null` is not a subtype of reference types

This is the change that rejects programs, and it is on by default. `scala.Null` is no longer a
subtype of the reference types, so a value that may be `null` must say so in its type:

```scala
val a: String = null          // error
val b: String | Null = null   // ok
```

##### 2.1 The `unsafeNulls` escape hatch

Existing code that has not been ported needs a way to keep compiling. The language feature
`scala.language.unsafeNulls`, introduced by
[scala/scala3#9884](https://github.com/scala/scala3/pull/9884), provides it, and remains available
and unchanged. It relaxes null-related checking for the remainder of the enclosing scope, and
`-language:unsafeNulls` relaxes it for a whole compilation run.

Its scope may be an entire project, a file, a class, a method, or a single block, so a migration
can proceed at whatever granularity suits it:

```scala
// this file is checked, like all others: null checking is on by default

def main(): Unit =
  val s: String = null                 // error

def legacy() =
  import scala.language.unsafeNulls    // this block is not checked
  val s: String = null                 // ok
```

There is no import in the other direction. Once a region is `unsafeNulls`, everything nested
within it is too; checking cannot be switched back on for an inner scope. A project therefore has
a single boundary between checked and unchecked code, at whatever granularity it chooses, rather
than an alternating structure.

Inside an `unsafeNulls` scope, with `T` a reference type:

1. the members of `T` can be selected on `T | Null`;
2. a value of type `T` may be compared with `T | Null` and with `Null`;
3. extension methods and implicit conversions designed for `T_2` apply to `T_1` whenever
   `T_1 <: T_2` under the *legacy* subtyping rules (where `Null` is below every reference type),
   even if `T_1 </: T_2` under the rules of this section;
4. a value of type `T_1` may be used where `T_2` is expected under the same condition;
5. `null` may be used as `AnyRef`, so `null.eq(x)` is accepted;
6. if an `unsafeNulls` block produces a value that does not conform to the expected type of an
   enclosing checked block, the last expression is cast to the expected type, provided it conforms
   under unsafe-nulls rules. This keeps an `unsafeNulls` region usable as an expression rather than
   merely as a statement.

An `unsafeNulls` scope is *similar* to, but not identical with, legacy Scala. It relaxes
null-related *checking*, but it does not undo the global changes of §1 and §4, so code that
depends on those can still fail. Overriding a Java method `T foo()` at `T = Unit` is an example:

```scala
val impl = new A[Unit]:          // Java: abstract class A<T> { abstract T foo(); }
  override def foo() = 123       // error even under unsafeNulls: Required: Unit |? Null
```

A compiler without explicit nulls accepts this; here `foo`'s result type is the flexible type
`Unit |? Null` rather than `Unit`, and the trailing `()` is inserted only for `Unit` exactly (§4.10).
Macros that inspect the shape of a type are affected in the same way (see
[Feature interactions](#feature-interactions)).

The rest of §2 describes the typing rules that hold wherever `unsafeNulls` is *not* in effect.
Inside an `unsafeNulls` scope, all of these rules are as they are today.

##### 2.2 Subtyping

The conformance rule for `scala.Null` (spec §3, "Conformance") is amended by prefixing it with a
scope condition. It reads:

> - *within the context of an `unsafeNulls` language import*, `S = scala.Null` and:
>   - `T = q.C[T_1, ..., T_n]` with `n ≥ 0` and `C` does not derive from `scala.AnyVal` and `C` is
>     not the hidden class of an `object`, or
>   - `T = T_1 { R }` and `scala.Null <: T_1`, or
>   - `T = { β => T_1 }` and `scala.Null <: T_1`.

The clause also loses the case for term designators, even under `unsafeNulls`. That is, the following
case is removed outright (see [§2.3](#23-singleton-types)):
>   - `T = q.x` with `scala.Null <: U`, where `q.x` is a term designator and `U` is a type.

By default, therefore, `Null` conforms only to `Null`, `AnyVal`, `Matchable`,
`Any` and `AnyKind`, and to unions and intersections built from those. In particular
`Null </: String`, so:

```scala
val s: String = null    // error: Found: Null  Required: String
```

To express a value that may be `null`, use a union type:

```scala
val s: String | Null = null   // ok
```

One consequence is that `throw null` no longer compiles, since `throw e` requires
`e: Throwable` and `Null </: Throwable`:

```scala
throw null                             // error: Found: Null  Required: Throwable
def f(t: Throwable | Null) = throw t   // error: Found: (t : Throwable | Null)  Required: Throwable
```

##### 2.3 Singleton types

The rule for term designators (spec §3, "Term Designators") is simplified to disallow `null` altogether,
even under `unsafeNulls`:

> All term designators are concrete types.
> The designator denotes the singleton set containing only the value denoted by `t`, i.e., the value
> `v` for which `t eq v`.

So `p.type` for a `p: String` denotes exactly `{v}`, never `{v, null}`. This makes `p.type` a
genuine singleton, which is what makes the flow-typing encoding of §3 sound.

##### 2.4 Member selection on nullable unions

Selecting a member of `T` on a value of type `T | Null` is an error when `Null` does not have that
member:

```scala
val s: String | Null = ???
s.length   // error: value length is not a member of String | Null
```

The error message points at the two available remedies:

```
Since explicit-nulls is enabled, the selection is rejected because
String | Null could be null at runtime.
If you want to select length without checking for a null value,
insert a .nn before .length or import scala.language.unsafeNulls.
```

Implicit search and extension-method resolution behave the same way: an extension method or
implicit conversion defined for `String` is not applicable to `String | Null`.

##### 2.5 Equality and pattern matching against `null`

Comparisons of a possibly-null value against `null` remain possible. They remain possible on
non-nullable types too: an unsound `null` can still arrive from Java, from an uninitialized field,
or from a cast. So `==`, `!=`, `eq`, `ne` and the pattern `case null` are all still allowed
between `Null` and reference types.

Pattern-match exhaustivity and reachability are adjusted as follows.

- Exhaustivity follows the selector type. A selector of type `T | Null` requires a `case null`
  (or some other pattern that matches `null`) to be exhaustive.
  Under `unsafeNulls` the `| Null` is stripped from the selector type before its space is
  computed, so a nullable selector does not require the null case there.

- Reachability differs between the two modes. Inside an `unsafeNulls` scope, `null` is part of
  the space of any reference-typed selector, so a `case null` arm is reachable. By default it is
  not, and such an arm on a non-nullable selector is reported as an unreachable case:

  ```scala
  def h(s: String) = s match
    case null => 1                    // warning: unreachable case
    case _    => 2
  ```

---

#### 3. Flow typing

Flow typing is a compatibility feature to ensure that existing code that assumes a value
to be non-null after a null check continues to work.
After a reference has been tested against
`null`, the reference has the non-nullable type in the scopes where the test is known to hold.

Flow typing is implemented as part of elaboration by inserting casts to the non-nullable type.
It does not change the type system.

Note that unlike §2, flow typing is not affected by `unsafeNulls`: it applies everywhere. It only ever
*narrows* a type from `T | Null` to `T`, so it does not reject programs; but it can change the
result of type inference and overload resolution, and so is discussed under
[Compatibility](#compatibility).

##### 3.1 Trackable references

Flow typing applies to a `TermRef` `p` that is **trackable**. `p` is trackable if any of:

1. `p` is a stable path — a path all of whose components are immutable `val`s or objects;
2. `p` is a reference to a field annotated `@scala.annotation.stableNull` through a stable prefix
   (see §3.2);
3. `p` is a reference to a local mutable variable `x` such that
   - every assignment to `x` occurs in the same compilation unit and is *reachable* — concretely,
     `x` is not assigned inside a closure or a nested definition; and
   - the use of `x` is not "out of order" with respect to its definition — concretely, the use does
     not occur inside a nested definition that could be called at a different time than the
     enclosing block executes.

The two conditions on mutable variables exist for the same reason. If a variable is captured and
mutated by a closure, or read from within one, the compiler cannot order the null check with
respect to the assignment:

```scala
var x: String | Null = ???
def y =
  x = null

if x != null then
  val a: String = x  // error: `x` is captured and mutated by a closure; not trackable
```

```scala
var x: String | Null = ???
def y =
  if x != null then
    val _: String = x   // error: use of `x` is out of order
if x != null then
  val a: String = x     // ok
  x = null
```

In the second example nothing assigns to `x` inside a closure, but `y` may be *called*
after the outer block has set `x = null`, so the fact established at the outer check does not hold
at the inner use.

The third restriction is that mutable *fields* of classes are never trackable by default, since
any code holding a reference to the object may reassign the field, and the compiler cannot see
which object a given alias refers to:

```scala
class Box:
  var x: String | Null = "hi"
  def foo(): Unit =
    if x != null then
      callOtherMethods()
      val a: String = x   // error: `x` may be mutated between the check and the use
```

`@stableNull` (§3.2) is the opt-in that lifts this restriction, at the cost of soundness.

##### 3.2 Mutable fields and `@stableNull`

A mutable *field* (`var` member of a class) is not trackable by default, because a concurrent
thread, a reentrant call, or unrelated code reachable from the same reference could assign `null`
between the check and the use. The annotation `scala.annotation.stableNull` opts a field in:

```scala
import scala.annotation.stableNull

class A:
  @stableNull private var s: String | Null = null
  def getS: String =
    if s == null then s = ""
    s   // s: String
```

An annotated field is tracked whenever it is accessed through a stable prefix.

`@stableNull` is unsound by construction, and is documented as such. It exists because the
alternative, a `var` field used as a lazily-initialized cache, which is a common
mutable-nullable idiom, is otherwise impossible to express without an unchecked `.nn` on every
read. Its intended use is a field local to a class where `null` means "not yet computed". It was
introduced privately in [scala/scala3#23528](https://github.com/scala/scala3/pull/23528) for the
3.8 standard library migration and made public in
[scala/scala3#25886](https://github.com/scala/scala3/pull/25886).

##### 3.3 Pattern matching

A `match` is a form of null test, and contributes flow facts in two ways. Both are enabled
wherever flow typing is, including inside an `unsafeNulls` scope.

First, if a `case null` — or a variable or wildcard pattern, which also matches `null` — appears
among the cases, the remaining cases are typed with `Null` stripped from the selector type:

```scala
val s: String | Null = ???
s match
  case null => ...
  case _    => // `s` has type `String` here
```

Second, after a case whose pattern is a type test or an extractor, a pattern that cannot match
`null`, the selector itself — not merely the variable the pattern binds — is known to be non-null
in the guard and body of that case:

```scala
def k(s: String | Null) = s match
  case _: String => s.length   // `s`, the selector path, is `String` here
  case null      => 0
```

The implementation currently applies this only to *unbound* type tests and extractors: it holds
for `case _: String` and `case Box(v)`, but not for `case t: String` or `case b @ Box(v)`, even
though those equally cannot match `null`.

##### 3.4 Encoding, and paths of length greater than one

Flow typing is implemented by inserting casts rather than by carrying a separate typing
environment. When `typedIdent` or `typedSelect` produces a tree `p` of type `T | Null` that the
analysis knows to be non-null, and `p` is not the left-hand side of an assignment, the
tree is cast to `p.type & T`.

Casting to `p.type & T`, rather than to `T`, is what allows flow typing to work for paths longer
than one component, because `p.type & T` is itself a stable path and so can be tracked in turn:

```scala
abstract class Node:
  val x: String
  val next: Node | Null

def f =
  val l: Node | Null = ???
  if l != null && l.next != null then
    val third: l.next.next.type = l.next.next
```

elaborates to

```scala
def f =
  val l: Node | Null = ???
  if l != null && l.$asInstanceOf$[l.type & Node].next != null then
    val third:
      l.$asInstanceOf$[l.type & Node].next
       .$asInstanceOf$[(l.type & Node).next.type & Node].next.type =
      l.$asInstanceOf$[l.type & Node].next
       .$asInstanceOf$[(l.type & Node).next.type & Node].next
```

This is also why §2.3 removes `null` from the denotation of `p.type`. The singleton `p.type` must
denote a set not containing `null` for the cast to be justified.

The rewrite takes a different form in a term position and in a singleton type position, since a
type cannot contain a cast. Instead of adding a cast, the compiler transforms a singleton
type to an intersection with the non-nullable version of its underlying type,
e.g., `x.type & T`.

In the following example, both occur in a single definition:

```scala
def f(x: String | Null): Unit =
  if x != null then
    val y: x.type = x
```

elaborates to

```scala
val y: x.type & String = x.$asInstanceOf$[(x : String | Null) & String]
```

The type annotation `x.type` has become the intersection `x.type & String`, while the initializer
`x` has become a cast. Note that the singleton on the left still refers to `x` at its declared type
`String | Null`, with the non-null leg intersected on the outside.

##### 3.5 What flow typing does not do

- It tracks only nullability. `if x == 0 then ...` does not give `x` the type `0`.
- It does not track aliasing between non-nullable paths:

  ```scala
  val s: String | Null = ???
  val s2: String | Null = ???
  if s != null && s == s2 then
    // s:  String     (inferred)
    // s2: String|Null (not inferred)
  ```
- It cannot track a path whose prefix is a mutable variable, e.g. `x.a` where `x` is a `var` —
  even where `x` itself is trackable.

---

#### 4. Flexible types for Java interoperability

##### 4.1 The problem

Every reference type appearing in a Java signature is implicitly nullable, and the signature does
not say whether any given occurrence is *intended* to be. The right outcome is that the
programmer, who often does know, gets to decide. There are two obvious translations, and neither
delivers that.

Consider a Java method `String takeAndReturnString(String s)`.

The **convenient** translation refuses to nullify results and nullifies arguments, giving
`String | Null => String`:

```scala
val s1: String | Null = j.takeAndReturnString("Hi")   // ok
val s2: String        = j.takeAndReturnString(null)   // ok, but unsound
```

Both lines compile, but `s2` is unsound — `takeAndReturnString` really can return `null` — and
the call may also break a Java method that was written expecting a non-null argument.

The **sound** translation nullifies results, giving `String => String | Null`:

```scala
val s3: String | Null = j.takeAndReturnString("Hi")   // ok
val s4: String        = j.takeAndReturnString(null)   // two errors
val s5: String = j.takeAndReturnString(null.asInstanceOf[String]).asInstanceOf[String]  // ok
```

This is correct but unusable in practice: `System.getProperty("x").trim` would not compile, and
Scala code very often interoperates with Java while assuming — usually correctly — that results
are non-null. Requiring a cast at every such point is not a realistic migration story.

So far this is only a convenience-versus-soundness trade-off, and one could argue for either
answer. **The decisive argument is that with an invariant type constructor, neither translation
can express what the programmer needs at all.** Consider a Java method `String[] getStringArray()`.
Scala's `Array` is invariant: `Array[A] <: Array[B]` requires `A <: B` *and* `B <: A`.

```scala
// convenient translation: Array[String]
val a1: Array[String | Null] = j.getStringArray()   // error
val a2: Array[String]        = j.getStringArray()   // ok

// sound translation: Array[String | Null] | Null
val a3: Array[String | Null] = j.getStringArray().nn   // ok
val a4: Array[String]        = j.getStringArray().nn   // error
```

Under the convenient translation the programmer cannot denote the array as an array of nullable
strings; under the sound one they cannot denote it as an array of non-null strings. Whichever
translation is chosen, one of the two things a programmer may legitimately want to say becomes
inexpressible. Flexible types are what make both expressible.

##### 4.2 Definition

A **flexible type** `T |? Null` is syntactic sugar for a type application
`<FlexibleType>[T]` of a fundamental type constructor

```
type <FlexibleType>[T] >: T | Null <: T
```

Flexible types are **non-denotable**: there is no source syntax for them, and users cannot write
one. Only the compiler constructs them. The compiler prints a flexible type as `T |? Null`, and can be asked to print it as
plain `T` with `-Yhide-flexible-types` (which the presentation compiler uses for hovers, inlay
hints and completions, so that IDE users are not shown a type they cannot write).

The design is inspired by Kotlin's [platform
types](https://kotlinlang.org/docs/java-interop.html#null-safety-and-platform-types).

##### 4.3 What flexible types allow

The consequence of the bounds is that a flexible type is usable in both directions:

```scala
// Java:
//   class J {
//     public void f(String s) {}
//     public String g() { return ""; }
//   }
// seen from Scala as:
//   class J:
//     def f(s: String |? Null): Unit
//     def g(): String |? Null

def useJ(j: J) =
  val x1: String = ""
  val x2: String | Null = null
  j.f(x1)        // ok: String        <: String |? Null
  j.f(x2)        // ok: String | Null <: String |? Null
  j.f(null)      // ok: Null          <: String |? Null

  val y1: String        = j.g()   // ok: String |? Null <: String
  val y2: String | Null = j.g()   // ok: String |? Null <: String | Null

  j.g().trim().length()           // ok, may throw NPE at run time
```

This gives Scala, at the Java boundary, exactly the safety guarantee that Java itself gives: none.
It does so deliberately, and confines the unsoundness to that boundary.

Returning to the invariant case of §4.1: `String[] getStringArray()` is nullified to
`Array[String |? Null] |? Null`, and now *both* denotations are available:

```scala
val a5: Array[String | Null] = j.getStringArray()   // ok
val a6: Array[String]        = j.getStringArray()   // ok
```

Both hold because `String |? Null` is simultaneously a subtype and a supertype of each of
`String` and `String | Null`, which is exactly what invariance demands:

- `Array[String |? Null] <: Array[String | Null]` requires `String |? Null` both ways.
  `String |? Null >: String | Null` holds by the lower bound, and
  `String |? Null <: String | Null` holds because `String |? Null <: String` by the upper bound,
  and a type conforms to a union if it conforms to either branch.
- `Array[String |? Null] <: Array[String]` requires `String |? Null` both ways.
  `String |? Null <: String` holds by the upper bound, and `String |? Null >: String` holds
  because the comparison is against the *lower* bound `String | Null`, and
  `String <: String | Null`.

##### 4.4 Overriding Java methods

A Java method `String f(String x)` is seen from Scala as `def f(x: String |? Null): String |? Null`. Because
a flexible type is equivalent to its nullable and its non-nullable form alike, an overriding
definition is free to pick either. Writing the bounds of §4.2 out at `T = String`, so that
`String |? Null` has lower bound `String | Null` and upper bound `String`:

- `String |? Null <: String` by the upper bound; and `String <: String |? Null` by the lower bound,
  since `String <: String | Null`. So `String |? Null =:= String`.
- `String |? Null <: String | Null`, since `String |? Null <: String` by the upper bound and
  `String <: String | Null`; and `String | Null <: String |? Null` by the lower bound. So
  `String |? Null =:= String | Null`.

(That a single type is equivalent to both is the deliberate unsoundness of flexible types described
in §4.1, confined here to the Java boundary.)

An overriding definition may therefore choose either form independently for each parameter and for
the result. Given the Java method above, all four of the following override it, inside an
`unsafeNulls` scope and outside one alike:

```scala
def f(x: String | Null): String | Null
def f(x: String):        String | Null
def f(x: String | Null): String
def f(x: String):        String
```

The last two are unsound in the usual way — a Java caller may pass `null`, or the Scala
implementation may return `null` through an unsound path — but rejecting them would make overriding
Java interfaces impractical.

Separately, the `matches` relation, which decides
which member overrides which, is evaluated with `Mode.SafeNulls` retracted
to follow the semantics of `unsafeNulls`.

##### 4.5 The flexification function

When the compiler loads a Java class it rewrites the types of its members: in `Namer` for a class
read from Java source, and in `ClassfileParser` for one read from bytecode. The transformation is
`ImplicitNullInterop.nullifyMember`, which applies the following function `f` to a member's type.

```
(1)  f(T)                 = T |? Null                       T a reference-class type reference
(2)  f(T)                 = T                               T a value class, or one of Unit, Any,
                                                            AnyKind, Nothing, Null, Singleton
(3)  f(X)                 = X |? Null                       X a type parameter of the Java class
(4)  f(C[A_1,...,A_n])    = C[A_1,...,A_n] |? Null          C Java-defined
(5)  f(C[A_1,...,A_n])    = C[f(A_1),...,f(A_n)] |? Null    C Scala-defined
(6)  f(A | B)             = (f°(A) | f°(B)) |? Null
(7)  f(A & B)             = (f°(A) & f°(B)) |? Null
(8)  f(T { R })           = (f°(T) { R }) |? Null
(9)  f((A_1,...,A_m)R)    = (f(A_1),...,f(A_m)) f(R)
(10) f(T)                 = T                               otherwise
```

In (6)–(8), `f°` denotes the rewritten operand with its own outer `|? Null` stripped, and the
outer `|? Null` shown is added only if the operands were in fact flexified — for (6) and (7) only
when *both* were, so that `(A |? Null) | (B |? Null)` becomes `(A | B) |? Null` rather than
`((A |? Null) | (B |? Null)) |? Null`. Refinement bodies `R` are never rewritten.

Rule (10) is the catch-all, and covers more than it might appear: constant types, match types and
other computed forms are returned unchanged, and no type of higher kind is ever flexified.

Some members are exempt from `f` altogether: enum value definitions, the `TYPE_` field of a boxed
primitive class, module values, and `toString` and `getClass`, which are total (§1.1). Within a
member that is rewritten, two positions are exempt: the result type of a constructor, whose
parameter types are still rewritten, and the parameters of an implicit or `using` section, so that
implicit search behaves predictably.

The transformation is deliberately *syntactic*. It does not ask whether `T <:< AnyRef`, because
the symbols being rewritten are typically still under construction during class loading, and a
subtyping query could force them prematurely or introduce a cycle. It therefore acts only on the
forms it recognises directly: type references whose class is not one of the exceptions in (2),
type parameter references of the Java class, applied types, and the structural cases above.

Notes on the interesting cases:

- **Type parameters (rule 3).** A Java type parameter is always nullable, so
  `class C<T> { T foo(); }` becomes `class C[T] { def foo(): T |? Null }`.
- **Java-defined generics (rule 4).** The *outermost* level of each type argument is left alone, so
  `List<T> makeList()` becomes `def makeList(): java.util.List[T] |? Null`, not
  `java.util.List[T |? Null] |? Null`. Nothing is lost by this: `java.util.List` is itself Java-defined and
  therefore itself rewritten, so `list.get(0)` already returns `T |? Null`.
- **Scala-defined generics (rule 5).** `Box<T> makeBox()`, where `Box` is Scala-defined, becomes
  `def makeBox(): Box[T |? Null] |? Null`. Scala classes are not rewritten inside their bodies, so the
  nullability has to be pushed into the type argument.
- **The two combined.** Only the outermost level of a Java-defined class's arguments is skipped;
  levels below it resume. So `List<Box<List<T>>> makeCrazyBoxes()` becomes

  ```scala
  def makeCrazyBoxes(): java.util.List[Box[java.util.List[T] |? Null]] |? Null
  ```

  Both `List`s are flexified; the `Box` between them is not, because it is an argument of a
  Java-defined `List`.
- **Arrays and varargs.** `String[] arr()` becomes `def arr(): Array[String |? Null] |? Null`, and
  `void setNames(String... names)` becomes `def setNames(names: (String |? Null)*): Unit`.
- **Constant fields.** A `final` field with a literal initializer is given a constant type when the
  class is read from bytecode, and rule (10) then leaves it alone: `final String NAME = "name"` is
  seen as `val NAME: ("name" : String)`. Read from Java *source* no constant type is formed, and
  the field is flexified to `String |? Null` like any other.

##### 4.6 Nullness annotations

The flexification is refined by Java nullness annotations, which the compiler reads in a
number of dialects (JSR-305, JSpecify, the Checker Framework, JetBrains, Android/AndroidX,
Spring, Lombok, RxJava, Reactor, Eclipse JDT, Jakarta, …; see `Definitions.NotNullAnnots` and
`Definitions.NullableAnnots` for the current lists).

- A member annotated with a recognized `@NonNull`/`@NotNull` annotation is not flexified at the
  outermost level. Its parameter types and inner types are still flexified:

  ```java
  class C {
    @NotNull String name;
    @NotNull List<String> getNames(String prefix);  // List is Java-defined
    @NotNull Box<String> getBoxedName();            // Box is Scala-defined
  }
  ```
  becomes
  ```scala
  class C:
    val name: String
    def getNames(prefix: String |? Null): java.util.List[String]
    def getBoxedName(): Box[String |? Null]
  ```

- A member annotated with a recognized `@Nullable` annotation is given an explicit `| Null` type
  rather than a flexible type — the annotation is a positive statement that `null` can occur, so
  the compiler holds the programmer to checking for it.

- Both kinds of annotation are honoured at nested positions as well as on the member itself, since
  Java type-use annotations can appear inside a type.

##### 4.7 Why flexible types stop at the Java boundary

Nullification of *member types* is applied to Java-defined symbols only. It is *not* applied to
Scala code compiled by an older compiler or with `-Yno-explicit-nulls`, even though such code is
equally implicitly nullable and TASTy records which units were compiled with explicit nulls, so the
compiler could tell them apart — as it does for type bounds, the one exception, covered by §4.8
below.

The attraction of doing so is real: it would extend to legacy Scala dependencies the same "denote
it either way" freedom that §4.1 establishes for Java, including for invariant constructors. This
proposal nevertheless leaves it out, because empirical observations suggest that it does
not pay for itself:

- Flexible types are only well-formed at simple kinds. Applying one to a higher-kinded type is a
  kind mismatch, so a blanket application to everything read from TASTy is not possible.
- Restricting the application to simple-kinded types does not rescue it, because a Scala type
  *variable* may itself be simple- or higher-kinded. The pattern is rare, but it makes it very hard
  to treat type variables consistently without occasionally reaching a higher-kinded one.
- The payoff is small. Most widely-depended-upon legacy Scala code is Scala 2, which is read
  through the Scala 2 unpickler rather than from TASTy, and the Scala 3 code that is affected
  rarely exhibits the invariant-constructor problem that motivates flexible types in the first
  place.
- Where interoperation with a legacy Scala dependency really is difficult, `unsafeNulls` is
  available as a scoped workaround with less complexity.

The practical consequence is that a Scala library compiled without explicit nulls keeps
the signatures it published: its `def find(k: K): V` is seen as returning `V`, not `V |? Null`. That is
unsound if it can return `null`.

##### 4.8 Type bounds mentioning `Null`

The one type read from TASTy that *is* rewritten is a type bound. A bound such as
`[T >: Null <: String]` is unsatisfiable under explicit nulls, since no type is both a supertype of
`Null` and a subtype of `String`. Such bounds appear in existing published code —
`def nullOf[T >: Null <: AnyRef]: T = null` is a known idiom.

Whenever a pickled type bound is read and its lower bound is exactly `Null`
while its upper bound is not nullable, the upper bound is flexified. So the declaration above, read
from a library, is seen as

```scala
class S[T >: Null <: String |? Null]
```

This transformation is applied both to the bounds of an abstract type definition and to
wildcard type arguments.

Two sources qualify. A Scala 2 pickle always does, since Scala 2 has no explicit nulls. Scala 3
TASTy does only when the unit it came from was itself compiled without explicit nulls, which the
`EXPLICITNULLS` attribute records. A unit compiled *with* explicit nulls is left alone.

Newly written code should write `[T >: Null <: String | Null]`.

##### 4.9 Warning on exposed flexible types

Flexible types are non-denotable, which makes them a poor thing to leak into a library's public
API by type inference. The compiler warns when the *inferred* result type of a public or protected
member of a class contains a flexible type:

```
method f exposes a flexible type in its inferred result type String |? Null.
Consider annotating the type explicitly
```

The remedy is to write the result type.

##### 4.10 Flexible types and `Unit`

There is one known rough edge, which accounts for 3 of the 16 residual Open Community Build
failures (§[Evidence](#evidence-the-open-community-build-and-the-community-build)). When a block
has expected type `Unit` and its last expression is not of type `Unit`, the compiler inserts a
trailing `()` into the block. That insertion tests for `Unit` exactly, so it does not fire for `Unit |? Null`:

```java
public abstract class AbstractClass<T> {
  public abstract T foo();
}
```

```scala
val implementation = new AbstractClass[Unit]:
  override def foo() =    // expected result type is Unit |? Null, not Unit
    123                   // error: `()` is not inserted
```

The workarounds are to write `()` at the end of the body, or to annotate the overriding `foo`'s result type as
`Unit` explicitly.

---

#### 5. Unsoundness

The resulting type system is not sound with respect to `null`, and does not claim to be. The
known holes are:

1. **Uninitialized fields.** A field is `null` between the start of the constructor and its
   initialization:

   ```scala
   class C:
     val f: String = foo(f)
     def foo(f2: String): String = f2
   // (new C).f == null, although its type says String
   ```
   This is detected by `-Wsafe-init`, which is orthogonal to this proposal.

2. **Java and legacy Scala.** A flexible type `T |? Null` may hold `null`; a call to a Java method whose
   result was annotated `@NotNull` incorrectly may return `null`; a Scala library compiled without
   explicit nulls may return `null` from a `T`-typed method.

3. **Relaxed Java overriding** (§4.4).

4. **`@stableNull`** (§3.2).

5. **Arrays** (§5.1 below), whose uninitialized elements are `null` at a non-nullable
   element type.

6. **Casts, reflection and deserialization**, which can place a `null` at any reference type.

7. **`unsafeNulls` scopes** (§2), by design.

The goal is not to close all of these but to reduce unsoundness to specific identifiable cases such as these.

##### 5.1 Arrays

**Element types, reads and writes.**

Arrays are invariant in their element type. The element type can be nullable or non-null,
and this is reflected in the types of reads (`apply`) and writes (`update`).
Writes are checked: `null` cannot be *stored* into an `Array[String]`.

**Array creation.**

Array creation using a raw `new Array[T](n)` is a soundness hole. This allocates `n` slots filled
with the default value of `T`, which for a reference type is `null`, and gives the result the type
`Array[T]`:

```scala
val a = new Array[String](3)   // type Array[String]; every element is null
val s: String = a(0)           // compiles; `s` is null at run time
```

Creating an array filled with existing elements using methods such as
`Array.apply`, `Array.fill`, `Array.tabulate`, or `map` is sound.

The `Array.ofDim` method delegates to `new Array`, so it is also unsound.

Users of raw `new Array[T](n)` and callers of `ofDim` should pass a nullable
element type as the type argument.

---

#### 6. Syntax changes

There are no changes to the source grammar. Nullable types are written with the existing union
type syntax, `T | Null`. Flexible types are non-denotable.

No new language feature object is introduced either: `scala.language.unsafeNulls` already exists,
and is used through the existing `import scala.language.<feature>` and `-language:<feature>`
forms.

---

### Compatibility

#### Source compatibility

Because the subtyping change of §2 now applies by default, existing source is *not* expected to
compile unchanged: a project that has not been ported needs `-language:unsafeNulls`, at whatever
granularity it chooses. The Open Community Build measures both configurations (below): 1074 of
1928 projects compile with no changes at all, and adding `-language:unsafeNulls` brings the
remainder down to 16 failures.

Those 16 are the changes that `unsafeNulls` does *not* mask, because they follow from the global
changes of §1 and §4 rather than from null checking:

- **Members of `null`.** `null.eq(x)`, `null.synchronized { ... }` and similar no longer resolve
  through `Object`. `eq`/`ne` are restored by `Predef` extension methods on `AnyRef | Null`. The
  remaining `Object` members on a `Null`-typed receiver are, in practice, not used.
- **`Any.equals` / `Any.hashCode` deprecation.** Existing calls on `Any`, `AnyVal`, `Matchable`
  and primitive receivers now produce a deprecation warning (an error under `-Werror`). Calls on
  reference receivers are unaffected.
- **Flow typing affects inference.** Since flow typing narrows `T | Null` to `T`, a type inferred
  in a context guarded by a null check may now be narrower, which can in principle change overload
  resolution or implicit search. No instance of this breaking a project was observed.
- **Flexible types in inferred signatures.** A public member whose result type is inferred from a
  Java call now has a flexible type inferred, and the compiler warns (§4.9). Under `-Werror` this
  is an error; the fix is a one-line explicit type annotation.
- **Inference of `Unit` result types through flexible types** (§4.10), which accounts for 3 of the
  16 residual Open Community Build failures.
- **Macros.** Macros that pattern-match on the *shape* of a `TypeRepr` may not expect to see a
  flexible type where they previously saw `TypeRef`. This is by far the largest observed source of
  incompatibility — 13 of the 16 residual failures; see
  [Feature interactions](#feature-interactions).
- **Default arguments from legacy dependencies.** Default arguments are elaborated at the *call
  site*, so a default defined in a dependency compiled without explicit nulls is re-typechecked
  under the caller's rules:

  ```scala
  // upstream.scala, compiled without explicit nulls
  def foo(s: String = null): String

  // consumer.scala, checked code
  val v  = foo()                                // error: Null does not conform to String
  val v2 = foo(null.asInstanceOf[String])       // workaround
  locally:
    import scala.language.unsafeNulls
    val v3 = foo()                              // better workaround
  ```

  The dependency cannot be changed by the consumer, so the practical remedy is a scoped
  `unsafeNulls` import. This affected one Community Build project (Lucre, through ScalaSTM).

Code that is *not* wrapped in `unsafeNulls` is expected to need changes; that is the point of the
proposal. Those changes are quantified below.

#### Binary compatibility

The proposal is backward binary compatible. None of the four changes alters
erasure, bytecode generation, or the runtime representation of any value:

- `Null` still erases to a reference type; `null` is still the JVM `null`.
- Flexible types erase to their upper bound, i.e. exactly as the corresponding Java type erases
  today.
- `T | Null` erases as `T | Null` does today.
- Flow typing inserts `asInstanceOf` casts which erase to no-ops on reference types.
- `.nn` is `inline` and erases to a null check plus a cast.

Bytecode produced by an older compiler links against bytecode produced by a newer one.

#### TASTy compatibility

- **Reading old TASTy.** A library published before this change keeps its member types: they are
  not nullified (§4.7). The one type read from TASTy that is adjusted is a type bound whose lower
  bound is exactly `Null`, and then only when the unit was compiled without explicit nulls (§4.8).
  That last condition is what the `EXPLICITNULLS` attribute in TASTy is for: it records whether a
  unit was compiled with explicit nulls, and this proposal reads it to make that decision.
- **Reading Scala 2 pickles.** Type bounds whose lower bound is exactly `Null` have their upper
  bound nullified (§4.8), so the `[T >: Null]` idiom continues to work.
- **Writing TASTy.** Flexible types are pickled with a dedicated `FLEXIBLEtype` tag.
  Inside the compiler, they are represented by an `AppliedType` of a type constructor with the
  bounds `>: T | Null <: T` (§4.2).

A newer compiler reading TASTy from an older one is therefore unaffected. An older compiler cannot
read `FLEXIBLEtype`, but that is already true today (the tag exists since flexible types were
introduced) and is governed by the usual TASTy version policy.

An `inline def` compiled without explicit nulls or with unsafe nulls, and called from code with safe nulls,
is typechecked under the caller's rules. If the typing of the `inline def` depends essentially on
the unsafe nulls rules, the caller needs to be made aware of it and forced to import `unsafeNulls` as well,
to make clear that code that it has pulled in via inlining requires `unsafeNulls` to typecheck.

The same applies to code that uses match types compiled without explicit nulls or with unsafe nulls
that depend essentially on the unsafe nulls rules.

#### Evidence: the Open Community Build and the Community Build

The changes have been evaluated on two corpora: the **Open Community Build** (OCB), 1,928
open-source projects used to validate major compiler releases, and the **Community Build**, 40
widely-used Scala libraries used as an integration test for the compiler. The detailed study is in
Lau's thesis (2026).

##### How disruptive is the proposal, and how much does `unsafeNulls` recover? (Open Community Build)

The OCB was run with explicit nulls in two configurations: the proposed default, and the same with
`-language:unsafeNulls` enabled globally for each project.

| Configuration | Projects failing (of 1,928) |
|---|---:|
| The proposed default | 854 |
| With `-language:unsafeNulls` | 16 |

Two readings of this matter, and they answer different questions.

The first number is the cost of the proposal as stated: 854 of 1,928 projects, 44%, would not
compile unchanged. Equivalently, **1,074 projects — 56% — already satisfy the non-null-by-default
discipline and need no changes at all.** That is the evidence that the default is the right way
round; it is not evidence that the transition is free.

The second number is the cost of the migration path: adding one compiler option takes the failures
to **16, a 99.17% compatibility rate**. `-language:unsafeNulls` is therefore a viable first step
for any project not ready to port, and the scope of that option can then be narrowed file by file.

The 16 residual failures are the ones `unsafeNulls` cannot mask; they have two causes:

- **13 projects: macros.** A `TypeRepr` match that has no case for `FlexibleType`. `unsafeNulls`
  does not help here, because flexible types are a global change to the types of Java symbols, not
  a checking rule. See [Feature interactions](#feature-interactions).
- **3 projects: the `Unit` insertion edge case** described in §4.10.

The failing projects are: beangle/commons, zio-archive/zio-connect, xuwei-k/httpz,
beangle/serializer, devsisters/shardcake, gaelrenoux/tranzactio,
kaizen-solutions/trace4cats-zio-extras, karelcemus/play-redis, laserdisc-io/log-effect,
narma/tranzactio, scalamock/scalamock, senia-psm/zio-test-akka-http, vitaliihonta/scala-ql,
zio-archive/zio-metrics-legacy, guardian/fastly-api-client and scalameta/munit.

Since the above was written, new runs of the Open Community Build have been performed with approximately
the same results. The outputs of these most recent builds are available at:
- [safe nulls, the proposed default](https://scala3.westeurope.cloudapp.azure.com/dashboard/compare?baseScalaVersion=3.10.1-RC1-bin-20260918-035a05a-NIGHTLY&baseBuildId=scheduled_3.10.1-RC1-bin-20260918-035a05a-NIGHTLY&targetScalaVersion=3.10.1-RC1-bin-20260924-ae67fdd&targetBuildId=dotty-staging%2Fdotty%3Adefault-safe-nulls%3A2026-09-24)
- [unsafe nulls](https://scala3.westeurope.cloudapp.azure.com/dashboard/compare?baseScalaVersion=3.10.1-RC1-bin-20260918-035a05a-NIGHTLY&baseBuildId=scheduled_3.10.1-RC1-bin-20260918-035a05a-NIGHTLY&targetScalaVersion=3.10.1-RC1-bin-20260923-9ea70bd&targetBuildId=dotty-staging%2Fdotty%3Arebased-explicit-nulls-as-default%3A2026-09-24)

##### What does porting actually cost? (Community Build)

The proposal's default was enabled on all 40 Community Build projects and they were ported by hand
(best-effort, some LLM-assisted; the ported sources are
[available online](https://github.com/HarrisL2/scala3/tree/safe-null-community-build/community-build/community-projects)).

**15 of the 40 projects compiled with no changes at all**: cats-mtl, coop, discipline,
discipline-munit, discipline-specs2, effpi, libretto, Monocle, munit-cats-effect, perspective,
scalacheck-effect, scalatestplus-scalacheck, scalatestplus-testng, shapeless-3 and
simulacrum-scalafix. Notably some of these (Monocle, scalatestplus-testng, effpi) do
interoperate with Java; they compile unchanged thanks to flexible types.

The projects needing the most work — sconfig, cats-effect-3, scala-stm,
scala-parallel-collections, scala-xml — are low-level, performance-sensitive libraries that use
`null` deliberately, to signal unset properties or to release references for the garbage
collector. That is the expected shape of the cost: the code that pays is the code that actually
uses `null`.

The changes, 2,586 lines in total, break down as:

| Change | Lines | Share | What it is |
|---|---:|---:|---|
| `\| Null` type annotation | 1,162 | 45% | widening a declared type to a nullable union |
| `.nn` | 595 | 23% | asserting non-nullness at a use site (`x.f` → `x.nn.f`) |
| `.asInstanceOf` cast | 216 | 8% | casting a nullable variable to the expected type |
| null guard | 72 | 3% | new explicit `== null` / `!= null` checks, to enable flow typing |
| `uninitialized` | 37 | 1% | for variables that are only `null` before first assignment |
| other | 504 | 20% | logic, imports, whitespace, reformatted signatures |

Two thirds of the work is widening types and adding `.nn` — mechanical, local edits. Only 3% of
lines are *new* null checks, which supports the claim that migration mostly makes existing
nullability visible rather than demanding new defensive code.

(`uninitialized` is the existing `scala.compiletime.uninitialized`, used for a `var` whose
declared type is non-nullable but which has no meaningful initial value; `.asInstanceOf` casts are
concentrated in the low-level libraries, where a variable that is non-null throughout its useful
life is assigned `null` at the end to release the reference — cats-effect-3 alone accounts for 96
of the 216.)

##### How important are flow typing and flexible types for migration?

Starting from the ported projects, flow typing and flexible types were each disabled in turn and
the projects recompiled. The following table shows the number of compile-time errors that reappear.

| Feature disabled | Errors | Projects affected |
|---|---:|---:|
| Flow typing | 424 | 17 of 40 |
| Flexible types | 1,579 | 31 of 40 |

The compile-time errors fall into four categories: `[E008]` member
selection on `T | Null` (69% / 53%), `[E007]` type mismatch (26% / 43%), `[E134]` no overload or
extension applies (3% / 3%), `[E050]` applied value does not take parameters (2% / 1%).

The two features are orthogonal in that their error sets do not overlap.

##### Proposed adoption path

The evidence above supports the following sequence, which is what this proposal asks for:

1. Enable explicit nulls by default in a major release. 56% of OCB projects compile unchanged.
2. A project that does not compile adds `-language:unsafeNulls`, which takes the OCB failure count
   to 0.83%. Those remaining failures are small, well-understood changes.
3. The project then narrows the scope of `unsafeNulls` — from the whole build to a module, a file,
   or a single block — as it ports. Ported and unported code interoperate throughout, because both
   agree on what the types mean; only the checking differs.

### Feature interactions

#### Macros and `quotes` reflection

This is by a wide margin the most significant interaction, and the cause of 13 of the 16 residual
OCB failures. `scala.quoted.Quotes` exposes types through pattern-match extractors, and a macro
that inspects the signature of a Java symbol now meets a flexible type where it previously met a
`TypeRef`: a macro that derives a type class from a Java method sees `String |? Null` rather than
`String`.

Note that `unsafeNulls` does **not** mitigate this: flexible types change what the types of Java
symbols *are*, globally, whereas `unsafeNulls` only relaxes checking.
The reflection API has a dedicated `FlexibleType`
form for flexible types: a `TypeTest`, an extractor, and `underlying`/`lo`/`hi` accessors.

A flexible type is an applied type (§4.2), so it also matches an
`AppliedType` case with the type constructor `<FlexibleType>` and the wrapped type as its single
argument. A macro that recurses structurally through applied types can therefore often keep working
without a `FlexibleType` case.
The general rule is to treat a flexible type as a form of type bounds and to
recurse on its upper bound for subtyping-related work.

#### Presentation compiler and IDEs

Because flexible types are non-denotable, showing them in hovers, inlay hints, completions and
"insert inferred type" code actions would offer users a type they cannot write. The presentation
compiler therefore sets `-Yhide-flexible-types`, so a Java method's result is displayed as
`String` rather than `String |? Null`. Compiler error messages, by contrast, print `String |? Null`, since
there the distinction matters.

#### Scala.js and Scala Native

Neither backend is affected: nothing in erasure or code generation changes, and `Null` continues
to be a reference type after erasure on all backends.

#### The standard library

The Scala 3 standard library is already compiled under explicit nulls. `@stableNull` was introduced
specifically for this migration. The library's published signatures are unchanged by this proposal.

#### Naming of compiler settings

`-Yexplicit-nulls` becomes redundant, since what it enabled is now the default.
[scala/scala3#27081](https://github.com/scala/scala3/pull/27081) keeps it as a no-op for backwards
compatibility; it could later be deprecated and removed. Projects that already set it are already
ported and need no change. The same PR introduces `-Yno-explicit-nulls` to revert to the
behaviour before this SIP; note that this is a stronger revert than `-language:unsafeNulls`,
since it also undoes the global changes of §1 and §4.

## Related work

**Prior publications on this feature in Scala.**

- Abel Nieto, Yaoyu Zhao, Ondřej Lhoták, Angela Chang and Justin Pu. *Scala with Explicit Nulls.*
  ECOOP 2020. [PDF](https://plg.uwaterloo.ca/~olhotak/pubs/ecoop20.pdf),
  [DOI](https://doi.org/10.4230/LIPIcs.ECOOP.2020.25).

- Harris Lau. *Transitioning to Explicit Nulls in Scala.* MMath thesis, University of Waterloo,
  2026. [PDF](https://plg.uwaterloo.ca/~olhotak/pubs/thesis-h-lau-mmath.pdf),
  [UWSpace](https://uwspace.uwaterloo.ca/items/40965233-8035-4b45-b8c7-8b00a4ffd430).

**Why implicit nulls are a type-system defect.**

- Nada Amin and Ross Tate. *Java and Scala's Type Systems are Unsound: The Existential Crisis of
  Null Pointers.* OOPSLA 2016. [DOI](https://doi.org/10.1145/2983990.2984004),
  [preprint](https://raw.githubusercontent.com/namin/unsound/master/doc/unsound-oopsla16.pdf).

**Implementation.**

The feature is implemented in the Scala 3 compiler under the `-Yexplicit-nulls` flag.
The following pull requests related to the feature remain open.

- [scala/scala3#26941](https://github.com/scala/scala3/pull/26941) — specification changes for
  explicit nulls (§1.4, §2.2, §2.3, §4.2).
- [scala/scala3#27081](https://github.com/scala/scala3/pull/27081) — enable explicit nulls and safe
  nulls by default (§2, §3).

Change 1, making `scala.Null` a subclass of `AnyVal` under `-Yexplicit-nulls`, is already in main:
it was merged as [scala/scala3#25393](https://github.com/scala/scala3/pull/25393).

Reference documentation:
[Explicit Nulls](https://dotty.epfl.ch/docs/reference/experimental/explicit-nulls.html) and
[implementation notes](https://dotty.epfl.ch/docs/internals/explicit-nulls.html).

**Other languages.**

Three concerns recur across languages that have addressed nullability: flow typing, interoperation
with a less null-safe language, and incremental adoption of null safety by an existing codebase.
Scala is at an unusual intersection, needing all three at once.

| | Scala | Kotlin | Java | TypeScript | C# | Dart |
|---|:-:|:-:|:-:|:-:|:-:|:-:|
| Flow typing | ✓ | ✓ | | ✓ | ✓ | ✓ |
| Interoperation with a less-safe language | ✓ | ✓ | | ✓ | | |
| Incremental adoption | ✓ | | ✓ | | ✓ | ✓ |

- **Kotlin** is the closest comparison: a JVM language with Java interop. It writes nullable types
  as `T?` rather than as a union, calls flow typing *smart casts*, and uses non-denotable
  **platform types** for values from Java — the direct model for flexible types. Three differences
  are worth noting. Kotlin's smart casts do not handle mutable class fields at all, requiring the
  programmer to copy the field into a local `val`; Scala's `@stableNull` (§3.2) offers an opt-in
  instead. Kotlin is stricter at runtime: assigning a platform-typed `null` to a non-null variable
  throws immediately, so a non-null-typed variable never holds `null`, whereas Scala only throws
  at the point of use. Kotlin was designed with explicit nulls from the start, so it never
  faced Scala's migration problem.
- **C#** added nullable reference types in C# 8, opt-in per project or per file via
  `#nullable enable` — a granularity close to the `unsafeNulls` scoping proposed here — with flow
  analysis and an unchecked `!` "null-forgiving" operator. C# additionally separates the two halves
  of the migration: the programmer can enable nullable *annotations* without warnings (to stabilize
  an API's nullability contract first) or enable *warnings* without annotations (to find unsafe
  accesses first).
- **TypeScript** enables null safety with `strictNullChecks`, writes nullable types as unions as
  Scala does, has flow typing, and has an unchecked postfix `!`. Its interop problem is worse than
  Scala's — JavaScript is untyped, so the fallback is the top type `any` rather than a
  platform/flexible type. Crucially, `strictNullChecks` is all-or-nothing at the project boundary,
  with no scoped opt-in.
- **Dart** introduced *sound* null safety in 2.12: nullable types written `T?`, flow analysis, a
  checked `!`, and a `late` modifier for deferred initialization, backed by the runtime checks the
  language already performs. Dart is the closest analogue to Scala's situation — retrofitting onto
  a large existing ecosystem — and it managed the transition with library-level language versioning
  plus a mixed-mode unsound period and a `dart migrate` tool. That granularity is coarser than the
  file- and block-level control proposed here.
- **Java** does not enforce nullability in the type system; the ecosystem uses annotations read by
  external tools — the Checker Framework's Nullness Checker, NullAway, IDE inspectors, and the
  [JSpecify](https://jspecify.dev/) standard. This proposal consumes all of these
  (§4.6).
- **Swift** and **Rust** sidestep the problem entirely by having no null, using `Optional`/`Option`
  with language support. That is not open to a JVM language that must interoperate with Java.

## FAQ
