---
layout: sip
permalink: /sips/:title.html
stage: implementation
status: waiting-for-implementation
title: SIP-50 - Struct Classes
---

**By: Julien Richard-Foy**

## History

| Date          | Version            |
|---------------|--------------------|
| Oct 20th 2022 | Initial Version    |

## Summary

We introduce “struct classes”, a new flavour of class definitions that is a middle
ground between regular classes and case classes. Like case classes, struct classes
structurally implement `equals`, `hashCode`, and `toString`. However, unlike with
case classes, the companions of struct classes don't have `apply` and `unapply`
methods. This allows developers to benefit from structural equality while still
having the possibility of making evolutions (e.g., adding a new field) in a
backward binary compatible way.

Here is a quick example that illustrates the definition and usage of a struct class:

~~~ scala
struct class User(name: String, age: Int):
  def withName(newName: String): User =
    copy(name = newName) // "copy" is a private method synthesized by the compiler
  def withAge(newAge: Int): User =
    copy(age = newAge)

val alice = User("Alice", 42)
val bob   = User("Bob", 18)
println(bob) // prints "User(Bob, 18)"
assert(alice != bob)
val updatedBob = bob.withAge(bob.age + 1) // constructor parameters are public members
assert(updatedBob == User("Bob", 19)) // structural equality
~~~

Then, it is possible to define a new version of the class definition `User`, with
a new field, and without breaking the backward binary compatibility:

~~~ scala
// new field "email"
struct class User(name: String, age: Int, email: Option[String]):
  // old constructor added for compatibility
  def this(name: String, age: Int): User = this(name, age, email = None)
  def withName(newName: String): User = copy(name = newName)
  def withAge(newAge: Int): User = copy(age = newAge)
  // new convenient transformation methods
  def withEmail(newEmail: String): User = copy(email = Some(newEmail))
  def withoutEmail: User = copy(email = None)
~~~

## Motivation

### Introductory Example Based on Case Classes

Currently, case classes are often preferred to simple classes to model data
structures because they provide convenient features:

- concise syntax at the definition and usage sites (direct field access, `copy`
  method)
- structural implementation of `equals`, `hashCode`, and `toString`
- support for "constructor patterns" in match expressions.

However, case classes have a major drawback: they can hardly evolve in a binary
compatible way. Adding or removing fields to a case class almost always breaks the
binary compatibility.

Consider for example the following case class definition `User` and its usage:

~~~ scala
case class User(name: String, age: Int)

val alice = User("Alice", 42)
val updatedAlice = alice.copy(age = alice.age + 1)
assert(updatedAlice == User("Alice", 43))
updatedAlice match
  case User(_, age) => println(s"Alice is $age years old")
~~~

We would like to add a new field, say `email`, without breaking existing code that
relies on the above definition of `User`.

A first attempt is to provide a default value, so that existing code would always
use that default value:

~~~ scala
case class User(name: String, age: Int, email: Option[String] = None)
~~~

Unfortunately, this does not work because the new definition of `User` has a
different constructor type signature from the former definition of `User`. As a
consequence, existing code constructing instances of `User` will have to be
recompiled with the new definition of `User`.

One way to fix this problem would be to re-introduce the old constructor signature
as a secondary constructor:

~~~ scala
case class User(name: String, age: Int, email: Option[String]):
  def this(name: String, age: Int): User = this(name, age, email = None)
~~~

Now, the previous constructor is still in the ABI of `User`, however this is still not
enough because we also have a problem with the signature of the compiler-generated
method `copy`. Indeed, the new version of `copy` is not binary compatible with the
former one.

One way to fix this problem would be to make the primary constructor `private`.
If we do that, the compiler propagates the `private` visibility to the generated
`copy` method as well. However, if we make it private, we have to provide public
transformation methods allowing users of the class to transform instances.

This means that the case class should have been designed from the beginning with
a `private` constructor:

~~~ scala
case class User private (name: String, age: Int):
  // public transformation methods
  def withName(newName: String): User = copy(name = newName)
  def withAge(newAge: Int): User = copy(age = newAge)
  
object User:
  // public constructor
  def apply(name: String, age: Int): User = new User(name, age)
~~~

With this definition of `User`, our example program would look as follows:

~~~ scala
val alice = User("Alice", 42)
val updatedAlice = alice.withAge(alice.age + 1)
assert(updatedAlice == User("Alice", 43))
updatedAlice match
  case User(_, age) => println(s"Alice is $age years old")
~~~

Then, adding the `email` field would be achieved as follows:

~~~ scala
case class User private (name: String, age: Int, email: Option[String]):
  // public constructor
  def this(name: String, age: Int): User = this(name, age, email = None)
  // public transformation methods
  def withName(newName: String): User = copy(name = newName)
  def withAge(newAge: Int): User = copy(age = newAge)
  def withEmail(newEmail: Option[String]): User = copy(email = newEmail)
  
object User:
  // public constructor (only needed for binary compatibility)
  def apply(name: String, age: Int): User = new User(name, age)
~~~

Unfortunately, the new definition of `User` is still not compatible with the previous
one. Indeed, existing code that was using pattern matching does not compile anymore
because the type `User` now has three fields instead of two, so the compiler would
fail with a message like "Wrong number of argument patterns for User; expected
(String, Int, Option[String])".

Note that the type signature of the method `unapply` has not changed, meaning that
it would be possible to run existing code (without recompiling it) with the new
classfile of `User` with no issues. However, this works only when we add new
fields, not when we remove them.

There is a way to disable pattern matching on the case class by explicitly defining
the `unapply` method to be `private`:

~~~ scala
// (previous code has been omitted for brevity)
object User:
  private def unapply(user: User): User = user
~~~

When we do this, the compiler does not generate the `unapply` method, which makes
it impossible for users of the class `User` to use it in match expression in
"constructor patterns". Users can still use "typed patterns", though:

~~~ scala
updatedAlice match
  case user: User => println(s"Alice is ${user.age} years old")
~~~

For reference, here is the complete definition of `User` so that we can add or
remove fields without breaking the binary compatibility:

~~~ scala
case class User private (name: String, age: Int, email: Option[String]):
  // public constructor
  def this(name: String, age: Int): User = this(name, age, email = None)
  // public transformation methods
  def withName(newName: String): User = copy(name = newName)
  def withAge(newAge: Int): User = copy(age = newAge)
  def withEmail(newEmail: Option[String]): User = copy(email = newEmail)
  
object User:
  // public constructor (only needed for binary compatibility)
  def apply(name: String, age: Int): User = new User(name, age)
  // explicit extractor
  private def unapply(user: User): User = user
~~~

So, it _is_ possible to define case classes and make them evolve without breaking
the backward binary compatibility, but that somewhat requires "undoing" some of
the things the compiler does when we define case classes.

Would it be simpler to go the other way around? Namely, not use a case class in the
first place, and manually "re-do" some of the things the compiler does for case
classes.

### Using Regular Classes

The first version of the type `User`, with support for structural equality,
and JVM-based serialization, can be defined as follows:

~~~ scala
class User(val name: String, val age: Int) extends Serializable:
  private def copy(name: String = name, age: Int = age): User = new User(name, age)
  // structural implementation of "toString", "equals", and "hashCode"
  override def toString(): String = s"User($name, $age)"
  override def equals(that: Any): Boolean =
    that match
      case user: User => user.name == name && user.age == age
      case _ => false
  override def hashCode(): Int =
    37 * (37 * (17 + name.##) + age.##)
  // transformation methods
  def withName(newName: String): User = copy(name = newName)
  def withAge(newAge: Int): User = copy(age = newAge)
~~~

This definition can be used by our example program as follows:

~~~ scala
val alice = User("Alice", 42)
val updatedAlice = alice.withAge(alice.age + 1)
assert(updatedAlice == User("Alice", 43))
updatedAlice match
  case user: User => println(s"Alice is ${user.age} years old")
~~~

To add the new field `email`, we would make the primary constructor `private`
and introduce a secondary constructor with the same signature as the former one.
We would also update the implementations of `toString`, `equals`, and `hashCode`.

~~~ scala
class User private (val name: String, val age: Int, val email: Option[String]) extends Serializable:
  // public constructor that matches the signature of the previous primary constructor
  def this(name: String, age: Int): User = this(name, age, None)
  private def copy(name: String = name, age: Int = age, email: Option[String] = email): User = new User(name, age, email)
  // structural implementation of "toString", "equals", and "hashCode"
  override def toString(): String = s"User($name, $age, $email)"
  override def equals(that: Any): Boolean =
    that match
      case user: User => user.name == name && user.age == age && user.email == email
      case _ => false
  override def hashCode(): Int =
    37 * (37 * (37 * (17 + name.##) + age.##) + email.##)
  // transformation methods
  def withName(newName: String): User = copy(name = newName)
  def withAge(newAge: Int): User = copy(age = newAge)
  def withEmail(newEmail: Option[String]): User = copy(email = newEmail)
~~~

Adding one field requires adding it to the `private` constructor, to the `private`
method `copy`, to the `toString` implementation, to the `equals` implementation,
to the `hashCode` implementation, and to add a transformation method (here,
`withEmail`). This arguably a lot of effort.

Are there any other solutions?

### Using Code Generation or Meta-Programming

Other solutions used by the community are based on code generation or meta-programming.

[Contraband](https://eed3si9n.com/contraband-an-alternative-to-case-class/) is an
example of tool that generates "case class like" definitions from an _ad-hoc_
interface definition language. Approaches based on code generation have the following
drawbacks:
- the definitions of the data types use another language than Scala that you need
  to learn and get familiar with the surrounding tooling
- they require customizing the build definition to correctly generate the sources
- they are often not well supported by IDEs
- they make the code harder to navigate through.

The last type of solutions are based on meta-programming facilities.
[data-class](https://github.com/alexarchambault/data-class) and
[scalameta](https://github.com/scalameta/scalameta/blob/01cb1137cac89d1453846ef1e7acb8f4a8833e6c/scalameta/common/shared/src/main/scala/org/scalameta/data/data.scala)
are two examples of such solutions. They seem to be hard to maintain(see e.g.
[data-class#120](https://github.com/alexarchambault/data-class/issues/120) and
[scalameta#2485](https://github.com/scalameta/scalameta/issues/2485)). Also, IDE
support of meta-programming-based approaches is not always good.

### Scope of the Proposal

We would like to support, at the language level, the definition of data types
that would support the following features:
- can evolve in a binary compatible way (ie, developers can define a new version
  of the data type, with added or removed fields, in a backward binary compatible
  way)
- structural implementation of `equals`, `hashCode`, `toString`
- support of JVM-based serialization
- primary constructor parameters promoted to public fields

## Proposed solution

A new "flavour" of class definition: `struct class`.

### High-level overview

Here is how to define the first version of the type `User` used in the Motivation
section, with a `struct class`:

~~~ scala
struct class User(name: String, age: Int):
  def withName(newName: String): User = copy(name = newName)
  def withAge(newAge: Int): User = copy(age = newAge)
~~~

This defines a type `User` with a public constructor, and two public fields `name`
and `age`. The type `User` automatically extends `Serializable`, and automatically
defines a private method `copy` (similar to what a case class would have). It also
structurally override the implementation of `equals`, `hashCode`, and `toString`.

Here is how the type `User` could be used:

~~~ scala
// public constructor
val alice = User("Alice", 42)
// constructor parameters are public members
val updatedAlice = alice.withAge(alice.age + 1)
// structural equality
assert(updatedAlice == User("Alice", 43))
// only typed patterns are supported in match expressions
updatedAlice match
  case user: User => println(s"Alice is ${user.age} years old")
~~~

Then, developers can define the following new version of `User` with an added
field `email`:

~~~ scala
struct class User(name: String, age: Int, email: Option[String]):
  // manually add former constructor
  def this(name: String, age: Int): User = this(name, age, email = None)
  def withName(newName: String): User = copy(name = newName)
  def withAge(newAge: Int): User = copy(age = newAge)
  // transformation method to change the email
  def withEmail(newEmail: Option[String]): User = copy(email = newEmail)
~~~

And the existing program that uses the former definition of `User` is still binary
compatible with the new version of `User`.

Similarly, developers can define yet another version of `User` where the `email`
field would have been removed, and still keep the backward binary compatibility:

~~~ scala
struct class User(name: String, age: Int):
  // manually add former constructor
  def this(name: String, age: Int, email: Option[String]): User = this(name, age)
  def withName(newName: String): User = copy(name = newName)
  def withAge(newAge: Int): User = copy(age = newAge)
  // "email" methods, for compatibility
  def email: Option[String] = None
  def withEmail(newEmail: Option[String]): User = this
~~~

Note that the fact that we explicitly define the transformation methods (here,
`withName`, `withAge`, etc.) may be seen as a lack of "language support". On the
contrary, this gives more flexibility to the developers to define custom transformation
methods. For instance, the following snippet shows two possibilities of transformation
methods to set the value of the `email` field:

~~~ scala
// 1st solution, as above
def withEmail(newEmail: Option[String]): User
// 2nd solution
def withEmail(newEmail: String): User
def withoutEmail: User
~~~

Here, in the 2nd solution, calling `withEmail` would always set the email to
`Some(newEmail)`, and calling `withoutEmail` would set it to `None`. We can imagine
similar convenience methods for fields containing collections of values.

Internally, a `struct class` is implemented in a similar way as a `case class`: the
compiler synthesizes the `private` method `copy`, and overrides the default implementations
of `toString`, `hashCode`, and `equals`. For reference, here is what the definition
of the first version of `User` "desugars" to:

~~~ scala
import scala.util.hashing.MurmurHash3

class User(val name: String, val age: Int) extends Serializable:
  private def copy(name: String = name, age: Int = age): User = User(name, age)
  override def toString() = s"User($name, $age)"
  override def hashCode() =
    MurmurHash3.finalizeHash(
      MurmurHash3.mix(MurmurHash3.mix(MurmurHash3.productSeed, name), age),
      2
    )
  override def equals(that: Any): Boolean =
    that match
      case user: User => user.name == name && user.age == age
      case _ => false

  def withName(newName: String): User = copy(name = newName)
  def withAge(newAge: Int): User = copy(age = newAge)
end User
~~~

### Specification

A struct class is a `class` definition prefixed with `struct`:

~~~ text
TmplDef ::= 'struct' 'class' ClassDef
~~~

A struct class is required to have a parameter list that is not contextual. The
formal parameters in the first parameter list are called _elements_ and are treated
specially. A `val` prefix is implicitly added to such a parameter, unless the parameter
already carries a `val` or `var` modifier. Hence, an accessor definition for the
parameter is generated.

A struct class definition `c[tps](ps_1)...(ps_n)` with type parameters `tps` and
value parameters `ps` is handled as follows. A `private` method named `copy` is
implicitly added to the class definition unless the class already has a member
(directly defined or inherited) with that name, or the class has a repeated parameter.
The method is defined as follows:

~~~ scala
private def copy[Ts](ps_1')...(ps_n'): c[Ts] = c[Ts](xs_1)...(xs_n)
~~~

Where `Ts` is the vector of types defined in the type parameter section of the class
definition. Each `xs_i` denotes the parameter names of the parameter section `ps_i`.
The value parameters `ps_1j'` of the first parameter list have the form
`x_1j: T_1j = this.x_1j`. The other parameters `ps_ij'` of the `copy` method are
defined as `x_ij: T_ij`. In all cases, `x_ij` and `T_ij` refer to the name and type
of the corresponding class parameter `ps_ij`.

Every struct class implicitly overrides some method definitions of class `scala.AnyRef`
unless a definition of the same method is already given in the struct class itself or
a concrete definition of the same method is given in some base class of the struct
class different from `AnyRef`. In particular:


- Method `def equals(that: Any): Boolean` is structural equality, where two instances
  are equal if they both belong to the struct class in question and they have equal
  (with respect to `equals`) constructor arguments (restricted to the class’s elements,
  i.e., the first parameter list)
- Method `def hashCode(): Int` computes a hash-code. If the `hashCode` method of the
  data structure members map equal (with respect to `equals`) values to equal hash-codes,
  then the struct class `hashCode` method does too
- Method `def toString(): String` returns a string representation which contains the
  name of the class and its elements.

Finally, mirrors (instances of `scala.deriving.Mirror`) are not synthesized by the
compiler for struct classes.

### Compatibility

The changes described in this proposal are backward binary compatible because they do
not affect the bytecode produced by the existing language features.

The changes described in this proposal are backward TASTy compatible because they
don’t require any changes at the TASTy level.

Source compatibility is preserved, and the semantics of existing valid programs is not
changed because the proposal does not change the existing language features, it only
adds a new keyword, `struct`.

## Alternatives

Besides the two solutions shown in the Motivation section (based on regular classes
or case classes), we also considered a more powerful variant of `struct class` that
would also automatically generate the transformation methods (`withName`, `withAge`, 
etc. in the example). However, this solution is more complex to specify, and it also
has some annoying drawbacks. In particular, it does not work well with all the names,
especially with symbolic names (e.g., `+`, `%`, etc.), or names that contain
acronyms (e.g. consider a field `httpHeaders`, should the transformation method
be named `withHttpHeaders` or `withHTTPHeaders`?). Furthermore, it would be extremely
complicated to specify rules to handle "smarter" transformation methods, such as
`withoutEmail` in our example, to specifically handle fields whose type is `Option`,
or a collection.

## Related work

- [Pre-SIP discussion](https://contributors.scala-lang.org/t/pre-sip-structural-data-structures-that-can-evolve-in-a-binary-compatible-way/5684)
- [SIP-43 - Pattern matching with named fields](https://github.com/scala/improvement-proposals/pull/44) introduces a new type of patterns that
  would be worth supporting out of the box in struct classes.

## FAQ

N/A.
