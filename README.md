# Scala Improvement Proposals

This repository contains the proposals of the Scala Improvement Process.

For more details about the Scala Improvement Process, please read the
[documentation](https://docs.scala-lang.org/sips).

The SIP pages of the documentation website are generated from data in this
repository. The GitHub workflow `.github/workflows/generate-docs.yaml`
periodically executes the script `.github/scripts/generate-docs.scala`,
which generates the website content and pushes it to the repository
[scala/docs.scala-lang](https://github.com/scala/docs.scala-lang).

Note that the state of the proposals in the “design” stage is defined by
their [labels](https://github.com/scala/improvement-proposals/labels),
whereas the state of the proposals in the “implementation” stage is defined
by their YAML frontmatter (e.g.
[SIP-42](https://github.com/scala/improvement-proposals/blob/583b458e3a7c0d2310e1a71a3812d73f7f6efebc/content/binary-integer-literals.md?plain=1#L1-L7)).
