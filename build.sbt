name := "Avocado"

version := "0.0.1"

organization := "edu.berkeley.cs.amplab"

scalaVersion := "2.9.2"

libraryDependencies ++= Seq(
  "org.spark-project" % "spark-core_2.9.3" % "0.7.3",
  "org.streum" % "configrity-core_2.9.2" % "1.0.0",
  "edu.berkeley.cs.amplab.adam" % "adam-commands" % "0.5.0-SNAPSHOT",
  "org.scalatest" %% "scalatest" % "1.9.1" % "test"
)

resolvers ++= Seq(
  "Typesafe" at "http://repo.typesafe.com/typesafe/releases",
  "Scala Tools Snapshots" at "http://scala-tools.org/repo-snapshots/",
  "Local Maven Repository" at "file://"+Path.userHome.absolutePath+"/.m2/repository",
  "Spray" at "http://repo.spray.cc"
)
