plugins {
    kotlin("jvm") version libs.versions.kotlin.get()
    kotlin("plugin.serialization") version libs.versions.kotlin.get()
    `maven-publish`
}

val libVersion: String by project

group = "dev.gabrielolv"
version = libVersion

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
    implementation("com.h2database:h2:2.3.232")

    // Test dependencies
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation("io.kotest:kotest-runner-junit5:5.9.1")
    testImplementation("io.kotest:kotest-assertions-core:5.8.0")
    testImplementation("io.kotest:kotest-property:5.8.0")

    testImplementation("io.ktor:ktor-client-mock:3.1.1")

    testImplementation("io.ktor:ktor-client-content-negotiation:3.1.1")
    testImplementation("io.ktor:ktor-serialization-kotlinx-json:3.1.1")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(19)
}

// --- Publishing Configuration ---
publishing {
    publications {
        // Create a publication named 'maven'. You can name it anything.
        create<MavenPublication>("maven") {
            // Apply the component generated by the kotlin("jvm") plugin.
            from(components["java"])

            // Set the artifact details (group and version are inherited)
            // artifactId defaults to project.name (your directory name), override if needed
            // artifactId = "my-library-artifact"

            // --- POM Metadata (Customize this!) ---
            pom {
                name.set("KAIA")
                description.set("A concise description of your library.")
                url.set("https://github.com/GabrielOlvH/kaia")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("gabrielolvh")
                        name.set("Gabriel Oliveira")
                        email.set("gabrielh.oliveira222@gmail.com")
                    }
                }
                scm {
                    connection.set("scm:git:git://github.com/dev-gabrielolvh/kaia.git")
                    developerConnection.set("scm:git:ssh://github.com:gabrielolvh/kaia.git")
                    url.set("https://github.com/gabrielolvh/kaia/tree/main")
                }
            }
        }
    }
    repositories {
        // Configure Sonatype Nexus repository
        maven {
            name = "SonatypeNexus"
            
            // Dynamically determine repository URL based on version (SNAPSHOT vs Release)
            url = uri(
                if (version.toString().endsWith("SNAPSHOT")) {
                    "https://nexus3-production-b3d5.up.railway.app/repository/maven-snapshots/"
                } else {
                    "https://nexus3-production-b3d5.up.railway.app/repository/maven-releases/"
                }
            )
            
            credentials {
                // Use environment variables or gradle.properties for credentials
                username = System.getenv("NEXUS_USERNAME") ?: project.findProperty("nexus.username") as String?
                password = System.getenv("NEXUS_PASSWORD") ?: project.findProperty("nexus.password") as String?
            }
        }
    }
}

// Optional: Ensure publish task runs after build
tasks.withType<PublishToMavenRepository>().configureEach {
    dependsOn(tasks.build)
}
