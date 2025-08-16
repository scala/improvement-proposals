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

As `'''` is not valid syntax in Scala today, there are no backwards compatibility 
concerns.

## Motivation


This proposal resolves a lot of issues working with status-quo triple-quoted strings.
These issues aren't rocket science, but are a constant friction that makes working
with triple-quoted strings annoying and unpleasant.

### Verbosity & Visual Clarity

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

### Other Delimiters

`'''` was chosen as a currently-unused syntax in Scala, but other options are also
possible:

- Triple-double-quotes `"""` are already used with a particular semantic, so we cannot
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

- Single-quoted strings with `"` cannot currently span multiple lines, and so
  they could be specified to have these dedenting semantics when used multi-line. 
    - This has the advantage of not introducing a new delimiter, as `"` is already
      used for strings
    - This has the disadvantage that a single `"` isn't very visually distinct 
      when used for demarcating blocks of text, and separating them vertically
      from the code before and after
    - Some languages do have multi-line strings with single-character delimiters,
      e.g. Javascripts template literals use a single-backtick

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

Many other languages have exactly this feature, all with exactly the same reason
and exactly the same specification: trimming the leading and trailing newlines, along
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