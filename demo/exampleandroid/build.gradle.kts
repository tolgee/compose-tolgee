import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.android)
//  alias(libs.plugins.tolgee) // uncomment to enable compiler plugin
}

android {
  namespace = "io.tolgee.demo.exampleandroid"
  compileSdk = 36

  defaultConfig {
    applicationId = "io.tolgee.demo.exampleandroid"
    minSdk = 21
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"
  }

  buildFeatures {
    buildConfig = true
  }

  buildTypes {
    release {
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
}

kotlin {
  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_17)
  }
}

// Uncomment to change configuration of compiler plugin
//tolgee {
//  // change compile time behavior
//  compilerPlugin {
//    android {
//      // Replaces Context.getString occurrences with Context.getStringT (tolgee extension)
//      replaceGetString.set(true) // default true
//      replacePluralString.set(true) // default true
//    }
//  }
//}

dependencies {
  implementation(libs.android)
  implementation(libs.activity)

  implementation(libs.coroutines.android)
  implementation(project(":core"))
}