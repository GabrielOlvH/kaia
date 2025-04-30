plugins {
    kotlin("multiplatform") version "1.9.23"
    kotlin("plugin.serialization") version "1.9.23"
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
                api(project(":core")) // Tools depend on core

                // Ktor client if tools make direct HTTP calls
                // implementation(libs.ktor.client.core) // Provided transitively by :core?
                // implementation(libs.ktor.client.content.negotiation)
                // implementation(libs.ktor.serialization.kotlinx.json)

                // Other dependencies for specific tools?
                // e.g., database drivers if a tool interacts directly
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(project(":core")) // For testing tools
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
