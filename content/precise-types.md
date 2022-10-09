---
layout: sip
permalink: /sips/:title.html
stage: design
status: submitted
title: SIP-48 - Precise Type Modifier
---

**By: Oron Port**

## History

| Date          | Version            |
|---------------|--------------------|
| Aug 31st 2022 | Initial Draft      |
| Oct 10th 2022 | Changed to modifier|


## Summary

Currently the Scala compiler is eager to widen types in various situations to minimize entropy. 
We propose adding a `precise` modifier that the user can apply on type parameter declaration to indicate to the compiler that the most precise type must be inferred. 


## Motivation

The standard Scala compiler behavior eagerly widens types to reduce type entropy. 
Here are some examples:
~~~ scala
class Box[T] //Box[T] will be the same for all examples in this document
def np[T](t: T): Box[T] = ???

//Numeric and string literals are widened to their respective supertypes
val one = np(1)                     //: Box[Int] 
val str = np("hi")                  //: Box[String]

//Union types are widened to their common supertype
val cond: Boolean = true
val uni = np(if cond then 1 else 2) //: Box[Int]
val lst = np(1 :: 2 :: 3 :: Nil)    //: Box[List[Int]]

//Tupled values are widened to their supertype
val tpl = np((1, (2, "three")))     //: Box[(Int, (Int, String))]

//Singleton objects are widened to their super class
class Foo
val foo = new Foo
val obj = np(foo)                   //: Box[Foo]

//Covariant type parameters are widened to their supertype
class Bar[A, +B, -C]
def npBar[A, B, C](bar : Bar[A, B, C]): Box[(A, B, C)] = ???
val bar = npBar(new Bar[1, 1, 1])   //: Box[(1, Int, 1)]

//Given summon of contravariant type parameters are widened
class Cov[+T]
trait TC[-T]:
  type Out
  val value: Box[Out] = ???
object TC:
  given [T]: TC[Cov[T]] with
    type Out = T
val smn = summon[TC[Cov[1]]].value  //: Box[Int]
~~~

In many cases this is the expected result/behavior, but there are use-cases where we want to keep the precise type information. 
For example, we want to represent a size-safe vector that maintains its precise size compile-time information as much as possible, and also has the runtime size information as a fallback. The vector should be able to concatenate with another vector, where the size of the concatenation equals to the sum of both vector sizes.

### Size-Safe Vector: Naïve Implementation
The following is a naive implementation of the vector.
*Code*:
[scastie](https://scastie.scala-lang.org/M0r5o487T7GjFc28Iw9UsQ)

~~~ scala
import compiletime.ops.int.+
class Vec[+S <: Int](val size: S):
  def ++[TS <: Int](that: Vec[TS]): Vec[S + TS] = 
    Vec[S + TS]((size + that.size).asInstanceOf[S + TS])
val v1 = Vec(1)
val v1T: Vec[1] = v1 // error
val v2 = Vec(2)
val v2T: Vec[2] = v2 // error
val v3 = v1T ++ v2T
val v3T: Vec[3] = v3 // error
assert(v3T.size == 3)
val one = 1
val vOne = Vec(one)
val vOneT: Vec[one.type] = vOne // error
val vTwo = Vec(one + 1)
val vTwoT: Vec[Int] = vTwo
val vThree = vOneT ++ vTwoT
val vThreeT: Vec[Int] = vThree
assert(vThreeT.size == 3)
~~~

*Output*:
~~~scala
-- [E007] Type Mismatch Error: vec.scala:5:18 -------------------
5 |val v1T: Vec[1] = v1 // error
  |                  ^^
  |                  Found:    (v1 : Vec[Int])
  |                  Required: Vec[(1 : Int)]
  |
-- [E007] Type Mismatch Error: vec.scala:7:18 -------------------
7 |val v2T: Vec[2] = v2 // error
  |                  ^^
  |                  Found:    (v2 : Vec[Int])
  |                  Required: Vec[(2 : Int)]
  |
-- [E007] Type Mismatch Error: vec.scala:9:18 -------------------
9 |val v3T: Vec[3] = v3 // error
  |                  ^^
  |                  Found:    (v3 : Vec[(1 : Int) + Int])
  |                  Required: Vec[(3 : Int)]
  |
-- [E007] Type Mismatch Error: vec.scala:13:27 -------------------
13 |val vOneT: Vec[one.type] = vOne // error
   |                           ^^^^
   |                           Found:    (vOne : Vec[Int])
   |                           Required: Vec[(one : Int)]
   |
~~~

The naive implementation suffers from two types of widening, as discussed previously: 
  * Any expression is automatically widened when applied as an argument (lines 5, 7, 13).
  * A covariant type parameter in an argument is widened (line 9).


### Size-Safe Vector: Singleton Implementation
[SIP23](https://docs.scala-lang.org/sips/42.type.html) that introduced literal types also introduced the ability to upper-bound a type parameter with `Singleton` to prevent the compiler from widening the type. However, this also introduces another constraint that there must be a known stable path for the parameter. This is where our second `Singleton`-based implementation fails.

*Code*:
[scastie](https://scastie.scala-lang.org/yPDn3K6pQHafik7aOQFs8g)
~~~ scala
import compiletime.ops.int.+
class Vec[+S <: Int & Singleton](val size: S):
  def ++[TS <: Int & Singleton](that: Vec[TS]): Vec[S + TS] = 
    Vec[S + TS]((size + that.size).asInstanceOf[S + TS])
val v1 = Vec(1)
val v1T: Vec[1] = v1 
val v2 = Vec(2)
val v2T: Vec[2] = v2 
val v3 = v1T ++ v2T
val v3T: Vec[3] = v3 
assert(v3T.size == 3)
val one = 1
val vOne = Vec(one)
val vOneT: Vec[one.type] = vOne 
val vTwo = Vec(one + 1) // error
val vTwoT: Vec[Int] = vTwo
val vThree = vOneT ++ vTwoT // error
val vThreeT: Vec[Int] = vThree
assert(vThreeT.size == 3)
~~~

*Output*:
~~~scala
-- [E007] Type Mismatch Error: vec.scala:14:15 -------------------
14 |val vTwo = Vec(one + 1) // error
   |               ^^^^^^^
   |               Found:    Int
   |               Required: Int & Singleton
   |
-- [E007] Type Mismatch Error: vec.scala:16:22 -------------------
16 |val vThree = vOneT ++ vTwoT // error
   |                      ^^^^^
   |                      Found:    (vTwoT : Vec[Int])
   |                      Required: Vec[Int & Singleton]
   |
~~~

We no longer have a widening problem, but `Singleton` prevents us from applying all possible values since some values are not singletons.
Additionally, `Singleton` does not enable us to preserve tuple argument information, which is required in other use-cases. Furthermore, `Singleton` can [present soundness problems](https://github.com/lampepfl/dotty/issues/4944), and may be deprecated in the future.

The alternative to using `Singleton` involves macros that can traverse the inlined argument tree and precisely type its entire structure. This method is too complex and does not provide a full solution that covers all corner-cases (e.g., covariant type parameters are still widened, which forces to only rely on invariance and its limitations).

### Size-Safe Vector: Type class implementation
A type class implementation exposes another widening problem. 

*Code*:
[scastie](https://scastie.scala-lang.org/w4AEVSYUTh6pdfxa5bEKMQ)
~~~ scala
import compiletime.ops.int.+
class Vec[+S <: Int](val size: S)
object Vec:
  trait TC[-T]:
    type S <: Int
    def apply(t: T): Vec[S]
  given [S0 <: Int]: TC[Vec[S0]] with
    type S = S0
    def apply(t: Vec[S0]): Vec[S0] = t
  extension [L](lhs: L)(using tcL: TC[L])
    def ++[R](rhs: R)(using tcR: TC[R]): Vec[tcL.S + tcR.S] = 
      Vec[tcL.S + tcR.S]((tcL(lhs).size + tcR(rhs).size).asInstanceOf[tcL.S + tcR.S])
val v1T: Vec[1] = Vec[1](1)
val v2T: Vec[2] = Vec[2](2)
val v3 = v1T ++ v2T
val v3T: Vec[3] = v3 // error
~~~

*Output*:
~~~ scala
-- [E007] Type Mismatch Error: vec.scala:16:18 -------------------
16 |val v3T: Vec[3] = v3 // error
   |                  ^^                                         
   |                  Found:    (v3 : Vec[Int + Int])
   |                  Required: Vec[(3 : Int)]
~~~
Implicit summoning of a covariant type parameter causes widening. To avoid widening here we can define the type class `TC` as invariant (and modify the related given declarations), or drop the covariance from `Vec`, or add a `Singleton` upper-bound to `S0`.

As seen from all the examples thus far, the scala compiler is very eager to widen types in various cases and we need a mechanism to prevent this when the compile-time information preservation is crucial.

## Proposed solution

### High-level overview

We propose to add a `precise` modifier that can be applied on type parameters to indicate to the compiler that term arguments annotated with those precise types are considered to be *precisely typed*. Precise typing is not only preventing widening for literals as demonstrated so far, but also prevents any widening in the expression composition, so that composable expressions like tuples can preserve their precise representation throughout their entire structure.

Here is our earlier implementation of `Vec`, modified to work with all examples by applying the `precise` modifier:
~~~ scala
class Vec[precise +S <: Int](val size: S):
  def ++[precise TS <: Int](that: Vec[TS]): Vec[S + TS] = 
    Vec[S + TS]((size + that.size).asInstanceOf[S + TS])
~~~
The `precise` modifier helps preserving the precise size type when constructing a vector (precise typing of the size term argument), and when the vector is applied as an argument to the concatenation (not widening the covariant size type parameter).


### Specification

#### Add The `precise` Modifier
The precise modifier is a soft keyword that is only available at a type parameter declaration position.
The following grammar rules are modified to support this new soft keyword:
~~~
ClsTypeParam ::=  {Annotation} [‘precise’] [‘+’ | ‘-’] id [HkTypeParamClause] TypeParamBounds ;
DefTypeParam ::=  {Annotation} [‘precise’] id [HkTypeParamClause] TypeParamBounds ;
TypTypeParam ::=  {Annotation} [‘precise’] id [HkTypeParamClause] TypeBounds ;
HkTypeParam  ::=  {Annotation} [‘precise’] [‘+’ | ‘-’] (id [HkTypeParamClause] | ‘_’) TypeBounds ;
~~~ 

#### Precise Types
A type is considered to be precise when at least of one of following occurs:
  * It is a type parameter declaration that is directly modified with `precise` 
  ~~~ scala
  //`T` is a precise type parameter declaration
  class PreciseBox[precise T]
  ~~~
  * It is a type variable reference with a precise type parameter origin
  ~~~ scala
  //`: T` type variable is precise because it has the precise origin type parameter `T` 
  def id[precise T](t: T): Box[T] = ??? 
  val x = id(1) //: Box[1]
  ~~~
  * It is substituting a precise type parameter (may also be a wildcard substitution)
  ~~~ scala
  def id[precise T](t: T): Box[T] = ???
  def idBoxBox[BB](x: Box[BB]): Box[BB] = ???
  //`BB` is precise because it substitutes the precise type parameter `T` 
  val bb1 = idBoxBox(id(1)) //: Box[1] 
  ~~~
  * It is an Alias of a precise type parameter.
  ~~~ scala
  class PreciseBox[precise T]
  //`A` is precise because it is aliasing the precise type parameter `T`
  type PB[A] = PreciseBox[A]
  ~~~
  * It is introduced by an applied type and it is in a precise position of that type. 
    A precise position can present directly, when the position parameter is annotated with `precise`, or indirectly by "boring" through an applied type composition so that all applied type parameters are precise if the applied type itself is in a precise position.
  ~~~ scala
  class PreciseBox[precise T]
  //`BB` is precise because it was introduced by `PreciseBox` in the precise position `T`
  def pbox[BB](x: PreciseBox[BB]): Box[BB] = ??? 
  //`BB` is imprecise because it was not introduced by `PreciseBox` and is not annotated with precise
  def pbox[BB](b: BB)(x: PreciseBox[BB]): Box[BB] = ??? 
  class Cov[+T]
  //`BB` is precise because it was introduced by `Cov` which is applied in `PreciseBox` in the precise position `T`
  def pboxcov[BB](x: PreciseBox[Cov[BB]]): Box[BB] = ??? 
  ~~~
  * It is a upper-bounded by precise type.
    Upper-bounds may manifest either directly in a type param declaration or dynamically constrained by applying one method as an argument of another.
  ~~~ scala
  //`T` is precise because it is upper-bounded by `P`
  def id[precise P, T <: P](t: T): Box[T] = ??? 
  ~~~
  * It is a by-name reference with a precise result type
  ~~~ scala
  //`: => T` is precisely typed because the result `T` is precise
  def id[precise T](t: => T): Box[T] = ??? 
  ~~~
  * It is a FuncXX type with a precise result type
  ~~~ scala
  //`: (Int, Int) => T` is precisely typed because the result `T` is precise
  def id[precise T](t: (Int, Int) => T): Box[T] = ??? 
  ~~~

#### Precise Term Arguments
A term expression is *precisely typed* when it is applied in a term argument position that is annotated by a precise type. 
~~~ scala
//`t` argument is precise because it is annotated by the precise param `T` 
def id[precise T](t: T): Box[T] = ??? 
~~~
**Tuple arguments special case**
We also support a special case where tuple of terms applied on an argument annotated with tuple type of the same arity. This causes each part of the tuple term to be precisely typed or not according to the specific precise type of that position.
~~~ scala
def id[precise T1, T2](t: (T1, T2)): Box[(T1, T2)] = ??? 
//`(1, 2)` is precisely typed because `T1` is precise
//`3` is not precisely typed because `T2` is not precise
val x = id(((1, 2), 3)) //: Box[((1, 2), Int)]
~~~


#### Precise Typing (of Term Argument Expressions)
Precise typing (mode) is activated when an expression is applied in a precise argument position. Here are the same examples given in the motivation, but now precisely typed due to the `precise` modifier application:
~~~ scala
def id[precise T](t: T): Box[T] = ???

//Numeric and string literals are kept precise
val one = id(1)                     //: Box[1] 
val str = id("hi")                  //: Box["hi"]

//Union types are kept precise
val cond: Boolean = true
val uni = id(if cond then 1 else 2) //: Box[1 | 2]
val lst = id(1 :: 2 :: 3 :: Nil)    //: Box[List[1 | 2 | 3]]

//Tupled values are kept precise
val tpl = id((1, (2, "three")))     //: Box[(1, (2, "three"))]

//Singleton objects are kept precise
class Foo
val foo = new Foo
val obj = id(foo)                   //: Box[foo.type]

//Covariant type parameters are kept precise
class Bar[A, precise +B, -C]
def npBar[A, B, C](bar : Bar[A, B, C]): Box[(A, B, C)] = ???
val bar = npBar(new Bar[1, 1, 1])   //: Box[(1, 1, 1)]

//Given summon of contravariant type parameters are kept precise
class Cov[+T]
trait TC[precise -T]:
  type Out
  val value: Box[Out] = ???
object TC:
  given [T]: TC[Cov[T]] with
    type Out = T
val smn = summon[TC[Cov[1]]].value  //: Box[1]
~~~
Note: how the compiler infers the actual precise type of an argument remains unchanged. This proposal just limits situations in which these types are widened.

When an expression is precisely typed, it is carried out throughout its entire composition. Here is an example that demonstrates this concept. We use the naive implementation of `Vec` we introduced earlier, but now we apply `Vec` construction and concatenation as an expression within a precise argument position:
~~~ scala
def precisely[precise T](t: T): T = t
//naive Vec implementation
class Vec[+S <: Int](val size: S):
  def ++[TS <: Int](that: Vec[TS]): Vec[S + TS] = 
    Vec[S + TS]((size + that.size).asInstanceOf[S + TS])

val v = precisely(Vec(1) ++ Vec(2) ++ Vec(3)) //: Vec[6]
~~~


**Blocks Stop Precise Typing**
As long as we have an expression composition in a precise position, precise typing remains active. Precise typing stops when a new block is formed, and only maintains the precise typing for the final expression of the block. E.g.:
~~~ scala
def id[precise T](t: T): Box[T] = ???
val one = id {  //: Box[1]
  val npOne = 1 //: Int
  val npTwo = 2 //: Int
  1
}
~~~
The only exception to this rule are closure blocks (can be formed by Single Abstract Methods (SAMs)). If a closure block is formed in a precise type position then the entire block is precisely typed.


#### Precise Covariant/Contravariant Type Parameters
Precise covariant/contravariant type parameters are never widened, unless the compiler needs to widen them to pass typechecking.
~~~ scala
class PreciseBox[precise +T]
def idBox[B <: Int](pb: PreciseBox[B], wb: PreciseBox[Int]): Box[B] = ???
val pb = PreciseBox[1]
val x = idBox(pb, pb) //: Box[1]
~~~

#### Precise Default Parameter Value Desugaring
Precise arguments can accept default arguments and have them typed precisely. 
~~~ scala
def id[precise T](t: T = 1): Box[T] = ???
val one = id()            //: Box[1]
val two = id(2)           //: Box[2]
def idTpl[precise T](t: T = (1, 2)): Box[T] = ???
val tpl12 = idTpl()       //: Box[(1, 2)]
val tpl34 = idTpl((3, 4)) //: Box[(3, 4)]
~~~

#### Special Consideration: Method Overloading
Precise parameters have no effect over method overloading selection. Between several method options the compiler chooses the most appropriate one based on the imprecise typing rules. Once a method is selected (assuming no ambiguity occurs), then the compiler applies the arguments precisely, according to the rules discussed thus far. Two methods with same signature but different precise modifiers are considered to be ambiguous.

#### Special Consideration: Given Instances
Similarly to method overloading, precise parameters have no effect over choosing the given instance. Two instances with same signature but different precise modifiers are considered to be ambiguous.

#### Special Consideration: Implicit Conversions
Similarly to method overloading, precise parameters have no effect over choosing the appropriate implicit conversion. Two implicits with same signature but different precise modifiers are considered to be ambiguous.

#### Special Consideration: Extension Methods & Implicit Classes
Similarly to method overloading, precise parameters have no effect over choosing the appropriate extension or implicit class methods. Two such methods with same signature but different precise modifiers are considered to be ambiguous.


#### Overriding Methods with Precise Params
To override a method, the precise type parameters must match, or else an error is generated.
~~~ scala
object preciseOverrideOK:
  abstract class Foo:
    def id[precise T](t: T): T
  class Bar extends Foo:
    def id[precise T](t: T): T = t

object preciseOverrideMorePrecise:
  abstract class Foo:
    def id[T](t: T): T
  class Bar extends Foo:
    def id[precise T](t: T): T = t // error

object preciseOverrideLessPrecise:
  abstract class Foo:
    def id[precise T](t: T): T
  class Bar extends Foo:
    def id[T](t: T): T = t // error
~~~

#### Type/Opaque Aliases with Precise Params
A type alias type variable declarations must match the precise modifier of the aliased class/type, or drop precise modifiers. It is not possible to add a `precise` modifier in an alias if the aliased type is not, or else an error is generated. Opaque type alias are forbidden from dropping `precise` as well.
~~~ scala
object samePreciseOK:
  trait Foo[precise T]
  type FooAlias[precise A] = Foo[A]
  opaque type FooOpaque[precise A] = Foo[A]
object morePreciseErr:
  trait Foo[T]
  type FooAlias[precise A] = Foo[A] // error
  opaque type FooOpaque[precise A] = Foo[A] // error
object lessPreciseErr:
  trait Foo[precise T]
  type FooAlias[A] = Foo[A]
  opaque type FooOpaque[A] = Foo[A] // error
~~~

#### Extending Traits & Classes with Precise Params
A class can extend an existing trait or class without precise arguments and increase the preciseness of its values by applying precise modifiers on the relevant type parameters. However, if the extended class or trait already has precise type parameter, the extending class must preserve the same modifier.
~~~ scala
object samePreciseOK:
  trait Foo[precise T]:
    val value: T
  class FooExtend[precise A](val value: A) extends Foo[A]
object morePreciseOK:
  trait Foo[T]:
    val value: T
  class FooExtend[precise A](val value: A) extends Foo[A]
object lessPreciseErr:
  trait Foo[precise T]:
    val value: T
  class FooExtend[A](val value: A) extends Foo[A] // error
~~~ 

#### Typechecking of Polynomial Types with Precise Params
Signature of polynomial types and values must include precise modifiers to match, or else a type-mismatch error is generated.
~~~ scala
type Id1 = [precise T] => T => Box[T]
val id1Check: Id1 = [T] => (t : T) => Box(t) // error

type Id2 = [T] => T => Box[T]
val id2Check: Id2 = [precise T] => (t: T) => Box(t) // error
~~~

### Compatibility
Fully backward compatible.


### Performance

This feature comes with some performance penalty. Extra logic is added to most typing operations to identify precise types (unless precise mode is already active). Additionally, because methods are selected according to their arguments' imprecise types, arguments may need to be (re)typed precisely as discussed in special condition subsections of the spec.

## Alternatives

Without this proposal it is extremely difficult to achieve the same precise type functionality, as discussed in the motivation section.


## Related work

### Implementation

The implementation was completed assuming a `@precise` annotation and will be changed to `precise` modifier if this SIP is accepted.
https://github.com/lampepfl/dotty/pull/15765

### Contributors Discussion

https://contributors.scala-lang.org/t/pre-sip-exact-type-annotation/5835

### Related Issues
* [Unexpected literal widening without a Singleton upperbound (post-SIP23)](https://github.com/scala/bug/issues/10838)
* [Unexpected widening of covariant literal types](https://github.com/lampepfl/dotty/issues/8231)
* [The Singleton "kind" is not an upper bound](https://github.com/lampepfl/dotty/issues/4944)
* [Need precise a way to disable widening that works on locals](https://github.com/lampepfl/dotty-feature-requests/issues/48)
* [Summoning covariant class leads to Nothing in type argument](https://github.com/lampepfl/dotty-feature-requests/issues/313)
* [Singleton subtyping, and can we mark scala.Singleton as experimental?](https://contributors.scala-lang.org/t/singleton-subtyping-and-can-we-mark-scala-singleton-as-experimental/2797)
* [Literal-type operation signature should produce a Singleton upper-bounded type](https://github.com/lampepfl/dotty/issues/8257)
* [[Experiment] Introduce hard ConstantTypes](https://github.com/lampepfl/dotty/pull/14360)
* [Union type with object used with generic type infers wrong type even with type ascription](https://github.com/lampepfl/dotty/issues/14642)


## FAQ

None currently.


## Acknowledgment

The work on this SIP was sponsored by [DFiant](www.dfiant.works).