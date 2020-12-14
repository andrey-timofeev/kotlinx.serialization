plugins {
    kotlin("multiplatform")
    id("kotlinx-serialization")
}

apply(from = rootProject.file("gradle/native-targets.gradle"))
apply(from = rootProject.file("gradle/configure-source-sets.gradle"))

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(project(":kotlinx-serialization-core"))
                api(project(":kotlinx-serialization-protobuf"))
            }
        }
    }
}
