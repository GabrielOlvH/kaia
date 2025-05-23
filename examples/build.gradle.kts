plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.plugin.serialization)
}

kotlin {
    // Define the targets matching :core
    jvm()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(kotlin("stdlib"))
                // Depend on other modules
                api(project(":core"))
                api(project(":llm-providers"))
                api(project(":agents"))
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


    }
}
