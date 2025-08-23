---
layout: sip
permalink: /sips/:title.html
stage: implementation
status: under-review
title: SIP-61 - Unroll Default Arguments for Binary Compatibility
---

**By: Li Haoyi**

## History

| Date        | Version            |
|-------------|--------------------|
| 23 Aug 2024 | Initial Draft      |


## Summary

This proposal provides a syntax to "unpack" a `case class` _type_ into a definition-site 
parameter list via the `unpack` keyword, and to "unpack" `case class` _value_ into a
definition-site argument list via `*`: 

```scala
case class RequestConfig(url: String, 
                         connectTimeout: Int,
                         readTimeout: Int)

def downloadSimple(unpack config: RequestConfig) = doSomethingWith(config)
def downloadAsync(unpack config: RequestConfig, ec: ExecutionContext) = doSomethingWith(config)
def downloadStream(unpack config: RequestConfig) = doSomethingWith(config)

// Call with individual parameters
val data = downloadSimple("www.example.com", 1000, 10000)
val futureData = downloadAsync("www.example.com", 1000, 10000, ExecutionContext.global)
val stream = downloadStream(url = "www.example.com", connectTimeout = 1000, readTimeout = 10000)

// Call with config object
val config = RequestConfig("www.example.com", 1000, 10000)
val data2 = downloadSimple(config*)
val futureData2 = downloadAsync(config*, ExecutionContext.global)
val stream2 = downloadStream(config*)
```

The delegation performed by `unpack` is very similar to inheritance with
`extends`, or composition with `export`. Scala has always had good ways to DRY up repetitive
member definitions, but has so far had no good way to DRY up repetitive parameter lists.
`unpack` provides the way to do so.

## Motivation

This proposal removes a tremendous amount of boilerplate converting between data structures
and method calls in Scala. For example, the code snippet above without this feature would 
have the pa:

```scala
case class RequestConfig(url: String, 
                         connectTimeout: Int,
                         readTimeout: Int)

def downloadSimple(url: String,
                   connectTimeout: Int,
                   readTimeout: Int) = doSomethingWith(config)
def downloadAsync(url: String,
                  connectTimeout: Int,
                  readTimeout: Int,
                  ec: ExecutionContext) = doSomethingWith(config)
def downloadStream(url: String,
                   connectTimeout: Int,
                   readTimeout: Int) = doSomethingWith(config)

// Call with individual parameters
val data = downloadSimple("www.example.com", 1000, 10000)
val stream = downloadStream(url = "www.example.com", connectTimeout = 1000, readTimeout = 10000)

// Call with config object
val config = RequestConfig("www.example.com", 1000, 10000)
val data = downloadSimple(
  url = config.url,
  connectTimeout = config.connectTimeout,
  readTimeout = config.readTimeout
)
val futureData = downloadAsync(
  url = config.url,
  connectTimeout = config.connectTimeout,
  readTimeout = config.readTimeout,
  ec = ExecutionContext.global
)
val stream = downloadStream(
  url = config.url,
  connectTimeout = config.connectTimeout,
  readTimeout = config.readTimeout
)
```

Apart from the huge amounts of code that are required without `unpack` keyword, at both
definition-site and call-site, there are some specific things worth noting:

1. The "interesting" parts of the code are much harder to spot with all the boilerplate.
   For example, `downloadAsync` takes an extra `ec: ExecutionContext`, while the
   other two `download` methods do not. This is obvious in the `unpack` implementation,
   but invisible in the boilerplate of the status-quo implementation

2. `Call with individual parameters` is very convenient, but the verbosity
   happens in the `Call with config object` use case. Both scenarios are 
   extremely common in practice: sometimes you want to call a method now, sometimes you want
   to save the parameters and call the method later.

An alternative way to write this today would be using the `RequestConfig` object as the
API to the `download` methods:

```scala
case class RequestConfig(url: String, 
                         connectTimeout: Int,
                         readTimeout: Int)

def downloadSimple(config: RequestConfig) = doSomethingWith(config)
def downloadAsync(config: RequestConfig, ec: ExecutionContext) = doSomethingWith(config)
def downloadStream(config: RequestConfig) = doSomethingWith(config)

// Call with individual parameters
val data = downloadSimple(RequestConfig("www.example.com", 1000, 10000))
val futureData = downloadAsync(RequestConfig("www.example.com", 1000, 10000), ExecutionContext.global)
val stream = downloadStream(RequestConfig(url = "www.example.com", connectTimeout = 1000, readTimeout = 10000))

// Call with config object
val config = RequestConfig("www.example.com", 1000, 10000)
val data = downloadSimple(config)
val futureData = downloadAsync(config, ExecutionContext.global)
val stream = downloadStream(config)
```

This removes one set of boilerplate from the `Call with config object` section, but
adds new boilerplate to the `Call with individual parameters` section. Although it is more 
concise, this change complicates the API of `download`, needing users to remember to import
and use the `RequestConfig` wrapper. 

Apart from the boilerplate, some things to note:

1. The `RequestConfig` object is really just an implementation detail of `download` meant
   to shared parameters and args between the different `download` methods. From a user 
   perspective, the name is meaningless and the contents are arbitrary: someone calling 
   `downloadAsync` would have to pass some params inside a `RequestConfig`, some parameters
   outside `RequestConfig`, with no reason why some parameters should go in one place or another

2. If you want to share code between even more methods, you may end up with multiple `FooConfig`
   objects that the user has to construct to call your method, possibly nested. The user would
   have to import several `Config` classes and instantiate a tree-shaped data structure just to
   call these methods. But this tree-structure does not model anything the user cares about, but
   instead models the code-sharing relationships between the various `def download` methods

```scala
case class RequestConfig(url: String,
                         timeoutConfig: TimeoutConfig)
case class TimeoutConfig(connectTimeout: Int,
                         readTimeout: Int)
case class AsyncConfig(retry: Boolean, ec: ExecutionContext)
def downloadSimple(config: RequestConfig) = doSomethingWith(config)
def downloadAsync(config: RequestConfig, asyncConfig: AsyncConfig) = doSomethingWith(config)
def downloadStream(config: RequestConfig, asyncConfig: AsyncConfig) = doSomethingWith(config)

// Call with individual parameters
val data = downloadSimple(RequestConfig("www.example.com", TimeoutConfig(1000, 10000)))
val futureData = downloadAsync(
  RequestConfig("www.example.com", TimeoutConfig(1000, 10000)), 
  AsyncConfig(true, ExecutionContext.global)
)
val stream = downloadStream(
  RequestConfig(
    url = "www.example.com", 
    timeoutConfig = TimeoutConfig(connectTimeout = 1000, readTimeout = 10000)
  ),
  AsyncConfig(retry = true, ec = ExecutionContext.global)
)
```

There are other more sophisticated ways that a library author can try to resolve this problem -
e.g. builder patterns - but the fundamental problem is unsolvable today. `unpack`/`*` solves
this neatly, allowing the library author to use `unpack` in their definition-site parameter lists
to share parameters between definitions, and the library user can either pass parameters 
individually or unpack a configuration object via `*`, resulting in both the definition site
and the call site being boilerplate-free, even in the more involved example above:

```scala
case class RequestConfig(url: String,
                         unpack timeoutConfig: TimeoutConfig)
case class TimeoutConfig(connectTimeout: Int,
                         readTimeout: Int)
case class AsyncConfig(retry: Boolean, ec: ExecutionContext)
def downloadSimple(unpack config: RequestConfig) = doSomethingWith(config)
def downloadAsync(unpack config: RequestConfig, unpack asyncConfig: AsyncConfig) = doSomethingWith(config)
def downloadStream(unpack config: RequestConfig, unpack asyncConfig: AsyncConfig) = doSomethingWith(config)

// Call with individual parameters
val data = downloadSimple("www.example.com", 1000, 10000)
val futureData = downloadAsync(
  "www.example.com", 
  1000,
  10000, 
  true,
  ExecutionContext.global
)

val stream = downloadStream(
  url = "www.example.com", 
  connectTimeout = 1000, 
  readTimeout = 10000,
  retry = true, 
  ec = ExecutionContext.global
)

// Call with config object
val config = RequestConfig("www.example.com", TimeoutConfig(1000, 10000))
val asyncConfig = AsyncConfig(retry = true, ec = ExecutionContext.global)

val data = downloadSimple(config*)
val futureData = downloadAsync(config*, asyncConfig*)
val stream = downloadStream(config*, asyncConfig*)
```

## Applications in the Wild

### Requests-Scala

One application for this is Requests-Scala codebase, which inspired the example above.
In the real code, the list of parameters is substantially longer. `def apply` and `def stream`
sharing most parameters - but not all of them - and `apply` delegates to `stream` internally.
There is also already a `case class Request` object that encapsulates the "common" parameters
between them, which is useful if you want to save a request config to use later:

```scala
class Requester{
  def apply(
     url: String,
     auth: RequestAuth = sess.auth,
     params: Iterable[(String, String)] = Nil,
     headers: Iterable[(String, String)] = Nil,
     data: RequestBlob = RequestBlob.EmptyRequestBlob,
     readTimeout: Int = sess.readTimeout,
     connectTimeout: Int = sess.connectTimeout,
     proxy: (String, Int) = sess.proxy,
     cert: Cert = sess.cert,
     sslContext: SSLContext = sess.sslContext,
     cookies: Map[String, HttpCookie] = Map(),
     cookieValues: Map[String, String] = Map(),
     maxRedirects: Int = sess.maxRedirects,
     verifySslCerts: Boolean = sess.verifySslCerts,
     autoDecompress: Boolean = sess.autoDecompress,
     compress: Compress = sess.compress,
     keepAlive: Boolean = true,
     check: Boolean = sess.check,
     chunkedUpload: Boolean = sess.chunkedUpload,
   ): Response = {
    ...
    stream(
    url = url,
    auth = auth,
    params = params,
    blobHeaders = data.headers,
    headers = headers,
    data = data,
    readTimeout = readTimeout,
    connectTimeout = connectTimeout,
    proxy = proxy,
    cert = cert,
    sslContext = sslContext,
    cookies = cookies,
    cookieValues = cookieValues,
    maxRedirects = maxRedirects,
    verifySslCerts = verifySslCerts,
    autoDecompress = autoDecompress,
    compress = compress,
    keepAlive = keepAlive,
    check = check,
    chunkedUpload = chunkedUpload,
    onHeadersReceived = sh => streamHeaders = sh,
    )
    ...
  }
  def stream(
    url: String,
    auth: RequestAuth = sess.auth,
    params: Iterable[(String, String)] = Nil,
    blobHeaders: Iterable[(String, String)] = Nil,
    headers: Iterable[(String, String)] = Nil,
    data: RequestBlob = RequestBlob.EmptyRequestBlob,
    readTimeout: Int = sess.readTimeout,
    connectTimeout: Int = sess.connectTimeout,
    proxy: (String, Int) = sess.proxy,
    cert: Cert = sess.cert,
    sslContext: SSLContext = sess.sslContext,
    cookies: Map[String, HttpCookie] = Map(),
    cookieValues: Map[String, String] = Map(),
    maxRedirects: Int = sess.maxRedirects,
    verifySslCerts: Boolean = sess.verifySslCerts,
    autoDecompress: Boolean = sess.autoDecompress,
    compress: Compress = sess.compress,
    keepAlive: Boolean = true,
    check: Boolean = true,
    chunkedUpload: Boolean = false,
    redirectedFrom: Option[Response] = None,
    onHeadersReceived: StreamHeaders => Unit = null,
    ): geny.Readable = ...
  
  def apply(r: Request, data: RequestBlob, chunkedUpload: Boolean): Response =
    apply(
      r.url,
      r.auth,
      r.params,
      r.headers,
      data,
      r.readTimeout,
      r.connectTimeout,
      r.proxy,
      r.cert,
      r.sslContext,
      r.cookies,
      r.cookieValues,
      r.maxRedirects,
      r.verifySslCerts,
      r.autoDecompress,
      r.compress,
      r.keepAlive,
      r.check,
      chunkedUpload,
    )

  def stream(
    r: Request,
    data: RequestBlob,
    chunkedUpload: Boolean,
    onHeadersReceived: StreamHeaders => Unit,
  ): geny.Writable =
    stream(
      url = r.url,
      auth = r.auth,
      params = r.params,
      blobHeaders = Seq.empty[(String, String)],
      headers = r.headers,
      data = data,
      readTimeout = r.readTimeout,
      connectTimeout = r.connectTimeout,
      proxy = r.proxy,
      cert = r.cert,
      sslContext = r.sslContext,
      cookies = r.cookies,
      cookieValues = r.cookieValues,
      maxRedirects = r.maxRedirects,
      verifySslCerts = r.verifySslCerts,
      autoDecompress = r.autoDecompress,
      compress = r.compress,
      keepAlive = r.keepAlive,
      check = r.check,
      chunkedUpload = chunkedUpload,
      redirectedFrom = None,
      onHeadersReceived = onHeadersReceived,
    )
}

case class Request(
  url: String,
  auth: RequestAuth = RequestAuth.Empty,
  params: Iterable[(String, String)] = Nil,
  headers: Iterable[(String, String)] = Nil,
  readTimeout: Int = 0,
  connectTimeout: Int = 0,
  proxy: (String, Int) = null,
  cert: Cert = null,
  sslContext: SSLContext = null,
  cookies: Map[String, HttpCookie] = Map(),
  cookieValues: Map[String, String] = Map(),
  maxRedirects: Int = 5,
  verifySslCerts: Boolean = true,
  autoDecompress: Boolean = true,
  compress: Compress = Compress.None,
  keepAlive: Boolean = true,
  check: Boolean = true,
)
```

Requests-Scala is like this way because `requests.get(url = "...", data = ..., readTimeout = ...)`
is the API that users want, which can also be seen by the popularity of the upstream Python Requests 
library. However, providing this call-site API requires huge amounts of boilerplate, whereas
with `unpack` it could be defined as follows:

```scala
class Requester{
  def apply(
     unpack request: Request,
     chunkedUpload: Boolean = sess.chunkedUpload,
   ): Response = {
    ...
    stream(
      request*,
      chunkedUpload = chunkedUpload,
      onHeadersReceived = sh => streamHeaders = sh,
    )
    ...
  }
  def stream(
    unpack request: Request,
    chunkedUpload: Boolean = false,
    redirectedFrom: Option[Response] = None,
    onHeadersReceived: StreamHeaders => Unit = null,
    ): geny.Readable = ...
}

case class Request(
  url: String,
  auth: RequestAuth = RequestAuth.Empty,
  params: Iterable[(String, String)] = Nil,
  headers: Iterable[(String, String)] = Nil,
  readTimeout: Int = 0,
  connectTimeout: Int = 0,
  proxy: (String, Int) = null,
  cert: Cert = null,
  sslContext: SSLContext = null,
  cookies: Map[String, HttpCookie] = Map(),
  cookieValues: Map[String, String] = Map(),
  maxRedirects: Int = 5,
  verifySslCerts: Boolean = true,
  autoDecompress: Boolean = true,
  compress: Compress = Compress.None,
  keepAlive: Boolean = true,
  check: Boolean = true,
)
```

Things to note:
* There is a massive reduction in boilerplate from 147 lines to 40 lines, and
  the code is much clearer as now the differences between `def apply`, `def stream`,
  and `case class Request` are obvious at a glance

* The `def apply(r: Request, data: RequestBlob, chunkedUpload: Boolean)`
  and `def stream(r: Request, data: RequestBlob, chunkedUpload: Boolean, onHeadersReceived: StreamHeaders => Unit)`
  overloads are no longer necessary. If someone has a `Request` object, they can simply call
  `requests.get.apply(request*, ...)` or `requests.get.stream(request*, ...)` to pass it in,
  without needing a dedicated overload taking a `r: Request` object as the first parameter

### uPickle

uPickle has a similar API, where the user can call 
```scala
val s: String = upickle.default.write(value, indent = 2, sortKeys = true)

val baos = new ByteArrayOutputStram()
upickle.default.writeToOutputStream(value, baos, indent = 2, sortKeys = true)

val b: Array[Byte][] = upickle.default.writeToByteArray(value, indent = 2, sortKeys = true)
```

This requires definitions such as

```scala
trait Api {
  def write[T: Writer](t: T,
                       indent: Int = -1,
                       escapeUnicode: Boolean = false,
                       sortKeys: Boolean = false): String
  
  def writeTo[T: Writer](t: T,
                         out: java.io.Writer,
                         indent: Int = -1,
                         escapeUnicode: Boolean = false,
                         sortKeys: Boolean = false): Unit
  
  def writeToOutputStream[T: Writer](t: T,
                                     out: java.io.OutputStream,
                                     indent: Int = -1,
                                     escapeUnicode: Boolean = false,
                                     sortKeys: Boolean = false): Unit

  def writeToByteArray[T: Writer](t: T,
                                  indent: Int = -1,
                                  escapeUnicode: Boolean = false,
                                  sortKeys: Boolean = false): Array[Byte]

  def stream[T: Writer](t: T,
                        indent: Int = -1,
                        escapeUnicode: Boolean = false,
                        sortKeys: Boolean = false): geny.Writable
}
```

With `unpack`, this could be consolidated as:

```scala
trait Api {
  case class WriteConfig(indent: Int = -1,
                         escapeUnicode: Boolean = false,
                         sortKeys: Boolean = false)
  
  def write[T: Writer](t: T,
                       unpack writeConfig: WriteConfig): String
  
  def writeTo[T: Writer](t: T,
                         out: java.io.Writer,
                         unpack writeConfig: WriteConfig): Unit
  def writeToOutputStream[T: Writer](t: T,
                                     out: java.io.OutputStream,
                                     unpack writeConfig: WriteConfig): Unit

  def writeToByteArray[T: Writer](t: T,
                                  unpack writeConfig: WriteConfig): Array[Byte]

  def stream[T: Writer](t: T,
                        unpack writeConfig: WriteConfig): geny.Writable
}
```

### OS-Lib

OS-Lib has similar APIs, e.g. 
```scala
os.walk(path, preOrder = false, followLinks = true)
os.walk.attrs(path, preOrder = false, followLinks = true)
os.walk.stream(path, preOrder = false, followLinks = true)
```

These are defined as 

```scala
object walk{
  def apply(
      path: Path,
      skip: Path => Boolean = _ => false,
      preOrder: Boolean = true,
      followLinks: Boolean = false,
      maxDepth: Int = Int.MaxValue,
      includeTarget: Boolean = false
  ): IndexedSeq[Path] = {
    stream(
      path,
      skip,
      preOrder,
      followLinks,
      maxDepth,
      includeTarget
    ).toArray[Path].toIndexedSeq
  }
  def attrs(
    path: Path,
    skip: (Path, os.StatInfo) => Boolean = (_, _) => false,
    preOrder: Boolean = true,
    followLinks: Boolean = false,
    maxDepth: Int = Int.MaxValue,
    includeTarget: Boolean = false
  ): IndexedSeq[(Path, os.StatInfo)] = {
    stream
      .attrs(
        path,
        skip,
        preOrder,
        followLinks,
        maxDepth,
        includeTarget
      )
      .toArray[(Path, os.StatInfo)].toIndexedSeq
  }
  object stream {
    def apply(
      path: Path,
      skip: Path => Boolean = _ => false,
      preOrder: Boolean = true,
      followLinks: Boolean = false,
      maxDepth: Int = Int.MaxValue,
      includeTarget: Boolean = false
    ): Generator[Path] = {
      attrs(
        path,
        (p, _) => skip(p),
        preOrder, 
        followLinks,
        maxDepth,
        includeTarget
      ).map(_._1)
    }
    def attrs(
      path: Path,
      skip: (Path, os.StatInfo) => Boolean = (_, _) => false,
      preOrder: Boolean = true,
      followLinks: Boolean = false,
      maxDepth: Int = Int.MaxValue,
      includeTarget: Boolean = false
    ): Generator[(Path, os.StatInfo)]
  }
}
```

With `unpack`, this could be consolidated into

```scala
object walk{
  case class Config(path: Path,
                    skip: Path => Boolean = _ => false,
                    preOrder: Boolean = true,
                    followLinks: Boolean = false,
                    maxDepth: Int = Int.MaxValue,
                    includeTarget: Boolean = false)

  def apply(unpack config: Config): IndexedSeq[Path] = {
    stream(config*).toArray[Path].toIndexedSeq
  }
  def attrs(unpack config: Config): IndexedSeq[(Path, os.StatInfo)] = {
    stream.attrs(config*)
      .toArray[(Path, os.StatInfo)].toIndexedSeq
  }
  object stream {
    def apply(unpack config: Config): Generator[Path] = {
      attrs(path, (p, _) => skip(p), preOrder, followLinks, maxDepth, includeTarget).map(_._1)
    }
    def attrs(unpack config: Config): Generator[(Path, os.StatInfo)] = ???
  }
}
```

Things to note:

1. A lot of these methods are forwarders/wrappers for each other, purely for convenience, and
   `*` can be used to forward the `config` object from the wrapper to the inner method

2. Sometimes the parameter lists are subtly different, e.g. `walk.stream.apply` and 
   `walk.stream.attrs` have a different type for `skip`. In such cases `unpack` cannot work
   and so the forwarding has to be done manually. 

## Detailed Behavior

`unpack` unpacks the parameter _name_, _type_, and any _default value_ into the enclosing parameter
list. As we saw earlier `unpack` can be performed on any parameter list: `def`s, `class` 
constructors, `case class`es:

```scala
// Definition-site Unpacking
case class RequestConfig(url: String,
                         unpack timeoutConfig: TimeoutConfig)
case class TimeoutConfig(connectTimeout: Int,
                         readTimeout: Int)
case class AsyncConfig(retry: Boolean, ec: ExecutionContext)
def downloadSimple(unpack config: RequestConfig) = doSomethingWith(config)
def downloadAsync(unpack config: RequestConfig, unpack asyncConfig: AsyncConfig) = doSomethingWith(config)
def downloadStream(unpack config: RequestConfig, unpack asyncConfig: AsyncConfig) = doSomethingWith(config)
```

### Nested and Adjacent Unpacks

There can be multiple hops, e.g. `downloadSimple` unpacks `RequestConfig`, and `RequestConfig`
unpacks `TimeoutConfig`, and there can be multiple `unpack`s in a single parameter list as shown
in `def downloadAsync` above. 

Any names colliding during `unpack`ing should result in an error, just like if you wrote:

```scala
def downloadSimple(foo: Int, foo: Int) = ???
// -- [E161] Naming Error: --------------------------------------------------------
// 1 |def downloadSimple(foo: Int, foo: Int) = ???
//   |                             ^^^^^^^^
//   |foo is already defined as parameter foo
//   |
//   |Note that overloaded methods must all be defined in the same group of toplevel definitions
// 1 error found
```

Similar errors should be shown for

```scala
case class HasFoo(foo: Int)
def downloadSimple(foo: Int, unpack hasFoo: HasFoo) = ???
```

Or 

```scala
case class HasFoo(foo: Int)
case class AlsoHasFoo(foo: Int)
def downloadSimple(unpack hasFoo: HasFoo, unpack alsoHasFoo: AlsoHasFoo) = ???
```


### Generics

Unpacking should work for generic methods and `case class`es:

```scala
case class Vector[T](x: T, y: T)
def magnitude[T](unpack v: Point[T])
magnitude(x = 5.0, y = 3.0) // 4.0: Double
magnitude(x = 5, y = 3) // 4: Int
```

And for generic case classes referenced in non-generic methods:


```scala
case class Vector[T](x: T, y: T)
def magnitudeInt(unpack v: Point[Int])
magnitude(x = 5, y = 3) // 4: Int
```

### Orthogonality

`unpack` on definitions and `*` on `case class` values are orthogonal: either can be used without
the other. We already saw how you can use `unpack` at the definition-site and just pass parameters
individually at the call-site:

```scala
case class RequestConfig(url: String, 
                         connectTimeout: Int,
                         readTimeout: Int)

def downloadSimple(unpack config: RequestConfig) = ???

val data = downloadSimple("www.example.com", 1000, 10000)
```

Similarly, you can define parameters individually at the definition-site and `unpack` a `case class`
with matching fields at the call-site

```scala
def downloadSimple(url: String,
                   connectTimeout: Int,
                   readTimeout: Int) = ???

case class RequestConfig(url: String,
                         connectTimeout: Int,
                         readTimeout: Int)

val config = RequestConfig("www.example.com", 1000, 10000)
val data = downloadSimple(config*)
```

And you can `unpack` a different `case class` onto an `unpack`-ed parameter list as long
as the names of the parameters line up:

```scala
case class RequestConfig(url: String, 
                         connectTimeout: Int,
                         readTimeout: Int)

def downloadSimple(unpack config: RequestConfig) = ???

case class OtherConfig(url: String,
                       connectTimeout: Int,
                       readTimeout: Int)

val config = OtherConfig("www.example.com", 1000, 10000)
val data = downloadSimple(config*)
```

Mix `unpack`-ed and individually passed argments:

```scala
case class AsyncConfig(retry: Boolean, ec: ExecutionContext)
case class RequestConfig(url: String,
                         connectTimeout: Int,
                         readTimeout: Int)

def downloadAsync(unpack config: RequestConfig, unpack asyncConfig: AsyncConfig) = ???

case class OtherConfig(url: String,
                       connectTimeout: Int,
                       readTimeout: Int,
                       retry: Boolean)

val config = OtherConfig("www.example.com", 1000, 10000, true)
downloadAsync(config*, retry = true)
```

### Case Class Construction Semantics

`unpack` may-or-may-not re-create a `case class` instance passed via `config*` to an 
`unpack`ed parameter list. This is left up to the implementation. But for the vast majority
of `case class`es with non-side-effecting constructors and structural equality, whether or 
not the `case class` instance is re-created is entirely invisible to the user.

### Name-Based Unpacking

Unpacking at callsites via `*` is done by-field-name, rather than positionally. That means
that even if the field names are in different orders, it will still work

```scala
def downloadSimple(url: String,
                   connectTimeout: Int,
                   readTimeout: Int) = ???

case class RequestConfig(connectTimeout: Int, // Different order!
                         url: String,
                         readTimeout: Int)

val config = RequestConfig("www.example.com", 1000, 10000)
val data = downloadSimple(config*) // OK
// Equivalent to the following, which is allowed today in Scala
val data = downloadSimple(
  connectTimeout = config.connectTimeout,
  url = config.url,
  readTimeout = config.readTimeout
) 
```

In general, we believe that most developers think of their `case class`es as defined by
the field names and types, rather than by the field ordering. So having `*` unpack `case class`
values by field name seems like it would be a lot more intuitive than relying on the parameter 
order and hoping it lines up between your `case class` and the parameter list you are unpacking 
it into. 


## Prior Art

### Scala `extends` and `export`

`unpack` is similar to Scala's `extends` and `export` clauses, except rather than applying
to members of a trait it applies to parameters in a parameter list. It serves a similar purpose,
and has similar ways it can be abused: e.g. too-deep chains of `unpack`-ed `case class`es are
confusing just like too-deep inheritance hierarchies with `extends`.

### uPickle's `@flatten`

uPickle has the `@flatten` annotation which flattens out nested case classes during
JSON serialization.

```scala
case class Outer(msg: String, @flatten inner: Inner) derives ReadWriter
case class Inner(@flatten inner2: Inner2) derives ReadWriter
case class Inner2(i: Int) derives ReadWriter

write(Outer("abc", Inner(Inner2(7)))) // {"msg": "abc", "i": 7}
```

Like `unpack`, `@flatten` can be used recursively to flatten out
a multi-layer `case class` tree into a single flat JSON object, as shown above

### MainArg's `case class` embedding

MainArgs allows you to re-use sets of command-line flags - defined by case classes - 
in method `def`s that define the sub-command entrypoints of the program:

```scala
object Main{
  @main
  case class Config(@arg(short = 'f', doc = "String to print repeatedly")
                    foo: String,
                    @arg(doc = "How many times to print string")
                    myNum: Int = 2,
                    @arg(doc = "Example flag")
                    bool: Flag)
  implicit def configParser = ParserForClass[Config]

  @main
  def bar(config: Config,
          @arg(name = "extra-message")
          extraMessage: String) = {
    println(config.foo * config.myNum + " " + config.bool.value + " " + extraMessage)
  }
  @main
  def qux(config: Config,
          n: Int) = {
    println((config.foo * config.myNum + " " + config.bool.value + "\n") * n)
  }

  def main(args: Array[String]): Unit = ParserForMethods(this).runOrExit(args)
}
```
```bash
$ ./mill example.classarg bar --foo cow --extra-message "hello world"
cowcow false hello world

$ ./mill example.classarg qux --foo cow --n 5
```

In this example, you can see how `def bar` and `def qux` both make use of the parameters from
`case class Config`, along with their own unique parameters `extraMessage` or `n`. This
serves a similar purpose as `unpack` would serve in Scala code, de-coupling the possibly-nested
`case class` data structure from the flag parameter list exposed to users (in MainArgs, users 
interacting with the program via the CLI).

### Python

Python's [PEP-692](https://peps.python.org/pep-0692/) defines an `Unpack[_]` marker type
that can be used together with `TypedDict` classes. These work similarly to `unpack` in this
proposal, but use typed-dictionary-based implementation for compatibility with Python's 
widespread use of `**kwargs` to forward parameters as a runtime dictionary.

> ```python
> from typing import TypedDict, Unpack
> 
> class Movie(TypedDict):
>     name: str
>     year: int
> 
> def foo(**kwargs: Unpack[Movie]) -> None: ...
> ```
> 
> means that the `**kwargs` comprise two keyword arguments specified by `Movie`
> (i.e. a name keyword of type `str` and a year keyword of type `int`). This indicates 
> that the function should be called as follows:
> 
> ```python
> kwargs: Movie = {"name": "Life of Brian", "year": 1979}
> 
> foo(**kwargs)                               # OK!
> foo(name="The Meaning of Life", year=1983)  # OK!
> ```
### Kotlin

Kotlin has an open extension proposal [KEEP-8214](https://youtrack.jetbrains.com/issue/KT-8214)
to support a `dataarg` modifier on `data class`es that functions identically to `unpack`
in this proposal

```kotlin
data class Options(
  val firstParam: Int = 0,
  val secondParam: String = "",
  val thirdParam: Boolean = true
)

fun foo(dataarg options: Options){}

fun f() {
    foo(secondParam = "a", thirdParam = false)
    foo(1)
    foo()  
}
```