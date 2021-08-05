import $ivy.`de.tototec::de.tobiasroeser.mill.vcs.version_mill0.9:0.1.1`
import $ivy.`io.github.alexarchambault.mill::mill-native-image_mill0.9:0.1.3`
import $ivy.`io.github.alexarchambault.mill::mill-native-image-upload:0.1.3`

import java.io.File

import de.tobiasroeser.mill.vcs.version._
import io.github.alexarchambault.millnativeimage.NativeImage
import io.github.alexarchambault.millnativeimage.upload.Upload
import mill._

import scala.util.Properties

def scalaVersions = Seq("2.12.12", "2.12.13", "2.12.14", "2.13.4", "2.13.5", "2.13.6")

object native extends Cross[Native](scalaVersions: _*)

class Native(private val scalaVersion: String) extends NativeImage {
  def nativeImageGraalVmJvmId = "graalvm-java11:21.2.0"
  def nativeImageName = "metabrowse"
  def classPath = T{
    // cache this?
    val sbt = if (Properties.isWin) "sbt.bat" else "sbt"
    os.proc(sbt, s"++$scalaVersion", "server-cli/writeCp").call(
      cwd = os.pwd / os.up,
      stdout = os.Inherit
    )
    val cpFile = os.pwd / os.up / "server-cli-class-path"
    val cp = os.read(cpFile).trim.split(File.pathSeparator)
    cp.toSeq.map(path => os.Path(path, os.pwd)).filter(os.exists(_)).map(PathRef(_))
  }
  def nativeImageClassPath = T{
    val workingDir = T.dest / "working-dir"
    os.makeDir.all(workingDir)
    classPath().map { r =>
      if (os.isFile(r.path))
        maybePatchJar(r.path, workingDir, !_.startsWith("org/wildfly/common/net/Substitutions")) match {
          case None => r
          case Some(updatedJar) => PathRef(updatedJar)
        }
      else
        r
    }
  }
  def nativeImageMainClass = "metabrowse.server.cli.MetabrowseServerCli"

  def copyToArtifacts(directory: String = "../artifacts/") = T.command {
    val _ = Upload.copyLauncher(
      nativeImage().path,
      directory,
      "metabrowse",
      compress = true
    )
  }
}

private def maybePatchJar(
  jar: os.Path,
  workingDir: os.Path,
  keep: String => Boolean
): Option[os.Path] = {

  import java.io._
  import java.util.zip._
  import scala.collection.JavaConverters._

  var zf: ZipFile = null
  var fos: FileOutputStream = null
  var zos: ZipOutputStream = null
  try {
    zf = new ZipFile(jar.toIO)

    if (zf.entries.asScala.iterator.map(_.getName).forall(keep))
      None
    else {
      val destJar = workingDir / jar.last
      fos = new FileOutputStream(destJar.toIO)
      zos = new ZipOutputStream(fos)
      val buf = Array.ofDim[Byte](64*1024)
      for (ent <- zf.entries.asScala if keep(ent.getName)) {
        zos.putNextEntry(ent)
        var is: InputStream = null
        try {
          is = zf.getInputStream(ent)
          var read = -1
          while ({
            read = is.read(buf)
            read >= 0
          }) {
            if (read > 0)
              zos.write(buf, 0, read)
          }
        } finally {
          if (is != null)
            is.close()
        }
      }
      zos.finish()

      Some(destJar)
    }
  } finally {
    if (zf != null) zf.close()
    if (zos != null) zos.close()
    if (fos != null) fos.close()
  }
}


def publishVersion = T{
  val state = VcsVersion.vcsState()
  if (state.commitsSinceLastTag > 0) {
    val versionOrEmpty = state.lastTag
      .filter(_ != "latest")
      .map(_.stripPrefix("v"))
      .flatMap { tag =>
        val idx = tag.lastIndexOf(".")
        if (idx >= 0) Some(tag.take(idx + 1) + (tag.drop(idx + 1).toInt + 1).toString + "-SNAPSHOT")
        else None
      }
      .getOrElse("0.0.1-SNAPSHOT")
    Some(versionOrEmpty)
      .filter(_.nonEmpty)
      .getOrElse(state.format())
  } else
    state
      .lastTag
      .getOrElse(state.format())
      .stripPrefix("v")
}

def upload(directory: String = "../artifacts/") = T.command {
  val version = publishVersion()

  val path = os.Path(directory, os.pwd)
  val launchers = os.list(path).filter(os.isFile(_)).map { path =>
    path.toNIO -> path.last
  }
  val ghToken = Option(System.getenv("UPLOAD_GH_TOKEN")).getOrElse {
    sys.error("UPLOAD_GH_TOKEN not set")
  }
  val (tag, overwriteAssets) =
    if (version.endsWith("-SNAPSHOT")) ("latest", true)
    else ("v" + version, false)
  Upload.upload("alexarchambault", "metabrowse", ghToken, tag, dryRun = false, overwrite = overwriteAssets)(launchers: _*)
}
