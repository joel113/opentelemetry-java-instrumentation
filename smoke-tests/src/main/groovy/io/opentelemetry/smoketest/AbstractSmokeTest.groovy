package io.opentelemetry.smoketest


import spock.lang.Shared
import spock.lang.Specification

abstract class AbstractSmokeTest extends Specification {

  @Shared
  protected String workingDirectory = System.getProperty("user.dir")
  @Shared
  protected String buildDirectory = System.getProperty("io.opentelemetry.smoketest.builddir")
  @Shared
  protected String shadowJarPath = System.getProperty("io.opentelemetry.smoketest.agent.shadowJar.path")
  @Shared
  protected String[] defaultJavaProperties
  @Shared
  protected Process serverProcess
  @Shared
  protected String exporterPath = System.getProperty("ota.exporter.jar")

  @Shared
  protected File logfile

  def countSpans(prefix) {
    return logfile.text.tokenize('\n').count {
      it.startsWith prefix
    }
  }

  def setupSpec() {
    if (buildDirectory == null || shadowJarPath == null) {
      throw new AssertionError("Expected system properties not found. Smoke tests have to be run from Gradle. Please make sure that is the case.")
    }

    defaultJavaProperties = [
      "-javaagent:${shadowJarPath}",
      "-Dio.opentelemetry.auto.slf4j.simpleLogger.defaultLogLevel=debug",
      "-Dorg.slf4j.simpleLogger.defaultLogLevel=debug"
    ]

    ProcessBuilder processBuilder = createProcessBuilder()

    processBuilder.environment().put("JAVA_HOME", System.getProperty("java.home"))

    processBuilder.redirectErrorStream(true)
    logfile = new File("${buildDirectory}/reports/testProcess.${this.getClass().getName()}.log")
    processBuilder.redirectOutput(ProcessBuilder.Redirect.to(logfile))

    serverProcess = processBuilder.start()


  }

  String javaPath() {
    final String separator = System.getProperty("file.separator")
    return System.getProperty("java.home") + separator + "bin" + separator + "java"
  }

  def cleanupSpec() {
    serverProcess?.waitForOrKill(1)
  }

  abstract ProcessBuilder createProcessBuilder()
}