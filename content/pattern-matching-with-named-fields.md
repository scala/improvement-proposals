---
layout: sip
title: SIP-NN - Pattern matching with named fields
status: submitted
permalink: /sips/:title.html
redirect_from: /sips/pending/2021-06-25-named-pattern-matching.html
---

## History

| Date          | Version       |
|---------------|---------------|
| Feb 25th 2022 | Initial Draft |

## Summary

With the first [first SIP ever named and default arguments](https://docs.scala-lang.org/sips/named-and-default-arguments.html) a feature disparity between case class constructors and case class deconstructors was introduced. This was an long outstanding issue. (for example [scala/bugs#6524](scala_bug))

This SIP introduces a readable, optional extensible, and intuitive way to deconstruct case classes in pattern matching.

A WIP PR can be found here: https://github.com/Jentsch/dotty/tree/pattern-matching-with-named-fields

This SIP is for now structured in three sections: 
[Motivation](##Motivation) to visualize the desired outcome. 
[Design](##Design) to enumerate all goals and constrains for this SIP.
[Encoding](##Encoding) describe how the design can be implemented. In [Alternatives](##Alternatives) we look at other options.

## Motivation

Given one wants to use pattern matching on a case class:

```scala
case class User(name: String, age: Int, city: String)

val user = User(name = "Anna", age = 10, city = "Berlin")

val annasCity = user match
  case User(name = "Anna", _, city = c) => c
```

The Deconstruction allows the same syntax as the construction and seems to be what people intuitively expect.
See for example the very first post in [Scala Contributors Thread][contributors-thread] for this topic.

Without names in patterns, it becomes unclear what `c` means. The example above would be written as:

```scala
val annasCity = user match
  case User("Anna", _, c) => c
```

This common pattern makes it hard to see which parameter means what. The same issue led to named arguments. Adding underscores until the compiler is happy is also not a great experience. IDEs help here, by showing the names.

Additionally, the code breaks every time a field of `User` gets added, rearranged, or removed.
In the best case, the broken patterns fail to compile and needs to fixed manually.
In the worst case, a pattern breaks silently, if two fields with the same type switch places.

My personal motivation comes from using [`matchPattern` in ScalaTest](https://www.scalatest.org/user_guide/using_matchers#matchingAPattern)
and got bitten by it every time my data model changed slightly. So named pattern matching is also allowed where ever normal pattern matching allowed:

```scala
// Partial Function
def usersInCity(users: List[User], city: String) =
  users.collect {
    case User(name = name, _, city = user.city) => name
  }

user should matchPattern { 
  case User(name = "Sarah", _*) => 
} // Partial function in ScalaTest

// Assignments
for {
  User(name = userName, _, _) <- db.fetchUser()
} yield userName

val User(age = ageOfUser, _*) = user
```

## Design

The goal is to allow named parameter in deconstruction as in construction.

### Defining names

Definitions can have a new modifier `match`.

*TODO* Desugared example of the User class.

Pseudo Code to match position and name:
```
val matchers = definitions.filter(hasMatchModifier)
val nth = definitions.filter(startsWithUnderscoreAndHasNumber)

assert(matchers.length == nth.length)

val pairs = matchers.zip(nth)
```

All fields of a case class are implicitly a `match` field. So writing `case class User(match id: Sting)` is redundant and can be shorten to `case class User(id: String)`.

### Mixed usage

Mixed patterns, with positional and named patterns are allowed to keep the similarity.
If a positional pattern comes after a named pattern, all named patterns before it must be at the correct position.

```scala
  case User("Anna", age = 10, "Berlin") => // name the second field to improve readability
  case User(_, city = c, _) => // error, because city is at the wrong position
```

### Ignoring unused fields

Per default all patterns have to use all fields either by position or name.
This is to allow save evolution of an API.
Like in the ScalaTest example above, it not always desired to use all fields of a pattern.
To ignore all unused fields I propose to use `_*` at the end of the pattern:

```
  case User(name = "Anna", _*)
```

Note: If it becomes best practice to always use this token, we can make it the default behavior later on.

### Case classes with sequences

For the case that the extractor is a mixture of [product and sequence match](https://dotty.epfl.ch/docs/reference/changed-features/pattern-matching.html#product-sequence-match) the name of the sequence can only be used at the last position.
With this SIP the `_*` token becomes overloaded for patterns with VarArgs. 

Following interactions are possible: *TODO*: Clean up

```
case class Country(name: String, pop: Long, cities: String*)

county match:
  // Error, unused fields: name und pop
  case Country(cities = "Berlin", "Hamburg")
  
  // Ignores all other cities and the missing pop field
  case Country(cities = "Berlin", _*)

  // Error, name is used after cities
  case Country(cities = "Berlin", "Hamburg", name = "Germany")

  // Ignores nothing, so the missing population is an error:
  case Country(name = _, cities = "Berlin")

  // Ignore all cities and unused fields:
  case Country(name = "Germany", _*)
  // Positional equivalent
  case Country("Germany", _*)

  // Ignore all cities, but not fields, so the missing population is an error: 
  case Country(name = "Germany", cities = _*)

  // Ignore fields:
  case Country(name = "Germany", cities = _*, _*)
```

### Disallow same name twice

It's not allowed to use the same name twice:

```scala
  case User(city = city1, city = city2, age = _, city = _) => a // error city is used twice
  case User(name1, _, _, name = name2) => a // error name is used twice
```

### Syntax

This SIP proposes to change the syntax of `Patterns` to allow names in patterns and the keyword `match` as a modifier:

```
Patterns          ::=  NamedPattern {‘,’ NamedPattern}
ArgumentPatterns  ::=  ‘(’ [Patterns] ‘)’
                    |  ‘(’ [Patterns ‘,’] PatVar ‘*’ ‘)’
NamedPattern      ::= [id ‘=’] Pattern

LocalModifier     ::= ...
                    | ‘match’      
```

### Desugaring

> One important principle of Scala’s pattern matching design is that case classes should be abstractable. I.e. we want to be able to represent the abilities of a case class without exposing the case class itself. That also allows code to evolve from a case class to a regular class or a different case class while maintaining the interface of the old case class. [Martin Odersky](https://contributors.scala-lang.org/t/pattern-matching-with-named-fields/1829/52)

Options:
* Desugar everything down to positional patterns (like the prototype) Downside binary compatibility: Changing the order of fields in a lib will change the meaning of the binary representation, but not on source code level.
* Don't desugar, but pass the name through the whole pipeline until the class file. Binary and source code representation are aligned. Downside: way more complex with potential performance impact.


### Compatibility

Source code: The new syntax wan't valid before, so allowing it won't brake any code. That also means older Scala versions won't be able to consume source code which use named patterns or declare directly match definitions.

Libraries: Using a library that declares match definitions from an older Scala version must be okay. The `match` modifier should be ignored. Case class from an older Scala version don't provide match definitions.

### Implementation

*TODO*:

Current draft can be found here: lampepfl/dotty#15437.

## Alternatives

Some of the alternatives where explored for this SIP.

### Without any changes to the language

One alternative way of archiving most objectives, that is doable with current Scala, is to use specialized extractors.

```scala
object User:
  object age:
    def unapply(user: User): Option[Int] =
      Some(user.age)

user match
  case User.age(y) => y
```

Libraries like [Monocle][monocle] could be extended to reduce the boilerplate, but some boilerplate would remain.
In addition, this breaks the intuitive similarity between construction and deconstruction.

### Alternative syntax

In `case User(age = a)` one could get confused, what gets defined (`a`) and what is the name from the case class.

One alternative could be `case User(a <- age)` instead, but that would be deviation of the syntax in comparison to named arguments.

Another issue is that the mixing of positional and named patterns lead to inconsistencies in the language. A syntax with clear separation would avoid that problem. For example `case User { age = a }`. 

### Alternative desugaring

As the above described desugaring has its drawbacks. Here are some alternatives with other drawbacks, and maybe better trade-offs.

#### Use underscore methods

In the spirit of [name based pattern matching](https://dotty.epfl.ch/docs/reference/changed-features/pattern-matching.html#name-based-match) instead of using the modifier `match`, we could use an underscore at the beginning of method name:

```scala
object User:
  class UserMatching(user: User):
    def _1 = user.name
    def _name = user.name

    @deprecatedName
    def _oldName = user.name.toUpperCase
    ...
  
  def unapply(user: User) = UserMatching(user)
```

Pro:

* Option to write n

Con:

* Not as explicit as the current proposal

#### Annotated `unapply` method

```scala
object User:
  @names("name", "age", "city")
  def unapply(user: User): Some[(String, Age, String)] = ...
```

Pro:

* simple to implement (was done in the first draft implementation)

Con:

* no clear way of encoding deprecated name
* annotations are not very scalaish
* no reuse possible

##### Add type as vehicle

```scala
object User:
  type Unapply = ("name", "age", "city")
  def unapply(user: User): Some((String, Age, String)) = ...
```

Pro:

* should be easy to implement
* the type and the method can inherit from traits and allow some kind of reuse

Con:

* the type and the method can be defined in unrelated traits. Only at the use site of the can be checked if the type and the method agree on the arity of the pattern.
* no clear way of encoding deprecated name

#### Partial destructuring in guards

Lionel Parreaux proposed a more powerful mechanism, where if guards of cases could them self contains destructuring patterns.

```scala
user match:
  // both cases do the same thing
  case user: User if s"$first $_" <- user.name => first
  case User(name = s"$first $_") => first
```

His proposal is strictly more powerful, but arguably less intuitive. Both, pattern matching with named fields and Partial destructuring in guards could be implemented along each other. Named fields for simple patterns and destructuring in guards for complex patterns. However, they offer two ways to do the same thing and could lead to lots of bike shedding, if both got added to the language.

## Open questions

Search for TODO in this file.

## References

* [Scala Contributors Thread][contributors-thread]

* [Monocle][monocle]
* [Named Tuple Arguments / Anonymous Case Classes][named-tuple]
* [Partial Destructuring in Guards][partial-destructuring-in-guards]
* [Ticket in scala/bugs](scala_bug)

[monocle]: https://www.optics.dev/Monocle/ "Monocle"
[named-tuple]: https://contributors.scala-lang.org/t/named-tuple-arguments-anonymous-case-classes/4352
[contributors-thread]: https://contributors.scala-lang.org/t/pattern-matching-with-named-fields/1829/20 "Scala Contributors thread"
[partial-destructuring-in-guards]: http://lptk.github.io/programming/2018/12/12/scala-pattern-warts-improvements.html#-partial-destructuring-in-guards
[scala_bug]: https://github.com/scala/bug/issues/6524
