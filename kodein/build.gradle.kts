import com.vanniktech.maven.publish.SonatypeHost
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.dokka)
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.vanniktech.publish)
    `maven-publish`
    signing
}

val libGroup = "dev.datlag.tolgee"
val libName = "tolgee-kodein"
val libVersion = "0.1.5"

group = libGroup
version = libVersion

dokka {
    dokkaSourceSets.configureEach {
        sourceLink {
            localDirectory.set(file("src"))
            remoteUrl("https://github.com/DatL4g/compose-tolgee/tree/master/kodein/src")
        }
    }
}

kotlin {
    androidTarget {
        publishAllLibraryVariants()
    }
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    iosX64()
    iosArm64()
    iosSimulatorArm64()

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        binaries.executable()
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.components.resources)
            api(project(":compose"))

            implementation(libs.kodein.compose)
        }

        androidMain.dependencies {
            implementation(compose.ui)
        }
    }
}

android {
    compileSdk = 35
    namespace = "dev.datlag.tolgee.kodein"

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_17
    }
}

mavenPublishing {
    publishToMavenCentral(host = SonatypeHost.S01, automaticRelease = true)
    signAllPublications()

    coordinates(
        groupId = libGroup,
        artifactId = libName,
        version = libVersion
    )

    pom {
        name.set(libName)

        description.set("Compose Multiplatform localization wrapper for Tolgee")
        url.set("https://github.com/DatL4g/compose-tolgee")

        licenses {
            license {
                name.set("Apache License 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }

        scm {
            url.set("https://github.com/DatL4g/compose-tolgee")
            connection.set("scm:git:git://github.com/DatL4g/compose-tolgee.git")
        }

        developers {
            developer {
                id.set("DatL4g")
                name.set("Jeff Retz (DatLag)")
                url.set("https://github.com/DatL4g")
            }
        }
    }
}