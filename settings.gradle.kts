dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Add other repositories like your custom one if needed for *dependencies*
        // maven { url = uri("https://maven.gabrielolv.dev/repository/maven-releases/") }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}
rootProject.name = "KAIAgents"

include(":core", ":agents", ":tools", ":llm-providers")
