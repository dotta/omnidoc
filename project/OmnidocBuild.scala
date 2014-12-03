import sbt._
import sbt.Artifact.SourceClassifier
import sbt.Keys._

object OmnidocBuild extends Build {

  val playVersion = "2.4.0-M2"

  val playOrganisation = "com.typesafe.play"

  // these dependencies pull in all the others
  val playProjects = Seq(
    "anorm",
    "play-cache",
    "play-integration-test",
    "play-java-jpa"
  )

  val excludeArtifacts = Seq(
    "build-link",
    "play-exceptions",
    "play-netty-utils"
  )

  val externalModules = Seq(
    playOrganisation %% "play-slick" % "0.9.0-M3",
    playOrganisation %% "play-ebean" % "1.0.0-M1"
  )

  val nameFilter = excludeArtifacts.foldLeft(AllPassFilter: NameFilter)(_ - _)
  val playModuleFilter = moduleFilter(organization = playOrganisation, name = nameFilter)

  val Omnidoc = config("omnidoc").hide

  val PlaydocClassifier = "playdoc"

  val extractedSources = TaskKey[Seq[Extracted]]("extractedSources")
  val sourceUrls       = TaskKey[Map[String, String]]("sourceUrls")
  val javadoc          = TaskKey[File]("javadoc")
  val scaladoc         = TaskKey[File]("scaladoc")
  val playdoc          = TaskKey[File]("playdoc")

  lazy val omnidoc = project
    .in(file("."))
    .settings(omnidocSettings: _*)

  def omnidocSettings: Seq[Setting[_]] =
    projectSettings ++
    publishSettings ++
    localIvySettings ++
    inConfig(Omnidoc) {
      updateSettings ++
      extractSettings ++
      scaladocSettings ++
      javadocSettings ++
      packageSettings
    }

  def projectSettings: Seq[Setting[_]] = Seq(
                   name :=  "play-omnidoc",
           organization :=  playOrganisation,
                version :=  playVersion,
           scalaVersion :=  "2.10.4",
     crossScalaVersions :=  Seq("2.10.4", "2.11.2"),
              resolvers +=  Resolver.typesafeRepo("releases"),
      ivyConfigurations +=  Omnidoc,
    libraryDependencies ++= playProjects map (playOrganisation %% _ % playVersion % Omnidoc.name),
    libraryDependencies ++= externalModules map (_ % Omnidoc.name),
    libraryDependencies +=  playOrganisation %% "play-docs" % playVersion,
             initialize :=  { PomParser.registerParser }
  )

  def publishSettings: Seq[Setting[_]] = Seq(
    publishTo := {
      if (isSnapshot.value) Some(Opts.resolver.sonatypeSnapshots)
      else Some(Opts.resolver.sonatypeStaging)
    },
    homepage := Some(url("https://github.com/playframework/omnidoc")),
    licenses := Seq("Apache 2" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
    pomExtra := {
      <scm>
        <url>https://github.com/playframework/omnidoc</url>
        <connection>scm:git:git@github.com:playframework/omnidoc.git</connection>
      </scm>
      <developers>
        <developer>
          <id>playframework</id>
          <name>Play Framework Team</name>
          <url>https://github.com/playframework</url>
        </developer>
      </developers>
    },
    pomIncludeRepository := { _ => false }
  )

  // use a project-local ivy cache so that custom pom parsing is always applied on update
  def localIvySettings: Seq[Setting[_]] = Seq(
    ivyPaths := new IvyPaths(baseDirectory.value, Some(target.value / "ivy")),
    resolvers += Resolver.file("ivy-local", appConfiguration.value.provider.scalaProvider.launcher.ivyHome / "local")(Resolver.ivyStylePatterns),
    publishLocalConfiguration ~= { c => new PublishConfiguration(c.ivyFile, resolverName = "ivy-local", c.artifacts, c.checksums, c.logging, c.overwrite) },
    resolvers += "scalaz-releases" at "http://dl.bintray.com/scalaz/releases" // specs2 depends on scalaz-stream
  )

  def updateSettings: Seq[Setting[_]] = Seq(
    transitiveClassifiers := Seq(SourceClassifier, PlaydocClassifier),
        updateClassifiers := updateClassifiersTask.value
  )

  def extractSettings: Seq[Setting[_]] = Seq(
                 target := crossTarget.value / "omnidoc",
      target in sources := target.value / "sources",
       extractedSources := extractSources.value,
                sources := extractedSources.value.map(_.dir),
             sourceUrls := getSourceUrls(extractedSources.value),
    dependencyClasspath := Classpaths.managedJars(configuration.value, classpathTypes.value, update.value),
      target in playdoc := target.value / "playdoc",
                playdoc := extractPlaydocs.value
  )

  def scaladocSettings: Seq[Setting[_]] = Defaults.docTaskSettings(scaladoc) ++ Seq(
          sources in scaladoc := (sources.value ** "*.scala").get,
           target in scaladoc := target.value / "scaladoc",
    scalacOptions in scaladoc := scaladocOptions.value,
                     scaladoc := rewriteSourceUrls(scaladoc.value, sourceUrls.value, "/src/main/scala", ".scala")
  )

  def javadocSettings: Seq[Setting[_]] = Defaults.docTaskSettings(javadoc) ++ Seq(
         sources in javadoc := (sources.value ** "*.java").get,
          target in javadoc := target.value / "javadoc",
    javacOptions in javadoc := javadocOptions.value
  )

  def packageSettings: Seq[Setting[_]] = Seq(
    mappings in (Compile, packageBin) ++= {
      def mapped(dir: File, path: String) = dir.*** pair rebase(dir, path)
      mapped(playdoc.value,  "play/docs/content") ++
      mapped(scaladoc.value, "play/docs/content/api/scala") ++
      mapped(javadoc.value,  "play/docs/content/api/java")
    }
  )

  /**
   * Custom update classifiers task that only resolves classifiers for Play modules.
   * Also redirects warnings to debug for any artifacts that can't be found.
   */
  def updateClassifiersTask = Def.task {
    val playModules       = update.value.configuration(Omnidoc.name).toSeq.flatMap(_.allModules.filter(playModuleFilter))
    val classifiersModule = GetClassifiersModule(projectID.value, playModules, Seq(Omnidoc), transitiveClassifiers.value)
    val classifiersConfig = GetClassifiersConfiguration(classifiersModule, Map.empty, updateConfiguration.value, ivyScala.value)
    IvyActions.updateClassifiers(ivySbt.value, classifiersConfig, quietLogger(streams.value.log))
  }

  /**
   * Redirect logging above a certain level to debug.
   */
  def quietLogger(underlying: Logger, minimumLevel: Level.Value = Level.Info): Logger = new Logger {
    def log(level: Level.Value, message: => String): Unit = {
      if (level.id > minimumLevel.id) underlying.log(Level.Debug, s"[$level] $message")
      else underlying.log(level, message)
    }
    def success(message: => String): Unit = underlying.success(message)
    def trace(t: => Throwable): Unit = underlying.trace(t)
    override def ansiCodesSupported: Boolean = underlying.ansiCodesSupported
  }

  def extractSources = Def.task {
    val log          = streams.value.log
    val targetDir    = (target in sources).value
    val dependencies = (updateClassifiers.value filter artifactFilter(classifier = SourceClassifier)).toSeq
    log.info("Extracting sources...")
    IO.delete(targetDir)
    dependencies map { case (conf, module, artifact, file) =>
      val name = s"${module.organization}-${module.name}-${module.revision}"
      val dir = targetDir / name
      log.debug(s"Extracting $name")
      IO.unzip(file, dir, -"META-INF*")
      val sourceUrl = module.extraAttributes.get(SourceUrlKey)
      if (sourceUrl.isEmpty) log.warn(s"Source url not found for ${module.name}")
      Extracted(dir, sourceUrl)
    }
  }

  def extractPlaydocs = Def.task {
    val log          = streams.value.log
    val targetDir    = (target in playdoc).value
    val dependencies = updateClassifiers.value matching artifactFilter(classifier = PlaydocClassifier)
    log.info("Extracting playdocs...")
    IO.delete(targetDir)
    dependencies foreach { case file =>
      log.debug(s"Extracting $file")
      IO.unzip(file, targetDir, -"META-INF*")
    }
    targetDir
  }

  def scaladocOptions = Def.task {
    val sourcepath   = (target in sources).value.getAbsolutePath
    val docSourceUrl = sourceUrlMarker("€{FILE_PATH}")
    Seq(
      "-sourcepath", sourcepath,
      "-doc-source-url", docSourceUrl
    )
  }

  def javadocOptions = Def.task {
    val label = "Play " + version.value
    Seq(
      "-windowtitle", label,
      "-notimestamp",
      "-subpackages", "play",
      "-exclude", "play.api:play.core"
    )
  }

  // Source linking

  case class Extracted(dir: File, url: Option[String])

  val SourceUrlKey = "info.sourceUrl"

  val NoSourceUrl = "javascript:;"

  // first part of path is the extracted directory name, which is used as the source url mapping key
  val SourceUrlRegex = sourceUrlMarker("/([^/\\s]*)(/\\S*)").r

  def sourceUrlMarker(path: String): String = s"http://%SOURCE;${path}%"

  def getSourceUrls(extracted: Seq[Extracted]): Map[String, String] = {
    (extracted flatMap { source => source.url map source.dir.name.-> }).toMap
  }

  def rewriteSourceUrls(baseDir: File, sourceUrls: Map[String, String], prefix: String, suffix: String): File = {
    val files = baseDir.***.filter(!_.isDirectory).get
    files foreach { file =>
      val contents = IO.read(file)
      val newContents = SourceUrlRegex.replaceAllIn(contents, matched => {
        val key  = matched.group(1)
        val path = matched.group(2)
        sourceUrls.get(key).fold(NoSourceUrl)(_ + prefix + path + suffix)
      })
      if (newContents != contents) {
        IO.write(file, newContents)
      }
    }
    baseDir
  }

  object PomParser {

    import org.apache.ivy.core.module.descriptor.ModuleDescriptor
    import org.apache.ivy.plugins.parser.{ ModuleDescriptorParser, ModuleDescriptorParserRegistry }
    import org.apache.ivy.plugins.parser.m2.PomModuleDescriptorBuilder

    val extraKeys = Set(SourceUrlKey)

    val extraParser = new CustomPomParser(CustomPomParser.default, addExtra)

    def registerParser = ModuleDescriptorParserRegistry.getInstance.addParser(extraParser)

    def addExtra(parser: ModuleDescriptorParser, descriptor: ModuleDescriptor): ModuleDescriptor = {
      val properties = getExtraProperties(descriptor, extraKeys)
      CustomPomParser.addExtra(properties, Map.empty, parser, descriptor)
    }

    def getExtraProperties(descriptor: ModuleDescriptor, keys: Set[String]): Map[String, String] = {
      import scala.collection.JavaConverters._
      PomModuleDescriptorBuilder.extractPomProperties(descriptor.getExtraInfo)
        .asInstanceOf[java.util.Map[String, String]].asScala.toMap
        .filterKeys(keys)
        .map { case (k, v) => ("e:" + k, v) }
    }

  }

}
