[![Build Status](https://travis-ci.org/kailuowang/mau.svg?branch=master)](https://travis-ci.org/kailuowang/mau)

## Mau - A tiny library for an auto polling data container


## Installation

mau is available on scala 2.11, 2.12, and scalajs.

Add the following to your build.sbt
```scala
libraryDependencies += 
  "com.kailuowang" %% "mau-core" % "0.0.1"
```

## Motivation

Sometimes there is a need to store a small set of data in memory to minimize latency at the cost of controlled staleness. 
 
This is commonly achieved through some caching library which lazy loads the data from the backend. 

This approach is mostly okay but when cached data expires TTL, one or more incoming requests need to wait for the data retrieved from
the backend again. This may become problematic when the backend has significant longer latency.

Another problem that simple caching with TTL doesn't solve is resilience against backend disruptions. 
When the backend becomes unavailable, sometimes we can trade a bit more staleness to avoid frontend service  
        

Mau provides a pure functional auto pooling data container called `RefreshRef`. 
User can use it as a single item cache that auto polls from some backend.
Here is an example

```scala
import cats.implicits._
import cats.effect.IO
import scala.concurrent.duration._

val r = mau.RefreshRef.resource[IO, Int] //create a resource of a RefreshRef to hold an Int 

class MyService(ref: RefreshRef[IO, Int]) {
  def serveReq: IO[String] =
    for { 
      i <- ref.getOrFetch(10.second, 60.seconds) {  //refresh every 10 second, but when refresh fails, allow 60 seconds of staleness
            getDataFromBackend    //heavy backend effect to get the Int
          } {
            case SomeBackendException => IO.unit   //tolerate some backend exceptions
          }   
    } yield s"Current number is $i"
}

val serviceR: Resource[IO, MySerivce] = r.map(new MyService(_))  //a Resource of the service

```        
Mau is built on top of [`Ref`](https://typelevel.org/cats-effect/concurrency/ref.html) from [cats-effect](https://typelevel.org/cats-effect).
It has only roughly 100 lines of code, but with extensive tests.
Any collaboration is more than welcome as the main purpose of open sourcing this is to solicit collaboration.  



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

[Code of Conduct]: https://github.com/typelevel/cats-effect/blob/master/CODE_OF_CONDUCT.md
