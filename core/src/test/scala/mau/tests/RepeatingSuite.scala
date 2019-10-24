package mau
package tests

import cats.effect.IO
import cats.effect.concurrent.Ref
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers
import cats.implicits._

import scala.concurrent.{ExecutionContext, Future}
import concurrent.duration._

class RepeatingSuite extends AsyncFreeSpec with Matchers {

  implicit override def executionContext: ExecutionContext =
    ExecutionContext.global

  implicit val ctx = IO.contextShift(executionContext)
  implicit val timer = IO.timer(executionContext)

  implicit def toFuture[A](ioA: IO[A]): Future[A] = ioA.unsafeToFuture()

  "Repeating" - {
    "repeat effect when created, pause when resource released" in {
      for {
        ref <- Ref[IO].of(0)
        _ <- Repeating.resource(ref.update(_ + 1), 50.milliseconds).use { _ =>
          timer.sleep(500.milliseconds)
        }
        _ <- timer.sleep(500.milliseconds)
        result <- ref.get

      } yield {
        result should be > 7
        result should be < 15
      }
    }

    "pause" - {
      "pause effect" in {
        for {
          ref <- Ref[IO].of(0)
          _ <- Repeating.resource(ref.update(_ + 1), 50.milliseconds).use { r =>
            timer.sleep(500.milliseconds) >> r.pause >> timer.sleep(500.milliseconds)
          }
          result <- ref.get
        } yield {
          result should be > 7
          result should be < 15
        }
      }

      "returns true called when running" in {
        Repeating
          .resource(IO.unit, 50.milliseconds)
          .use { r =>
            r.pause
          }
          .map(_ shouldBe true)
      }

      "returns false called when paused" in {
        Repeating
          .resource(IO.unit, 50.milliseconds)
          .use { r =>
            r.pause >> r.pause
          }
          .map(_ shouldBe false)
      }

    }

    "resume" - {

      "resume paused effect" in {
        for {
          ref <- Ref[IO].of(0)
          _ <- Repeating.resource(ref.update(_ + 1), 50.milliseconds).use { r =>
            r.pause >> r.resume >> timer.sleep(500.milliseconds)
          }
          result <- ref.get

        } yield {
          result should be > 7
        }
      }

      "returns false called when running" in {
        Repeating
          .resource(IO.unit, 50.milliseconds)
          .use { r =>
            r.resume
          }
          .map(_ shouldBe false)
      }

      "returns true called when pause" in {
        Repeating
          .resource(IO.unit, 50.milliseconds)
          .use { r =>
            r.pause >> r.resume
          }
          .map(_ shouldBe true)
      }

    }

    "running" - {
      "returns true called when running" in {
        Repeating
          .resource(IO.unit, 50.milliseconds)
          .use { r =>
            r.running
          }
          .map(_ shouldBe true)
      }

      "returns true called when pause" in {
        Repeating
          .resource(IO.unit, 50.milliseconds)
          .use { r =>
            r.pause >> r.running
          }
          .map(_ shouldBe false)
      }
    }

    "safe for long run" in {
      for {
        ref <- Ref[IO].of(0)
        _ <- Repeating.resource(ref.update(_ + 1), 0.milliseconds).use { r =>
          timer.sleep(5.seconds)
        }
        result <- ref.get
      } yield {
        result should be > 100000
      }
    }
  }
}
