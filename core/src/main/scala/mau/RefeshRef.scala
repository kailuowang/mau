package mau

import cats.effect.{Concurrent, Fiber, Timer}
import cats.effect.concurrent.Ref
import cats.implicits._
import scala.concurrent.duration.FiniteDuration

class RefreshRef[F[_], V] private(ref: Ref[F, Option[(V, Fiber[F, Unit])]])(implicit F: Concurrent[F], T: Timer[F]) {

  def cancel: F[Boolean] = ref.modify {
    case None => (None, F.pure(false))
    case Some((_, f)) => (None, f.cancel *> F.pure(true))
  }.flatten

  def getOrFetch(period: FiniteDuration)(fetch: F[V]): F[V] = {
    def setRefresh: F[V] = {
      def loop: F[Unit] =
        (Timer[F].sleep(period) >> fetch).flatMap { v =>
          ref.tryUpdate(_.map(_.leftMap(_ => v)))
        } >> loop

      for {
        initialV <- fetch
        fiber <- F.start(loop)
        registeredO  <- ref.tryModify {
          case None => (Some((initialV, fiber)), true)
          case e @ Some(_) => (e, false)
        }
        _ <- if(registeredO.fold(false)(identity)) F.unit else
          fiber.cancel
      } yield initialV
    }

    ref.get.flatMap {
      case Some((v, _)) => v.pure[F]
      case None => setRefresh
    }
  }
}

object RefreshRef {
  def create[F[_]: Concurrent: Timer, V]: F[RefreshRef[F, V]] =
    Ref.of(none[(V, Fiber[F, Unit])]).map(new RefreshRef[F, V](_))
}
