addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "0.15.0")

addSbtPlugin("com.eed3si9n" % "sbt-unidoc" % "0.4.2")

addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.4.0")

addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.10.0")

addSbtPlugin("com.thesamet" % "sbt-protoc" % "1.0.0-RC4")
libraryDependencies += "com.thesamet.scalapb" %% "compilerplugin" % "0.10.9"

libraryDependencies += "org.scala-sbt" %% "scripted-plugin" % sbtVersion.value

addSbtPlugin("com.geirsson" % "sbt-ci-release" % "1.5.4")

addSbtPlugin("com.typesafe" % "sbt-mima-plugin" % "0.6.1")

addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject" % "1.0.0")

addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.3.1")

addSbtPlugin("org.scala-debugger" % "sbt-jdi-tools" % "1.1.1")

addSbtPlugin("org.scalameta" % "sbt-munit" % "0.7.18")

addSbtPlugin("org.scalameta" % "sbt-mdoc" % "2.2.12")
