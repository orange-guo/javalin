/*
 * Javalin - https://javalin.io
 * Copyright 2017 David Åse
 * Licensed under Apache 2.0: https://github.com/tipsy/javalin/blob/master/LICENSE
 */

package io.javalin.core.util

import io.javalin.http.InternalServerErrorResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.*
import java.util.zip.Adler32
import java.util.zip.CheckedInputStream

object Util {

    @JvmStatic
    fun normalizeContextPath(contextPath: String) = ("/$contextPath").replace("/{2,}".toRegex(), "/").removeSuffix("/")

    @JvmStatic
    fun prefixContextPath(contextPath: String, path: String) = if (path == "*") path else ("$contextPath/$path").replace("/{2,}".toRegex(), "/")

    fun classExists(className: String) = try {
        Class.forName(className)
        true
    } catch (e: ClassNotFoundException) {
        false
    }

    private fun serviceImplementationExists(className: String) = try {
        val serviceClass = Class.forName(className)
        val loader = ServiceLoader.load(serviceClass)
        loader.any()
    } catch (e: ClassNotFoundException) {
        false
    }

    fun dependencyIsPresent(dependency: OptionalDependency) = try {
        ensureDependencyPresent(dependency)
        true
    } catch (e: Exception) {
        false
    }

    private val dependencyCheckCache = HashMap<String, Boolean>()

    fun ensureDependencyPresent(dependency: OptionalDependency, startupCheck: Boolean = false) {
        if (dependencyCheckCache[dependency.testClass] == true) {
            return
        }
        if (!classExists(dependency.testClass)) {
            val message = missingDependencyMessage(dependency)
            if (startupCheck) {
                throw IllegalStateException(message)
            } else {
                JavalinLogger.warn(message)
                throw InternalServerErrorResponse(message)
            }
        }
        dependencyCheckCache[dependency.testClass] = true
    }

    internal fun missingDependencyMessage(dependency: OptionalDependency) = """|
            |-------------------------------------------------------------------
            |Missing dependency '${dependency.displayName}'. Add the dependency.
            |
            |pom.xml:
            |<dependency>
            |    <groupId>${dependency.groupId}</groupId>
            |    <artifactId>${dependency.artifactId}</artifactId>
            |    <version>${dependency.version}</version>
            |</dependency>
            |
            |build.gradle:
            |implementation group: '${dependency.groupId}', name: '${dependency.artifactId}', version: '${dependency.version}'
            |
            |Find the latest version here:
            |https://search.maven.org/search?q=${URLEncoder.encode("g:" + dependency.groupId + " AND a:" + dependency.artifactId, "UTF-8")}
            |-------------------------------------------------------------------""".trimMargin()

    @JvmStatic
    fun printHelpfulMessageIfLoggerIsMissing() {
        if (!loggingLibraryExists()) {
            System.err.println("""
            |-------------------------------------------------------------------
            |${missingDependencyMessage(OptionalDependency.SLF4JSIMPLE)}
            |-------------------------------------------------------------------
            |OR
            |-------------------------------------------------------------------
            |${missingDependencyMessage(OptionalDependency.SLF4J_PROVIDER_API)} and
            |${missingDependencyMessage(OptionalDependency.SLF4J_PROVIDER_SIMPLE)}
            |-------------------------------------------------------------------
            |Visit https://javalin.io/documentation#logging if you need more help""".trimMargin())
        }
    }

    fun loggingLibraryExists(): Boolean {
        return classExists(OptionalDependency.SLF4JSIMPLE.testClass) ||
                serviceImplementationExists(OptionalDependency.SLF4J_PROVIDER_API.testClass)
    }

    @JvmStatic
    fun logJavalinBanner(showBanner: Boolean) {
        if (showBanner) JavalinLogger.info("\n" + """
          |       __                      __ _           ______
          |      / /____ _ _   __ ____ _ / /(_)____     / ____/
          | __  / // __ `/| | / // __ `// // // __ \   /___ \
          |/ /_/ // /_/ / | |/ // /_/ // // // / / /  ____/ /
          |\____/ \__,_/  |___/ \__,_//_//_//_/ /_/  /_____/
          |
          |          https://javalin.io/documentation
          |""".trimMargin())
    }

    @JvmStatic
    fun logJavalinVersion() = try {
        val properties = Properties().also {
            val propertiesPath = "META-INF/maven/io.javalin/javalin/pom.properties"
            it.load(this.javaClass.classLoader.getResourceAsStream(propertiesPath))
        }
        val (version, buildTime) = listOf(properties.getProperty("version")!!, properties.getProperty("buildTime")!!)
        JavalinLogger.startup("You are running Javalin $version (released ${formatBuildTime(buildTime)}).")
    } catch (e: Exception) {
        // it's not that important
    }

    private fun formatBuildTime(buildTime: String): String? = try {
        val (release, now) = listOf(Instant.parse(buildTime), Instant.now())
        val formatter = DateTimeFormatter.ofPattern("MMMM d, yyyy").withLocale(Locale.US).withZone(ZoneId.of("Z"))
        formatter.format(release) + if (now.isAfter(release.plus(90, ChronoUnit.DAYS))) {
            ". Your Javalin version is ${ChronoUnit.DAYS.between(release, now)} days old. Consider checking for a newer version."
        } else ""
    } catch (e: Exception) {
        null // it's not that important
    }

    fun getChecksumAndReset(inputStream: ByteArrayInputStream): String {
        inputStream.mark(Int.MAX_VALUE) //it's all in memory so there is no readAheadLimit
        val cis = CheckedInputStream(inputStream, Adler32())
        var byte = cis.read()
        while (byte > -1) {
            byte = cis.read()
        }
        inputStream.reset()
        return cis.checksum.value.toString()
    }

    @JvmStatic
    fun getResourceUrl(path: String): URL? = this.javaClass.classLoader.getResource(path)

    fun getFileUrl(path: String): URL? = if (File(path).exists()) File(path).toURI().toURL() else null

    fun isKotlinClass(clazz: Class<*>): Boolean {
        try {
            for (annotation in clazz.declaredAnnotations) {
                // Note: annotation.simpleClass can be used if kotlin-reflect is available.
                if (annotation.annotationClass.toString().contains("kotlin.Metadata")) {
                    return true
                }
            }
        } catch (ignored: Exception) {
        }
        return false
    }

    @JvmStatic
    fun getPort(e: Exception) = e.message!!.takeLastWhile { it != ':' }

    fun <T : Any?> findByClass(map: Map<Class<out Exception>, T>, exceptionClass: Class<out Exception>): T? = map.getOrElse(exceptionClass) {
        var superclass = exceptionClass.superclass
        while (superclass != null) {
            if (map.containsKey(superclass)) {
                return map[superclass]
            }
            superclass = superclass.superclass
        }
        return null
    }

}
