---
layout: sip
title: SIP-65 - Generic Number Literals
stage: implementation
status: waiting-for-implementation
permalink: /sips/:title.html
---

**By: rjolly**

## History

| Date          | Version                  |
|---------------|--------------------------|
| Jul 23rd 2019 | Initial Draft            |

Your feedback is welcome! If you're interested in discussing this proposal, head over to [this](https://contributors.scala-lang.org/t/status-of-experimental-numeric-literals/6658) Scala Contributors thread and let me know what you think.

## Proposal

Support generic number literals, as in `val x: BigInt = 111111100000022222222222`.

## Motivation

See <https://github.com/scala/scala3/pull/6919>.

### Specification

See <https://dotty.epfl.ch/docs/reference/experimental/numeric-literals.html>.

## Alternatives

See <https://contributors.scala-lang.org/t/pre-sip-sharp-string-interpolation/5836>.

## Implementation

The implementation of generic number literals can be found at <https://github.com/scala/scala3/pull/6919>.
