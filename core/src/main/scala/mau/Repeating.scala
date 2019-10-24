package mau

abstract class Repeating[F[_]] {
  def pause: F[Boolean]
  def resume: F[Boolean]
}
