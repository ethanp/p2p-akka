name := "p2p-akka-cluster"

version := "1.0"

scalaVersion := "2.11.6"

val akkaVersion = "2.3.11"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-remote" % akkaVersion,
  "com.typesafe.akka" %% "akka-cluster" % akkaVersion,
  "com.typesafe.akka" %% "akka-testkit" % akkaVersion % "test",
  "org.scalatest" %% "scalatest" % "2.2.2" % "test"
)

logLevel in compile := Level.Error

TaskKey[Unit]("execClient") := (runMain in Compile).toTask(" ethanp.cluster.Client").value

TaskKey[Unit]("execServer") := (runMain in Compile).toTask(" ethanp.cluster.Server").value
