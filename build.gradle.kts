// Notably — build script.
//
// The project intentionally depends on nothing beyond the Kotlin standard
// library and the JDK itself (java.security / javax.crypto are part of the
// JVM, not third-party artifacts). The custom `jar` task therefore produces
// a self-contained "fat" jar containing notably + kotlin-stdlib, which is
// the closest thing to a shadow jar without pulling in a plugin.

plugins {
    kotlin("jvm") version "1.9.24"
    application
}

group = "io.github.buibakhanh"
version = "1.0.0"

repositories {
    mavenCentral()
}

kotlin {
    // Keep the toolchain pinned so the build is reproducible.
    jvmToolchain(17)
}

application {
    mainClass.set("notably.MainKt")
    applicationName = "notably"
}

tasks.jar {
    archiveFileName.set("notably.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = "notably.MainKt"
        attributes["Implementation-Title"] = "notably"
        attributes["Implementation-Version"] = version
        attributes["Built-By"] = "Bui Bao Khanh"
    }
    // Bundle the runtime classpath (i.e. kotlin-stdlib) into the jar so the
    // artifact can be run with a plain `java -jar notably.jar`.
    val runtimeClasspath = configurations.runtimeClasspath.get()
    from(runtimeClasspath.map { dependency ->
        if (dependency.isDirectory) dependency else zipTree(dependency)
    }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/9/module-info.class")
    }
}

// The test module is a plain `main()`-based harness (see
// src/test/kotlin/notably/NotablyTest.kt); it does not use JUnit and the
// project declares no external dependencies. The stock `test` task therefore
// stays disabled and the `selftest` JavaExec task runs the harness directly
// on the test runtime classpath (`gradle selftest`, also wired into `check`).
tasks.test {
    enabled = false
}

tasks.register<JavaExec>("selftest") {
    group = "verification"
    description = "Runs the built-in main()-based test harness (no JUnit)."
    mainClass.set("notably.NotablyTestKt")
    classpath = sourceSets["test"].runtimeClasspath
}

tasks.named("check") { dependsOn("selftest") }
