plugins {
    id("build-logic.kotlin-dsl-published-gradle-plugin")
    id("build-logic.test-junit5")
}

description = "Gradle plugin to that automatically signs all Publications in Sigstore"

dependencies {
    implementation(project(":sigstore-gradle:sigstore-gradle-sign-base-plugin"))

    testImplementation(project(":sigstore-testkit"))
}

pluginBundle {
    website = "https://github.com/sigstore/sigstore-java"
    vcsUrl = "https://github.com/sigstore/sigstore-java.git"
    tags = listOf("sigstore", "sign")
}

gradlePlugin {
    plugins {
        named("dev.sigstore.sign") {
            displayName = "Sign artifacts via Sigstore"
            description = "Plugin for signing artifacts via Sigstore"
        }
    }
}
configure<PublishingExtension> {
    repositories {
        maven(layout.buildDirectory.dir("tmp-repo")) {
            name = "tmp"
        }
    }
}