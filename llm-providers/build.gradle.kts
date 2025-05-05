plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.plugin.serialization)
}

kotlin {
    // Define the targets matching :core
    jvm()
    linuxX64()
    mingwX64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(kotlin("stdlib"))
                api(project(":core")) // Providers depend on core (LLMMessage, etc.)

                // Ktor client dependencies needed for API calls
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)
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
                 implementation(libs.ktor.client.cio)
             }
        }
        val jvmTest by getting {
             dependencies {
             }
        }

        val linuxX64Main by getting { 
            dependsOn(commonMain)
            dependencies {
                // Ktor engine for Native (Curl)
                implementation("io.ktor:ktor-client-curl:2.3.9") // Not in TOML, keep as-is
            }
        }
        val mingwX64Main by getting { 
            dependsOn(commonMain)
            dependencies {
                // Ktor engine for Native (Curl)
                implementation("io.ktor:ktor-client-curl:2.3.9") // Not in TOML, keep as-is
            }
        }
    }
}