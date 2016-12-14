import com.banno._

name := "samza-mesos"

BannoSettings.settings

Samza.core

libraryDependencies ++= {
  val mesosVersion = "0.21.0"
  Seq(
    "org.apache.mesos" % "mesos" % mesosVersion,
    "org.scalatest" %% "scalatest" % "2.2.2" % "test"
  )
}
