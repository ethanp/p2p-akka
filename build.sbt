name := "p2p-akka"

version := "1.0"

scalaVersion := "2.11.5"

val akkaVersion = "2.3.9"

libraryDependencies ++= Seq(
    "org.scala-lang" % "scala-reflect" % scalaVersion.value,
    "com.typesafe.akka" %% "akka-remote" % akkaVersion,
    "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
    "com.typesafe.akka" %% "akka-contrib" % akkaVersion,
    "com.typesafe.akka" %% "akka-testkit" % akkaVersion % "test",
    "org.scalatest" %% "scalatest" % "3.0.0-SNAP4" % "test"
)

parallelExecution in Test := false

//logLevel in compile := Level.Error

//TaskKey[Unit]("execClient") := (runMain in Compile).toTask(" ethanp.cluster.Client").value

//TaskKey[Unit]("execServer") := (runMain in Compile).toTask(" ethanp.cluster.Server").value
