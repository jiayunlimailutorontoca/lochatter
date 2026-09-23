plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.1.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.21" apply false
}

// Optional: put Gradle outputs outside a cloud-synced checkout. Those folders sometimes
// replace files with placeholders, and Gradle refuses to snapshot them.
// Set `chatter.buildDir=C:/somewhere` in ~/.gradle/gradle.properties.
providers.gradleProperty("chatter.buildDir").orNull?.let { base ->
    layout.buildDirectory.set(file("$base/root"))
    subprojects { layout.buildDirectory.set(file("$base/${project.name}")) }
}
