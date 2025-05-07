plugins {

    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.plugin.serialization)
}

kotlin {
    jvm()
    linuxX64()
    mingwX64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(kotlin("stdlib"))

                api(libs.ktor.client.cio)
                api(libs.ktor.serialization.kotlinx.json)
                api(libs.ktor.client.content.negotiation)
                implementation("org.jetbrains.kotlin:kotlin-reflect:${libs.versions.kotlin.get()}")
                api(libs.kotlinx.coroutines.core)
                api(libs.kotlinx.serialization.json)
                api("io.ktor:ktor-utils:2.3.9")
                api(libs.ulid.kotlin)
                api(libs.ktor.client.core)
                api("app.cash.sqldelight:runtime:2.0.1")
                api("app.cash.sqldelight:coroutines-extensions:2.0.1")
                api(libs.arrow.core)
                api(libs.arrow.fx.coroutines)
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
                implementation("app.cash.sqldelight:native-driver:2.0.2")
                implementation("io.ktor:ktor-client-curl:2.3.9")
            }
        }
        val mingwX64Main by getting { 
            dependsOn(commonMain)
            dependencies {
                implementation("app.cash.sqldelight:native-driver:2.0.2")
                implementation("io.ktor:ktor-client-curl:2.3.9")
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
