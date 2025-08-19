---
layout: sip
permalink: /sips/:title.html
stage: implementation
status: under-review
title: SIP-XX - Dedented Multiline String Literals
---

**By: Li Haoyi**

## History

| Date          | Version            |
|---------------|--------------------|
| Aug 15th 2025 | Initial Draft      |

## Summary

This SIP proposes a `'''` syntax for dedented multiline string literals that remove leading
indentation at a language level, rather than using the `.stripMargin` library method:

```scala
> def helper = {
    val x = '''
    i am cow
    hear me moo
    '''
    x
  }

> println(helper)
i am cow
hear me moo
```

This is a common feature in other languages (see [Prior Art](#prior-art)) with exactly
the same semantics, although unlike other languages Scala's `"""` already has an existing
semantic, and so for this proposal the currently-unused `'''` syntax is chosen instead.

Dedented strings automatically strip:

- The first newline after the opening `'''`
- The final newline and any whitespace before the closing `'''`
- Any indentation on every line up to the position of the closing `'''`

The opening `'''` MUST be followed immediately by a newline, and the trailing `'''` MUST
be preceded by a newline followed by whitespace characters. Lines within the
dedented string MUST be either empty, or have indentation equal-to-or-greater-than
the closing delimiter.

If a user explicitly wants indentation to be present in the string, they
can simply adjust the contents accordingly:

```scala
> def helper = {
    // string with two-space indents before each line
    val x = '''
      i am cow
      hear me moo
    '''
    x
  }

> println(helper)
  i am cow
  hear me moo
```

And if a user wants leading or trailing newlines, they can add those as well

If a user explicitly wants indentation to be present in the string, they
can simply adjust the contents accordingly:

```scala
> def helper = {
    // string with two-space indents before each line, and leading and trailing newlines
    val x = '''

      i am cow
      hear me moo

    '''
    x
  }

> println(helper)

  i am cow
  hear me moo

```

In most use cases, we expect `'''` to be preferred, although `"""` strings can continue to
exist for backwards compatibility and referred to as "raw multiline strings".

We allow _Extended Delimiters_ with more than three `'''`, to allow the strings to contain arbitrary
contents, similar to what is provided in [C#](#c) and [Swift](#swift). e.g. if you want the string to contain `'''`, you can use a four-`''''` delimiter to
stop the `'''` within the body from prematurely closing the literal:


```scala
> def helper = {
    val x = ''''
    '''
    i am cow
    hear me moo
    '''
    ''''
    x
  }

> println(helper)
'''
i am cow
hear me moo
'''
```

Dedented string literals should be able to be used anywhere a normal `"` or triple `"""`
can be used:

- Literal types (`String & Singleton`)
- String interpolation, with builtin or custom interpolators
- Inline methods and Macros that require the literal value at compile-time
- Pattern matching

```scala
foo match{
  case '''
    i am cow
    hear me moo
  ''' =>
}
```

As `'''` is not valid syntax in Scala today, there are no technical backwards compatibility
concerns. See the section on [Choice Of Delimiters](#choice-of-delimiters) for a discussion on why
`'''` is proposed and some viable alternatives

We expect that traditional `"""` strings will remain in use - e.g. for single-line scenarios such
as those found in
[Scalatags](https://github.com/com-lihaoyi/scalatags/blob/0024ce995f301b10a435c672ff643f2a432a7f3b/scalatags/test/src/scalatags/generic/BasicTests.scala#L46-L61),
[Mill](https://github.com/com-lihaoyi/mill/blob/50e775b31d3f8fc8734c0a90dc231a4dd5ba1d4f/integration/invalidation/invalidation/src/ScriptsInvalidationTests.scala#L29),
[Cask](https://github.com/com-lihaoyi/cask/blob/2bbee717e176a62d6a9af6c8187fbf219aad913d/docs/build.sc#L42),
[Ammonite](https://github.com/com-lihaoyi/Ammonite/blob/2fdc440b23c9bc7eb782c496c05ec1d3c10ee3d6/amm/repl/src/test/scala/ammonite/interp/AutocompleteTests.scala#L62-L104),
[PPrint](https://github.com/com-lihaoyi/PPrint/blob/abea5a533dcb054ab0ef67a4418636faf8e243a5/pprint/test/src/test/pprint/VerticalTests.scala#L32),
[OS-Lib](https://github.com/com-lihaoyi/os-lib/blob/72605235899b65e144ffe48821c63085cb9062ad/os/test/src/PathTests.scala#L34),
[Requests-Scala](https://github.com/com-lihaoyi/requests-scala/blob/a9541623017816a53ecafc5052d02ef7ec62cf2c/requests/src/requests/Requester.scala#L257),
[FastParse](https://github.com/com-lihaoyi/fastparse/blob/d8f95daef21d6e6f9734624237f993f4cebfa881/fastparse/test/src-2.12%2B/fastparse/CustomWhitespaceMathTests.scala#L54-L58),
and other projects - but most multi-line strings would be served better by `'''` as the default choice

## Motivation


This proposal resolves a lot of issues working with status-quo triple-quoted strings.
These issues aren't rocket science, but are a constant friction that makes working
with triple-quoted strings annoying and unpleasant.

### Verbosity & Visual Clarity

Using traditional `""".stripMargin` strings with `|` and `.stripMargin` is very verbose, which
interferes with visually reading the code. There are many different ways to format them,
none of them particularly good, and many of them bad.

Furthermore, very often you don't want the leading or trailing newline either, which means
you need to put text on the first line of the multi-line string which breaks vertical
alignment and makes it hard to skim. e.g. see the canonical example above translated
from `'''` strings to status-quo `"""` strings below:

```scala
def helper = {
  val x = """i am cow
  |hear me moo""".stripMargin
  x
}
```

In particular, the "shape" of raw multiline string is the line-wrapping
zig-zag that is hard to skim at a glance, and hard to correlate with the
actual contents of the string

```
             i am cow
   hear me moo
```

This can be mitigated by indenting the subsequent lines, but that results in lots
of unnecessary indentation

```scala
def helper = {
  val x = """i am cow
            |hear me moo""".stripMargin
  x
}
```

Or it can be solved by following `.stripMargin` with `.trim`, at a cost of more verbosity
and visual noise:

```scala
def helper = {
  val x = """
    |i am cow
    |hear me moo
    |""".stripMargin.trim
  x
}
```

It can also be mitigated by indenting it as follows:

```scala
def helper = {
  val x =
    """i am cow
      |hear me moo""".stripMargin
  x
}
```

There are a huge number of ways to write and format dedented multiline strings
today, and yet none of them are great to look at visually, and there are even more ways you
can format them badly. Overall this zoo of options seems inferior to the proposed dedented
multiline string syntax, which has a single valid way of writing the example above, with
much better visual clarity than any of the existing options:

```scala
def helper = {
  val x = '''
  i am cow
  hear me moo
  '''
  x
}
```

Note how with this dedented string literal:

* The string contents forms a single rectangular block on screen, so you don't need to
  read the code in a zig-zag fashion line-by-line left-to-right to see the contents of the string
  (as you would have to do with the first of the example of above)

* There is also no non-string contents to the left or to the right of the string contents: `|`s, opening or
  closing `"""`s, or `.stripMargin` method calls. This makes the multiline string contents stand
  out clearly from the rest of the code without distraction.

* The amount of horizontal-space used is much less than the examples using traditional multiline
  strings above: without multiple levels of indentations, without a trailing `""".stripMargin`
  extending the last line, or `.stripMargin.trim`

### Incorrectness with Multiline Interpolation

`""".stripMargin` strings can misbehave when used with string interpolations that may
span multiple lines. For example:

```scala
def helper = {
  val scalazOperators = Seq("<$>", "<*>", "|@|", "|->").mkString(",\n  ")
  s"""
  |import scalaz.{
  |  $scalazOperators
  |}
  |""".stripMargin
}
println("SCALAZ CODE EXAMPLE:\n" + helper)
```

```scala
SCALAZ CODE EXAMPLE:
import scalaz.{
  <$>,
  <*>,
@|,
->
}
```

Note how `.stripMargin` accidentally stripped the `|` from `|@|` and `|->`, which
is not what the user expects, causing a compile error. This is not just a theoretical
concern, but has resulted in multiple bugs in widely-used tools and libraries:

- `stripMargin`-related bugs were encountered when implementing the
  [Ammonite REPL](https://github.com/com-lihaoyi/Ammonite)'s import handling and wrapper-code
  generation, which inspired the minimized example above

- Mill encountered a similar bug recently where `|`s in interpolating strings
  were being removed by `stripMargin`, resulting in the documentation examples being incorrect
  https://github.com/com-lihaoyi/mill/pull/4544

### Literal/Singleton Types

`.stripMargin` strings are not literals, and cannot generate `String & Singleton` types
even though from a user perspective the user really may just want a string literal.

```scala
def helper = {
  val x: String & Singleton = """i am cow
  |hear me moo""".stripMargin
  x
}
```

```scala
-- [E007] Type Mismatch Error: -------------------------------------------------
2 |    val x: String & Singleton = """i am cow
3 |    |hear me moo""".stripMargin
  |                                ^
  |                                Found:    String
  |                                Required: String & Singleton
  |
  | longer explanation available when compiling with `-explain`
```

This means that `""".stripMargin` strings cannot take part in type-level logic
on `String & Singleton` types like normal strings can:

```scala
scala> val x: "hello" = "hello"
```

```scala
val x: "hello" = hello
```

```scala
scala> val x: """i am cow
     |   |hear me moo""".stripMargin = """i am cow
     |   |hear me moo""".stripMargin
```

```scala
-- Error: ----------------------------------------------------------------------
2 |  |hear me moo""".stripMargin = """i am cow
  |                 ^
  |                 end of statement expected but '.' found
```

### Literal String Expressions

This also means that any macros that may work on string literals, e.g. validating
the string literal at build time, would not be able to work with `""".stripMargin` strings.
This includes `inline def`s or macros that may want to validate or process these
string literals at compile time (e.g. validating SQL literals, preventing
directory traversal attacks, pre-compiling regexes or parsers, etc.).

One example is FastParse's `StringIn` parser which generates code
for efficiently parsing the given strings at compile time, fails when `""".stripMargin`
strings are given:

```scala
@ def foo[_:P] = P(
    StringIn(
      """i am cow
        |hear me moo""".stripMargin
    )
  )
cmd2.sc:2: Function can only accept constant singleton type
  StringIn(
          ^
```

`""".stripMargin` cannot be used in annotations like `@implicitNotFound`. As shown below,
it does not properly update the error message, because `""".stripMargin` is not a literal
string. Using triple-quoted strings without `.stripMargin` results in the error message being
updated correctly, but then you lose the ability to properly dedent the error:

```scala
scala> @scala.annotation.implicitNotFound(
     | """i am cow
     |   |hear me moo""".stripMargin.toUpperCase) class Foo()
// defined class Foo

scala> implicitly[Foo]
-- [E172] Type Error: ----------------------------------------------------------
1 |implicitly[Foo]
  |               ^
  |No given instance of type Foo was found for parameter e of method implicitly in object Predef
1 error found

```

### Pattern Matching


`""".stripMargin` strings also cannot participate in pattern matches:

```scala
def foo: String = ???

foo match {
  case """i am cow
  |hear me moo""".stripMargin =>
```

```scala
-- [E040] Syntax Error: --------------------------------------------------------
3 |   |hear me moo""".stripMargin =>
  |                  ^
  |                  '=>' expected, but '.' found
```

Both normal single-quoted `"` and triple-quoted `"""` strings can be pattern matched on,
but with triple-quoted strings it includes indentation and so is frustrating to use in practice
due to needing to manually de-dent the string to avoid matching the indentation

```scala
def helper = {
  foo match {
    case """i am cow
hear me moo""" =>
  }
}
```

### Downstream Tooling Complexity

The last major problem with the existing `""".stripMargin` pattern is that all tools
that read, write, or analyze Scala source files or classfiles need to be aware of it.
This results in complexity of these tools' user experience or implementation, or bugs
if the tool does not handle it precisely.

1. [pprint.log](https://github.com/com-lihaoyi/PPrint) prints out multi-line strings
   triple-quoted, but these cannot be pasted into source code without fiddling
   with `|`s and `.stripMargin`s to make sure the indentation is fixed

2. [uTest's assertGoldenLiteral](https://github.com/com-lihaoyi/utest?tab=readme-ov-file#assertgoldenliteral)
   does not work for multi-line strings because of not handling indentation properly

3. [munit hardcodes support for """.stripMargin strings](https://scalameta.org/munit/docs/assertions.html#assertnodiff)
   when pretty-printing values in errors

4. [Mill's bytecode change-detection](https://github.com/com-lihaoyi/mill/pull/2417)
   will detect spurious changes if a `""".stripMargin` string is indented or de-dented,
   due to not recognizing the pattern in the bytecode

5. [ScalaFmt's flag assumeStandardLibraryStripMargin](https://scalameta.org/scalafmt/docs/configuration.html#assumestandardlibrarystripmargin)
   adds a special case, complicating it's configuration schema due to the fact that `stripMargin`
   is a library method and thus ScalaFmt cannot guarantee it's behavior

6. IntelliJ IDEA needs [substantial amounts of special casing](https://github.com/JetBrains/intellij-scala/blob/idea252.x/scala/scala-impl/src/org/jetbrains/plugins/scala/format/StripMarginParser.scala)
   to parse, format, and generate `""".stripMargin` strings.

All this complexity would go away with the proposed de-dented multiline strings: rather
than every downstream tool needing hard-coded support to be `stripMargin`-aware,
tools will only need to generate `'''` multiline strings, which can then be pasted into
user code with arbitrary indentation and they will do the right thing.

## Implementation

TODO

## Limitations

Dedented `'''` strings MUST be multiline strings. Using this syntax for single-line
strings is not allowed,

```scala
val x = '''hello'''
```

As mentioned above, the opening and closing delimiters MUST have a leading/trailing
newline, making the `'''` delimiters "vertical" delimiters that are easy to scan
rather than "horizontal" delimiters like `"` or `"""` which requires the reader
to scan left and right to determine the bounds of the string literal.


All lines within a dedented `'''` string MUST be indented further than the closing
delimiter. That means this is illegal:

```scala
def helper = {
    // string with two-space indents before each line
    val x = '''
      i am cow
hear me moo
    '''
    x
  }
```

Furthermore, the indentation of each line MUST start with the same whitespace characters
as the indentation of the closing delimiter, and cannot e.g. use a different mix of tabs
and spaces. Doing so is an error.

## Alternatives

### `.stripMargin`

The current status quo solution for this is `""".stripMargin` strings, which we have
discussed the limitations and problems with in the [Motivation](#motivation) section above.

### Dedenting Interpolator

One option is to use current triple-quoted strings with an interpolator, e.g.

```scala
def helper = {
  val x = tq"""
  i am cow
  hear me moo
  """
  x
}
```

This `tq"""` interpolator could be a macro that looks at the source code and removes
indentation, avoiding the problems with runtime indentation removal we
[discussed above](#incorrectness-with-mutliline-interpolation). A custom interpolator could also work in [pattern matching](#pattern-matching).
However, using an interpolator does not solve the other issues of multiline strings
not being valid [literal types](#literalsingleton-types) or [literal string expressions](#literal-string-expressions).


Custom interpolators also do not compose: having a dedicate `tq"""` interpolator also
means multiline strings cannot be used with other existing interpolators, such as `s""`,
`r""`, or user-specified interpolators like `sql""` introduced by libraries like
[ScalaSql](https://github.com/com-lihaoyi/scalasql).

### Macro-based `.stripMargin`

A macro-based `.stripMarginMacro` could avoid the issue with composition of interpolators
mentioned above, but still will suffer from the issue of not being
[literal types](#literalsingleton-types) or
[literal string expressions](#literal-string-expressions), and also would not work
in [pattern matching](#pattern-matching).

### Choice Of Delimiters

`'''` was chosen as a currently-unused syntax in Scala, with plenty of precedence
for `'''`-quoted strings in other languages. Languages like Python, Groovy,
Dart, and Elixir all have both `"""` and `'''` strings without any apparent issue,
with several (e.g. Groovy and Elixir) having different semantics between the two syntaxes.

The similar "single-quote Char" syntax is `'\''` is relatively rare in typical
Scala code - a quick search of the libraries I have checked out finds 141 uses of `'\''`,
compared to 24331 uses of `.stripMargin` that could benefit from this improved syntax -
which suggests that the benefit will be widespread and the similarity with `'\''` would
be edge case that occurs rarely and cause minimal confusion.

Other options to consider are listed below

#### Double-single-quotes

Like `'''`, `''` is also currently invalid syntax in Scala, and could be used for
defining multi-line strings:

```scala
def helper = {
  val x = ''
  i am cow
  hear me moo
  ''
  x
}
```

For all intents and purposes this is identical to the `'''` proposal, with some tweaks:

- `''` looks less similar to a `Char` literal `'\''`, so less chance of confusion
- `''` looks less simila to the triple-quoted strings common in other languages, so
  there is less benefit of familiarity.

#### Triple-or-more double-quotes

-  `"""` are already used with a particular semantic, so we cannot
  change those, despite them being used in every other language like [Java](#java),
  [C#](#c), and [Swift](#swift).

- Similarly, we cannot use four-double-quotes as the delimiter for the new semantics,
  because those are already valid syntax today, and (perhaps unintuitively) represent
  triple-quoted strings with quotes inside of them:

```scala
@ """"
  """".toCharArray
res0: Array[Char] = Array('\"', '\n', '\"')
```

#### Single-quoted Multiline Strings
Single-quoted strings with `"` cannot currently span multiple lines, and so
they could be specified to have these dedenting semantics when used multi-line.

```scala
def openingParagraph = "
  i am cow
  hear me moo
"
```

This has the advantage of not introducing a new delimiter, as `"` is already
used for strings.


A single `"` would require that `"`s in the multi-line string be escaped. Given
that `"`s are very common characters to have in strings, that would be very annoying,
and mean that people would still need to use `""".stripMargin` strings in common cases

```scala
def openingParagraph = "
  {
    \"i am\": \"cow\",
    \"hear me\": \"moo\"
  }
"
```

It is possible to define rules such that `"`s do not need to escape, but it could
complicate parsing. e.g. one suggested rule is _"the first line starting with a `"`
and with an odd number of `"`s terminates the multi-line string"_. That works for the scenario
above:

```scala
def openingParagraph = "
  {
    "i am": "cow",
    "hear me": "moo"
  }
"
```

But fails in other simple cases like:

```scala
// The `"...` closes the string prematurely
def openingParagraph = "
  One dark and stormy night,
  he said
  "...i am cow
  hear me moo"
".toJson
```

```scala
def openingParagraph = "
  {
    "i am": "cow",
    "hear me": "moo"
  }
" + '"' // This becomes an unclosed string!
```

```scala
def openingParagraph = "
  {
    "i am": "cow",
    "hear me": "moo"
  }
" // A single-`"` string
// The preceding comment causes this to become an unclosed string literal!
```

Furthermore, this could cause confusion when embeding the multi-line string in surrounding code:

```scala
// This parses as `foo` being passed one parameter
foo(
  "
  this is
  ","
  not a drill
  "
)
// This parses as `foo` being passed two parameter
foo(
  "
this is
  ",
"
not a drill
"
)
```

Another possible rule is indentation-based: _"the first single-quote preceded on a line
only by whitespace that is indented equal-or-less than the opening quote"_ closes the string"_.
_"indentation of the opening quote"_ could mean one

1. The column offset of the `"` character itself. That would mean the entire string body must
   be to the right of the opening quote, which does force a more verbose layout that takes
   more vertical and horizontal space:

```scala
def openingParagraph = "
                         i am cow
                         hear me moo
                       "
def openingParagraph =
  "
    i am cow
    hear me moo
  "
```

2. The indentation of the statement which contains the opening quote. This allows a more compact
   syntax in some cases, but not others

```scala
def openingParagraph = "
  i am cow
  hear me moo
"

// The indentation of statement below is starts at "hello"
"hello"
  .map(
    foo => "
i am cow
hear me moo
"
  )
```

3. The column-offset of the first non-whitespace character on the line containing the opening `"`

```scala
def openingParagraph = "
  i am cow
  hear me moo
"

// The indentation/closing-quote is measured from the start of `foo =>`
"hello"
  .map(
    foo => "
    i am cow
    hear me moo
    "
  )
```

In general, such lexing rules very unusual: there is no precedence for this kind of
_"string terminates on a line with an odd number of quotes sprinkled anywhere within it"_
syntax anywhere in the broader programming landscape. Apart from violating users expectations,
such rules also violate tooling assumptions: while it is possible to do
such "line-based" lexing in the Scala compiler's hand-written parser, I expect it will
be challenging for other external tools, e.g. FastParse's parser combinators or syntax
highlighters like Github Linguist, Highlight.js, or Prism.js are not typically able
to encode rules such as _"the first line starting with a `"`
and with an odd number of `"`s terminates the multi-line string"_


#### Single-Quote with Header

One delimiter that uses `"`s, avoids introducing a new `'''` delimiter, and also
avoids the parsing edge cases and implementation challenges would be , e.g. `"---\n` would
need to be followed by `\n---"`. This header could be variable length, allowing the ability
to embed arbitrary contents without escaping, similar to the extendable `'''` delimiters
proposed above and present in [C#'s](#c) or [Swift's](#swift).

```scala
def openingParagraph = "---
  One dark and stormy night,
  he said
  "...i am cow
  hear me moo"
---"
```

Although in theory the delimiter between `"` and `\n` could contain any characters except
`"` and `\n` while remaining unambiguous, in practice we will likely want to limit it to
a small set e.g. dashes-only to avoid unnecessary flexibility in the syntax

### Other Syntaxes
- Triple-backticks are another syntax that is currently available, and so could be used as
  a multi-line delimiter. This has the advantage of being similar to blocks used in
  markdown, with a similar meaning, but several disadvantages:
   - It would collide if a user tries to embed Scala code in a markdown code block. In fact,
     I couldn't even figure out how to embed triple-backticks in this document!
   - Backticks are currently used for identifiers while single-quotes are used for literals,
     so single-quotes seems more appropriate to use for multi-line literals than backticks.
   - Single-quotes also would look more familiar to anyone coming from other languages like
     Python or Elixir (albeit with slightly different semantics) while triple-backticks have
     no precedence in any programming language.

- Other syntaxes like `@"..."` are possible, but probably too esoteric to be worth considering

### Alternative ways of specifying indentation

The proposed rule of specifies the indentation to be removed relies on the indentation of
the trailing `'''` delimiter. Other possible approaches include:

- The minimum indentation of any non-whitespace line within the string, which is why [Ruby does](#ruby)
    - This does not allow the user to define strings with all lines indented by some amount,
      unless the indentation of the closing delimiter is counted as well. But if the indentation
      of the closing delimiter is counted, then it is simpler to just use that, and prohibit
      other lines from being indented less than the delimiter

- An explicit indentation-counter, which is what YAML does, e.g. with the below text block
  dedenting the string by 4 characters:
```yaml
example: >4
    Several lines of text,
    with some "quotes" of various 'types',
    and also a blank line:

    and some text with
    extra indentation
    on the next line,
    plus another line at the end.
```

This works, but it is very unintuitive for users to have to translate the indentation to be
removed (which is a visual thing) into a number that gets written at the top of the block. In
contrast, the current proposal specifies the indentation to be removed in terms of the
indentation of the closing delimiter, which keeps it within the "visual" domain without
needing the user to count spaces.

## Prior Art

Many other languages have dedented string literals, all with exactly the same reason
and almost the same specification: trimming the leading and trailing newlines, along
with indentation. Many have similar rules for flexible delimiters to allow the strings
to contain arbitrary contents

### Java

Java since [JEP 378](https://openjdk.org/jeps/378) now multiline strings called "text blocks"
that implement exactly this, with identical leading/trailing newline and indentation removal
policies:

```java
String html = """
              <html>
                  <body>
                      <p>Hello, world</p>
                  </body>
              </html>
""";
```

> The re-indentation algorithm takes the content of a text block whose line terminators have
> been normalized to LF. It removes the same amount of white space from each line of content
> until at least one of the lines has a non-white space character in the leftmost position.
> The position of the opening """ characters has no effect on the algorithm, but the position
> of the closing """ characters does have an effect if placed on its own line.

Java doesn't have extended delimiters like those proposed here, but requires you to escape
`\"""` included in the text block using a backslash to prevent premature closing of the literal.

### C#

C# has [Raw String Literals](https://learn.microsoft.com/en-us/dotnet/csharp/language-reference/proposals/csharp-11.0/raw-string-literal)
with identical leading/trailing newline and indentation removal policies

```csharp
var xml = """
          <element attr="content">
            <body>
            </body>
          </element>
          """;
```

> To make the text easy to read and allow for indentation that developers like in code,
> these string literals will naturally remove the indentation specified on the last line
> when producing the final literal value. For example, a literal of the form:

C# also allows arbitrary-length delimiters as described in this propsoal

```csharp
var xml = """"
          Ok to use """ here
          """";
```

> Because the nested contents might itself want to use """ then the starting/ending
> delimiters can be longer

### Swift

Swift has [Multiline Strings](https://docs.swift.org/swift-book/documentation/the-swift-programming-language/stringsandcharacters/#Multiline-String-Literals) that behave identically to that described in this proposal,
with identical leading/trailing newline and indentation removal policies

```swift
let quotation = """
The White Rabbit put on his spectacles.  "Where shall I begin,
please your Majesty?" he asked.

"Begin at the beginning," the King said gravely, "and go on
till you come to the end; then stop."
"""
```

> A multiline string literal includes all of the lines between its opening and closing
> quotation marks. The string begins on the first line after the opening quotation marks
> (""") and ends on the line before the closing quotation marks, which means that neither
> of the strings below start or end with a line break:
>
> A multiline string can be indented to match the surrounding code. The whitespace before the
> closing quotation marks (""") tells Swift what whitespace to ignore before all of the other
> lines. However, if you write whitespace at the beginning of a line in addition to what’s before
> the closing quotation marks, that whitespace is included.

Swift also supports extended delimiters similar to that described in this proposal:

```swift
let threeMoreDoubleQuotationMarks = #"""
Here are three more double quotes: """
"""#
```

> String literals created using extended delimiters can also be multiline string literals.
> You can use extended delimiters to include the text """ in a multiline string, overriding
> the default behavior that ends the literal. For example:


### Elixir

Elixir's [Multiline Strings](https://hexdocs.pm/elixir/1.7.4/syntax-reference.html#strings)
behave exactly as this proposal:

```elixir
...> test = """
...>     this
...>     is
...>     a
...>     test
...> """
"    this\n    is\n    a\n    test\n"
...>test = """
...>     This
...>     Is
...>     A
...>     Test
...>     """
"This\nIs\nA\nTest\n"
```

> Multi-line strings in Elixir are written with three double-quotes, and can have unescaped
> quotes within them. The resulting string will end with a newline. The indentation of the
> last """ is used to strip indentation from the inner string. For example:

Elixir allows both `"""` and `'''` syntax for multi-line strings, with `'''`-delimited strings
allowing you to embed `"""` in the body (and vice versa). This is similar to Python's syntax
for triple-quoted strings

### Bash

Bash has multiple variants of `<< HEREDOC`:

```bash
cat << EOF
The current working directory is: $PWD
You are logged in as: $(whoami)
EOF
```

This includes `<<- HEREDOC` strings that strip indentation. This is done relatively naively,
by simply removing all leading `\t` tab characters.

> The first line starts with an optional command followed by the special redirection
> operator `<<` and the delimiting identifier.
>
> * You can use any string as a delimiting identifier, the most commonly used are EOF or END.
> * If the delimiting identifier is unquoted, the shell will substitute all variables, commands
>   and special characters before passing the here-document lines to the command.
> * Appending a minus sign to the redirection operator <<-, will cause all leading tab characters
>   to be ignored. This allows you to use indentation when writing here-documents in shell scripts. Leading whitespace characters are not allowed, only tab.
> * The here-document block can contain strings, variables, commands and any other type of input.
> * The last line ends with the delimiting identifier. White space in front of the delimiter is
>   not allowed.

### Ruby

Ruby has [Squiggly Heredoc](https://ruby-doc.org/core-2.5.0/doc/syntax/literals_rdoc.html#label-Strings),
inspired by Bash, but with a different "least indented non-whitespace-only" line policy
for removing indentation, rather than Bash's tab-based removal or a
closing-delimiter-indentation policy like the other languages above

```ruby
expected_result = <<~SQUIGGLY_HEREDOC
  This would contain specially formatted text.

  That might span many lines
SQUIGGLY_HEREDOC
```

> The indentation of the least-indented line will be removed from each line of the content.
> Note that empty lines and lines consisting solely of literal tabs and spaces will be ignored
> for the purposes of determining indentation, but escaped tabs and spaces are considered
> non-indentation characters.

As a HEREDOC-inspired syntax, you can change the header of your multi-line string in Ruby
to include arbitrary text in the body. This is different in form but similar in function to
the extended delimiters described in this proposal

```ruby
expected_result = <<~MY_CUSTOM_SQUIGGLY_HEREDOC
  This would contain specially formatted text.
  SQUIGGLY_HEREDOC
  That might span many lines
MY_CUSTOM_SQUIGGLY_HEREDOC
```

### Ocaml

Ocaml [allows single-quoted to span multiple lines](https://ocaml.org/manual/5.3/lex.html#sss:stringliterals),
and automatically removes indentation if the newline character is escaped with a preceding `\`:

```ocaml
# let contains_unexpected_spaces =
    "This multiline literal
     contains three consecutive spaces."

  let no_unexpected_spaces =
    "This multiline literal \n\
     uses a single space between all words.";;
val contains_unexpected_spaces : string =
  "This multiline literal\n   contains three consecutive spaces."
val no_unexpected_spaces : string =
  "This multiline literal \nuses a single space between all words."
```

However, Ocaml's "raw" string syntax `{| |}` does not have a mode that removes indentation,
which is an [open issue on the OCaml repo](https://github.com/ocaml/ocaml/issues/13860)

- 