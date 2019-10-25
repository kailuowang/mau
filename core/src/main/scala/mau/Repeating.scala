package mau

import cats.effect.concurrent.{Deferred, Ref}
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

  /**
    * see doc of [resource]
    */
  def create[F[_]](
      effect: F[Unit],
      repeatDuration: FiniteDuration,
      runInParallel: Boolean
    )(implicit F: Concurrent[F],
      T: Timer[F]
    ): F[Repeating[F]] = {
    Ref[F]
      .of(none[Fiber[F, Unit]])
      .map { ref =>
        new Repeating[F] {
          def pause: F[Boolean] =
            for {
              fiberO <- ref.getAndSet(None)
              success <- fiberO.traverse(_.cancel).map(_.isDefined)
            } yield success

          def resume: F[Boolean] = {
            def loop: F[Unit] = {
              val runEffect = if (runInParallel) F.start(effect).void else effect
              T.sleep(repeatDuration) >> runEffect >> loop
            }

            running.ifM(
              false.pure[F],
              for {
                go <- Deferred[F, Unit]
                fiber <- F.start(go.get >> loop)
                success <- ref.modify { existing =>
                  if (existing.isDefined) (existing, false)
                  else (Some(fiber), true)
                }
                _ <- if (success) go.complete(()) else fiber.cancel
              } yield success
            )
          }

          def running: F[Boolean] = ref.get.map(_.isDefined)
        }
      }
      .flatTap(_.resume)

  }

  /**
    *
    * @param effect the effect to be repeated
    * @param repeatDuration duration between each repetition
    * @param runInParallel run the effect in parallel. This will make the execution
    *                      timing more accurate. Also it prevent `pause` from canceling
    *                      the effect.
    *
    */
  def resource[F[_]: Concurrent: Timer](
      effect: F[Unit],
      repeatDuration: FiniteDuration,
      runInParallel: Boolean
    ): Resource[F, Repeating[F]] =
    Resource.make(create(effect, repeatDuration, runInParallel))(_.pause.void)
}
