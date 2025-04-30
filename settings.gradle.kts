dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        // Repository for Kotlin/Native prebuilt distributions - REMOVED
        // maven { url = uri("https://maven.pkg.jetbrains.space/public/p/kotlin/native/dev/") }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}
rootProject.name = "KAIAgents"

include(":core", ":agents", ":tools", ":llm-providers")
