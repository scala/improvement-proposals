# Scala Tooling Ecosystem

This file lists tools that are used to work with Scala (other than the compiler
itself), and that may be impacted by changes to the language. As stated in the
[Scala Improvement Process Specification](https://docs.scala-lang.org/sips/process-specification.html),
whenever a proposal reaches the implementation stage, the Committee notifies the
impacted tools that they should implement support for it.

## Tools Impacted by Changes in the Grammar

- [Scalameta parser][Scalameta]
- [Scalafmt]: depends on the Scalameta parser, and it needs to add specific
  support for each new syntactic feature
- [Scalafix]: depends on the Scalameta parser
- [VS Code Scala syntax]: also used by GitHub to provide syntax highlighting
  (see https://github.com/github/linguist/tree/master/vendor)
- [Metals]: depends on all of the above to provide various functionalities
  (syntax errors as-you-type, formatting, refactors, syntax highlighting)
- [IntelliJ]: maintains its own parser
- [Pygments]: used by Python-based website generators like MkDocs
- [Highlight.js]: used by the Scala website
- [Prism.js]: used for example by website generators like Docusaurus (which is
  used by many OSS Scala projects)
- [tree-sitter]

## Tools Impacted by Other (Non-Syntactical) Changes

- [Metals]
- [IntelliJ]

[Metals]: https://github.com/scalameta/metals
[IntelliJ]: https://github.com/JetBrains/intellij-scala
[Scalameta]: https://github.com/scalameta/scalameta
[Scalafmt]: https://github.com/scalameta/scalafmt
[Scalafix]: https://github.com/scalacenter/scalafix
[VS Code Scala syntax]: https://github.com/scala/vscode-scala-syntax
[Pygments]: https://github.com/pygments/pygments
[Highlight.js]: https://github.com/highlightjs/highlight.js/blob/main/src/languages/scala.js
[Prism.js]: https://github.com/PrismJS/prism/blob/master/components/prism-scala.js
[tree-sitter]: https://github.com/tree-sitter/tree-sitter-scala
