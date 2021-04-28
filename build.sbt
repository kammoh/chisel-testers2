// SPDX-License-Identifier: Apache-2.0

organization := "edu.berkeley.cs"
name := "chiseltest"

version := "0.5-SNAPSHOT"

scalaVersion := "2.13.5"

crossScalaVersions := Seq("2.12.13", "2.13.5")

resolvers ++= Seq(Resolver.sonatypeRepo("snapshots"), Resolver.sonatypeRepo("releases"))

testFrameworks += new TestFramework("utest.runner.Framework")

publishMavenStyle := true

Test / publishArtifact := false
pomIncludeRepository   := { x => false }

// scm is set by sbt-ci-release
pomExtra := (
  <url>http://chisel.eecs.berkeley.edu/</url>
  <licenses>
    <license>
      <name>apache_v2</name>
      <url>https://opensource.org/licenses/Apache-2.0</url>
      <distribution>repo</distribution>
    </license>
  </licenses>
<developers>
  <developer>
    <id>ducky64</id>
    <name>Richard Lin</name>
  </developer>
</developers>
)

publishTo := {
  val v = version.value
  val nexus = "https://oss.sonatype.org/"
  if (v.trim.endsWith("SNAPSHOT")) {
    Some("snapshots".at(nexus + "content/repositories/snapshots"))
  } else {
    Some("releases".at(nexus + "service/local/staging/deploy/maven2"))
  }
}

// Provide a managed dependency on X if -DXVersion="" is supplied on the command line.
val defaultVersions = Map("chisel3" -> "3.5-SNAPSHOT", "treadle" -> "1.5-SNAPSHOT")

scalacOptions ++= Seq(
  "-language:reflectiveCalls",
  "-deprecation",
  "-feature",
  "-Xcheckinit",
  "-Xlint:infer-any",
  "-Xlint:type-parameter-shadow",
  "-Yrangepos"
) ++ {
  CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((2, n)) if n >= 13 => Seq("-Ymacro-annotations", "-Wunused")
    case _                       => Seq("-Ywarn-unused")
  }
}

libraryDependencies ++= Seq(
  "edu.berkeley.cs" %% "chisel3"       % defaultVersions("chisel3"),
  "edu.berkeley.cs" %% "treadle"       % defaultVersions("treadle"),
  "org.scalatest"   %% "scalatest"     % "3.2.8",
  "org.scalacheck"  %% "scalacheck"    % "1.15.3",
  "com.lihaoyi"     %% "utest"         % "0.7.7",
  "org.scala-lang"   % "scala-reflect" % scalaVersion.value,
  compilerPlugin("org.scalameta"   % "semanticdb-scalac" % "4.4.15" cross CrossVersion.full),
  compilerPlugin("edu.berkeley.cs" % "chisel3-plugin"    % defaultVersions("chisel3") cross CrossVersion.full)
) ++ {
  CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((2, n)) if n >= 13 => Nil
    case _ =>
      Seq(
        compilerPlugin("org.scalamacros" % "paradise" % "2.1.1" cross CrossVersion.full)
        // "org.scala-lang.modules" %% "scala-collection-compat" % "2.4.3"
      )
  }
}
