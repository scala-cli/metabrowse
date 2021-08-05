package metabrowse.server.cli

import caseapp._
import metabrowse.server.MetabrowseServer
import metabrowse.server.Sourcepath

import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import scala.meta.dialects
import scala.meta.metabrowse.Util
import scala.util.Properties
import java.net.ServerSocket

object MetabrowseServerCli extends CaseApp[Options] {
  def run(options: Options, args: RemainingArgs): Unit = {

    // silence undertow logging
    sys.props("org.jboss.logging.provider") = "slf4j"

    val port =
      if (options.port >= 0) options.port
      else randomPort()

    val dialect =
      options.dialect.map(_.trim).filter(_.nonEmpty) match {
        case Some(name) =>
          Util.dialect(name).getOrElse {
            System.err.println(s"Unknown dialect: '$name'")
            System.err.println(s"Available dialects: ${Util.availableDialectNames.mkString(", ")}")
            sys.exit(1)
          }
        case None =>
          val sv = Properties.versionNumberString
          if (sv.startsWith("2.12.")) dialects.Scala212
          else dialects.Scala213
      }

    val server = new MetabrowseServer(
      dialect = dialect,
      scalacOptions = options.scalac.filter(_.nonEmpty),
      host = options.host,
      port = port
    )

    val sourcePath = Sourcepath(
      path(options.classPath),
      path(options.sourcePath)
    )

    val message = options.message
      .replace("{HOST}", options.host)
      .replace("{PORT}", options.port.toString)

    try {
      server.start(sourcePath)
      System.err.println(message)
      while (System.in.read() != -1) {}
    } finally {
      try server.stop()
      catch {
        case t: Throwable =>
      }
    }
  }

  private def path(values: List[String]): List[Path] =
    values
      .flatMap(_.split(File.pathSeparator).toSeq)
      .filter(_.nonEmpty)
      .map(Paths.get(_))

  private def randomPort(): Int = {
    val s = new ServerSocket(0)
    val port = s.getLocalPort
    s.close()
    port
  }
}
