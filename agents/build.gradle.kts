plugins {
    kotlin("multiplatform") version "1.9.23"
    alias(libs.plugins.kotlin.plugin.serialization)
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
                implementation(libs.kotlinx.serialization.json)
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
