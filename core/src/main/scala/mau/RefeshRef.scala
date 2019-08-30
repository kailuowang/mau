package mau

import java.time.Instant

import cats.effect.{Concurrent, Fiber, Resource, Timer}
import cats.effect.concurrent.Ref
import cats.implicits._
import mau.RefreshRef.Item

import scala.concurrent.duration.FiniteDuration

class RefreshRef[F[_], V] private(ref: Ref[F, Option[Item[F, V]]], onRefreshed: V => F[Unit])(
  implicit F: Concurrent[F], T: Timer[F]) {

  def cancel: F[Boolean] = ref.modify {
    case None => (None, F.pure(false))
    case Some(Item(_, f, _)) => (None, f.cancel *> F.pure(true))
  }.flatten


  def getOrFetch(period: FiniteDuration)(fetch: F[V]): F[V] =
    get(period, None)(fetch)(PartialFunction.empty)

  def getOrFetch(period: FiniteDuration, staleTimeout: FiniteDuration)
                (fetch: F[V])
                (errorHandler: PartialFunction[Throwable, F[Unit]])
  : F[V] = get(period, Some(staleTimeout))(fetch)(errorHandler)

  def get: F[Option[V]] = ref.get.map(_.map(_.v))


  private def get(period: FiniteDuration, staleTimeoutO: Option[FiniteDuration])
                 (fetch: F[V])
                 (errorHandler: PartialFunction[Throwable, F[Unit]])
  : F[V] = {
    def startRefresh: F[V] = {

      def onFetchError(e: Throwable): F[Unit] = {
        def isStale: F[Boolean] =
          staleTimeoutO.fold(F.pure(false)) { timeout =>
            for {
              itemO <- ref.get
              now <- nowF
            } yield
              itemO.fold(false)(_.lastFetch.isBefore(now.minusNanos(timeout.toNanos)))
          }

        errorHandler.lift(e) match {
          case None =>
            cancel.void
          case Some(fu) =>
            (fu >> isStale).flatMap { stale =>
              if(stale) cancel.void
              else loop
            }
        }
      }

      def loop: F[Unit] =
        Timer[F].sleep(period) >>
          fetch.attempt.flatMap {
            case Left(e) => onFetchError(e)

            case Right(v) =>
              for {
                _ <- onRefreshed(v)
                now <- nowF
                _ <- ref.tryUpdate(_.map(_.copy(v = v, lastFetch = now)))
                _ <- loop
              } yield ()

          }


      for {
        initialV <- fetch
        fiber <- F.start(loop)
        now <- nowF
        registeredO  <- ref.tryModify {
          case None => (Some(Item(initialV, fiber, now)), true)
          case e @ Some(_) => (e, false)
        }
        _ <- if(registeredO.fold(false)(identity)) F.unit else
          fiber.cancel //cancel if setting refresh fails
      } yield initialV
    }

    ref.get.flatMap {
      case Some(Item(v, _, _)) => v.pure[F]
      case None => startRefresh
    }
  }

  private def nowF: F[Instant] = F.delay(Instant.now)
}

object RefreshRef {

  private case class Item[F[_], V](v: V, refresh: Fiber[F, Unit], lastFetch: Instant)

  def create[F[_]: Concurrent: Timer, V](onRefreshed: V => F[Unit]): F[RefreshRef[F, V]] =
    Ref.of(none[Item[F, V]]).map(new RefreshRef[F, V](_, onRefreshed))

  /**
   * Cancel itself after use
   */
  def resource[F[_]: Concurrent: Timer, V](onRefreshed: V => F[Unit]): Resource[F, RefreshRef[F, V]] =
    Resource.make(create[F, V](onRefreshed))(_.cancel.void)


  def resource[F[_]: Concurrent: Timer, V]: Resource[F, RefreshRef[F, V]] =
    resource[F, V]((_: V) => Concurrent[F].unit)
}
