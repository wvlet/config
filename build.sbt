import ReleaseTransformations._

val SCALA_2_12 = "2.12.2"
scalaVersion in ThisBuild := SCALA_2_12

val buildSettings = Seq[Setting[_]](
  crossScalaVersions := Seq(SCALA_2_12, "2.11.11"),
  organization := "org.wvlet",
  description := "A framework for structured data mapping",
  crossPaths := true,
  publishMavenStyle := true,
  // For performance testing, ensure each test run one-by-one
  concurrentRestrictions in Global := Seq(Tags.limit(Tags.Test, 1)),
  incOptions := incOptions.value.withNameHashing(true),
  logBuffered in Test := false,
  updateOptions := updateOptions.value.withCachedResolution(true),
  sonatypeProfileName := "org.wvlet",
  pomExtra := {
  <url>https://github.com/xerial/wvlet</url>
    <licenses>
      <license>
        <name>Apache 2</name>
        <url>http://www.apache.org/licenses/LICENSE-2.0.txt</url>
      </license>
    </licenses>
    <scm>
      <connection>scm:git:github.com/wvlet/config.git</connection>
      <developerConnection>scm:git:git@github.com:wvlet/config.git</developerConnection>
      <url>github.com/wvlet/config.git</url>
    </scm>
    <developers>
      <developer>
        <id>leo</id>
        <name>Taro L. Saito</name>
        <url>http://xerial.org/leo</url>
      </developer>
    </developers>
  },
  // Use sonatype resolvers
  resolvers ++= Seq(
    Resolver.sonatypeRepo("releases"),
    Resolver.sonatypeRepo("snapshots")
  ),
  // Release settings
  releaseTagName := { (version in ThisBuild).value },
  releaseProcess := Seq[ReleaseStep](
    checkSnapshotDependencies,
    inquireVersions,
    runClean,
    runTest,
    setReleaseVersion,
    commitReleaseVersion,
    tagRelease,
    ReleaseStep(action = Command.process("publishSigned", _), enableCrossBuild = true),
    setNextVersion,
    commitNextVersion,
    ReleaseStep(action = Command.process("sonatypeReleaseAll", _), enableCrossBuild = true),
    pushChanges
  ),
  releaseCrossBuild := true
)

val wvletTest = "org.wvlet" %% "wvlet-test" % "0.27" % "test"

lazy val wvletConfig =
  Project(id = "wvlet-config", base = file(".")).settings(
    buildSettings,
    description := "wvlet configuration module",
    libraryDependencies ++= Seq(
      "org.scala-lang" % "scala-compiler" % scalaVersion.value % "provided",
      "org.scala-lang" % "scala-reflect" % scalaVersion.value,
      "org.yaml" % "snakeyaml" % "1.14",
      "org.wvlet" %% "surface" % "0.14-SNAPSHOT",
      "org.wvlet" %% "wvlet-log" % "1.2.3",
      wvletTest
    )
  )

