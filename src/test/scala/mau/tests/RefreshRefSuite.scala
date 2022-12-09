package mau
package tests


import cats.effect.testing.scalatest.AsyncIOSpec
import cats.effect.{Concurrent, IO, Ref, Resource}
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._
import cats.implicits._
import org.scalatest.freespec.AsyncFreeSpec

import scala.util.control.NoStackTrace

class RefreshRefSuite extends AsyncFreeSpec with AsyncIOSpec with Matchers {

  def testWithRef[A](f: RefreshRef[IO, Int] => IO[A]): IO[A] =
    RefreshRef
      .resource[IO, Int]((_: Int) => IO(print(".")))
      .use(f)

  def counter: IO[Ref[IO, Int]] = Ref.of[IO, Int](0)

  def concurrently[A](
      concurrency: Int
    )(treadEnvF: IO[A]
    )(threadAction: A => IO[Unit]
    ): Resource[IO, List[A]] =
    (Resource
      .make {
        List.fill(concurrency)(treadEnvF).traverse { tef =>
          for {
            te <- tef
            fiber <- Concurrent[IO].start(
              IO.sleep(25.milliseconds) >> //sleep here to force a concurrent race
                threadAction(te)
            )

          } yield (te, fiber)
        }
      } {
        _.traverse(_._2.cancel).void
      })
      .map(_.map(_._1))

  "empty returns None" in {
    testWithRef { ref =>
      ref.get
    }.asserting(_ shouldBe None)
  }

  "get with fetch returns value" in {
    testWithRef { ref =>
      ref.getOrFetch(1.seconds)(IO(1))
    }.asserting(_ shouldBe 1)
  }

  "cancel return false if there is no refreshing" in {
    testWithRef { ref =>
      ref.cancel
    }.asserting(_ shouldBe false)
  }

  "cancel return true if something is canceled" in {
    testWithRef { ref =>
      for {
        _ <- ref.getOrFetch(1.seconds)(IO(1))
        r <- ref.cancel
      } yield r
    }.asserting(_ shouldBe true)
  }

  "auto refresh value" in {
    testWithRef { ref =>
      for {
        count <- counter
        _ <- ref.getOrFetch(50.milliseconds) {
          count.update(_ + 1) *> count.get
        }
        _ <- IO.sleep(150.milliseconds)
        c <- count.get
      } yield c
    }.asserting(_ should be > (1))
  }

  "no longer refresh after cancel" in {
    testWithRef { ref =>
      for {
        count <- counter
        _ <- ref.getOrFetch(50.milliseconds) {
          count.update(_ + 1) *> count.get
        }
        _ <- ref.cancel
        _ <- IO.sleep(200.milliseconds)
        c <- count.get
      } yield c
    }.asserting(_ should be < (2))
  }

  "does not register a second refresh value" in {
    testWithRef { ref =>
      for {
        count <- counter
        _ <- ref.getOrFetch(1.second)(IO(1))
        _ <- ref.getOrFetch(50.milliseconds) {
          count.update(_ + 1) *> count.get
        }
        _ <- IO.sleep(100.milliseconds)
        c <- count.get
      } yield c
    }.asserting(_  shouldBe 0)
  }

  "get the refreshed value on a second fetch" in {
    testWithRef { ref =>
      for {
        count <- counter
        _ <- ref.getOrFetch(50.milliseconds) {
          count.update(_ + 1) *> count.get
        }
        _ <- IO.sleep(100.milliseconds)
        newValue <- ref.getOrFetch(1.second)(IO(1))
      } yield newValue
    }.asserting(_  should be >= (2))
  }

  "register a second refresh value if the first one is canceled" in {
    testWithRef { ref =>
      for {
        count <- counter
        _ <- ref.getOrFetch(1.second)(IO(1))
        _ <- ref.cancel
        _ <- ref.getOrFetch(50.milliseconds) {
          count.update(_ + 1) *> count.get
        }
        _ <- IO.sleep(100.milliseconds)
        c <- count.get
      } yield c
    }.asserting(_ should be > (1))
  }

  "returns None after cancel" in {
    testWithRef { ref =>
      for {
        _ <- ref.getOrFetch(1.second)(IO(1))
        _ <- ref.cancel
        r <- ref.get
      } yield r
    }.asserting(_ shouldBe None)
  }

  "resource cancel itself after use" in {

    (for {
      count <- counter
      _ <- RefreshRef.resource[IO, Int].use { ref =>
        for {
          _ <- ref.getOrFetch(50.milliseconds) {
            count.update(_ + 1) *> count.get
          }
          _ <- IO.sleep(100.milliseconds)
        } yield ()
      }
      _ <- IO.sleep(150.milliseconds)
      c <- count.get
    } yield c).asserting { c =>
      c should be > (0)
      c should be < (4)
    }

  }

  "concurrent access doesn't not produce multiple refreshes" in {
    testWithRef { ref =>
      concurrently(10)(counter) { threadCount =>
        ref
          .getOrFetch(50.milliseconds) {
            threadCount.update(_ + 1) *> threadCount.get
          }
          .void
      }.use { threadCounts =>
        for {
          _ <- IO.sleep(350.milliseconds)
          counts <- threadCounts.traverse(_.get)
        } yield counts
      }
    }.asserting(_.count(_ > 2) shouldBe 1)
  }

  "concurrent get works" in {
    testWithRef { ref =>
      ref.getOrFetch(50.milliseconds)(IO(1)) >>
        concurrently(10)(Ref.of[IO, Option[Int]](None)) { read =>
          ref.get.flatMap(read.set)
        }.use { threadReads =>
          IO.sleep(100.milliseconds) >>
            threadReads.traverse(_.get).map { results =>
              results.forall(_ == Some(1))
            }
        }
    }.asserting(_  shouldBe true)
  }

  "failed refresh stops and removes the value" in {
    testWithRef { ref =>
      for {
        count <- counter
        _ <- ref.getOrFetch(50.milliseconds) {
          count.update(_ + 1) *> count.get.ensure(new Exception("Boom"))(_ <= 1)
        }
        _ <- IO.sleep(150.milliseconds)
        c <- count.get
        v <- ref.get
      } yield (c, v)
    }.asserting{ case (c, v) =>
      c shouldBe 2
      v shouldBe None
    }
  }

  "zero length refresh period simply fetch on every time it fetches" in {
    testWithRef { ref =>
      for {
        count <- counter
        _ <- ref.getOrFetch(0.milliseconds) {
          count.update(_ + 1).as(1)
        }
        c1 <- count.get
        _ <- IO.sleep(150.milliseconds)

        _ <- ref.getOrFetch(0.milliseconds) {
          count.update(_ + 1).as(1)
        }
        c2 <- count.get
      } yield (c1, c2)
    }.asserting { case (c1, c2) =>
      c1 shouldBe 1
      c2 shouldBe 2
    }
  }

  "handle error with custom error handler" in {
    testWithRef { ref =>
      for {
        count <- counter
        _ <- ref.getOrFetch(50.milliseconds, 1.second) {
          count.update(_ + 1) *> count.get.ensure(IntentionalErr)(_ <= 1)
        } {
          case IntentionalErr => IO.unit
        }
        _ <- IO.sleep(150.milliseconds)
        c <- count.get
        v <- ref.get
      } yield (c, v)
    }.asserting { case (c, v) =>
      c should be >= (3)
      v shouldBe Some(1)
    }
  }

  "rethrow error with custom error handler" in {
    testWithRef { ref =>
      for {
        count <- counter
        _ <- ref.getOrFetch(50.milliseconds, 1.second) {
          count.update(_ + 1) *> count.get.ensure(IntentionalErr)(_ <= 1)
        } {
          case IntentionalErr => IO.raiseError(IntentionalErr)
        }
        _ <- IO.sleep(150.milliseconds)
        c <- count.get
        v <- ref.get
      } yield (c, v)
    }.asserting { case (c, v) =>
      c shouldBe 2
      v shouldBe None
    }
  }

  "remove stale value after continuous failed refresh" in {
    testWithRef { ref =>
      for {
        count <- counter
        _ <- ref.getOrFetch(50.milliseconds, 100.milliseconds) {
          count.update(_ + 1) *> count.get.ensure(IntentionalErr)(_ <= 1)
        } {
          case IntentionalErr => IO.unit
        }
        _ <- IO.sleep(350.milliseconds)
        c <- count.get
        v <- ref.get
      } yield (c, v)

    }.asserting { case (c, v) =>
      c should be < (5)
      v shouldBe None
    }
  }

  "reset stale IO by successful refresh" in {
    testWithRef { ref =>
      for {
        count <- counter
        _ <- ref.getOrFetch(100.milliseconds, 300.milliseconds) {
          count.update(_ + 1) *> count.get.ensure(IntentionalErr)(
            i => i != 2 && i != 8
          )
        } {
          case IntentionalErr => IO.unit
        }
        _ <- IO.sleep(2.seconds)
        v <- ref.get
      } yield
        v//if the IO wasn't reset , the failed 8th refresh would kill the refresh
    }.asserting(_.get should be > 8 )
  }

}

case object IntentionalErr extends RuntimeException with NoStackTrace
