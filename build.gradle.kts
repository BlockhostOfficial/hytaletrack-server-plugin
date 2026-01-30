plugins {
    base
    id("java")
    id("com.diffplug.spotless") version "8.2.1"
    id("net.ltgt.errorprone") version "4.4.0"
    id("com.github.spotbugs") version "6.4.8"
    id("org.openrewrite.rewrite") version "7.25.0"
}

tasks.named<UpdateDaemonJvm>("updateDaemonJvm") {
    languageVersion = JavaLanguageVersion.of(25)
}

spotless {
    java {
        trimTrailingWhitespace()
        leadingTabsToSpaces(2)
        endWithNewline()
    }
}

rewrite {
    activeRecipe("org.openrewrite.staticanalysis.CommonStaticAnalysis")
    activeRecipe("org.openrewrite.staticanalysis.CodeCleanup")
    activeRecipe("org.openrewrite.staticanalysis.JavaApiBestPractices")
    activeRecipe("org.openrewrite.java.migrate.UpgradeToJava25")
    isExportDatatables = true
}

spotbugs {
    ignoreFailures = true
}

group = "com.hytaletrack"
version = "1.0.0-SNAPSHOT"
description = "Submit server data to HytaleTrack for display on player and server profiles"

repositories {
    maven("https://maven.hytale.com/release") {
        name = "hytale-release"
        mavenContent {
            releasesOnly()
        }
        content {
            includeGroup("com.hypixel.hytale")
        }
    }
    maven("https://maven.hytale.com/pre-release") {
        name = "hytale-pre-release"
        mavenContent {
            releasesOnly()
        }
        content {
            includeGroup("com.hypixel.hytale")
        }
    }
    mavenCentral()
}

dependencies {
    errorprone("com.google.errorprone:error_prone_core:2.46.0")
    spotbugs("com.github.spotbugs:spotbugs:4.9.8")

    rewrite("org.openrewrite.recipe:rewrite-static-analysis:2.26.0")
    rewrite("org.openrewrite.recipe:rewrite-migrate-java:3.26.0")
    rewrite("org.openrewrite.recipe:rewrite-rewrite:0.19.0")

    compileOnly("com.hypixel.hytale:Server:2026.01.22-6f8bdbdc4")
    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks {
    processResources {
        inputs.property("version", project.version)
        inputs.property("description", project.description ?: "")

        filesMatching(
            listOf(
                "manifest.json"
            )
        ) {
            expand(inputs.properties)
        }
    }
    test {
        useJUnitPlatform()
    }
}
