import java.io.File
import java.nio.file.{Files, Path, Paths}
import scala.util.Properties
import scalapb.compiler.Version.scalapbVersion
import scalajsbundler.util.JSON._
import sbtcrossproject.{crossProject, CrossType}

lazy val Version = new {
  def scala213 = "2.13.6"
  def scala212 = "2.12.14"
  def scalameta = "4.4.24"
}

inThisBuild(
  List(
    organization := "org.scalameta",
    licenses := Seq(
      "Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")
    ),
    homepage := Some(url("https://github.com/scalameta/metabrowse")),
    autoAPIMappings := true,
    apiURL := Some(url("https://scalameta.github.io/metabrowse")),
    developers := List(
      Developer(
        "olafurpg",
        "Ólafur Páll Geirsson",
        "olafurpg@users.noreply.github.com",
        url("https://geirsson.com")
      ),
      Developer(
        "jonas",
        "Jonas Fonseca",
        "jonas@users.noreply.github.com",
        url("https://github.com/jonas")
      )
    ),
    scalaVersion := Version.scala213,
    crossScalaVersions := Seq(
      Version.scala213,
      Version.scala212
    ),
    scalacOptions := Seq(
      "-deprecation",
      "-encoding",
      "UTF-8",
      "-feature",
      "-unchecked"
    )
  )
)

(Global / cancelable) := true

(publish / skip) := true
crossScalaVersions := Nil

def addPaigesLikeSourceDirs(config: Configuration, srcName: String) =
  Def.settings(
    config / unmanagedSourceDirectories ++= {
      val srcBaseDir = baseDirectory.value
      val scalaVersion0 = scalaVersion.value
      def extraDirs(suffix: String) =
        List(srcBaseDir / "src" / srcName / s"scala$suffix")
      CrossVersion.partialVersion(scalaVersion0) match {
        case Some((2, y)) if y <= 12 =>
          extraDirs("-2.12-")
        case Some((2, y)) if y >= 13 =>
          extraDirs("-2.13+")
        case Some((3, _)) =>
          extraDirs("-2.13+")
        case _ => Nil
      }
    }
  )

lazy val example = project
  .in(file("paiges") / "core")
  .settings(
    publish / skip := true,
    addPaigesLikeSourceDirs(Compile, "main"),
    addCompilerPlugin(
      "org.scalameta" % "semanticdb-scalac" % Version.scalameta cross CrossVersion.full
    ),
    scalacOptions ++= Seq(
      "-Yrangepos",
      "-Xplugin-require:semanticdb"
    ),
    libraryDependencies ++= List(
      "org.scalatest" %% "scalatest" % "3.1.4" % Test,
      "org.scalacheck" %% "scalacheck" % "1.14.0" % Test,
      "org.scalatestplus" %% "scalacheck-1-14" % "3.2.2.0" % Test
    ),
    test := {} // no need to run paiges tests.
  )

val needsPatchedWildfly = Properties.isWin

lazy val maybeAddPatchedWildfly =
  if (needsPatchedWildfly)
    Def.settings(
      Compile / unmanagedJars += {
        import java.io._
        import java.util.zip._
        import scala.collection.JavaConverters._
        import sys.process._
        val baseJarPath = Seq("cs", "fetch", "--intransitive", "org.wildfly.common:wildfly-common:1.5.2.Final").!!.linesIterator.map(_.trim).filter(_.nonEmpty).toStream.head
        val baseJar = Paths.get(baseJarPath)
        val tmpDir = baseDirectory.value.toPath.resolve("target/patched-jar")
        Files.createDirectories(tmpDir)
        val destJar = tmpDir.resolve("wildfly-common-patched.jar")

        def strip(name: String): Boolean =
          name.startsWith("org/wildfly/common/net/Substitutions")

          var zf: ZipFile = null
          var fos: FileOutputStream = null
          var zos: ZipOutputStream = null
          try {
            zf = new ZipFile(baseJar.toFile)
            fos = new FileOutputStream(destJar.toFile)
            zos = new ZipOutputStream(fos)
            val buf = Array.ofDim[Byte](64*1024)
            for (ent <- zf.entries.asScala if !strip(ent.getName)) {
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
          } finally {
            if (zf != null) zf.close()
            if (zos != null) zos.close()
            if (fos != null) fos.close()
          }

        destJar.toFile
      }
    )
  else
    Def.settings()

lazy val server = project
  .in(file("metabrowse-server"))
  .settings(
    moduleName := "metabrowse-server",
    resolvers += Resolver.sonatypeRepo("releases"),
    resolvers += Resolver.sonatypeRepo("snapshots"),
    libraryDependencies ++= {
      val undertow = "io.undertow" % "undertow-core" % "2.0.30.Final"
      val xnio = "org.jboss.xnio" % "xnio-nio" % "3.8.0.Final"
      List(
        if (needsPatchedWildfly) undertow.exclude("org.wildfly.common", "wildfly-common") else undertow,
        "org.slf4j" % "slf4j-api" % "1.8.0-beta4",
        if (needsPatchedWildfly) xnio.exclude("org.wildfly.common", "wildfly-common") else xnio,
        "org.scalameta" % "semanticdb-scalac-core" % Version.scalameta cross CrossVersion.full,
        ("org.scalameta" %% "mtags" % "0.10.5").cross(CrossVersion.full)
      )
    },
    maybeAddPatchedWildfly,
    (Compile / packageBin) := {
      import java.io.FileOutputStream
      import java.nio.file.attribute.FileTime
      import java.nio.file.Files
      import java.util.zip._
      import scala.collection.JavaConverters._
      val base = (Compile / packageBin).value
      val updated = base.getParentFile / s"${base.getName.stripSuffix(".jar")}-with-resources.jar"

      val fos = new FileOutputStream(updated)
      val zos = new ZipOutputStream(fos)

      val zf = new ZipFile(base)
      val buf = Array.ofDim[Byte](64 * 1024)
      for (ent <- zf.entries.asScala) {
        zos.putNextEntry(ent)
        val is = zf.getInputStream(ent)
        var read = -1
        while ({
          read = is.read(buf)
          read >= 0
        }) {
          if (read > 0)
            zos.write(buf, 0, read)
        }
      }
      zf.close()

      // FIXME - use fullOptJS: https://github.com/scalameta/metabrowse/issues/271
      val _ = (js / Compile / fastOptJS / webpack).value
      val targetDir = (js / Compile / npmUpdate).value
      // scalajs-bundler does not support setting a custom output path so
      // explicitly include only those files that are generated by webpack.
      val includes: FileFilter =
        "index.html" | "metabrowse.*.css" | "*-bundle.js" | "favicon.png"
      val paths: PathFinder =
        (
          targetDir./("assets").allPaths +++
            targetDir./("vs").allPaths +++
            targetDir.*(includes)
        ) --- targetDir
      val mappings = paths.get pair sbt.io.Path.relativeTo(targetDir)
      val prefix = "metabrowse/server/assets/"
      for ((f, path) <- mappings) {
        if (f.isDirectory) {
          val ent = new ZipEntry(prefix + path.stripSuffix("/") + "/")
          ent.setLastModifiedTime(FileTime.fromMillis(f.lastModified()))
          zos.putNextEntry(ent)
        } else {
          val ent = new ZipEntry(prefix + path)
          ent.setLastModifiedTime(FileTime.fromMillis(f.lastModified()))
          zos.putNextEntry(ent)
          val b = Files.readAllBytes(f.toPath)
          zos.write(b)
        }
      }

      zos.finish()
      zos.close()
      fos.close()

      updated
    },
    exportJars := true
  )
  .dependsOn(coreJVM)

lazy val copyNativeImage = taskKey[Unit]("")

def platformSuffix: String = {
  val arch = sys.props("os.arch").toLowerCase(java.util.Locale.ROOT) match {
    case "amd64" => "x86_64"
    case other => other
  }
  val os =
    if (Properties.isWin) "pc-win32"
    else if (Properties.isLinux) "pc-linux"
    else if (Properties.isMac) "apple-darwin"
    else sys.error(s"Unrecognized OS: ${sys.props("os.name")}")
  s"$arch-$os"
}

lazy val `server-cli` = project
  .in(file("metabrowse-server-cli"))
  .enablePlugins(CustomNativeImagePlugin)
  .settings(
    moduleName := "metabrowse-server-cli",
    Compile / mainClass := Some("metabrowse.server.cli.MetabrowseServerCli"),
    (assembly / mainClass) := Some("metabrowse.server.cli.MetabrowseServerCli"),
    (assembly / assemblyJarName) := "metabrowse-server.jar",
    libraryDependencies ++= List(
      "com.github.alexarchambault" %% "case-app" % "2.1.0-M2",
      "org.scalameta" %% "svm-subs" % "20.2.0",
      "org.graalvm.nativeimage" % "svm" % nativeImageVersion.value
    ),
    libraryDependencies := {
      libraryDependencies.value.filter { mod =>
        !mod.revision.startsWith("101")
      }
    },
    nativeImageJvmIndex := "https://github.com/coursier/jvm-index/raw/master/index.json",
    nativeImageCoursier := {

      def endsWithCaseInsensitive(s: String, suffix: String): Boolean =
        s.length >= suffix.length &&
          s.regionMatches(true, s.length - suffix.length, suffix, 0, suffix.length)

      def findInPath(app: String): Option[Path] = {
        val asIs = Paths.get(app)
        if (Paths.get(app).getNameCount >= 2) Some(asIs)
        else {
          def pathEntries =
            Option(System.getenv("PATH"))
              .iterator
              .flatMap(_.split(File.pathSeparator).iterator)
          def pathSep =
            if (Properties.isWin) Option(System.getenv("PATHEXT")).iterator.flatMap(_.split(File.pathSeparator).iterator)
            else Iterator("")
          def matches = for {
            dir <- pathEntries
            ext <- pathSep
            app0 = if (endsWithCaseInsensitive(app, ext)) app else app + ext
            path = Paths.get(dir).resolve(app0)
            if Files.isExecutable(path)
          } yield path
          matches.toStream.headOption
        }
      }

      findInPath("cs").map(_.toFile).getOrElse(sys.error("cs not found"))
    },
    nativeImageVersion := "21.2.0",
    copyNativeImage := {
      val executable = nativeImage.value
      val destDir = Paths.get("artifacts")
      val ext = if (Properties.isWin) ".exe" else ""
      val dest = destDir.resolve(s"metabrowse-$platformSuffix$ext")
      Files.createDirectories(destDir)
      Files.copy(executable.toPath, dest)
      System.err.println(s"Copied native image to $dest")
    }
  )
  .dependsOn(server)

lazy val cli = project
  .in(file("metabrowse-cli"))
  .settings(
    moduleName := "metabrowse-cli",
    (assembly / mainClass) := Some("metabrowse.cli.MetabrowseCli"),
    (assembly / assemblyJarName) := "metabrowse.jar",
    scalacOptions ++= {
      CrossVersion.partialVersion(scalaBinaryVersion.value) match {
        case Some((2, 11)) =>
          Seq("-Xexperimental")
        case _ =>
          Nil
      }
    },
    libraryDependencies ++= List(
      "com.thesamet.scalapb" %% "scalapb-json4s" % "0.11.1",
      "com.github.alexarchambault" %% "case-app" % "2.0.0-M9",
      "com.github.pathikrit" %% "better-files" % "3.9.1"
    ),
    libraryDependencies ++= {
      CrossVersion.partialVersion(scalaVersion.value) match {
        case Some((2, major)) if major >= 13 =>
          Seq(
            "org.scala-lang.modules" %% "scala-parallel-collections" % "1.0.3"
          )
        case _ =>
          Seq()
      }
    }
  )
  .dependsOn(server)

lazy val js = project
  .in(file("metabrowse-js"))
  .settings(
    (publish / skip) := true,
    moduleName := "metabrowse-js",
    addPaigesLikeSourceDirs(Test, "test"),
    Compile / additionalNpmConfig := Map("private" -> bool(true)),
    Test / additionalNpmConfig := (Compile / additionalNpmConfig).value,
    scalaJSUseMainModuleInitializer := true,
    webpack / version := "4.20.2",
    startWebpackDevServer / version := "3.11.2",
    useYarn := true,
    // FIXME - use fullOptJS: https://github.com/scalameta/metabrowse/issues/271
    Compile / fastOptJS / webpackExtraArgs ++= Seq(
      "-p",
      "--mode",
      "production"
    ),
    webpackConfigFile := Some(baseDirectory.value / "webpack.config.js"),
    libraryDependencies ++= Seq(
      "org.scala-js" %%% "scalajs-dom" % "0.9.8",
      "org.scalatest" %%% "scalatest" % "3.1.4" % Test
    ),
    (Compile / npmDevDependencies) ++= Seq(
      "clean-webpack-plugin" -> "3.0.0",
      "copy-webpack-plugin" -> "4.6.0",
      "css-loader" -> "0.28.11",
      "mini-css-extract-plugin" -> "0.4.3",
      "file-loader" -> "1.1.11",
      "html-webpack-plugin" -> "3.2.0",
      "image-webpack-loader" -> "4.6.0",
      "style-loader" -> "0.23.0",
      "ts-loader" -> "5.2.1",
      "typescript" -> "2.6.2",
      "webpack-merge" -> "4.2.2"
    ),
    (Compile / npmDependencies) ++= Seq(
      "pako" -> "1.0.6",
      "monaco-editor" -> "0.13.1",
      "roboto-fontface" -> "0.7.0",
      "material-components-web" -> "0.21.1"
    )
  )
  .dependsOn(coreJS)
  .enablePlugins(ScalaJSPlugin, ScalaJSBundlerPlugin)

lazy val core = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Pure)
  .in(file("metabrowse-core"))
  .jsSettings(
    (publish / skip) := true,
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule))
  )
  .settings(
    moduleName := "metabrowse-core",
    (Compile / PB.targets) := Seq(
      scalapb.gen(
        flatPackage = true // Don't append filename to package
      ) -> (Compile / sourceManaged).value./("protobuf")
    ),
    (Compile / PB.protoSources) := Seq(
      // necessary workaround for crossProjects.
      baseDirectory.value./("../src/main/protobuf")
    ),
    libraryDependencies ++= List(
      "org.scalameta" %%% "scalameta" % Version.scalameta,
      "com.thesamet.scalapb" %%% "scalapb-runtime" % scalapbVersion % "protobuf"
    )
  )
lazy val coreJVM = core.jvm
lazy val coreJS = core.js

commands += Command.command("metabrowse-site") { s =>
  val cliRun = Array(
    "cli/run",
    "--clean-target-first",
    "--non-interactive",
    "--target",
    "target/metabrowse",
    (example / Compile / classDirectory).value,
    (example / Test / classDirectory).value
  ).mkString(" ")

  "example/test:compile" ::
    cliRun ::
    s
}

val sbtPlugin = project
  .in(file("sbt-metabrowse"))
  .enablePlugins(BuildInfoPlugin)
  .settings(
    name := "sbt-metabrowse",
    scalaVersion := Version.scala212,
    crossScalaVersions := Seq(Version.scala212),
    publishLocal := publishLocal
      .dependsOn((coreJVM / publishLocal))
      .dependsOn((cli / publishLocal))
      .value,
    sbt.Keys.sbtPlugin := true,
    // scriptedBufferLog := false,
    scriptedLaunchOpts += "-Dproject.version=" + version.value,
    buildInfoPackage := "metabrowse.sbt",
    buildInfoKeys := Seq[BuildInfoKey](
      version,
      "scalametaVersion" -> Version.scalameta,
      (coreJVM / scalaVersion),
      (coreJVM / scalaBinaryVersion)
    )
  )
  .enablePlugins(ScriptedPlugin)

lazy val tests = project
  .in(file("metabrowse-tests"))
  .configs(IntegrationTest)
  .settings(
    (publish / skip) := true,
    Defaults.itSettings,
    run / baseDirectory := (ThisBuild / baseDirectory).value,
    buildInfoPackage := "metabrowse.tests",
    (Test / compile / compileInputs) :=
      (Test / compile / compileInputs)
        .dependsOn(
          (example / Compile / compile),
          (example / Test / compile)
        )
        .value,
    libraryDependencies ++= List(
      "org.scalameta" %% "testkit" % Version.scalameta,
      "org.scalameta" % "semanticdb-scalac-core" % Version.scalameta cross CrossVersion.full,
      "org.scalatest" %% "scalatest" % "3.1.4",
      "org.scalacheck" %% "scalacheck" % "1.14.0",
      "org.seleniumhq.selenium" % "selenium-java" % "3.141.59" % IntegrationTest,
      "org.slf4j" % "slf4j-simple" % "1.8.0-beta4"
    ),
    (IntegrationTest / compile) := {
      _root_.io.github.bonigarcia.wdm.WebDriverManager.chromedriver.setup()
      (IntegrationTest / compile).value
    },
    buildInfoKeys := Seq[BuildInfoKey](
      "sourceroot" -> (ThisBuild / baseDirectory).value,
      "exampleClassDirectory" -> List(
        (example / Compile / classDirectory).value,
        (example / Test / classDirectory).value
      )
    ),
    (Test / fork) := true
  )
  .dependsOn(cli, server)
  .enablePlugins(BuildInfoPlugin)

commands += Command.command("ci-test") { s =>
  s"++${sys.env("SCALA_VERSION")}" ::
    "Test / compile" ::
    "metabrowse-site" ::
    "test" ::
    s
}
