package mau

import cats.effect.{Async, Fiber, Resource, Ref, Temporal}
import cats.implicits._

import scala.concurrent.duration.FiniteDuration

abstract class RefreshRef[F[_], V] {

  /**
    * Cancel polling and remove data from memory
    * @return true if there was data and polling, false if it's empty
    */
  def cancel: F[Boolean]

  /**
    * Simply gets the data from memory
    * @return Some(v) if exists, None if not
    */
  def get: F[Option[V]]

  protected def get(
      period: FiniteDuration,
      staleTimeoutO: Option[FiniteDuration]
    )(fetch: F[V]
    )(errorHandler: PartialFunction[Throwable, F[Unit]]
    ): F[V]

  /**
    * Either gets the data from the memory if available, or use the `fetch` to retrieve the data, and setup
    * a polling every `period` to update the data in memory using `fetch`. Hence the first call to `ref.getOrFetch` will take longer
    * to actually load the data from upstream to memory. Subsequent call will always return the data from memory.
    *
    * When any exception occurs during `getDataFromUpstream`, the refresh stops, and the data is removed from the memory.
    * All subsequent requests will incure effect in `fetch`, whose failure will be surfaced, until
    * it succeeds.
    *
    * @param period if set to zero will simply return `fetch`
    * @param fetch
    * @return
    */
  def getOrFetch(period: FiniteDuration)(fetch: F[V]): F[V] =
    get(period, None)(fetch)(PartialFunction.empty)

  /**
    * Like `getOrFetch(period: FiniteDuration)(fetch: F[V])` but with added resilency against failures in `fetch`.
    *
    * After `staleTimeout` of continuous polling failures, the polling will stop and data removed.
    * A success `fetch`  resets the timer.
    *
    * @param period if set to zero will simply return `fetch`
    * @param staleTimeout timeout after the last successful `fetch`RepeatingSuite.scala
    * @param fetch
    * @param errorHandler
    * @return
    */
  def getOrFetch(
      period: FiniteDuration,
      staleTimeout: FiniteDuration
    )(fetch: F[V]
    )(errorHandler: PartialFunction[Throwable, F[Unit]]
    ): F[V] =
    get(period, Some(staleTimeout))(fetch)(errorHandler)

}

object RefreshRef {
  private type Instant = Long

  private case class Item[F[_], V](
      v: V,
      refresh: Fiber[F, Throwable, Unit],
      lastFetch: Instant)

  def create[F[_], V](
      onRefreshed: V => F[Unit]
    )(implicit F: Async[F],
      T: Temporal[F]
    ): F[RefreshRef[F, V]] =
    Ref.of(none[Item[F, V]]).map { ref =>
      new RefreshRef[F, V] {

        def cancel: F[Boolean] =
          ref.modify {
            case None                => (None, F.pure(false))
            case Some(Item(_, f, _)) => (None, f.cancel *> F.pure(true))
          }.flatten

        /**
          * Simply gets the data from memory
          * @return Some(v) if exists, None if not
          */
        def get: F[Option[V]] = ref.get.map(_.map(_.v))

        protected def get(
            period: FiniteDuration,
            staleTimeoutO: Option[FiniteDuration]
          )(fetch: F[V]
          )(errorHandler: PartialFunction[Throwable, F[Unit]]
          ): F[V] = {
          def startRefresh: F[V] = {

            def onFetchError(e: Throwable): F[Unit] = {
              def isStale: F[Boolean] =
                staleTimeoutO.fold(F.pure(false)) { timeout =>
                  for {
                    itemO <- ref.get
                    now <- nowF
                  } yield itemO
                    .fold(false)(_.lastFetch < (now - timeout.toNanos))
                }

              errorHandler.lift(e) match {
                case None =>
                  cancel.void
                case Some(fu) =>
                  (fu >> isStale)
                    .flatMap { stale =>
                      if (stale) cancel.void
                      else loop
                    }
                    .handleErrorWith(_ => cancel.void)
              }
            }

            def loop: F[Unit] =
              Temporal[F].sleep(period) >>
                fetch.attempt.flatMap {
                  case Left(e) => onFetchError(e)

                  case Right(v) =>
                    onRefreshed(v) *>
                      nowF.flatMap { now =>
                        ref.tryUpdate(_.map(_.copy(v = v, lastFetch = now)))
                      } *> loop
                }

            for {
              initialV <- fetch
              fiber <- F.start(loop)
              now <- nowF
              registeredO <- ref.tryModify {
                case None        => (Some(Item(initialV, fiber, now)), true)
                case e @ Some(_) => (e, false)
              }
              _ <- if (registeredO.fold(false)(identity)) F.unit
              else
                fiber.cancel //cancel if setting refresh fails
            } yield initialV
          }

          if (period.toNanos == 0L) fetch
          else
            ref.get.flatMap {
              case Some(Item(v, _, _)) => v.pure[F]
              case None                => startRefresh
            }
        }

        private val nowF: F[Instant] =
          T.monotonic.map(_.length)
      }
    }

  /**
    * Cancel itself after use
    */
  def resource[F[_]: Async, V](
      onRefreshed: V => F[Unit]
    ): Resource[F, RefreshRef[F, V]] =
    Resource.make(create[F, V](onRefreshed))(_.cancel.void)

  def resource[F[_]: Async, V]: Resource[F, RefreshRef[F, V]] =
    resource[F, V]((_: V) => Async[F].unit)
}
