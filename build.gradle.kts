plugins {
    kotlin("jvm") version "2.1.10"
    alias(libs.plugins.kotlin.plugin.serialization)
}

group = "dev.gabrielolv"
version = "0.2.1"

repositories {
    mavenCentral()
}

dependencies {
    // Arrow dependencies from version catalog
    implementation(libs.arrow.core)
    implementation(libs.arrow.fx.coroutines)


    implementation("com.github.f4b6a3:ulid-creator:5.2.3")
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.cio.jvm)

    // Test dependencies
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(19)
}