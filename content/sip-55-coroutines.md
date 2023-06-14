# Scala Improvement Process - Coroutines

---
layout: sip
permalink: /sips/:title.html
stage: design
status: submitted
title: SIP-55 - Concurrency with Coroutines
---

**By: Jack Viers and Diego Alonso and Raul Raja**

## History

| Date          | Version            |
|---------------|--------------------|
| 8th July 2022 | [PRE-SIP Proposal](https://contributors.scala-lang.org/t/pre-sip-suspended-functions-and-continuations/5801) |
| 19th April 2023 | Initial Draft    |
| 14th June 2023 | SIP submission |

## Summary

This SIP implements concurrency in Scala using coroutines.
A coroutine is written as a function with an implicit `Suspend` parameter.
The compiler transforms such a function into a state-machine class.
We leverage parametric polymorphism to write color-transparent higher order functions,
and thus address the [two-function-color problem](https://journal.stuffwithstuff.com/2015/02/01/what-color-is-your-function/).
No changes to the syntax or type system of Scala are needed.
The only front-end changes are generated additions to the standard library. 
The implementation is
done in the compiler backend, which does not depend on the runtime platform.
This is an opt-in feature, which has no effect on any code without coroutines.
We have [developed a prototype](https://github.com/47deg/scourt) implementation.

**Non-Goals:** This SIP does not improve or deprecate `for` comprehensions. 
We do not aim to introduce a generalized form of delimited continuations 
or algebraic effects.

## Motivation

_Concurrency_ is a programming language feature that allows us 1)
to split a program into parts that can run separately from others; and 2)
to mix those parts in a larger program that controls how 
those separate parts are run together and joins them into a final result.

Most operating systems offer managed concurrency solutions 
such as processes and threads. However, since these are 
relatively heavy, many languages have a form of self-managed
lightweight concurrency, such as fibers or coroutines.
Some examples are
the asynchronous modality for expressions in F#, 
[async-await in C#](https://learn.microsoft.com/en-us/events/dotnetconf-2020/asynchronous-courotines-with-c),
[goroutines in Go](https://go.dev/tour/concurrency/1), 
[suspended functions in Kotlin](https://kotlinlang.org/docs/coroutines-guide.html#table-of-contents),
or [Virtual Threads](https://openjdk.org/jeps/444) in Java.
The solution adopted for each language was designed to fit 
with the design of the rest of the language.

Scala, on the other hand, lacks direct language support for concurrency.
Instead, this has been supported by a succession of libraries over the 
years, such as Twitter util ([2010](https://github.com/twitter/util/commit/34f8876133f7f45ce3499c0e3df9e119e54c1a54#diff-0295d2fd9c8a7570fe2351b8c5c47d346c58bdb4f2c02a91b22a2fd5a682c96d)),
the Scala concurrent library ([2011](https://github.com/scala/scala/commit/d38ca89f8d50c0d6eafac623f0edc165c01f8bd4)),
`scalaz` ([2013](https://github.com/scalaz/scalaz/commit/2f49a3b58fb94bb5b0189f97b67982be55af9485)),
`scalaz-streams` --nowadays `fs2`-- ([2013](https://github.com/scalaz/scalaz-stream/commit/58a00e6836aedeb59616b94b12731b34833485fb)),
`monix` ([2015](https://github.com/monix/monix/commit/e623f3475fcd7076930004e93fe68a86d2e1eb00)),
`cats-effect` ([2017](https://github.com/typelevel/cats-effect/commit/74dd07050712f66c4c78d7e1fb056078e013e3b4)),
and `zio` ([2018](https://github.com/zio/zio/commit/1162f6bb79dc17eaf0dd810361b60cf4acdff22b)). 
None of these has given a fully satisfactory solution, one which combines
a syntax that fits Scala design and a clear semantics.
In this SIP we propose coroutines as the solution for concurrency in Scala.

#### Idiomatic concurrency for Scala

Let us first point out what features an ideal approach to 
concurrency in Scala should have:

* Since Scala is an expression-oriented language, expressions should be the 
  level at which concurrency can occur.
* Scala is a strict and sequential language: expressions are evaluated 
  in a fixed order, and each evaluation ends before the next one begins.
  Any shift from this semantics, such as the laziness of local variables 
  or parallel evaluation, should have a explcit mark such as the `lazy` modifier.
* Scala is a higher-order language. Functions can take other functions as
  parameters and return functions as a result. Idiomatic concurrency 
  in Scala should allow us to define **higher-order functions** (HOF) 
  that apply to concurrent or non-concurrent functions.
* A **direct style** syntax helps us mix concurrent expressions with the rest 
  of Scala as easily as with non-concurrent ones. It 
  should let us use them in a `val` declaration, a `var` assignment,
  the conditions or the branches of an `if-then-else`, 
  the cases of a pattern match, etc.
* An **applicative** style should let us apply any function, 
  concurrent or not, to any expression, concurrent or not, 
  using the same syntax for "normal" functions and expressions,
  without the need for any special combinators.

#### Running examples

We compare our solution with the _status quo_ using three examples: 
a modified `gcd` as an example of a first-order function;
a `map` for lists as an example of a generic HOF; 
and a `pipe,` as an example of a combinator that composes 
two input functions and returns another function. 
Here are they in "plain" code:

``` scala
def isZero(n: Int): Boolean
def mod(num: Int, den: Int): Int

def gcd(a: Int, b: Int): Int =
  if isZero(b) then a else gcd(b, mod(a, b)) + 1

extension [Y] (list: List[Y]) 
  def map[Z](fili: Y => Z): List[Z] = list match 
    case Nil =>  Nil
    case y :: ys => fili(y) :: ys.map(fun)

def pipe[X, Y, Z](tick: X => Y, tock: Y => Z): X => Z =
  (x: X) => tock(tick(x))
```

These examples are not meant to give industrial motivation. We use 
them to show how expressive or verbose each solution can be if we 
want to make `isZero`, `mod`, `fili`, `tick` or `tock` concurrent.

#### Threads

The Java Virtual Machine (JVM) supports a wrapped form of OS threads 
using a Thread Class. Threads can be used as a crude form of
direct style concurrency. For instance, in the `gcd` we could
make the code of `isZero` or `mod` run in a separate thread, 
without needing to change the `gcd` body.
However, because of its overheads, threads are not a scalable solution.

#### Akka 

Akka implements parallel and distributed programming based on 
the actor model. Akka is a highly scalable and reliable library
used by several companies.
However, concurrency in Akka is only available at the level of actors,
an instance of a class that implements a `receive` method.
For instance, to build a version of `gcd` with Akka in so 
`isZero` or `mod` are concurrent, we must declare an actor 
class for each function, put the parameters and results of
each function into a message class, and for each actor write a
`receive` method that sends or handles those messages.
Thus, Akka concurrency is not at the expression level
and thus cannot be used in a direct or applicative style.

#### Functional Effect Libraries

Libraries such as `scalaz,` `fs2`, `monix,` `cats-effect,` or `zio,` 
implement expression-level concurrency by marking such expressions
with a wrapper `Task` type.
This type has a `delay` primitive that takes an expression as
input and returns a concurrent task to evaluate that input.
Here is the `gcd` example using the `Task` type, in which the bodies 
of `isZero` and `mod` are delayed into tasks.

``` scala
def isZero(n: Int): Task[Boolean] = 
  Task.delay(n == 0)
def mod(num: Int, den: Int): Task[Int] = 
  Task.delay(num % den)

def gcd(a: Int, b: Int): Task[Int] =
  isZero(b) flatMap
    case true => Task.pure(a)
    case false => mod(a, b).flatMap(m => gcd(b, m)).map(g => g + 1)
```

By its type, it may seem that the `mod` function runs concurrently 
and returns a number, but that is not the case.
The `mod` function returns a `Task` object,
which represents a tree of concurrent operations that yield a number. 
These operations are not performed until and unless the 
program invokes a "run" method, which returns the number.
Thus, a Task types represents concurrency by adding a level of
indirection, which splits the program code between two levels.
The outer level is the Scala code that deals with `Task` objects
and its methods, such as `flatMap`, `map`, and `pure`.
The inner language are the variables `a`, `b`, `m`, `g` variables, 
and the operations on them such as the true or false match, 
the calls to `mod`, `isZero` or `gcd`, or the final addition of 1.

This type indirection, the fact that `Task` is a different type from
the results it yields, is a downside of this solution.
Instead of being written in a direct style, in the task-variant of `gcd` 
we use a `flatMap` on the result of `isZero` instead of using an `if`; 
and the symbols `m` or `g` to carry middle results are not local `val`, 
but lambda-variables in a `flatMap` or `map` block. This code is also
not applicative, as we cannot apply `gcd` to the `mod(a, b)` call, 
or add `1` to the recursive call.

Although we can use for-comprehensions to make 
`flatMap` and `map` blocks look like variable assignments, 
this syntax sugar is far from a direct and applicative style.

``` scala
      for
        m <- mod(a, b)
        g <- gcd(b, m)
      yield g + 1
```


##### Example: List map or traverse

Moving to the second example of mapping a list.
First, since we want our `map` to take a concurrent function, 
the  `fili` parameter now has the `Task[Z]` return type; 
and second, since our `map` is itself concurrent, it now has 
 `Task[List[Z]]` as its return type. Thus the code that follows:

``` scala
extension [Y] (list: List[Y])
  def mapT[Z](fili: Y => Task[Z]): Task[List[Z]] = list match
    case Nil => Task.pure(Nil)
    case y :: ys =>
      for
        z <- fili(y)
        zs <- ys.mapT(fun)
      yield z :: zs
```

This code example is no longer in a direct or applicative style. 
It also reveals a disadvantage of a Task type, that the signature
of this "map" does not match that of the map method of `List`.
This is why Task libraries give it another name, such as `traverse`. 
Even though its code, structure, and sequential semantics is very much
like `map`, we need to make a duplicate for it to work with Tasks.
This is not only a problem for `map`: filters, folds, or scans, of 
trees, vectors, or hashmaps, need to be duplicated as well. 
This is why using task libraries often requires learning two ways 
to do the same thing. 
Whether we choose to arrange those duplicate combinators 
in a type-class hierarchy, such as those in `scalaz` or `cats`, 
or whether we just place them in a companion "Task" object, 
the effort to learn these combinators is the same.

##### Third example: function combinations.

Since our `pipe` takes two functions as parameters, a concurrent-aware pipe
should handle three cases: only `tick` is concurrent, only `tock` 
is concurrent, or both are. Because of the `Task` type, each case 
has a different signature and needs to be a separate function.

``` scala
def map[X, Y, Z](tick: X => Task[Y], tock: Y => Z): X => Task[Z] =
  (x: X) => tick(x).map(tock)

def contramap[X, Y, Z](tick: X => Y, tock: Y => Task[Z]): X => Task[Z] =
  (x: X) => tock(tick(x))

def andThen[X, Y, Z](tick: X => Task[Y], tock: Y => Task[Z]): X => Task[Z] =
  (x: X) => tick(x).flatMap(tock)
```

We have used the names from the `Kleisli` class from the `cats` library.
Once again, we can no longer use a direct or applicative style
and have to use the `map` or `flatMap` methods of Task.
Much like we had to duplicate the list map into a list traverse, here 
we need to write one case for each combination of concurrent and normal functions.

## Proposed Solution

We propose adding coroutines to Scala. A coroutine is a function or procedure 
with the ability to suspend and resume its execution:

> [...] the fundamental characteristics of a coroutine [are that]
> the values of data local to a coroutine persist between successive calls; 
> [and] the execution of a coroutine is suspended as control leaves it, 
> only to carry on where it left off when control re-enters [later].
> [Revisiting Coroutines](https://dl.acm.org/doi/abs/10.1145/1462166.1462167)


Other languages encode coroutines or concurrency constructs using
keywords (such as `suspend` in Kotlin) or `async/await` syntax blocks. 
Instead, we propose to encode coroutines in Scala using contextual parameters, 
which does not require modifying the lexicon or grammar of Scala, 
nor adding any major features to its type system.

### High–Level Overview

Our encoding starts from observing that functions and coroutines
mean different things. This difference is metaphorically referred to as 
[the function color]((http://journal.stuffwithstuff.com/2015/02/01/what-color-is-your-function/)),
with normal non-concurrent functions called "green" (or blue),
and coroutines are called "red" functions.
The key constraint is that 
"_You can only call a red function from within another red function_".
Thus, any call to a coroutine can only occur either a) within the body
of another coroutine, or b) within a _launcher_ that handles the 
execution of those coroutines.
Scala, **we can encode this restriction with contextual parameters**. 
An contextual parameter requires any call to that function
to have an entry of that type in its implicit scope. For instance, 
to come from another function with a parameter of that type.
To see this technique, in the `gcd` example we can turn 
`isZero`, `mod`, and `gcd`, into coroutines by adding 
a contextual  `Suspend`  parameter:

``` scala
def isZero(n: Int)(using s: Suspend): Boolean = 
  s.shift(_.resume(n == 0))
def mod(num: Int, den: Int)(using s: Suspend): Int = 
  s.shift(_.resume(num % den))

def gcd(a: Int, b: Int)(using Suspend): Int =
  if isZero(b) then a else gcd(b, mod(a, b)) + 1
```

The `Suspend` parameter grants `isZero` and `mod` the ability to _suspend_
at some points and resume from there. This capability is used by the `shift`
primitive, which takes an expression that is to be evaluated concurrently.
By marking coroutines with contextual parameters, we can
use the type checker to enforce the constraint. 
We can also hide from the source code the `Suspend` argument 
in the calls to `isZero`, `mod`, and `gcd` (recursive).
Thanks to this feature, the body of our `gcd` coroutine is exactly the
same as that of the initial `gcd` functions, 
and is written in a direct and applicative style.

##### Example: color-transparent `map` HOF

We can use the same technique to turn the `map` for lists into a coroutine: 

``` scala
extension[A](list: List[A]) 
  def mapC[B](fili: A => Suspend ?=> B)(using Suspend): List[B] = 
    list match 
      case Nil => Nil
      case x :: xs => fili(x) :: xs.mapC(fili)
```

`mapC` is a coroutine, as indicated by its implicit `Suspend` parameter.
Its input function `fili` is also a coroutine, 
hence the implicit function `A => Suspend ?=> B` type.
Much like for `gcd`, the body of `mapC` is _almost_ like that of 
the initial `map`, and written in a direct applicative style.
However, like the `mapT` for tasks, this `mapC` has to be different
from `map` because their types do not match: `mapC` has `Suspend` parameters
but `map` does not; `mapC` is a coroutine, and `map` is not.

Our goal is to define a single `map` that acts as a normal HOF 
when applied to a normal function, and acts as a higher-order coroutine
when applied to a coroutines. 
This is known as a **color-transparent** function, in which the color 
of the inputs determine the color of the call.
Since the green `map` and the red `mapC` only differ in their `Suspend` 
parameters, all we need is to entangle those types.
Luckily, Scala has a tool to tie two types: parametric polymorphism.
Just as the result type `List[B]` is tied to the `fili` function by 
the `B` parameter, we can tie the `Suspend` of `fili` with the `Suspend` 
of `mapC` by using a "color" type parameter:

``` scala
sealed trait Color 
sealed trait Green extends Color
sealed trait Red extends Green

sealed trait Suspend[+ Col <: Color]
```

Intuitively, `Suspend` now is not the capability to suspend 
but a question: "_Can this function suspend?_". 
The `Col` type, which is one of the color types, gives the answer:
`Green` marks normal functions that cannot suspend, 
and `Red` denotes coroutines that can.
If we add a color parameter to the `map,` it looks thus:

``` scala
extension[A](list: List[A]) 
  def map[B, Col <: Color](fili: A => Suspend[Col] ?=> B)(using Suspend[Col]): List[B] = 
    list match 
      case Nil => Nil
      case x :: xs => fili(x) :: xs.map(fili)
```

We can apply `map` either to a green or to a red `fili` function.
The type system enforces the color constraint: a call to `map` with a
red `fili` function (`Col = Red`) requires a implicit `Suspend[Red]` 
in the scope of the call. On the other hand, if `fili` is a 
green function, then the `map` acts like a green function.
Unlike the example for task types, which requires different methods
`map` and `traverse` for task functions vs normal functions, 
here we have a single direct-style definition of `map`. 
To make our technique practical, green calls to this `map` should 
generate same code as a call to the initial `map`.

##### Color-Transparent Pipe

Since the `pipe` takes several functions as inputs, a color-transparent
`pipe` should take several "color" parameters, one for each input function. 
We refer to this as a **polychromatic** function:

``` scala
def pipe[X, Y, Z, C <: Color, D <: Color](
  tick: X => Suspend[C] ?=> Y,
  tock: Y => Suspend[D] ?=> Z
): X => Suspend[C & D] ?=> Z =
    (x: X) => tock(tick(x))
```

Here, the `Suspend` capability of `pipe` appears in its return type. 
Thus, `pipe` returns either an ordinary function or a coroutine as its result.
The intersection type `C & D` indicates that a call to `pipe` 
can be green only if both `tick` and `tock` are green but 
must be red if either `tick` is or if `tock` is.
In contrast to the `Task` type above, which requires one function 
for each mix of pure or concurrent functions, 
this color-transparent `pipe` coroutine covers all four cases.

#### Code transformation for coroutine

The essential feature of a coroutine is that, at some specific points,
it can suspend its execution and save its local variables, so that 
later in may restore those variables and resume its execution.
Thus, we need to preserve the state of a suspended coroutine, but we cannot
leave it in the stack of the running thread, because upon suspension the 
thread may _shift_ to do something else and pop the coroutine's frame,
The way to implement these features is to save the state of a suspended 
coroutine execution, with its local variables, in a heap object.
The essence of the code transformation is to split the code of the coroutine
into a set of state transitions applied to a "frame" object.

To do this, the code transformation has to: 1)
identify the **suspension points** of the coroutine, 2)
generate a `Frame` class to store the coroutine state 
between suspensions, 3)
generate a `runNextLeg` function to carry out the transition
between the entry, exit, and suspension points, 4)
generate an `init` method that builds the initial frame, 
and 5) finally transform every call to the coroutine
into a call to that `init` method. 
Let us see these steps as applied to the `gcd` example.

**1. Identify suspension points:** 
Both `isZero` and `mod` have a suspension point at the call to `shift`.
In the body of `gcd`, its suspension points are the call to `isZero`, to `mod`, 
and the recursive call to `gcd`.
Thus, the execution of a `gcd` may be suspended at one of these four states: 
at the beginning of the coroutine, at the call to `isZero`, 
at the call to `mod`, or at the call to  `gcd`. 

**2) Generate a Frame class:**
To indicate which of these states an execution is at, the `Frame` class 
for the `gcd` has a `state` field. It also has two fields for the parameters
`a` and `b` of `gcd`, and the fields `z`, `m` and `g` to store the 
intermediate results of each call.
The frame also has a `result` field, used to record errors or cancellations.
Finally, the `completion` field is used to signal a parent context
whenever the coroutine is suspended or ended. 
The methods `invokeSuspend` and `create` can be used as a hook
for the underlying launcher to handle the execution of the coroutine.

```scala
/**
 * @param result Any current or intermediate result between invocations
 * @param completion The launcher continuation, used for scheduling and returning results at launcher boundaries.
 */
class GcdFrame(
  var state: Int, 
  var a: Int, var b: Int, var z: Boolean, var m: Int, var g: Int, 
  var result: Throwable | Any | Null, 
  completion: Continuation[Any | Null]
) extends ContinuationImpl(completion, completion.context()){

  // Applies the runNextLeg generated state machine, possibly with a current result
  override def invokeSuspend(x: Either[Throwable | Any | Null]): Any | Null = ???

  // This is the hook of the coroutine for an external launchers.
  // Ideally, suspensions, errors and returns are propagated to the passed completion.
  override def create(value: Any | Null, completion: Continuation[Any | Null]) = ???
}
```
In general, though, the heap frame does not need to keep _all_ the
local variables of the coroutines, just those "lifetime" 
(from declaration to last use) spans over a suspension point.

**3) Generate a `runNextLeg` function:**
This function resumes a coroutine from the state, 
given as a `Frame` object, in which it was suspended.
The function executes the next leg of the coroutine code 
until it reaches either another pause or the exit point. 
We look at [the `runNextLeg` procedure for `gcd`](https://gist.github.com/diesalbla/62677eac3ff10edab745b91b58a48f29), which was generated by our prototype implementation.
The method has one `case` for each suspension point.
The body of each `case` loads the local variables from the frame, 
and runs the operations that follow from the suspension point.
If it approaches another suspension, it stores the local variables
into the frame object, updates the label, informs the parent 
context (the `completion` in the frame) about the suspension,
and returns a `Suspended` marker. 
Once things have been resumed, it informs the `completion` about 
the resumption, checks for errors, loads the local variables 
from the frame fields, and moves on.
The transitions of the state machine emulates the strict order 
of operations that there was in the `gcd` code: 
so `isZero` precedes the `if` test, the `mod` call precedes 
the recursive call, etc.

**4) Generate an initialisation method**, 
that takes the parameters of the coroutine, create a frame 
in an initial state, with the given parameters, 
and calls to the `runNextLeg` function. 
This initialisation also takes a _completion_ parameter,
used to notify the caller context (coroutine or launcher)
if and when the coroutine execution ends or is suspended.
Note: our prototype combines this and the `runNextLeg` 
function one method, as microoptimisation.

**5) Transform each coroutine call** into a call to that 
_initialisation_ method, passing to it the arguments 
of the call together with a _completion_ parameter 
that refers to its caller context.

#### Lanes for Color-transparent functions

A color-transparent HOF, like `map`, should be usable either as a 
normal HOF or as a coroutine, depending on the functions we give it.
To do this, a color-transparent HOF is compiled, in the backend,
to two variants that we refer to as its green or red lane.
- The **green lane** serves calls that are made with a green function,
  for which we can use the HOF as if it was a routine. This lane should have
  the same object code as the HOG would if there were no `Suspend` parameters.
- The **red lane** serves calls in which the HOF is used as a coroutine that
  takes as input a coroutine. This lane consists of a `Frame` class and a 
 `runNextLeg` function, generated using the same code transformation as before.
  Its suspension points would be the calls to the input functions.

Once the lanes are in place, each call to the HOF is routed to the lane
that matches the color of the call by transforming the call.

#### Polychromatic functions

Two lanes are enough for `map` because the `map` takes a single color 
parameter for its single function input.
On the other hand, the `pipe` takes _several_ functions,
each of which can be green or red. 
If we were to build a lane for each color combination, we would need four lanes.
For more advanced combinators, the number of lanes would grow 
exponentially with the number of input functions. 
Since generating the code of all those lanes would not scale, 
we retrict code generation to two lanes: 
a full-green lane, to be used only if _all_ input functions are green,
and a full-red lane, to be used if _any_ of them is a coroutine.
The code of the latter would take all parameters as coroutines. 
If a call mixes green and red functions, the generation should
transform the green ones into red functions. 
This can be done by wrapping them in a closure with the signature of 
the red function but whose code just calls to the green function.

#### Launchers

To call a coroutine from a green function, we need a component,
the **Launcher**, to introduce an implicit `Suspend` marker.
The launcher implements the execution of coroutines,
by defining how to handle the calls to `shift`, `resume`, `raise`,
`invokeSuspend`, or `intercepted`.

Launchers tackle the major concerns of a concurrency library:

* **Cancellation**, which allows one or several coroutines,
  in a suspended or resumable state, to be stopped once
  their results and processes are no longer needed.
* Thread-shifting **blocking** operations, such as system calls, 
  to different OS threads in a low-frequency thread pool.
* **Scheduling** of coroutines amongst several threads, 
  so independent tasks may progress in parallel and thus
  achieve better throughput.
* We can improve **fairness** between competing tasks, 
  so that each one receives a share of CPU time,
  by switching tasks upon each call to `shift` or `resume.`
* **Structured Concurrency**, the guarantee that no 
  coroutine execution can outlive the lifetime or context
  of its spawning parent context.

Because each use case needs a different balance of these concerns, 
programmers need to write and use diverse launchers. 
To this end, we leave launchers as an open-ended part,
to be implemented as library code. A launcher extends this interface:

```scala
trait Launcher: 
  def cancel(message: String): Unit
  def cancel(message: String, cause: Throwable): Unit
```

We would include the following general-purpose launchers:

* An unsafe "fire-and-forget" launcher that evaluates a coroutine 
  immediately, without scheduling or error reporting.
  This launcher does not await on asynchronous executions,
  and it only has some short-circuiting error handling.
  It is unstructured, in that any coroutines started under may 
  keep running after this launcher has finished.
  This launcher should only be used for **unsafe** operations.
  This allows us to implement parallelism, as described below.
* A strucuterd deferred launcher that allows for parallel 
  execution of coroutines in collections.
* A structured launcher that shifts blocking operations
  (such as system calls) to a blocking threadpool. 
* An _uncancellable_ launcher to create nested uncancellable regions, 
  which is useful to wrap resources with finalizers.

#### Deferred launchers and parallel map

The color transparent `map`, if used as a coroutine,
suspends whenever the call to the `fili` function suspends.
The `map` is still _sequential_ in processing the list elements: 
until and unless the call to `fili` on the head element finishes, 
the `map` cannot start the call on `fili` on the next element.
In addition to this sequential map, programmers may also want a _parallel_ 
form of map, one that may start several calls to `fili` on several items
in the list, so that the map may progress even while one call suspends.
Some "task" libraries refer to this as a `parMap` or `parTraverse`.
For coroutines, we can implement such a `parMap` by means of special 
parallel launchers. For instance, our `ParMapLauncher` would keep a list of 
frames for the calls to `fili` that are started. As each call ends, the 
launcher would collect its result to the list and start the next one.
This way to add parallelism, via special launchers, resembles the use 
of scopes in [JEP 453 for Java](https://openjdk.org/jeps/453).

### Specification

We add the following declarations to the standard library
in a new `scala.coroutine` package.

``` scala
package scala.coroutine

sealed trait Color
sealed trait Green extends Color
sealed trait Red extends Green

trait Suspend[+ C <: Color] 

sealed trait Suspend:
  inline def shift[A](f: Continuation[A] => Unit)): A

```

As mentioned before, `Suspend[Col]` encodes the potential
to suspend, which is the mark of a coroutine.
The traits `Red` and `Green` indicate if it does suspend or not. 
The subtype relationship between them, together with the covariance
of `Suspend`, ensures that a normal functions cannot call a coroutine.
The `shift` uses the `Continuation` type, defined as follows: 

``` scala
trait Continuation[- A]
  type Ctx <: Tuple
  val executionContext: ExecutionContext
  def context(): Ctx
  def resume(value: A): Unit
  def raise(error: Throwable): Unit
  def contextService[T](): T | Null =
    context().toList.find(_.isInstanceOf[T]).map(_.asInstanceOf[T]).orNull

object Continuation:
  enum State:
    case Suspended, Undecided, Resumed

end Continuation

```

The `contextService`is used to lookup services on the `context` tuple.
This helps us to implement interception in launchers.
The `executionContext` is used by launchers to schedule
suspended definitions to underlying threads. The `raise` and `resume`
are used to complete coroutines in user-defined code.

#### Provided Launchers

We propose providing three basic `Launcher` 
subtypes: `Wrapped,` `Unwrapped,` and `Unsafe.`

```scala
trait Unsafe extends Launcher:
  def apply[A](block: Continuation[Any] ?=> A): Unit

trait Unwrapped extends Launcher:
  def apply[A](block: Continuation[Any] ?=> A): A
  
trait Wrapped extends Launcher:
  def apply[F[_], A](block: Continuation[Any] ?=> A): F[A]
```

An `Unsafe` launcher runs the continuation and returns immediately to
the call site with `Unit.` `Unsafe` launchers allow the user to ignore the
computational results of the coroutines launched within its apply -- to run the continuation
as a side effect without concern for its return values or internal
blocking behavior. The user or programmer has complete control over
how an unsafe launcher operates. Calls to cancel
are to be handled by the implementor of the `Unsafe` launcher.

An `Unwrapped` launcher runs the continuation, blocks at the call site,
and returns its result directly, raising any unhandled exceptions. For
example, calling a
`SomeUnwrappedLauncher(List.map(someSuspendedRedDefinition))` blocks
at each iteration in `map` and returns its final results as an `A` vaulue. 
Any internal calls in `someSuspendedRedDefinition` may thread-switch 
or delegate to other launchers for suspended definitions within the calls, but the
block itself must produce a blocking result of ype `A` as its return value. 
Calls to `cancel` are guaranteed to cancel any
continuations launched within the apply.

A `Wrapped` launcher will run the continuation and return its result
in some wrapper object, such as a `Future,` `IO,` `Deferred,` or
`Promise` that will give users of the launcher access to additional
capabilities dependent upon the object return type(s)
capabilities. The block must return a value of `A`, 
which must then be wrapped in `F` before `apply` exits.
Calls to cancel will delegate to the wrapper(s)
cancellation semantics, and block until those cancellations are
guaranteed to have executed at exit.

##### Schedulers

**schedulers** for structured and wrapped launchers can adopt 
existing ones such as those in `cats-effect`.
In our launchers we would use the calls to `shift` as yield-points,
to balance fairness. 
The  `Suspended` state can be used to identify the analogous
scheduling boundaries and to maintain processor affinity.

A Cached threadpool makes sense for the blocking `Unwrapped` launcher
described above.

The semantics of **Cancellation** vary from launcher to launcher, 
but any structured launcher must guarantee cancellation of all 
nested coroutines or launchers, by propagating any `CancellationException`
that may be raised.

#### Front-End Changes

The proposal does not add new keywords or lexical categories, or new constructs to the syntax.
It does not require any new features to the type system, 
but it may be convenient to add some constraints:

* The `Suspend` capability should only pass through a chain of method calls.
  The compiler should not allow a program to store it in a class field or
  capture it in a closure. In other words, the compiler should ensure it 
  cannot _escape_ from a method execution.
* The `Suspend` type may only appear for context parameters, bounds, or in context function types. 
* The type literals `Red` and `Green` can only appear as arguments to the `Suspend` type, 
  and the `Color` trait should only appear as a bound of the type parameter.

We may be able to enforce some of these constraints using
[erased definitions](https://dotty.epfl.ch/docs/reference/experimental/erased-defs.html),
but this feature is still experimental at the time of writing.

It may help us to add a compiler transformation rule to 
lift any normal function type (e.g. `Int => Char,` 
into a green function type  `Int => Suspend[Green] ?=> Char` 
to ensure methods are color-transparent.

#### Compiler transformation for coroutines (red functions)

We model the code transformation after the code transformation in Kotlin. 
Some useful references are the paper 
[Kotlin Coroutines, design and implementation](https://dl.acm.org/doi/abs/10.1145/3486607.3486751),
as well as _The Ultimate Breakdown of Kotlin Coroutines_, 
[Part 1](https://ilmirus.blogspot.com/2021/01/the-ultimate-breakdown-of-kotlin.html),
[Part 2](https://ilmirus.blogspot.com/2021/01/the-ultimate-breakdown-of-kotlin_31.html),
[Part 3](https://ilmirus.blogspot.com/2021/02/the-ultimate-breakdown-of-kotlin.html),
[Part 4](https://ilmirus.blogspot.com/2021/02/the-ultimate-breakdown-of-kotlin_1.html),
and [Part 5](https://ilmirus.blogspot.com/2021/07/the-ultimate-breakdown-of-kotlin.html).

Our prototype compiler plugin adds this transformation between the "Staging" 
and "PickleQuotes" phases of the compiler. The transformation has to remove
old method symbols in favor of the compiler-transformed symbols, 
to generate the Frame classes. 
This is why we could not use Scala 3 macros, as these cannot remove symbols.

In the Scala library, in a `scala.coroutine.impl`, we add enough primitives 
to allow coroutines to communicate with launcher libraries for execution. 
For context, in our prototype, the 
[following definitions](https://gist.github.com/diesalbla/5fadcd75d075617564b4aad403fbf810)
are necessary to complete these tasks. 
These allows us to write new launchers, which we are then integrated with 
the `runNextLeg` methods of each transformed coroutine, so as to control 
the scheduling and interception from `Launchers`.

These may also need to be platform-target specific and our prototype definitions 
only represent one possible implementation. While the automated transformation 
only uses the abstract methods `invokeSuspend` and `create`, users can provide new 
implementations as part of library code in `Launchers`. 
For instance, here is [an example blocking launcher](https://gist.github.com/diesalbla/9bedd409121e6f116ea1f00212234a0a)
that implements the Launcher API. 

### Compatibility

We developed this prototype separately and without knowing, at first, 
about the ["Async Strawman"](https://github.com/lampepfl/async) library.
As a result, there are some differences between them.
In this SIP, we have chosen an API closer to Kotlin implementation because 
it is proven to work, can be delivered, and has been adopted successfully, 
and the library interface is experimental.

We have designed coroutines to be encoded with context parameters
to avoid changing the grammar of Scala.
We propose these changes to be an opt-in features so as to reduce 
the risk of incompatibility altogether.

The types `Color,` `Green,` `Red,` and `Suspend` are added to a new
package in the standard library, out of the prelude,
to prevent name collisions with existing code. 
We ensure that coroutines are opt-in by adding an experimental
package for coroutines in teh standard library.

**Scala Collection Library.** 
With this SIP, we can redefine all the HOF in the standard library, 
such as the `map,` `filter,` `fold,` and `foreach` of collections, 
into color-transparent functions. 
We can also write overriding extensions that implement those HOFs
as part of a new `async` package.
Given the reach of the standard library, that may be a major
inconvenience for adoption and need some clarification.
We prefer to leave such an upgrade out of this SIP and as future work.

### Other concerns

Because this feature is built into the compiler's middle phases, 
it generates usable code across platforms.

#### Structured Concurrency

[Structured concurrency](https://openjdk.org/jeps/453) refers 
to the idea of making the dynamic relationship between concurrent
tasks look like that between procedures: 

> An invoked method [...] cannot outlive the method that invoked it, 
> nor can it return or throw an exception to a different method.
> Thus all subtasks finish before the task, each subtask is a child of its parent,
> and the lifetime of each subtask relative to the others and to the task
> is governed by the syntactic block structure of the code.

Provided that the launchers implemented enforce this constraint, that any 
spawned task cannot outlive its launcher, the coroutines proposed here
should be a structured form of concurrency.

#### Performance

One of the motivations behind the push for concurrency is that it allows
a process to split tasks amongst several cores in a CPU, 
and thus improve performance by using this parallelism.
However, creating those tasks has some overheads, such as the
memory used to represent those tasks and store their state in the heap.
The task libraries can use more heap memory because
each essential operation or chain thereof is a separate `Task` object. 
Some aspects of a state-machine implementation may need less memory.
For instance, it only allocates one object of the frame class 
per coroutine call, no matter how many suspension points it has.

### Open questions

## Alternatives


#### Platform-specific solutions

Some of the runtime platforms that Scala code is compiled into,
such as JVM Bytecode, JavaScript code, or native binaries, 
have concurrency solutions of their own.
Modern versions of the JVM and the Java library feature
[virtual threads](https://openjdk.org/jeps/444) 
and [structured concurrency](https://openjdk.org/jeps/437).
Scala programs that run on the JVM can already use these features; 
either through the [Java API](https://download.java.net/java/early_access/jdk20/docs/api/jdk.incubator.concurrent/jdk/incubator/concurrent/StructuredTaskScope.html)
or through an idiomatic wrapper like the [`ox` library](https://github.com/softwaremill/ox).
There is also some [ongoing work](https://github.com/scala-native/scala-native/pull/3286)
to support concurrency in Scala-Native.
Concurrency should be compatible across all runtimes 
and portable at the source-code level 
so that programmers do not need to learn separate interfaces 
to adapt their code to each platform.

#### Exceptions and Instrumentation

Most languages support exceptions. Throwing an exception pops all the stack
frames between throw and catch. 
The program cannot resume execution from the `throw` point exception.
However, if every method in the stack intercepts the exception, 
attaches a snapshot of its Frame to it, and throws it on,
then the exception would become a record of that stack.
Once we have this record, we could apply a code transformation that 
generates an auxiliary procedure to resume from the snapshot
for each method in that chain of "failed" calls. 
A similar idea was presented in
[_Continuations from generalized stack inspection_](https://dl.acm.org/doi/10.1145/3236771).
This approach has the benefit of making all HOF color-transparent
at a stroke and with no changes to their source code,
although calls to those functions given as arguments would need to be wrapped
in a similar exception handler that captured the snapshot.
This approach could be more efficient than our solution, 
since it does not  store the stack frames in the heap 
until and unless there is a suspension.
However, we prefer to avoid it for the following reasons.
On the one hand, a solution that relies on exception an handling mechanism 
could be too brittle and subject to bugs. 
For instance, a programmer could, too casually, write a catch statement
so wide so as to interfere with the instrumentation.

#### Syntax Caramel

Some macro libraries and compiler plugins offer a form of syntax sugar,
closer that for comprehensions to a direct style look, 
which help programmers to use `Task` types. Some of these are
[`scala-async`](https://github.com/scala/scala-async), 
[`monadless`](https://github.com/monadless/monadless),
[`better-monadic-for`](https://github.com/oleg-py/better-monadic-for),
[`dotty-async-cps`](https://github.com/rssh/dotty-cps-async), 
[`cats-effect-cps`](https://github.com/typelevel/cats-effect-cps),
or [`zio-direct`](https://github.com/zio/zio-direct).
Others have proposed improving for comprehensions, such as with
["Quiet for-comprehensions"](https://contributors.scala-lang.org/t/quiet-for-comprehensions/6160),
["Comprehensive function applications"](https://contributors.scala-lang.org/t/pre-sip-comprehensive-function-applications/5902),
["If comprehensions"](https://contributors.scala-lang.org/t/suggestion-if-comprehensions/6140), 
or ["Improve for-comprehensions"](https://contributors.scala-lang.org/t/pre-sip-improve-for-comprehensions-functionality/3509).
All of these ideas have merit, can be of great help, and some are easy to implement.
However, they do not solve the root problem that using task types means
having different incompatible types between "sequential" and concurrent 
expressions with distinct methods and operators.

## Related work

This SIP follows an engaging 
[PRE-SIP](https://contributors.scala-lang.org/t/pre-sip-suspended-functions-and-continuations) 
thread from last year.
Before that, [SIP-22](https://github.com/scala/improvement-proposals/blob/24738716f160d5a5dc14fdc889d2e9b512b128e0/content/async.md)
proposed adding `async` / `await` constructs into the language, 
but those were just special syntax sugar to use the `Future` datatype.

#### Prototype 

We (the SIP authors) have started to write a 
[prototype implementation of this SIP](https://github.com/47deg/scourt).
This prototype includes the basic types to implement coroutines, 
such as `Suspend` and `Continuation`. 
At the time of publishing the SIP, we have not added support
for colour-transparent functions as described.
This prototype was started in parallel and without knowing about 
the ["Async Strawman"](https://github.com/lampepfl/async) library.
As a result, there are some API differences with that library.
Since that library is currently experimental, 
in this SIP we have chosen to use Kotlin implementation, 
because we know that it works, it can be delivered, 
and it has been adopted successfully.


### Other languages

Our proposal is inspired by Kotlin suspended functions, 
which the Kotlin maintainers discuss in the paper
[_Kotlin coroutines: design and implementation_](https://dl.acm.org/doi/10.1145/3486607.3486751).
Java recently introduced [virtual threads](https://openjdk.org/jeps/444) 
and [structured concurrency](https://openjdk.org/jeps/437).
Unlike coroutines, these are a form of preemptive, not cooperative, concurrency.
The use of polymorphism to encode color-transparent functions 
was inspired by the ["linearity polymorphism" in Haskell](https://www.youtube.com/watch?v=5mxKEYzBAVk).

## FAQ

