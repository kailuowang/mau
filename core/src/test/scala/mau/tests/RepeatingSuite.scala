package mau
package tests

import cats.effect.testing.scalatest.AsyncIOSpec
import cats.effect.{IO, Ref}
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers
import cats.implicits._

import concurrent.duration._

class RepeatingSuite extends AsyncFreeSpec with AsyncIOSpec with Matchers {


  "Repeating" - {
    "repeat effect when created, pause when resource released" in {
      (for {
        counter <- Ref[IO].of(0)
        _ <- Repeating.resource(counter.update(_ + 1), 50.milliseconds, true).use {
          _ =>
            IO.sleep(500.milliseconds)
        }
        _ <- IO.sleep(500.milliseconds)
        count <- counter.get

      } yield count).asserting { count =>
        count should be > 5
        count should be < 15
      }

    }

    "pause" - {
      "pause effect" in {
        (for {
          counter <- Ref[IO].of(0)
          _ <- Repeating.resource(counter.update(_ + 1), 50.milliseconds, true).use {
            r =>
              IO.sleep(500.milliseconds) >> r.pause >> IO.sleep(500.milliseconds)
          }
          count <- counter.get
        } yield count).asserting { count =>
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
          .asserting(_ shouldBe true)
      }

      "returns false called when paused" in {
        Repeating
          .resource(IO.unit, 50.milliseconds, true)
          .use { r =>
            r.pause >> r.pause
          }
          .asserting(_ shouldBe false)
      }

      "does not cancel the effect already running in parallel" in {
        (for {
          counter <- Ref[IO].of(0)
          effect = IO.sleep(400.milliseconds) >> counter.update(_ + 1)
          _ <- Repeating.resource(effect, 100.milliseconds, true).use { r =>
            IO.sleep(200.milliseconds) >> r.pause
          }
          _ <- IO.sleep(700.milliseconds)
          count <- counter.get
        } yield count).asserting { count =>
          count should be > 0
        }
      }

      "cancels the effect already running but no in parallel" in {
        (for {
          counter <- Ref[IO].of(0)
          effect = IO.sleep(400.milliseconds) >> counter.update(_ + 1)
          _ <- Repeating.resource(effect, 100.milliseconds, false).use { r =>
            IO.sleep(200.milliseconds) >> r.pause
          }
          _ <- IO.sleep(700.milliseconds)
          count <- counter.get
        } yield  count).asserting { count =>
          count shouldBe 0
        }
      }
    }

    "resume" - {
      "resume paused effect" in {
        (for {
          counter <- Ref[IO].of(0)
          _ <- Repeating.resource(counter.update(_ + 1), 50.milliseconds, true).use {
            r =>
              r.pause >> r.resume >> IO.sleep(500.milliseconds)
          }
          count <- counter.get

        } yield  count).asserting { count =>
          count should be > 7
        }
      }

      "returns false called when running" in {
        Repeating
          .resource(IO.unit, 50.milliseconds, true)
          .use { r =>
            r.resume
          }
          .asserting(_ shouldBe false)
      }

      "returns true called when pause" in {
        Repeating
          .resource(IO.unit, 50.milliseconds, true)
          .use { r =>
            r.pause >> r.resume
          }
          .asserting(_ shouldBe true)
      }

    }

    "running" - {
      "returns true called when running" in {
        Repeating
          .resource(IO.unit, 50.milliseconds, true)
          .use { r =>
            r.running
          }
          .asserting(_ shouldBe true)
      }

      "returns true called when pause" in {
        Repeating
          .resource(IO.unit, 50.milliseconds, true)
          .use { r =>
            r.pause >> r.running
          }
          .asserting(_ shouldBe false)
      }
    }

    "safe for long run" in {
      (for {
        counter <- Ref[IO].of(0)
        _ <- Repeating.resource(counter.update(_ + 1), 0.milliseconds, true).use {
          _ =>
            IO.sleep(5.seconds)
        }
        count <- counter.get
      } yield count).asserting { count =>
        val threshod = 100000
        count should be > threshod
      }
    }
  }
}
