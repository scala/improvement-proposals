---
layout: sip
number: NN
permalink: /sips/:number.html
redirect_from:
  - /sips/:number
  - /sips/:title.html
stage: design
status: submitted
presip-thread: https://contributors.scala-lang.org/t/pre-sip-bindings-in-while-and-if-conditions/7567
title: Bindings in `while` and `if` Conditions
---

**By: Matthias Berndt**

## History

| Date          | Version            |
|---------------|--------------------|
| Oct 14th 2026 | Initial Draft      |

## Summary

Scala 3 already allows the condition of a `while` loop to be an arbitrary block, including
local definitions:

```scala
while
  val line = in.readLine()
  line != null
do
  process(line) // error: Not found: line
```

However, the definitions in that block are not visible in the body of the loop. This
proposal extends their scope: definitions in the condition block of a `while` loop are
visible in the loop body. In the same vein, definitions in the condition block of an `if`
expression become visible in its `then` and `else` branches. The syntax is unchanged.

This gives Scala a direct solution to the _loop-and-a-half_ problem (a loop whose exit
test sits in the middle of its body) without `var`s, duplicated code or `break`.

## Motivation

### The loop-and-a-half problem

Many loops need to do some work before they can decide whether to continue, and then use
the result of that work in the rest of the iteration. Reading input until a sentinel is
the textbook example:

```
loop
  line = readLine()
  exit if line == null
  process(line)
end
```

The problem is old. Knuth discusses it in _Structured Programming with go to Statements_
(1974), including Ole-Johan Dahl's proposal of a loop with the test in the middle
(`loop S; while B: T; repeat`). Soloway, Bonar and Ehrlich (_Cognitive Strategies and
Looping Constructs_, CACM 1983) found that novice programmers write such loops much more
reliably with a construct that matches this "read, test, process" structure. Roberts
(_Loop Exits and Structured Programming: Reopening the Debate_, SIGCSE 1995) made the same
point for teaching.

With a `while` loop in Scala today, the options are:

**1. Duplicate the first half of the loop.**

```scala
var line = in.readLine()
while line != null do
  process(line)
  line = in.readLine()
```

The read is written twice, and the two copies must be kept in sync. `line` has to be a
`var`, and it remains in scope after the loop.

**2. Assign a `var` in the condition block.**

```scala
var line: String = null
while
  line = in.readLine()
  line != null
do
  process(line)
```

This avoids the duplication, but needs a `var` with a dummy initial value, and again
leaks `line` into the enclosing scope.

**3. Use `boundary`/`break`.**

```scala
import scala.util.boundary, boundary.break

boundary:
  while true do
    val line = in.readLine()
    if line == null then
      break()
    process(line)
```

The loop condition is now meaningless, and the actual control flow is hidden in the
body.

Of course, the same can be expressed without a `while` loop, for example with iterators or
a tail-recursive function. But Scala has `while` loops, and this proposal is about them:
given that they are part of the language, would they be better with this change?

Interestingly, Scala 3 already has the syntax for a better solution. When `do`-`while`
was dropped, the recommended replacement was to put the body into the condition block:
`while { body; cond } do ()`. With the new control syntax, this generalizes to loops with
a test in the middle:

```scala
while
  val line = in.readLine()
  line != null
do
  process(line)
```

This is exactly Dahl's loop. It reads naturally and needs no `var`. The only thing
missing is for `line` to be visible in the body.

### `if` expressions

`if` expressions get the same rule as `while` loops: definitions in the condition block
are visible in the rest of the construct. This is useful whenever a value is computed only
in order to be tested and then used in the branches:

```scala
if
  val i = s.indexOf(' ')
  i >= 0
then
  s.substring(0, i)
else
  s
```

This also applies to `else if`, which is particularly useful when a value is needed only
in the `else` branch of an `if` chain. Today, such a value forces an extra level of
nesting:

```scala
if cache.contains(key) then
  cache(key)
else
  val parsed = parse(raw)
  if parsed.isEmpty then
    default
  else if parsed.size > limit then
    parsed.take(limit)
  else
    parsed
```

With this proposal, the chain stays flat:

```scala
if cache.contains(key) then
  cache(key)
else if
  val parsed = parse(raw)
  parsed.isEmpty
then
  default
else if parsed.size > limit then
  parsed.take(limit)
else
  parsed
```

Both parts of the proposal also mirror what `for` comprehensions already do: definitions
in the head of a `for`, whether generators or value definitions such as `y = f(x)`, are
visible in its body.

## Proposed solution

### Specification

If the condition of a `while` loop or an `if` expression is a block, then the definitions
and imports in that block are in scope

- for a `while` loop: in its body;
- for an `if` expression: in its `then` branch and in its `else` branch.

They are not visible after the `while` or `if`.

```scala
while
  val (key, value) = readEntry()
  key.nonEmpty
do
  map(key) = value

if
  val remaining = budget - spent
  remaining < 0
then
  reject(s"over budget by ${-remaining}")
else if remaining < reserve then
  warn(s"only $remaining left")
else
  approve(remaining)
```

This proposal does not change the meaning of the parenthesized syntax inherited from
Scala 2, as in `while ({ val x = f(); x > 0 }) g(x)`.

### Compatibility

**Source compatibility.** The change is incompatible in theory: a definition in the
condition block now shadows a definition of the same name from an enclosing scope in the
body or branches.

```scala
var i = 0
while { val i = 5; i > 0 } do
  println(i) // today: the outer `var i`; with this proposal: the inner `val i`
  ...
```

In practice, an unnoticed change in behavior is unlikely, because all of the following would
have to apply:

- The condition block of a `while` or `if` contains a definition. This is not common in
  the first place, because such definitions are of little use with the current scoping
  rules.
- The body or the branches refer to a definition of the same name from an enclosing scope.
- The type checker does not catch the change. In many cases, the two definitions have
  different types, and the program no longer compiles.

**Binary and TASTy compatibility, macros.** The proposal does not add new kinds of trees,
and the condition of a `while` or `if` remains an ordinary expression. Instead, the
implementation rewrites the construct into existing ones before type checking:

```scala
while { stats; cond } do body
  ==>
while { stats; if cond then { body; true } else false } do ()

if { stats; cond } then thenp else elsep
  ==>
{ stats; if cond then thenp else elsep }
```

Binary and TASTy compatibility are therefore not affected. Macros, however, see the
rewritten trees. A macro that inspects a `while` or `if` whose condition block contains
definitions sees a different tree shape than before. Again, the impact is likely limited,
because definitions in condition blocks are of little use today.

### Other concerns

**Tooling.** Syntax-based tools such as Scalameta and scalafmt are not affected, since the
syntax is unchanged. Tools that perform their own name resolution, such as IDEs that don't
use the compiler for this, need to apply the new scoping rule. Tools based on the
presentation compiler (e.g. Metals) get it automatically.

**Implementation.** A proof-of-concept implementation is available as the experimental
language feature `scala.language.experimental.conditionBindings` in the branch
[mberndt123/scala3@better-while-loop](https://github.com/mberndt123/scala3/tree/better-while-loop).

## Related work

Virtually every mainstream language offers some way to introduce a variable in the head of
a loop or an `if` and use it in the rest of the construct.

**Declarations in the condition.** C++ has allowed a declaration as the condition of `if`
and `while` since C++98 (`while (T x = f()) { ... }`). C++17 added an init-statement to
`if` and `switch` ([P0305R1](https://wg21.link/P0305R1)), and C++20 to the range-based
`for` loop ([P0614R1](https://wg21.link/P0614R1)). Go allows `if v := f(); cond { ... }`,
with `v` visible in all branches. Perl scopes `while (my $line = <$fh>) { ... }` to the loop.

**Pattern bindings in the condition.** Rust has `if let` and `while let`, and since
[Rust 1.88](https://blog.rust-lang.org/2025/06/26/Rust-1.88.0/) _let chains_ such as
`if let Some(x) = a && x > 0`. Swift has `if let`, `while let` and `guard let`. Java
(`o instanceof String s`, JEP 394) and C# (`out var`, patterns) bind variables in
conditions as well.

**Assignment expressions.** Python's assignment expressions
([PEP 572](https://peps.python.org/pep-0572/)) were motivated to a large part by the
loop-and-a-half problem: `while (chunk := f.read(8192)): process(chunk)`. Ruby supports
`while line = gets`. In both languages, the variable remains visible after the loop.

**Other.** Kotlin makes variables declared in a `do`-`while` body visible in its
condition. A [thread on amber-dev](https://mail.openjdk.org/pipermail/amber-dev/2024-January/008525.html)
discussed the same for Java and pointed out the same shadowing issue as in the
[Compatibility](#compatibility) section of this proposal. Ada's
`loop ... exit when cond; ... end loop;` places the exit test anywhere in the loop.

### References

- D. E. Knuth, _Structured Programming with go to Statements_, Computing Surveys 6(4), 1974.
- E. Soloway, J. Bonar, K. Ehrlich, _Cognitive Strategies and Looping Constructs: An
  Empirical Study_, Communications of the ACM 26(11), 1983.
- E. Roberts, _Loop Exits and Structured Programming: Reopening the Debate_,
  SIGCSE 1995.
