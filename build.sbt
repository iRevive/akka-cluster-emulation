
val akkaVersion = "2.4.17"
val scalaTestVersion = "3.0.1"

val dependencies = Seq(
  "com.typesafe.akka" %% "akka-actor"   % akkaVersion,
  "com.typesafe.akka" %% "akka-remote"  % akkaVersion,
  "com.typesafe.akka" %% "akka-slf4j"   % akkaVersion,

  "org.scalatest"     %% "scalatest"    % scalaTestVersion  % "test"
)

lazy val root = (project in file("."))
  .settings(
    name := "akka-cluster-emulation",
    version := "1.0",
    scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature", "-encoding", "UTF-8"),
    scalaVersion := "2.11.8",
    sources in(Compile, doc) := Seq.empty,
    publishArtifact in(Compile, packageDoc) := false,
    libraryDependencies ++= dependencies,
    mainClass in Compile := Some("AppBootstrap")
  )
  .enablePlugins(JavaAppPackaging)