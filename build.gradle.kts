plugins {
    id("java")
}

group = "com.hytaletrack"
version = "1.0.0-SNAPSHOT"
description = "Submit server data to HytaleTrack for display on player and server profiles"

repositories {
    mavenCentral()
    ivy {
        url = uri("https://hytaleserver.2b2t.fans/")
        patternLayout {
            artifact("[artifact].[ext]") // -> HytaleServer.jar
        }
        metadataSources {
            artifact()
        }
    }
}

dependencies {
    implementation("hytale:HytaleServer:1.0@jar")
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
