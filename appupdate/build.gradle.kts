// Self-contained module: no version catalog, no third-party dependency, no reference to the host
// app. Published through JitPack; it can also be included as a source module, in which case the
// host project only needs the Android Gradle and Kotlin Android plugins on its classpath.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    `maven-publish`
}

android {
    namespace = "online.kerja.appupdate"
    compileSdk = 36

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        // Readable by host apps on Kotlin 2.0 and newer, not only the compiler that built it.
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
    }
}

// JitPack sets the coordinates (com.github.<user>:<repo>:<tag>); these are for local publishing.
publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "online.kerja"
            artifactId = "appupdate"
            version = providers.gradleProperty("version").getOrElse("1.0.0")
            afterEvaluate { from(components["release"]) }
        }
    }
}
