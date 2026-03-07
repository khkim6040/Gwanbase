plugins {
    kotlin("jvm") version "1.9.22"
}

allprojects {
    group = "dev.gwanbase"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    dependencies {
        // Kotlin
        implementation(kotlin("stdlib"))

        // Logging
        implementation("io.github.microutils:kotlin-logging-jvm:3.0.5")
        implementation("ch.qos.logback:logback-classic:1.4.14")

        // Test
        testImplementation(kotlin("test"))
        testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
        testImplementation("io.kotest:kotest-assertions-core:5.8.0")
        testImplementation("io.kotest:kotest-property:5.8.0")
    }

    tasks.test {
        useJUnitPlatform()
        jvmArgs("-Xmx512m")
    }

    kotlin {
        jvmToolchain(17)
    }
}
