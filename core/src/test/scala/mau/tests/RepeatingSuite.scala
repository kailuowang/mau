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
        counter <- Ref[IO].of(0)
        _ <- Repeating.resource(counter.update(_ + 1), 50.milliseconds, true).use {
          _ =>
            timer.sleep(500.milliseconds)
        }
        _ <- timer.sleep(500.milliseconds)
        count <- counter.get

      } yield {
        count should be > 5
        count should be < 15
      }
    }

    "pause" - {
      "pause effect" in {
        for {
          counter <- Ref[IO].of(0)
          _ <- Repeating.resource(counter.update(_ + 1), 50.milliseconds, true).use {
            r =>
              timer.sleep(500.milliseconds) >> r.pause >> timer
                .sleep(500.milliseconds)
          }
          count <- counter.get
        } yield {
          count should be > 5
          count should be < 15
        }
      }

      "returns true called when running" in {
        Repeating
          .resource(IO.unit, 50.milliseconds, true)
          .use { r =>
            r.pause
          }
          .map(_ shouldBe true)
      }

      "returns false called when paused" in {
        Repeating
          .resource(IO.unit, 50.milliseconds, true)
          .use { r =>
            r.pause >> r.pause
          }
          .map(_ shouldBe false)
      }

      "does not cancel the effect already running in parallel" in {
        for {
          counter <- Ref[IO].of(0)
          effect = timer.sleep(400.milliseconds) >> counter.update(_ + 1)
          _ <- Repeating.resource(effect, 100.milliseconds, true).use { r =>
            timer.sleep(200.milliseconds) >> r.pause
          }
          _ <- timer.sleep(700.milliseconds)
          count <- counter.get
        } yield {
          count should be > 0
        }
      }

      "cancels the effect already running but no in parallel" in {
        for {
          counter <- Ref[IO].of(0)
          effect = timer.sleep(400.milliseconds) >> counter.update(_ + 1)
          _ <- Repeating.resource(effect, 100.milliseconds, false).use { r =>
            timer.sleep(200.milliseconds) >> r.pause
          }
          _ <- timer.sleep(700.milliseconds)
          count <- counter.get
        } yield {
          count shouldBe 0
        }
      }
    }

    "resume" - {
      "resume paused effect" in {
        for {
          counter <- Ref[IO].of(0)
          _ <- Repeating.resource(counter.update(_ + 1), 50.milliseconds, true).use {
            r =>
              r.pause >> r.resume >> timer.sleep(500.milliseconds)
          }
          count <- counter.get

        } yield {
          count should be > 7
        }
      }

      "returns false called when running" in {
        Repeating
          .resource(IO.unit, 50.milliseconds, true)
          .use { r =>
            r.resume
          }
          .map(_ shouldBe false)
      }

      "returns true called when pause" in {
        Repeating
          .resource(IO.unit, 50.milliseconds, true)
          .use { r =>
            r.pause >> r.resume
          }
          .map(_ shouldBe true)
      }

    }

    "running" - {
      "returns true called when running" in {
        Repeating
          .resource(IO.unit, 50.milliseconds, true)
          .use { r =>
            r.running
          }
          .map(_ shouldBe true)
      }

      "returns true called when pause" in {
        Repeating
          .resource(IO.unit, 50.milliseconds, true)
          .use { r =>
            r.pause >> r.running
          }
          .map(_ shouldBe false)
      }
    }

    "safe for long run" in {
      for {
        counter <- Ref[IO].of(0)
        _ <- Repeating.resource(counter.update(_ + 1), 0.milliseconds, true).use {
          _ =>
            timer.sleep(5.seconds)
        }
        count <- counter.get
      } yield {
        val threshod = if (Platform.isJs) 100 else 100000
        count should be > threshod
      }
    }
  }
}
