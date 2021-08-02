package scala.meta.metabrowse

import scala.meta.Dialect

object Util {
  def dialect(name: String): Option[Dialect] =
    Dialect.standards.get(name)
  lazy val availableDialectNames: Seq[String] =
    Dialect.standards.keySet.toVector.sorted
}
