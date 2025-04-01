plugins {
    kotlin("jvm") version libs.versions.kotlin.get()
    kotlin("plugin.serialization") version libs.versions.kotlin.get()
}

group = "dev.gabrielolv"
version = "1.0.1"

repositories {
    mavenCentral()
}

dependencies {
    // Arrow dependencies from version catalog
    implementation(libs.arrow.core)
    implementation(libs.arrow.fx.coroutines)

    // Additional dependencies
    implementation(libs.ulid.creator)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.cio.jvm)
    implementation(libs.exposed.core)
    implementation(libs.exposed.dao)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.datetime)
    implementation("com.github.jsqlparser:jsqlparser:4.9")

    // Test dependencies
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    // https://mvnrepository.com/artifact/com.h2database/h2
    implementation("com.h2database:h2:2.3.232")
}
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(19)
}