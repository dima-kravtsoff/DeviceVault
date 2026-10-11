import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

/**
 * The web app (../../index.html) is the single source of truth. This task copies it into a
 * generated assets folder on every build, so the Android app always ships the current page
 * and nobody has to remember to copy it by hand.
 */
abstract class CopyWebApp : DefaultTask() {
    @get:InputFile
    abstract val html: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val target = outputDir.get().dir("www").asFile
        target.mkdirs()
        html.get().asFile.copyTo(target.resolve("index.html"), overwrite = true)
    }
}

val copyWebApp = tasks.register<CopyWebApp>("copyWebApp") {
    html.set(rootProject.file("../index.html"))
    outputDir.set(layout.buildDirectory.dir("generated/webapp"))
}

android {
    namespace = "com.devicevault.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.devicevault.app"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        buildConfig = true
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyWebApp, CopyWebApp::outputDir)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.webkit)
    implementation(libs.play.services.auth)

    testImplementation(libs.junit)
}
