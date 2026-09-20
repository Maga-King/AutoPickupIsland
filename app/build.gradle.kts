import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
}

// Package the same hash-verified OEM reader used by the standalone carrier.
val integratedReaderAssets = layout.buildDirectory.dir("generated/integratedReaderAssets")
val copyIntegratedReader by tasks.registering(Copy::class) {
    val framework = providers.gradleProperty("colorOsFrameworkJar").orElse(
        rootProject.file("vendor/coloros/oplus-framework.jar").absolutePath)
    from(framework) { into("coloros-original") }
    into(integratedReaderAssets)
    doFirst {
        val hash = MessageDigest.getInstance("SHA-256").digest(file(framework.get()).readBytes())
            .joinToString("") { "%02x".format(it) }
        check(hash == "3b47a02bec547b3218f8a9ff94e11bb443e985b9b980314a9e0bedd82df8af78") {
            "Unmodified ColorOS framework hash mismatch"
        }
    }
}
tasks.named("preBuild") { dependsOn(copyIntegratedReader) }

android {
    namespace = "io.github.mio.autopickupisland"
    compileSdk = 36

    defaultConfig {
        applicationId = namespace
        minSdk = 34
        targetSdk = 36
        versionCode = 30
        versionName = "0.11.8"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets.getByName("main").java.srcDir("../shared/src/main/java")
    sourceSets.getByName("main").java.srcDir("../collector-carrier/src/main/java")
    sourceSets.getByName("main").assets.srcDir(integratedReaderAssets.get().asFile)

    androidResources {
        noCompress += listOf("apk", "ort", "webp")
    }
}

dependencies {
    compileOnly(project(":os4-api-stubs"))
    compileOnly(libs.xposed.api)
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    // The original ColorOS PCR plugin does not bundle Gson. Its OEM host supplies it;
    // our isolated module class loader must supply this dependency explicitly too.
    implementation("com.google.code.gson:gson:2.10.1")
    // OEM KMS references GeneratedMessageV3; lite protobuf does not provide it.
    implementation("com.google.protobuf:protobuf-java:3.25.5")
    implementation("org.luckypray:dexkit:2.2.0")
}
