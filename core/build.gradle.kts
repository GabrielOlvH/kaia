plugins {
    kotlin("multiplatform") version "1.9.23"
    kotlin("plugin.serialization") version "1.9.23"
    // id("app.cash.sqldelight") version "2.0.1" // Commented out until DB is configured
}

kotlin {
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
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
                api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
                api("io.ktor:ktor-utils:2.3.9")
                api("com.aallam.ulid:ulid-kotlin:1.3.0") // Added for ulid.ULID
                api("io.ktor:ktor-client-core:2.3.9") // Added for expect HttpClient
                api("app.cash.sqldelight:runtime:2.0.1")
                api("app.cash.sqldelight:coroutines-extensions:2.0.1")
                api("io.arrow-kt:arrow-core:1.2.1")
                api("io.arrow-kt:arrow-fx-coroutines:1.2.1")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }

        val jvmMain by getting {
            dependencies {
                implementation("app.cash.sqldelight:sqlite-driver:2.0.1")
                implementation("io.ktor:ktor-client-cio:2.3.9") // Added for JVM actual HttpClient
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
                implementation("app.cash.sqldelight:native-driver:2.0.2")
                implementation("io.ktor:ktor-client-curl:2.3.9") // Added for Native actual HttpClient
            }
        }
        val mingwX64Main by getting { 
            dependsOn(commonMain)
            dependencies {
                implementation("app.cash.sqldelight:native-driver:2.0.2")
                implementation("io.ktor:ktor-client-curl:2.3.9") // Added for Native actual HttpClient
            }
        }
    }
}

// Commented out until .sq files are added
// sqldelight {
//    databases {
//        create("AppDatabase") {
//            packageName.set("dev.gabrielolv.kaia.database") // Adjust package name
//            // sourceFolders.set(listOf("src/commonMain/sqldelight")) // Uncomment and adjust if you have .sq files in core
//        }
//    }
// }
