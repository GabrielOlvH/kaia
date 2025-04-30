plugins {
    kotlin("multiplatform") version "1.9.23"
    kotlin("plugin.serialization") version "1.9.23"

    // id("app.cash.sqldelight") version "2.0.1" // Removed: No database defined in this module
}

kotlin {
    // Define the targets matching :core
    jvm {
        compilations.all {
            kotlinOptions.jvmTarget = "1.8"
        }
        testRuns["test"].executionTask.configure {
            useJUnitPlatform()
        }
    }
    linuxX64()
    mingwX64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(kotlin("stdlib"))
                // Depend on other modules
                api(project(":core"))
                api(project(":llm-providers"))
                api(project(":tools"))

                // Add serialization explicitly as we use it directly here
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

                // Add Ktor client if agents make direct HTTP calls (consider moving to :llm-providers)
                // implementation(libs.ktor.client.core) // Provided transitively by :core or :llm-providers?
                // implementation(libs.ktor.client.content.negotiation)
                // implementation(libs.ktor.serialization.kotlinx.json)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(project(":core"))
                implementation(project(":llm-providers")) // For testing interactions
                implementation(project(":tools"))         // For testing interactions
            }
        }

        // Target-specific source sets matching :core
        val jvmMain by getting {
             dependencies {
                 // implementation(libs.ktor.client.cio) // If direct JVM HTTP calls needed
             }
        }
        val jvmTest by getting {
             dependencies {
                 implementation("org.jetbrains.kotlin:kotlin-test-junit5:1.9.23")
             }
        }

        // Link specific native targets directly to commonMain
        val linuxX64Main by getting { 
            dependsOn(commonMain)
            dependencies {
                 // implementation("io.ktor:ktor-client-curl:2.3.9") // If direct native HTTP calls needed
            }
        }
        val mingwX64Main by getting { 
            dependsOn(commonMain)
            dependencies {
                 // implementation("io.ktor:ktor-client-curl:2.3.9") // If direct native HTTP calls needed
            }
        }
    }
}
