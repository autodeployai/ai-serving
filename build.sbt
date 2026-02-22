name := (sys.props.getOrElse("gpu", "false") match {
  case "true" | "1" => "ai-serving-cuda"
  case _            => "ai-serving"
})

version := "2.2.0"

organization := "com.autodeployai"

organizationHomepage := Some(new URL("https://github.com/autodeployai"))

description := "Serving AI/ML models in the open standard formats PMML and ONNX with both HTTP (REST API) and gRPC endpoints"

homepage := Some(new URL("https://github.com/autodeployai/ai-serving"))

startYear := Some(2019)

licenses := Seq("Apache 2" -> new URL("http://www.apache.org/licenses/LICENSE-2.0.txt"))

scalaVersion := "2.13.17"

scalacOptions := Seq("-feature", "-language:_", "-unchecked", "-deprecation", "-encoding", "utf8")

scalacOptions in(Compile, doc) := Seq("-no-link-warnings")

val akkaVersion = "2.7.0"
val akkaHttpVersion = "10.5.3"
val pmml4sVersion = "1.5.8"
val onnxruntimeVersion = "1.22.0"

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
    "ch.qos.logback" % "logback-classic" % "1.5.13",
    "io.grpc" % "grpc-netty" % scalapb.compiler.Version.grpcJavaVersion,
    "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % scalapb.compiler.Version.scalapbVersion,
    "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion % "protobuf",
    "org.scalatest" %% "scalatest" % "3.0.8" % "test",
    "com.typesafe.akka" %% "akka-stream-testkit" % akkaVersion % "test",
    "com.typesafe.akka" %% "akka-http-testkit" % akkaHttpVersion % "test",
    "io.grpc" % "grpc-testing" % "1.28.0" % "test"
  )
}

packageOptions := Seq(
  Package.ManifestAttributes(("pmml4s-Version", pmml4sVersion)),
  Package.ManifestAttributes(("onnxruntime-Version", onnxruntimeVersion)),
)

PB.targets in Compile := Seq(
  scalapb.gen(flatPackage = true) -> (sourceManaged in Compile).value
)

parallelExecution in Test := false

assemblyMergeStrategy in assembly := {
  case "META-INF/io.netty.versions.properties"  => MergeStrategy.last
  case "META-INF/versions/9/module-info.class"  => MergeStrategy.concat
  case PathList("module-info.class")            => MergeStrategy.discard
  case PathList("google", "protobuf", xs @ _*)  => MergeStrategy.last
  case x                                        =>
    val oldStrategy = (assemblyMergeStrategy in assembly).value
    oldStrategy(x)
}

Test / fork := true
javaOptions in Test ++= Seq("--add-exports", "java.base/jdk.internal.math=ALL-UNNAMED")
