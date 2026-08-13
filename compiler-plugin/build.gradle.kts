plugins {
    kotlin("jvm")
    kotlin("kapt")
    alias(libs.plugins.dokka)
    `maven-publish`
    signing
    alias(libs.plugins.vanniktech.publish)
}

val libGroup = "io.tolgee.mobile-kotlin-sdk"
val libName = "compiler-plugin"

group = libGroup
version = libVersion

dokka {
    moduleName.set("Compiler Plugin")
    dokkaSourceSets.configureEach {
        sourceLink {
            localDirectory.set(file("src"))
            remoteUrl("https://github.com/tolgee/tolgee-mobile-kotlin-sdk/tree/master/compiler-plugin/src")
        }
    }
    // See core/build.gradle.kts — module pages carry their own branding.
    pluginsConfiguration.html {
        customAssets.from(rootProject.file("docs/logo-icon.svg"))
        customStyleSheets.from(rootProject.file("docs/tolgee.css"))
        homepageLink.set("https://tolgee.io/")
        footerMessage.set("© 2021-2026 Tolgee s.r.o. All rights reserved")
    }
}

dependencies {
    compileOnly(libs.auto.service)
    kapt(libs.auto.service)

    compileOnly(libs.kotlin.compiler.embeddable)
}

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()

    coordinates(
        groupId = libGroup,
        artifactId = libName,
        version = libVersion
    )

    pom {
        name.set(libName)

        description.set("Compiler plugin for Tolgee translations.")
        url.set("https://github.com/tolgee/tolgee-mobile-kotlin-sdk")

        licenses {
            license {
                name.set("Apache License 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }

        scm {
            url.set("https://github.com/tolgee/tolgee-mobile-kotlin-sdk")
            connection.set("scm:git:git://github.com/tolgee/tolgee-mobile-kotlin-sdk.git")
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