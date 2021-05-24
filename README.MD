[![Build Status](https://travis-ci.org/kailuowang/mau.svg?branch=master)](https://travis-ci.org/kailuowang/mau)

## Mau - A toolbox of mid level constructs built on cats-effect 3.x


## Installation

mau is available on scala 2.11, 2.12, 2.13 and scalajs.

Add the following to your build.sbt
```scala
libraryDependencies += 
  "com.kailuowang" %% "mau" % "0.3.0"
```

This library provides a set of small and well-tested mid-level constructs
  built on top of cats-effect. 
  The constructs are small, the code is easy to read and duplicate to your own projects. 
  The main value of this project is the test suites.   
  
## Features: 

* [RefreshRef](#refreshref) An auto polling data container
* [Repeating](#repeating) Repeating of an effect, which can be paused and resumed  



## RefreshRef

An auto polling data container

### Motivation
 
It is common that applications cache data from upstream in local memory to avoid latency while allowing some data staleness. 
This is usually achieved through some caching library which lazy loads the data from upstream, together with a TTL (Time to Live) 
to control the maximum staleness allowed. 

This lazy loading approach has one inconvenience -  when cached data expires TTL, data in the cache needs to be invalidated and re-retrieved
 from upstream, during which time incoming requests from downstream need to wait for the data from upstream and hence experience the higher latency.  
 
Another missed opportunity for lazy loading cache is resilience against upstream disruptions. 
When the upstream becomes temporarily unavailable, for some cases, we can tolerate a slightly more stale data to maintain availability
to downstream. That is, we might want to maintain service to downstream using the data in memory for a little longer 
while waiting for the upstream to get back online.

A different approach is polling upstream periodically to retrieve the latest version and update memory. 
This periodical polling keeps the data fresh. Also, when upstreams becomes unavailable, this approach can, optionally, 
allow a grace period of failed polls to maintain availability to downstream. A good example of this approach in Http cache is Fastly's 
[Stale-While-Revalidate and Stale-If-Error](https://www.fastly.com/blog/stale-while-revalidate-stale-if-error-available-today).
 
Mau is a pure functional implementation of this pooling approach in Scala.
It provides a data container called `RefreshRef`, which can be used as a single entry cache with auto polling from upstream.

This single-entry cache keeps the data in memory regardless of usage; hence it's only applicable when the number of such containers is bounded in the application. I.E., it's suitable to keep in memory information whose size is relatively fixed. 

A possible example use case might be a top 10 most popular songs on a music app.
The query can be expensive, but the data size is fixed and can allow some staleness.

Another example is distributed configuration. The applications can work with slightly stale configuration, but ideally, the reads should be directly from memory, and
in case of configuration service going down, the applications need to be able to remain functioning for a while. 


Best to demonstrate through examples:

### Usages

```scala
import cats.implicits._
import cats.effect.IO
import scala.concurrent.duration._

mau.RefreshRef.resource[IO, MyData] //create a resource of a RefreshRef that cancels itself after use,  
  .use { ref =>  // In real uses cases, `ref` should reused to serve multiple requests concurrently

  ref.getOrFetch(10.second) {  //refresh every 10 second
    getDataFromUpstream    //higher latency effect to get data from upstream
  }
}
```
`ref.getOrFetch` either gets the data from the memory if available, or use the `getDataFromUpstream` to retrieve the data, and setup 
a polling to periodically update the data in memory using `getDataFromUpstream`. Hence the first call to `ref.getOrFetch` will take longer
to actually load the data from upstream to memory. Subsequent call will always return the data from memory. 

In the above usage, since no error handler given, when any exception occurs during `getDataFromUpstream`, the refresh stops, and the data is removed from the memory. 
All subsequent requests will hit upstream through `getDataFromUpstream`, whose failure will surface to downstream, until 
upstream restores.

As pointed out by the comment, a `RefreshRef` is provided as a [`cats.effect.Resource`](https://typelevel.org/cats-effect/datatypes/resource.html), which gaurantees that the polling gets canceled after usage.

Here is a more advanced example that enables resilience against upstream disruption. 
  
```scala

ref.getOrFetch(10.second, 60.seconds) {  //refresh every 10 second, but when refresh fails, allow 60 seconds of staleness
  getDataFromUpstream     
} {
  case e: SomeBackendException => IO.unit   //tolerate a certain type of errors from upstream
}

```
In this example, `SomeBackendException` from `getDataFromUpstream` will be tolerated for 60 seconds, during which time data in memory will be returned.
After 60 seconds of continuous polling failures, the polling will stop and data removed.  
A success `getDataFromUpstream`  resets the timer. BTW, you can also choose to log the error and either rethrow or tolerate it.  


Mau is built on top of [`Ref`](https://typelevel.org/cats-effect/concurrency/ref.html) from [cats-effect](https://typelevel.org/cats-effect).
It has only roughly 100 lines of code, but with extensive tests.


## Repeating

`Repeating` is construct that allows repeating an effect at a certain frequency. Also the repeating can be paused and resumed. 

### Usages

```scala

import mau.Repeating

Repeating.resource(myEffect, repeatDuration = 50.milliseconds, runInParallel = true).use { repeating =>
  for {
    _ <- repeating.pause
    _ <- repeating.resume
  } yield ()   
}

```

### Alternative options
An alternative solution to this can be built using [fs2](https://fs2.io).  The basic idea was [given by Gavin Bisesi](https://gitter.im/typelevel/cats-effect?at=5db09d5ffb4dab784af1a29a) in the following example code
```scala
SignallingRef[F, Boolean](false).flatMap { paused =>
  val doStuff = fs2.Stream.eval(myEffect).repeat.pauseWhen(paused)
  pauseLogicStream(paused) concurrently doStuff
}
```

If you don't need pause/resume, consider [fs2-cron](https://github.com/fthomas/fs2-cron)

## Contribution

Any contribution is more than welcome. The main purpose of open sourcing this is to seek collaboration. 
If you have any questions feel free to submit an issue.

Please follow the [Scala Code of Conduct](https://www.scala-lang.org/conduct/). 


## License

```
Copyright (c) 2017-2019 Kailuo Wang

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```


