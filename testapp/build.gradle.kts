import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
}

val originalReaderAssets = layout.buildDirectory.dir("generated/originalReaderAssets")
val copyOriginalColorOsReader by tasks.registering(Copy::class) {
    val firmware = providers.gradleProperty("colorOsFrameworkJar").orElse(
        rootProject.file("vendor/coloros/oplus-framework.jar").absolutePath)
    from(firmware) { into("coloros-original") }
    into(originalReaderAssets)
    doFirst {
        val bytes = file(firmware.get()).readBytes()
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        check(sha == "3b47a02bec547b3218f8a9ff94e11bb443e985b9b980314a9e0bedd82df8af78") {
            "Unmodified ColorOS framework hash mismatch"
        }
    }
}
tasks.named("preBuild") { dependsOn(copyOriginalColorOsReader) }

dependencies {
    // Only supplies the original PCR host dependency; the OEM APK stays unchanged.
    implementation("com.google.code.gson:gson:2.10.1")
}

android {
    namespace = "io.github.mio.autopickupfixture"
    compileSdk = 36

    defaultConfig {
        applicationId = namespace
        minSdk = 34
        targetSdk = 36
        versionCode = 9
        versionName = "0.3.5"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // The exact same protocol/client sources as the module, not a test-only copy.
    sourceSets.getByName("main").java.srcDir("../shared/src/main/java")
    sourceSets.getByName("main").assets.srcDir(originalReaderAssets.get().asFile)
}
