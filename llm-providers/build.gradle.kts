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
                api(project(":core")) // Providers depend on core (LLMMessage, etc.)

                // Ktor client dependencies needed for API calls
                // Use 'api' if the provider interfaces/classes expose Ktor types, 'implementation' otherwise
                implementation("io.ktor:ktor-client-core:2.3.9")
                implementation("io.ktor:ktor-client-content-negotiation:2.3.9")
                implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.9")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(project(":core")) // For testing providers
                // implementation(libs.ktor.client.mock) // Add mock engine for testing
            }
        }

        // Target-specific source sets matching :core
        val jvmMain by getting {
             dependencies {
                 // Ktor engine for JVM
                 implementation("io.ktor:ktor-client-cio:2.3.9")
             }
        }
        val jvmTest by getting {
             dependencies {
                 implementation("org.jetbrains.kotlin:kotlin-test-junit5:1.9.23")
             }
        }

        val linuxX64Main by getting { 
            dependsOn(commonMain)
            dependencies {
                // Ktor engine for Native (Curl)
                implementation("io.ktor:ktor-client-curl:2.3.9") // Moved from desktopMain
            }
        }
        val mingwX64Main by getting { 
            dependsOn(commonMain)
            dependencies {
                // Ktor engine for Native (Curl)
                implementation("io.ktor:ktor-client-curl:2.3.9") // Moved from desktopMain
            }
        }
    }
}