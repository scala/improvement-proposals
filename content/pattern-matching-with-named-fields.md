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
user should matchPattern { case User(name = "Sarah", _*) => } // ScalaTest

val User(age = ageOfUser, _*) = user

for {
  User(name = userName, _*) <- db.fetchUser()
} yield userName

def neighboursNameOfUser(user: User, allUsers: List[User]) =
  allUsers.collect {
    case neighbour @ User(city = user.city, name = name, _*) if neighbour != user => name
  }
```

## Design

The goal is to allow named parameter in deconstruction as in construction.

### Defining names

Definitions can have a new modifier `match`. 

```
val matchers = definitions.filter(hasMatchModifier)
val nth = definitions.filter(startsWithUnderscoreAndHasNumber)

assert(matchers.length == nth.length)

val pairs = matchers zip nth
```

All fields of a case class are implicitly a `match` field. So writing `case class User(match id: Sting)` is redundant and can be shorten to `case class User(id: String)`.

### Mixed usage

Mixed patterns, with positional and named patterns are allowed to keep the similarity.
If a positional pattern comes after a named pattern, all named patterns before it must be at the correct position.
*TODO*: Look up better description from conversation

```scala
  case User("Anna", age = 10, "Berlin") => // name the second field to improve readability
  case User(_, city = c, _*) => // Leading underscore are especially useless
```

With this change the `_*` token becomes overloaded for patterns with VarArgs. 

```scala
case class Country(name: String, population: Long, cites: String*)

  // Ignore nothing
  case Country(name = _, cities = "Berlin")
  // Ignore all cities and fields:
  case Country(name = "Germany", _*)
  // Ignore all cities, but not fields:
  case Country(name = "Germany", cities = _*)
  // Ignore fields:
  case Country(name = _, cities = _*, _*)
```

### Case classes with sequences

For the case that the extractor is a mixture of [product and sequence match](https://dotty.epfl.ch/docs/reference/changed-features/pattern-matching.html#product-sequence-match) the name of the sequence can only be used at the last position.

```
case class Country(name: String, cities: String*)

county match:
  case Country(cities = "Berlin", "Hamburg") = ???  // ok
  case Country(cities = "Berlin", _*) = ??? // ok
  case Country(cities = "Berlin", "Hamburg", name = "Germany") = ??? // not okay
```

*TODO*: Check with section above

### Disallow same name twice

```scala
  case User(city = city1, city = city2, _*) => a // error city is used twice
  case User(name1, name = name2, _*) => a // error name is used twice
```

### Ignoring of unused parameters

Per default all patterns have to use all fields either by position or name.
This is to allow save evolution of an API.

To allow case class to become more extensible, all unused parameters should be ignored. So when a field is added to the cases class old patterns stay source compatible.

```scala
  case User(city = "Paris", _*) => // is the same as
  case User(_, _, "Paris") =>
```

### Syntax

This SIP proposes to change the syntax of `Patterns` to:

```
Patterns          ::=  NamedPattern {‘,’ NamedPattern}
ArgumentPatterns  ::=  ‘(’ [Patterns] ‘)’
                    |  ‘(’ [Patterns ‘,’] PatVar ‘*’ ‘)’
NamedPattern      ::= [id ‘=’] Pattern
```

*TODO*: Add new `match` modifier syntax here

### Desugaring

> One important principle of Scala’s pattern matching design is that case classes should be abstractable. I.e. we want to be able to represent the abilities of a case class without exposing the case class itself. That also allows code to evolve from a case class to a regular class or a different case class while maintaining the interface of the old case class. [Martin Odersky](https://contributors.scala-lang.org/t/pattern-matching-with-named-fields/1829/52)

*TODO*: 


### Compatibility

Older Scala version would just ignore the `match` modifier and would work as before. 

*TODO*: But how much compatibility do we want here, when a newer version consumes the case class of an older scala version? It should be possible to recover the names of the case classes, but that may leaks internals.

### Implementation

*TODO*:

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

In the spirit of [name based pattern matching](https://dotty.epfl.ch/docs/reference/changed-features/pattern-matching.html#name-based-match):

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

An alternative would be to prefix methods with a keyword (e.g. `case`) instead of using an underscore.

Pro:

* have more named fields than positional fields
* allows `@deprecatedName`
* enabled meaningful names in variadic patterns
* lazy evaluation patterns where bad field could have type `Nothing` or would throw could, but the pattern would match, as long the bad field isn't mentioned. (This would have many consequences)
* It's easy to add extractors for maps in another SIP. At least on the encoding side.

Con:

* How to detect that a name means the same as a position? Maybe detect simple patterns like the last line in the example?
* It's long and verbose, without any shortcuts in sight.
* An underscore at the beginning of a name isn't an unheard of pattern, even in Scala. This could accidentally expose fields, which weren't supposed to become fields.

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
