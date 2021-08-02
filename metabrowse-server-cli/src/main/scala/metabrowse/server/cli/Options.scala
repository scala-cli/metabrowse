package metabrowse.server.cli

import caseapp._

final case class Options(
  dialect: Option[String] = None,
  scalac: List[String] = Nil,
  host: String = "localhost",
  port: Int = 4000,
  sourcePath: List[String] = Nil,
  classPath: List[String] = Nil,
  message: String = "Metabrowse server listening at http://{HOST}:{PORT}, press Ctrl+C to exit."
)
