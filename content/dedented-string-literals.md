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
- The final newline before the closing `'''`
- Any indentation up to the position of the closing `'''`

The opening `'''` MUST be followed immediately by a newline, and the trailing `'''` MUST
be preceded by a newline followed by whitespace characters.

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

As `'''` is not valid syntax in Scala today, there are no backwards compatibility 
concerns.

## Motivation


This proposal resolves a lot of issues working with status-quo triple-quoted strings.
These issues aren't rocket science, but are a constant friction that makes working
with triple-quoted strings annoying and unpleasant.

## Verbosity & Visual Clarity

Using traditional `""".stripMargin` strings with `|` and `.stripMargin` is very verbose, which
interferes with visually reading the code.

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

## Incorrectness with Multiline Interpolation

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

## Literal/Singleton Types

`.stripLiteral` strings are not literals, and cannot generate `String & Singleton` types
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

This means that `""".stripLiteral` strings cannot take part in type-level logic
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

This also means that any macros that may work on string literals, e.g. validating
the string literal at build time, would not be able to work with multiline strings.
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
[discussed above](#incorrectness-with-mutliline-interpolation). However, using an interpolator
does not solve the other issues of multiline strings not being valid 
[literal types](#literalsingleton-types)

Having a dedicate `tq"""` interpolator also means multiline strings cannot be used with
other existing interpolators, such as `s""`, `r""`, or user-specified interpolators
like `sql""` introduced by libraries like [ScalaSql](https://github.com/com-lihaoyi/scalasql)

### Other syntaxes for multiline strings

`'''` was chosen as a currently-unused syntax in Scala, but other options are also
possible:

- Triple-double-quotes `"""` are already used with a particular semantic, so we cannot
  change those, despite them being used in every other language like [Java](#java),
  [C#](#c), and [Swift](#swift).

- Single-quoted strings with `"` cannot currently span multiple lines, and so
  they could be specified to have these semantics when used multi-line. This has
  the advantage of not introducing a new delimiter, but the disadvantage that a
  single `"` isn't very visually distinct when used for demarcating vertical blocks
  of text

- Triple-backticks are another syntax that is currently available, and so could be used as
  a multi-line delimiter. This has the advantage of being similar to blocks used in
  markdown, with a similar meaning, but the disadvantage that it would collide if a
  user tries to embed Scala code in a markdown code block

- Other syntaxes like `@"..."` are possible, but probably too esoteric to be worth considering


### Triple-Backticked Multiline Strings

## Prior Art

Many other languages have exactly this feature, all with exactly the same reason
and exactly the same specification: trimming the leading and trailing newlines, along
with indentation.

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

### Elixer

Elixer's [Multiline Strings](https://hexdocs.pm/elixir/1.7.4/syntax-reference.html#strings)
behave exactly as this proposal:

```elixer
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

### Ruby

Ruby has [Squiggly Heredoc](https://ruby-doc.org/core-2.5.0/doc/syntax/literals_rdoc.html#label-Strings) 
strings that have similar leading/trailing newline removal, but has a 
"least indented non-whitespace-only" line policy for removing indentation, rather than a
closing-delimiter policy like the other languages above

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