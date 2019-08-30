[![Build Status](https://travis-ci.org/kailuowang/mau.svg?branch=master)](https://travis-ci.org/kailuowang/mau)

## Mau - A tiny library for an auto polling data container


## Installation

mau is available on scala 2.11, 2.12, and scalajs.

Add the following to your build.sbt
```scala
libraryDependencies += 
  "com.kailuowang" %% "mau" % "0.0.1"
```

## Motivation

Sometimes there is a need to store a small set of data in memory to minimize latency at the cost of some controlled staleness. 
 
It is commonly achieved through some caching library which lazy loads the data from upstream, together with a TTL (Time to Live) 
to avoid stale data. 

This lazy loading approach has one inconvenience -  when cached data expires TTL, data in cache needs to be invalidated and re-retrieved
 from upstream, during which time incoming requests from downstream need to wait for the data from upstream and hence 
 experience the higher latency.  
 
Another missed opportunity for simple cache with TTL is resilience against upstream disruptions. 
When the upstream becomes temporarily unavailable, for some cases, we can even tolerate a slightly more stale data to maintain availability
to downstream. That is, we might want to maintain service to downstream using the data in memory for a little longer 
while waiting for the upstream to be back online.

A different approach (than lazy loading) is polling upstream periodically to get the latest version and update the one in memory. 
This periodical polling keeps the data fresh. Also when upstreams becomes unavailable, this approach can, optionally, allow a grace period of failed polls to maiintain availalbility to downstream. 

Mau is a pure functional implementation of this pooling approach in Scala. 
It provides a data container called `RefreshRef`, which can be used as a single item cache that auto polls from upstream.

Best to demonstrate through examples:

## Usages

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


