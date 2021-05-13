package mau
package tests

import cats.effect.{Concurrent, IO, Resource}
import cats.effect.concurrent.Ref
import org.scalatest.matchers.should.Matchers
import org.scalatest.funsuite.AsyncFunSuite

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import cats.implicits._

import scala.util.control.NoStackTrace

class RefreshRefSuite extends AsyncFunSuite with Matchers {

  implicit override def executionContext: ExecutionContext =
    ExecutionContext.global

  implicit val ctx = IO.contextShift(executionContext)
  implicit val timer = IO.timer(executionContext)

  def testWithRef[A](f: RefreshRef[IO, Int] => IO[A]): Future[A] =
    RefreshRef
      .resource[IO, Int]((_: Int) => IO(print(".")))
      .use(f)
      .unsafeToFuture()

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
              timer.sleep(25.milliseconds) >> //sleep here to force a concurrent race
                threadAction(te)
            )

          } yield (te, fiber)
        }
      } {
        _.traverse(_._2.cancel).void
      })
      .map(_.map(_._1))

  test("empty returns None") {
    testWithRef { ref =>
      ref.get.map(_ shouldBe None)
    }
  }

  test("get with fetch returns value") {
    testWithRef { ref =>
      ref.getOrFetch(1.seconds)(IO(1)).map(_ shouldBe 1)
    }
  }

  test("cancel return false if there is no refreshing") {
    testWithRef { ref =>
      ref.cancel
        .map(_ shouldBe false)
    }
  }

  test("cancel return true if something is canceled") {
    testWithRef { ref =>
      for {
        _ <- ref.getOrFetch(1.seconds)(IO(1))
        r <- ref.cancel
      } yield r shouldBe true
    }
  }

  test("auto refresh value") {
    testWithRef { ref =>
      for {
        count <- counter
        _ <- ref.getOrFetch(50.milliseconds) {
          count.update(_ + 1) *> count.get
        }
        _ <- timer.sleep(150.milliseconds)
        c <- count.get
      } yield c should be > (1)
    }
  }

  test("no longer refresh after cancel") {
    testWithRef { ref =>
      for {
        count <- counter
        _ <- ref.getOrFetch(50.milliseconds) {
          count.update(_ + 1) *> count.get
        }
        _ <- ref.cancel
        _ <- timer.sleep(200.milliseconds)
        c <- count.get
      } yield c should be < (2)
    }
  }

  test("does not register a second refresh value") {
    testWithRef { ref =>
      for {
        count <- counter
        _ <- ref.getOrFetch(1.second)(IO(1))
        _ <- ref.getOrFetch(50.milliseconds) {
          count.update(_ + 1) *> count.get
        }
        _ <- timer.sleep(100.milliseconds)
        c <- count.get
      } yield c shouldBe 0
    }
  }

  test("get the refreshed value on a second fetch") {
    testWithRef { ref =>
      for {
        count <- counter
        _ <- ref.getOrFetch(50.milliseconds) {
          count.update(_ + 1) *> count.get
        }
        _ <- timer.sleep(100.milliseconds)
        newValue <- ref.getOrFetch(1.second)(IO(1))
      } yield newValue should be >= (2)
    }
  }

  test("register a second refresh value if the first one is canceled") {
    testWithRef { ref =>
      for {
        count <- counter
        _ <- ref.getOrFetch(1.second)(IO(1))
        _ <- ref.cancel
        _ <- ref.getOrFetch(50.milliseconds) {
          count.update(_ + 1) *> count.get
        }
        _ <- timer.sleep(100.milliseconds)
        c <- count.get
      } yield c should be > (1)
    }
  }

  test("returns None after cancel") {
    testWithRef { ref =>
      for {
        _ <- ref.getOrFetch(1.second)(IO(1))
        _ <- ref.cancel
        r <- ref.get
      } yield r shouldBe None
    }
  }

  test("resource cancel itself after use") {

    (for {
      count <- counter
      _ <- RefreshRef.resource[IO, Int].use { ref =>
        for {
          _ <- ref.getOrFetch(50.milliseconds) {
            count.update(_ + 1) *> count.get
          }
          _ <- timer.sleep(100.milliseconds)
        } yield ()
      }
      _ <- timer.sleep(150.milliseconds)
      c <- count.get
    } yield {
      c should be > (0)
      c should be < (4)
    }).unsafeToFuture()

  }

  test("concurrent access doesn't not produce multiple refreshes") {
    testWithRef { ref =>
      concurrently(10)(counter) { threadCount =>
        ref
          .getOrFetch(50.milliseconds) {
            threadCount.update(_ + 1) *> threadCount.get
          }
          .void
      }.use { threadCounts =>
        for {
          _ <- timer.sleep(350.milliseconds)
          counts <- threadCounts.traverse(_.get)
        } yield counts.count(_ > 2) shouldBe 1
      }
    }
  }

  test("concurrent get works") {
    testWithRef { ref =>
      ref.getOrFetch(50.milliseconds)(IO(1)) >>
        concurrently(10)(Ref.of[IO, Option[Int]](None)) { read =>
          ref.get.flatMap(read.set)
        }.use { threadReads =>
          timer.sleep(100.milliseconds) >>
            threadReads.traverse(_.get).map { results =>
              results.forall(_ == Some(1)) shouldBe true
            }
        }
    }
  }

  test("failed refresh stops and removes the value") {
    testWithRef { ref =>
      for {
        count <- counter
        _ <- ref.getOrFetch(50.milliseconds) {
          count.update(_ + 1) *> count.get.ensure(new Exception("Boom"))(_ <= 1)
        }
        _ <- timer.sleep(150.milliseconds)
        c <- count.get
        v <- ref.get
      } yield {
        c shouldBe 2
        v shouldBe None
      }
    }
  }

  test("zero length refresh period simply fetch on every time it fetches") {
    testWithRef { ref =>
      for {
        count <- counter
        _ <- ref.getOrFetch(0.milliseconds) {
          count.update(_ + 1).as(1)
        }
        c1 <- count.get
        _ <- timer.sleep(150.milliseconds)

        _ <- ref.getOrFetch(0.milliseconds) {
          count.update(_ + 1).as(1)
        }
        c2 <- count.get
      } yield {
        c1 shouldBe 1
        c2 shouldBe 2
      }
    }
  }

  test("handle error with custom error handler") {
    testWithRef { ref =>
      for {
        count <- counter
        _ <- ref.getOrFetch(50.milliseconds, 1.second) {
          count.update(_ + 1) *> count.get.ensure(IntentionalErr)(_ <= 1)
        } {
          case IntentionalErr => IO.unit
        }
        _ <- timer.sleep(150.milliseconds)
        c <- count.get
        v <- ref.get
      } yield {
        c should be >= (3)
        v shouldBe Some(1)
      }
    }
  }

  test("rethrow error with custom error handler") {
    testWithRef { ref =>
      for {
        count <- counter
        _ <- ref.getOrFetch(50.milliseconds, 1.second) {
          count.update(_ + 1) *> count.get.ensure(IntentionalErr)(_ <= 1)
        } {
          case IntentionalErr => IO.raiseError(IntentionalErr)
        }
        _ <- timer.sleep(150.milliseconds)
        c <- count.get
        v <- ref.get
      } yield {
        c shouldBe 2
        v shouldBe None
      }
    }
  }

  test("remove stale value after continuous failed refresh") {
    testWithRef { ref =>
      for {
        count <- counter
        _ <- ref.getOrFetch(50.milliseconds, 100.milliseconds) {
          count.update(_ + 1) *> count.get.ensure(IntentionalErr)(_ <= 1)
        } {
          case IntentionalErr => IO.unit
        }
        _ <- timer.sleep(350.milliseconds)
        c <- count.get
        v <- ref.get
      } yield {
        c should be < (5)
        v shouldBe None
      }
    }
  }

  test("reset stale timer by successful refresh") {
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
        _ <- timer.sleep(2.seconds)
        v <- ref.get
      } yield {
        v.get should be > 8 //if the timer wasn't reset , the failed 8th refresh would kill the refresh
      }
    }
  }

}

case object IntentionalErr extends RuntimeException with NoStackTrace
