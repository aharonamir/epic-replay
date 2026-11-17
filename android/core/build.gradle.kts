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
    // The tests read both trees, so a change in either must run them (and must not be served from the cache).
    inputs.dir(rootProject.layout.projectDirectory.dir("../shared")).withPropertyName("sharedData")
    inputs.files(
        fileTree(rootProject.layout.projectDirectory) {
            exclude("**/build/**", "**/.gradle/**", "**/.kotlin/**", "**/.idea/**", "**/.cxx/**")
        }
    ).withPropertyName("androidTree")
}
