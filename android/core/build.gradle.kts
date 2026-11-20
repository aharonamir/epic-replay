// Pure Kotlin, no Android classes: the stage ports, guards and model contract live here so the
// conformance cases run in milliseconds on the JVM (plan section 2). Tests read ../shared/.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// Bytecode for Java 17, like the Android modules, built with whatever JDK 17 or newer runs Gradle.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    api(libs.serialization.json)
    implementation(libs.json.schema)
    api(libs.coroutines.core)
    testImplementation(libs.kotlin.test)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // Shared data is read where it lives, never copied (constitution XI, root CLAUDE.md rule 11).
    systemProperty("shared.dir", rootProject.layout.projectDirectory.dir("../shared").asFile.absolutePath)
    systemProperty("android.dir", rootProject.layout.projectDirectory.asFile.absolutePath)
    // -Dnetwork.test=true runs the tests that download a real file (RealDownloadTest); off by default.
    systemProperty("network.test", System.getProperty("network.test") ?: "")
    systemProperty("measure.cloud", System.getProperty("measure.cloud") ?: "") // the paid measurement of T419 (CommentatorMeasureTest)
    // The tests read both trees, so a change in either must run them (and must not be served from the cache).
    inputs.dir(rootProject.layout.projectDirectory.dir("../shared")).withPropertyName("sharedData")
    // JUnit silently skips a test method that returns a value (`= runBlocking { ... assertFailsWith ... }`): the
    // number of tests that ran must equal the number of @Test methods in the sources.
    doLast {
        val filtered = filter.includePatterns.isNotEmpty() || (filter as org.gradle.api.internal.tasks.testing.filter.DefaultTestFilter).commandLineIncludePatterns.isNotEmpty()
        if (!filtered) {
            val annotated = fileTree("src/test").matching { include("**/*.kt") }.files
                .sumOf { Regex("""(?m)^\s*@Test\b""").findAll(it.readText()).count() }
            val ran = fileTree(layout.buildDirectory.dir("test-results/test")).matching { include("*.xml") }.files
                .sumOf { Regex("""<testsuite [^>]*tests="(\d+)"""").find(it.readText())?.groupValues?.get(1)?.toInt() ?: 0 }
            if (annotated != ran) {
                throw GradleException("$annotated @Test methods in core/src/test but $ran tests ran: a test that returns a value is skipped by JUnit; end it with Unit")
            }
        }
    }
    inputs.files(
        fileTree(rootProject.layout.projectDirectory) {
            exclude("**/build/**", "**/.gradle/**", "**/.kotlin/**", "**/.idea/**", "**/.cxx/**")
        }
    ).withPropertyName("androidTree")
}
