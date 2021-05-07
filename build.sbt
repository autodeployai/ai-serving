name := "ai-serving"

version := "0.9.4"

organization := "ai.autodeploy"

organizationHomepage := Some(new URL("https://autodeploy.ai"))

description := "Serving AI/ML models in the open standard formats PMML and ONNX with both HTTP (REST API) and gRPC endpoints"

homepage := Some(new URL("https://github.com/autodeployai/ai-serving"))

startYear := Some(2019)

licenses := Seq("Apache 2" -> new URL("http://www.apache.org/licenses/LICENSE-2.0.txt"))

scalaVersion := "2.13.4"

scalacOptions := Seq("-feature", "-language:_", "-unchecked", "-deprecation", "-encoding", "utf8")

scalacOptions in(Compile, doc) := Seq("-no-link-warnings")

val akkaVersion = "2.6.4"
val akkaHttpVersion = "10.1.11"
val pmml4sVersion = "0.9.10"
val onnxruntimeVersion = "1.7.0"

libraryDependencies ++= {
  (sys.props.getOrElse("gpu", "false") match {
    case "true" | "1" => Some("com.microsoft.onnxruntime" % "onnxruntime_gpu" % onnxruntimeVersion)
    case _            => Some("com.microsoft.onnxruntime" % "onnxruntime" % onnxruntimeVersion)
  }).toSeq ++ Seq(
    "org.pmml4s" %% "pmml4s" % pmml4sVersion,
    "com.typesafe.akka" %% "akka-http" % akkaHttpVersion,
    "com.typesafe.akka" %% "akka-stream" % akkaVersion,
    "com.typesafe.akka" %% "akka-http-spray-json" % akkaHttpVersion,
    "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
    "ch.qos.logback" % "logback-classic" % "1.2.3",
    "io.grpc" % "grpc-netty" % scalapb.compiler.Version.grpcJavaVersion,
    "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % scalapb.compiler.Version.scalapbVersion,
    "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion % "protobuf",
    "org.scalatest" %% "scalatest" % "3.0.8" % "test",
    "com.typesafe.akka" %% "akka-stream-testkit" % akkaVersion % "test",
    "com.typesafe.akka" %% "akka-http-testkit" % akkaHttpVersion % "test",
    "io.grpc" % "grpc-testing" % "1.28.0" % "test"
  )
}


PB.targets in Compile := Seq(
  scalapb.gen(flatPackage = true) -> (sourceManaged in Compile).value
)

parallelExecution in Test := false

assemblyMergeStrategy in assembly := {
  case "META-INF/io.netty.versions.properties" => MergeStrategy.last
  case PathList("google", "protobuf", xs @ _*)   => MergeStrategy.last
  case x                                       =>
    val oldStrategy = (assemblyMergeStrategy in assembly).value
    oldStrategy(x)
}


