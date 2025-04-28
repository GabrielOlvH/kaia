plugins {
    kotlin("multiplatform") version libs.versions.kotlin.get()
    kotlin("plugin.serialization") version libs.versions.kotlin.get()
    `maven-publish`
    id("app.cash.sqldelight") version "2.0.1"
}

fun getBranchName(): String {
    val branchNameEnv = System.getenv("BRANCH_NAME")
    if (!branchNameEnv.isNullOrEmpty()) {
        return branchNameEnv
    }

    return try {
        val process = ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD")
            .redirectErrorStream(true)
            .start()

        process.inputStream.bufferedReader().use { it.readText() }.trim()
            .also { process.waitFor() }
            .replace("/", "-")
    } catch (e: Exception) {
        "unknown"
    }
}
val branch = getBranchName()

val libVersion: String by project

group = "dev.gabrielolv"
version = if (branch == "main") libVersion else "${libVersion}-${branch}"

repositories {
    mavenCentral()
}

kotlin {
    // Define the targets you want to support
    jvm() // Configures JVM source sets (jvmMain, jvmTest)

    // Define desktop targets and group them
    // Use presets if you prefer, but explicit definition is clear
    linuxX64()
    macosX64()
    mingwX64() // Windows

    sourceSets {

        val commonMain by getting {
            dependencies {
                // Dependencies that work on all platforms go here
                implementation(libs.arrow.core)
                implementation(libs.arrow.fx.coroutines)

                // Check if libs.ulid.creator supports multiplatform or find an alternative
                implementation(libs.ulid.kotlin)

                // kotlinx.coroutines and serialization core are multiplatform
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)

                // Ktor client core is multiplatform
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)

                // SQLDelight runtime for multiplatform
                implementation("app.cash.sqldelight:runtime:2.0.1")
                // Coroutines extensions might be multiplatform or need platform specifics
                implementation("app.cash.sqldelight:coroutines-extensions:2.0.1")

            }
        }

        val commonTest by getting {
            dependencies {

            }
        }

        val jvmMain by getting {
            dependencies {
                // JVM-specific dependencies
                implementation("app.cash.sqldelight:sqlite-driver:2.0.1") // JVM SQLite driver
                implementation(libs.ktor.client.cio) // JVM Ktor engine
            }
        }

        val jvmTest by getting {
            dependencies {
                // JVM-specific test dependencies if any
                // Add the JUnit 5 runner for JVM tests
                implementation(libs.kotest.runner.junit5)
            }
        }

        val desktopMain by creating {
            dependsOn(commonMain) // Inherit dependencies from commonMain
            dependencies {
                implementation("io.ktor:ktor-client-curl:3.1.1")

            }
        }

        val desktopTest by creating {
            dependencies {
                implementation("io.kotest:kotest-runner-native:5.9.1")
            }
        }
        linuxX64Main.get().dependsOn(desktopMain)
        mingwX64Main.get().dependsOn(desktopMain)
        macosX64Main.get().dependsOn(desktopMain)
    }
}

// Configure test tasks. JVM tests use JUnit Platform.
// The KMP plugin handles native test tasks.
tasks.withType<Test>().configureEach {
    // This configuration typically applies only to the JVM test task
    useJUnitPlatform()
}

// --- Publishing Configuration ---
publishing {
}

// Optional: Ensure publish task runs after build
tasks.withType<PublishToMavenRepository>().configureEach {
    dependsOn(tasks.build) // Consider if 'build' is appropriate for multiplatform publish
    // You might need to depend on specific tasks like `publishKotlinPublicationToSonatypeNexusRepository`
}
