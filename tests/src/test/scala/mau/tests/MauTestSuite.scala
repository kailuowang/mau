package mau
package tests


import cats.effect.IO
import cats.effect.concurrent.Ref
import org.scalatest.Matchers
import org.scalatest.funsuite.AnyFunSuiteLike

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import cats.implicits._
import cats.effect.implicits._

class RefreshRefSuite extends AnyFunSuiteLike with Matchers {
  implicit val ctx = IO.contextShift(ExecutionContext.global)
  implicit val timer = IO.timer(ExecutionContext.global)

  def testWithRef[A](f: RefreshRef[IO, Int] => IO[A]) =
    RefreshRef.resource[IO, Int].use(f).unsafeRunSync()

  def counter: IO[Ref[IO, Int]] = Ref.of[IO, Int](0)

  test("empty returns None") {
    testWithRef { ref =>
      ref.get
    } shouldBe None
  }

  test("get with fetch returns value") {
    testWithRef { ref =>
      ref.getOrFetch(1.seconds)(IO(1))
    } shouldBe 1
  }

  test("cancel return false if there is no refreshing") {
    testWithRef { ref =>
      ref.cancel
    } shouldBe false
  }

  test("cancel return true if something is canceled") {
    testWithRef { ref =>
      ref.getOrFetch(1.seconds)(IO(1)) >>
        ref.cancel
    } shouldBe true
  }

  test("auto refresh value") {
    testWithRef { ref =>
      for {
        count <- counter
        _ <- ref.getOrFetch(50.milliseconds) {
               count.update(_ + 1) *> count.get
             }
        _ <- timer.sleep(100.milliseconds)
        c <- count.get
      } yield
        c should be >(1)
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
      } yield
        c should be <(2)
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
      } yield
        c shouldBe 0
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
      } yield
        newValue should be >=(2)
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
      } yield c

    } should be >(1)
  }

  test("returns None after cancel") {
    testWithRef { ref =>
      for {
        _ <- ref.getOrFetch(1.second)(IO(1))
        _ <- ref.cancel
        r <- ref.get
      } yield
        r shouldBe None
    }
  }

  test("resource cancel itself after use") {

    val refreshCount = (for {
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
    } yield c).unsafeRunSync()

    refreshCount should be >(0)
    refreshCount should be <(4)
  }
}
