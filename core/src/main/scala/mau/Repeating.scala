package mau

import cats.effect.concurrent.Ref
import cats.effect.{Concurrent, Fiber, Resource, Timer}
import cats.implicits._

import scala.concurrent.duration.FiniteDuration

abstract class Repeating[F[_]] {

  /**
    *
    * @return true if it was running and now successfully paused
    */
  def pause: F[Boolean]

  /**
    *
    * @return true if it was paused and now successfully resumed
    */
  def resume: F[Boolean]

  def running: F[Boolean]
}

object Repeating {

  def create[F[_]](
      effect: F[Unit],
      repeatDuration: FiniteDuration
    )(implicit F: Concurrent[F],
      T: Timer[F]
    ): F[Repeating[F]] = {
    Ref[F]
      .of(none[Fiber[F, Unit]])
      .map { ref =>
        new Repeating[F] {
          def pause: F[Boolean] =
            for {
              fiberO <- ref.modify(existing => (None, existing))
              success <- fiberO.traverse(_.cancel).map(_.isDefined)
            } yield success

          def resume: F[Boolean] = {
            def loop: F[Unit] =
              T.sleep(repeatDuration) >> effect >> loop

            running.ifM(
              false.pure[F],
              for {
                fiber <- F.start(loop)
                success <- ref.modify { existing =>
                  if (existing.isDefined) (existing, false)
                  else (Some(fiber), true)
                }
                _ <- if (!success) fiber.cancel else F.unit
              } yield success
            )
          }

          def running: F[Boolean] = ref.get.map(_.isDefined)
        }
      }
      .flatTap(_.resume)

  }

  def resource[F[_]: Concurrent: Timer](
      effect: F[Unit],
      repeatDuration: FiniteDuration
    ): Resource[F, Repeating[F]] =
    Resource.make(create(effect, repeatDuration))(_.pause.void)
}
