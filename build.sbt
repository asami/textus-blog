import org.goldenport.cozy.CozyPlugin.autoImport._
import sbt.Keys.*

val scala3Version = "3.3.7"
def projectVersion(envName: String, fileName: String, fallback: String): String =
  sys.env.get(envName)
    .orElse {
      val versionFile = file("versions") / fileName
      if (versionFile.isFile)
        Some(IO.read(versionFile).trim).filter(_.nonEmpty)
      else
        None
    }
    .getOrElse(fallback)

val cncfVersion = projectVersion("CNCF_VERSION", "cncf-version.conf", "0.4.6")
val simpleModelingModelVersion = projectVersion("SIMPLEMODELING_MODEL_VERSION", "simplemodeling-model-version.conf", "0.1.6")
val cncfCollaboratorApiVersion = "0.1.0"

lazy val root = project
  .in(file("."))
  .enablePlugins(org.goldenport.cozy.CozyPlugin)
  .settings(
    organization := "org.textus",
    name := "textus-blog",
    version := "0.0.2-SNAPSHOT",

    scalaVersion := scala3Version,

    resolvers += Resolver.defaultLocal,
    resolvers += Resolver.file("Local Ivy", file(Path.userHome.absolutePath + "/.ivy2/local"))(Resolver.ivyStylePatterns),
    resolvers += "Local Maven Repository" at ("file://" + Path.userHome.absolutePath + "/.m2/repository"),
    resolvers += "SimpleModeling.org" at "https://www.simplemodeling.org/maven",

    libraryDependencies += "com.novocode" % "junit-interface" % "0.11" % "test",
    libraryDependencies += "org.typelevel" %% "cats-core" % "2.7.0",
    libraryDependencies += "org.typelevel" %% "cats-kernel-laws" % "2.7.0",
    libraryDependencies += "org.typelevel" %% "cats-free" % "2.7.0",
    libraryDependencies += "org.typelevel" %% "cats-effect" % "3.3.0",
    libraryDependencies += "org.typelevel" %% "kittens" % "3.5.0",
    libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.10" % "test",
    libraryDependencies += "org.typelevel" %% "cats-testkit" % "2.7.0" % "test",
    libraryDependencies += "org.typelevel" %% "discipline-core" % "1.3.0" % "test",
    libraryDependencies += "org.typelevel" %% "discipline-scalatest" % "2.1.5" % "test",
    libraryDependencies += "org.typelevel" %% "spire" % "0.18.0",
    libraryDependencies += "io.circe" %% "circe-core" % "0.14.3",
    libraryDependencies += "io.circe" %% "circe-generic" % "0.14.3",
    libraryDependencies += "io.circe" %% "circe-parser" % "0.14.3",
    libraryDependencies += "org.goldenport" %% "goldenport-cncf" % cncfVersion,
    libraryDependencies += "org.simplemodeling" %% "simplemodeling-model" % simpleModelingModelVersion,
    libraryDependencies += "org.goldenport" % "cncf-collaborator-api" % cncfCollaboratorApiVersion,

    dependencyOverrides ++= Seq(
      "org.goldenport" % "cncf-collaborator-api" % cncfCollaboratorApiVersion,
      "org.scala-lang.modules" %% "scala-xml" % "2.1.0",
      "org.scala-lang.modules" %% "scala-parser-combinators" % "2.3.0"
    ),

    cozyGeneratorBackend := "cozy",
    cozyDelegateProjectDir := None,
    cozyDelegateCommand := Seq("cozy"),
    cozyCncfVersion := cncfVersion,
    cozySimpleModelingModelVersion := simpleModelingModelVersion,
    cozyCncfCollaboratorApiVersion := cncfCollaboratorApiVersion,
    cozyManifestMetadata ++= Map(
      "component" -> "blog-component",
      "boundedContext" -> "content",
      "domain" -> "blog"
    ),

    Compile / sourceGenerators += Def.task {
      val out = (Compile / sourceManaged).value / "domain" / "meta" / "BuildVersion.scala"
      val content =
        "package domain.meta\n\nobject BuildVersion {\n" +
          "  val name: String = \"" + name.value + "\"\n" +
          "  val version: String = \"" + version.value + "\"\n" +
          "  val scalaVersion: String = \"" + scalaVersion.value + "\"\n" +
          "}\n"
      IO.write(out, content)
      Seq(out)
    }.taskValue,

    Compile / resourceGenerators += Def.task {
      val out = (Compile / resourceManaged).value / "META-INF" / "services" / "org.goldenport.cncf.component.Component$BundleFactory"
      IO.write(out, "org.simplemodeling.textus.blog.ComponentFactory\n")
      Seq(out)
    }.taskValue
  )
