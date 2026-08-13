import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

plugins {
    alias(libs.plugins.android) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.atomicfu) apply false
    alias(libs.plugins.binary.compatibility)
    alias(libs.plugins.cocoapods) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.dokka)
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.ktorfit) apply false
    alias(libs.plugins.multiplatform) apply false
    alias(libs.plugins.serialization) apply false
    alias(libs.plugins.skie) apply false
    alias(libs.plugins.vanniktech.publish) apply false
    alias(libs.plugins.versions)
}

// Ignore API on demo projects
apiValidation {
    ignoredProjects.add("demo")
    ignoredProjects.add("multiplatform-compose")
    ignoredProjects.add("exampleandroid")
    ignoredProjects.add("examplejetpack")
}

dependencies {
    dokka(project(":core"))
    dokka(project(":compose"))
    dokka(project(":gradle-plugin"))
//    dokka(project(":compiler-plugin"))
}

// Force new atomicfu version, compose uses 0.23.2
allprojects {
    configurations.all {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.kotlinx" && requested.name.startsWith("atomicfu")) {
                useVersion(libs.versions.atomicfu.get())
            }
        }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_1)
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_1)
    }
}

dokka {
    moduleName.set("Tolgee Mobile Kotlin SDK")
    dokkaPublications.html {
        suppressInheritedMembers.set(true)
        failOnWarning.set(true)
    }
    // Configured here only: the root project governs the aggregated publication.
    pluginsConfiguration.html {
        // Must be named logo-icon.svg — Dokka hardcodes that path for the nav logo and the favicon.
        customAssets.from("docs/logo-icon.svg")
        customStyleSheets.from("docs/tolgee.css")
        homepageLink.set("https://tolgee.io/")
        footerMessage.set("© 2021-2026 Tolgee s.r.o. All rights reserved")
    }
}

tasks.withType<DependencyUpdatesTask> {
    outputFormatter {
        val updatable = this.outdated.dependencies
        val markdown = if (updatable.isEmpty()) {
            buildString {
                append("### Dependencies up-to-date")
                appendLine()
                appendLine()
                appendLine("Everything up-to-date")
                appendLine()
                appendLine("### Gradle Version")
                appendLine()
                appendLine("**Current version:** ${this@outputFormatter.gradle.running.version}")
                appendLine("**Latest version:** ${this@outputFormatter.gradle.current.version}")
            }
        } else {
            buildString {
                append("## Updatable dependencies (${updatable.size})")
                appendLine()
                appendLine()
                append('|')
                append("Group")
                append('|')
                append("Module")
                append('|')
                append("Used Version")
                append('|')
                append("Available Version")
                append('|')
                appendLine()
                append('|')
                repeat(2) {
                    append("---")
                    append('|')
                }
                repeat(2) {
                    append(":-:")
                    append('|')
                }
                updatable.forEach { dependency ->
                    appendLine()
                    append('|')
                    append(dependency.group ?: ' ')
                    append('|')
                    append(dependency.name ?: ' ')
                    append('|')
                    append(dependency.version ?: ' ')
                    append('|')
                    append(dependency.available.release ?: dependency.available.milestone ?: ' ')
                    append('|')
                }
                appendLine()
                appendLine()
                appendLine("### Gradle Version")
                appendLine()
                appendLine("**Current version:** ${this@outputFormatter.gradle.running.version}")
                appendLine("**Latest version:** ${this@outputFormatter.gradle.current.version}")
            }
        }
        val outputFile = layout.buildDirectory.file("dependencyUpdates/report.md").get().asFile
        try {
            if (outputFile.exists()) {
                outputFile.delete()
            }
        } catch (ignored: Throwable) { }
        try {
            outputFile.parentFile?.mkdirs()
        } catch (ignored: Throwable) { }
        try {
            outputFile.writeText(markdown)
        } catch (ignored: Throwable) { }
    }
    rejectVersionIf {
        isNonStable(candidate.version) && !isNonStable(currentVersion)
    }
}

fun isNonStable(version: String): Boolean {
    val stableKeyword = listOf("RELEASE", "FINAL", "GA").any { version.uppercase().contains(it) }
    val regex = "^[0-9,.v-]+(-r)?$".toRegex()
    val isStable = stableKeyword || regex.matches(version)
    return isStable.not()
}