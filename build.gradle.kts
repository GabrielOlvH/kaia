plugins {
    `maven-publish`
}

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

val libVersion: String by project

group = "dev.gabrielolv"
version = if (branch == "main") libVersion else "${libVersion}-${branch}"

subprojects {
    apply(plugin = "maven-publish")
    afterEvaluate {
        publishing {
            repositories {
                mavenLocal()
            }
        }
    }
}
