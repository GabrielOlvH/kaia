import org.jetbrains.dokka.gradle.DokkaTaskPartial // Needed for Javadoc generation

plugins {
    kotlin("jvm") version libs.versions.kotlin.get()
    kotlin("plugin.serialization") version libs.versions.kotlin.get()
    `maven-publish`
}

// --- Versioning Logic (Keep as is) ---
fun getBranchName(): String {
    val branchNameEnv = System.getenv("BRANCH_NAME")
    if (!branchNameEnv.isNullOrEmpty()) {
        return branchNameEnv
    }

    return try {
        val process = ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD")
            .redirectErrorStream(true)
            .start()

        process.inputStream.bufferedReader().use { it.readText() }.trim()
            .also { process.waitFor() }
            .replace("/", "-")
    } catch (e: Exception) {
        "unknown"
    }
}
val branch = getBranchName()

val libVersion: String by project // Assumes libVersion is defined in gradle.properties

group = "dev.gabrielolv" // Your chosen group ID
// Your versioning logic based on branch
version = if (branch == "main" || branch == "master") libVersion else "${libVersion}-${branch}-SNAPSHOT" // Added -SNAPSHOT for non-main branches

repositories {
    mavenCentral()
}

dependencies {
    // --- Your dependencies remain the same ---
    implementation(libs.arrow.core)
    implementation(libs.arrow.fx.coroutines)
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
    implementation("com.github.jsqlparser:jsqlparser:5.1")
    implementation("com.h2database:h2:2.3.232")

    // Test dependencies
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation("io.kotest:kotest-runner-junit5:5.9.1")
    testImplementation("io.kotest:kotest-assertions-core:5.8.0")
    testImplementation("io.kotest:kotest-property:5.9.1")
    testImplementation("io.ktor:ktor-client-mock:3.1.2")
    testImplementation("io.ktor:ktor-client-content-negotiation:3.1.1")
    testImplementation("io.ktor:ktor-serialization-kotlinx-json:3.1.1")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(19)
}

java {
    withSourcesJar()
}

// --- Publishing Configuration ---
publishing {
    publications {
        // Publication name can be anything (e.g., "gpr", "maven", "release")
        create<MavenPublication>("gpr") { // Changed name to "gpr" for clarity
            // Use the 'java' component (includes main artifact and dependencies)
            //from(components["java"])

            artifact(tasks.named("jar"))
            // Artifact ID defaults to project name ("kaia"). Override if needed:
            // artifactId = "kaia-library"

            // --- POM Metadata (Customize as needed) ---
            pom {
                // Removed the custom branchName property, less standard for POMs
                // properties.set(mapOf("branchName" to getBranchName()))
                name.set("KAIA") // Library name
                description.set("Kotlin Asynchronous Interaction Architecture Library") // Update description
                url.set("https://github.com/GabrielOlvH/kaia") // Project URL
                licenses {
                    license {
                        // Ensure this matches your actual license file
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                developers {
                    developer {
                        id.set("GabrielOlvH") // Corrected ID to match GitHub username
                        name.set("Gabriel Oliveira")
                        email.set("gabrielh.oliveira222@gmail.com") // Optional: Your email
                    }
                }
                scm {
                    // Corrected SCM URLs to use your username
                    connection.set("scm:git:git://github.com/GabrielOlvH/kaia.git")
                    developerConnection.set("scm:git:ssh://github.com/GabrielOlvH/kaia.git")
                    url.set("https://github.com/GabrielOlvH/kaia/tree/main") // Link to main branch
                }
            }
        }
    }
    repositories {
        // --- Configure GitHub Packages Repository ---
        maven {
            name = "GitHubPackages"
            // URL format: https://maven.pkg.github.com/OWNER/REPOSITORY
            url = uri("https://maven.pkg.github.com/GabrielOlvH/kaia")
            credentials {
                // Use environment variables for credentials
                // GITHUB_ACTOR = Your GitHub Username (GabrielOlvH)
                // GITHUB_TOKEN = Your Personal Access Token (PAT) with write:packages scope
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}