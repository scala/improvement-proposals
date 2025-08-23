---
layout: sip
permalink: /sips/:title.html
stage: implementation
status: under-review
title: SIP-61 - Unroll Default Arguments for Binary Compatibility
---

**By: Li Haoyi**

## History

| Date          | Version            |
|---------------|--------------------|
| Feb 14th 2024 | Initial Draft      |


## Summary

This proposal provides a syntax to "unpack" a `case class` _type_ into a definition-site 
parameter list via the `unpack` keyword, and to "unpack" `case class` _value_ into a
definition-site argument list via `*`: 

```scala
case class RequestConfig(url: String, 
                         connectTimeout: Int,
                         readTimeout: Int)

def downloadSimple(unpack config: Config) = doSomethingWith(config)
def downloadAsync(unpack config: Config, ec: ExecutionContext) = doSomethingWith(config)
def downloadStream(unpack config: Config) = doSomethingWith(config)

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

## Limitations
## Alternatives
## Prior Art

### uPickle's `@flatten`
### MainArg's `case class` embedding
### Python
### Kotlin
### Javascript