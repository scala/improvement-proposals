---
layout: sip
permalink: /sips/:title.html
stage: implementation
status: waiting-for-implementation
title: SIP-NN Scala CLI as default Scala command
---

**By: Krzysztof Romanowski and  Scala CLI team**

## History

| Date          | Version            |
|---------------|--------------------|
| July 15th 2022 | Initial Draft      |

## Summary

We propose to replace current script that is installed as `scala` with Scala CLI - a batteries included tool to interact with Scala. Scala CLI brings all the features that the commands above provide and expand them with incremental compilation, dependency management, packaging and much more. 

Even though Scala CLI could replace `scaladoc` and `scalac` commands as well for now, we do not propose to replace them.


## Motivation

The current default `scala` script is quite limited since it can only start repl or run pre-compile Scala code.

The current script are lacking basic features such as support for resolving dependencies, incremental compilation or support for outputs other than JVM. This forces any user that wants to do anything more than just basic things to learn and use SBT, Mill or an other build tool and that adds to the complexity of learning Scala. 

We observe that the current state of tooling in Scala is limiting creativity, with quite a high cost to create e.g. an application or a script with some dependencies that target Node.js. Many Scala developers are not choosing Scala for their personal projects, scripts, or small applications and we believe that the complexity of setting up a build tool is one of the reasons. 

With this proposal our main goal is to turn Scala into a language with "batteries included" that will also respect the community-first aspect of our ecosystem.

## Proposed solution

We propose to gradually replace the current `scala`, `scalac` and `scaladoc` commands by single `scala` command that under the hood will be `scala-cli`. We could also add wrapper scripts for `scalac` and `scaladoc` that will mimic the functionality that will use `scala-cli` under the hood. 

The complete set of `scala-cli` features can be found in [its documentation](https://scala-cli.virtuslab.org/docs/overview).

Scala CLI brings many features like testing, packaging, exporting to sbt / Mill or upcoming support for publishing micro-libraries. Initially, we may want to limit the set of features available in the `scala` command by default. Scala CLI is a relatively new project and we should battle-proof some of its features before we commit to support them as part of the offical `scala` command. 

Scala CLI offers [multiple native ways to be installed](https://scala-cli.virtuslab.org/install#advanced-installation) so most users should find a suitable method. We would like these packages to become the default `scala` package in most repositories, often replacing existing `scala` packages.

### High-level overview

Let us show a few examples where adopting Scala CLI as `scala` command would be a significant improvement ofer current scripts. For this, we have assumed a minial set of features. Each additional  Scala CLI feature included, such as `package`, would add more and more use cases.

**Using REPL with a 3rd-party dependency**

Currently, to start a Scala REPL with a dependency on the class path, users need to resolve this dependency with all its transitive dependencies (coursier can help here) and pass those to the `scala` command using the `--cp` option. Alternatively, one can create an sbt project including a single dependency and use the `sbt console` task. Ammonite gives a better experience with its magic imports. 

With Scala CLI, starting a REPL with a given dependency is as simple as running:

```
scala-cli repl --dep com.lihaoyi::os-lib:0.7.8
```

Compared to Ammonite, default Scala REPLs provided by Scala 2 and 3 - that Scala CLI uses by default - are somewhat limited. However, Scala CLI also offers to start Ammonite instead of the default Scala REPL, by passing `--ammonite` (or `--amm`) option to `scala-cli repl`.

Additionally, `scala-cli repl` can also put code from given files / directories / snippets on the class path by just providing their locations as arguments. Running `scala-cli repl foo.scala baz` will compile code from `foo.scala` and the `baz` directory, and put their classes on the REPL class path (including their dependencies, scalac options etc. defined within those files).

Compilation (and running scaladoc as well) benefit in a similar way from the ability to manage dependencies.

** Providing reproductions of bugs **

Currently, when reporting a bug in the compiler (or any other Scala-related) repository, users need to provide depencencies, compiler options etc. in comments, create a repository containing a projet with a Mill / sbt configuration to reproduce. In general, testing the reporoduction or working on further minization is not straightworwad.

"Using directives", provided by Scala CLI give the ablity to include the whole configuration in single file, for example:

```scala
//> using platform "native"
//> using "com.lihaoyi::os-lib:0.7.8"
//> using options "-Xfatal-warnings"
 
def foo = println("<here comes the buggy warning with Scala Native and os-lib>")
```

The snippet above when run with Scala CLI without any configuration provided will use Scala Native, resolve and include `os-lib` and provide `-Xfatal-warnings` to the compiler. Even things such as the runtime JVM version can be configured with using directives.

Moreover, Scala CLI provides the ability to run GitHub gists (including multi-file ones), and more.

** Watch mode **

When working on a piece of code, it is often useful to have it compiled/run everytime the file is changed, and build tools offer a watch mode for that. This is how most people are using watch mode through a build tool. Scala CLI offers a watch mode for most of its commands (by using `--watch` / `-w` flags).


### Specification

 In order to be able to expand the functionality of Scala CLI and yet use the same core to power the `scala` command, we propose to include both `scala` and `scala-cli` commands in the installation package. Scala CLI already has a feature to limit accessible sub-commands based the binary name (all sub-commands in `scala-cli`, and a curated list in `scala`). Later, we can include more and more features from `scala-cli` into `scala`. 

The sub-commands necessary to include in the `scala` to match the functionalities of current commands:
 - `compile`
 - `run`
 - `repl`
 - `doc`

On top of that, we think that the following user-facing sub-commands should also be included:
 - `clean` - to rebuild project from start without any cached steps
 - `setup-ide` - to control the setup of BSP for IDE integration
 - `doctor` - to analyze if everything is installed properly

We also suggest to include additional sub-commands by default: 
 - `fmt` - to format the code using scalafmt
 - `test` - to run tests included in the code
 - `package` - to package the code into various package formats: JAR, "fat"  be, executable  be or even native application or docker image
 - `shebang` - a command useful for scripting, designed to be included in the "shebang" section of scripts
 - `export` - transform current project to sbt / Mill project using the same settings as provided. Useful to evolve prototypes into bigger projects

Each of these commands expands what the current scripts offer and can be discussed separately. We can even open a dedicated SIP for each of them.

Beyond that, `scala-cli` offers multiple sub-commands needed to manage itself (e.g. `update`) or its components (e.g. the Bloop server). In most cases,
these are not user-facing, but still handy. We can elaborate in more detail on what those commands are and why we need them, if necessary.

Scala CLI can also be configured with ["using directives"](https://scala-cli.virtuslab.org/docs/guides/using-directives) - a comment-based configuration syntax that should be placed at the top of Scala files. This allows for self-containing examples within one file since most of the configuration can be provided either from the command line or via using directives (command line has precedence). This is a game changer for use cases like scripting, reproduction, or within the academic scope.

We have described the motivation, syntax and implementation basis in the [dedicated pre-SIP](https://contributors.scala-lang.org/t/pre-sip-using-directives/5700). Currently, we recommend to write using directives as comments, so making them part of the language specification is not necessary at this stage. Moreover, the new `scala` command could ignore using directives in the initial version, however we strongly suggest to include comment-based using directives from the start. 

#### Commands, options and directives

We have generated a complete lists of supported [commands](https://github.com/romanowski/scala-cli/blob/6b7e9614078070ce8ff6cbcb0635b78901228749/website/src/pages/scala-command/commands.md), (CLI options)[https://github.com/romanowski/scala-cli/blob/6b7e9614078070ce8ff6cbcb0635b78901228749/website/src/pages/scala-command/cli-options.md] and (directives)[https://github.com/romanowski/scala-cli/blob/6b7e9614078070ce8ff6cbcb0635b78901228749/website/src/pages/scala-command/directives.md] that we propose for new `scala` command. Lists contain many options and commands that are marked as `experimental` and/or `internal` and as such, we could change them at any point.

The lists was generated automatically and starting from next release of Scala CLI such documentation will be included on our website. Using that lists, it will be easy to track and spot any changes in exposed APIs.

### Compatibility

Adopting Scala CLI as the new `scala` command, as is, will change some of the behaviour of today's scripts. Some examples:

- `scala repl` needs to be run to start a REPL instead of just `scala`
- with `scala compile` and `scala doc`, some of the more obscure (or brand new) compile options need to be prefixed with `-O`
- Scala CLI recognizes tests based on the extension used (`*.test.scala`) so running `scala compile a.scala a.test.scala` will only compile `a.scala`
- Scala CLI has its own versioning scheme, that is not related to the Scala compiler. Default version used may dynamically change when new Scala version is released.
- By default, Scala CLI manages its own dependencies (e.g. scalac, zinc, Bloop) and resolves them lazily. This means that the first run of Scala CLI resolves quite some dependencies. Moreover, Scala CLI periodically checks for updates, new defaults accessing online resources (but it is not required to work, so Scala CLI can work in offline environment once setup)
- Scala CLI can also be configured via using directives. Command line options have precedence over using directives, however using directives override defaults. Compiling a file starting with `//> using scala 2.13.8`, without providing a Scala version on the command line, will result in using `2.13.8` rather than the default Scala version. We consider this a feature. However, technically, this is a breaking change.

### Other concerns

Scala CLI brings [using directives](https://scala-cli.virtuslab.org/docs/guides/using-directives) and  [conventions to mark the test files](https://scala-cli.virtuslab.org/docs/commands/test#test-sources). We are not sure if both can be accepted as a part of this SIP or we should have seperate SIPs for both (we have opened a [pre-SIP for using directives](https://contributors.scala-lang.org/t/pre-sip-using-directives/5700/15))

Scala CLI is an ambitious project and may seem hard to maintain in the long-run. // TODO


### Open questions

The main open question for this proposal is wich commands/features should be included by default in the `scala` command. Another aspect is the release cadence: should the new `scala` command follow the current release cadence for Scala CLI (every 2 weeks) or stick to Scala one (every 6 weeks)?

## Alternatives

Scala CLI has many alternatives. The most obvious ones are sbt, Mill, or other build tools. However, these are more complicated than Scala CLI, and what is more important they are not designed as command-line first tools. Ammonite, is another alternative, however it covers only part of the Scala CLI features (REPL and scripting), and lacks many of the Scala CLI features (incremental compilation, Scala version selection, support for Scala.js and Scala Native, just to name a few). 

## Related work

- [Scala CLI website](https://scala-cli.virtuslab.org/) and [road map](https://github.com/VirtusLab/scala-cli/discussions/1101)
- [Pre-SIP](https://contributors.scala-lang.org/t/pre-sip-scala-cli-as-new-scala-command/5628/22)
- [leiningen](https://leiningen.org/) - a similar tool from Closure, but more configuration-oriented

## FAQ

This section will probably initially be empty. As discussions on the proposal progress, it is likely that some questions will come repeatedly. They should be listed here, with appropriate answers.
