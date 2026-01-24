plugins {
    id("java")
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
