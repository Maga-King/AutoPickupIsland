import java.security.MessageDigest

plugins { alias(libs.plugins.android.application) }
val originalReaderAssets = layout.buildDirectory.dir("generated/originalReaderAssets")
val copyOriginalColorOsReader by tasks.registering(Copy::class) {
    val firmware = providers.gradleProperty("colorOsFrameworkJar").orElse(
        rootProject.file("vendor/coloros/oplus-framework.jar").absolutePath)
    from(firmware) { into("coloros-original") }
    into(originalReaderAssets)
    doFirst {
        val sha = MessageDigest.getInstance("SHA-256").digest(file(firmware.get()).readBytes())
            .joinToString("") { "%02x".format(it) }
        check(sha == "3b47a02bec547b3218f8a9ff94e11bb443e985b9b980314a9e0bedd82df8af78") {
            "Unmodified ColorOS framework hash mismatch"
        }
    }
}
tasks.named("preBuild") { dependsOn(copyOriginalColorOsReader) }
android {
    namespace = "io.github.mio.collectorcarrier"
    compileSdk = 36
    defaultConfig {
        applicationId = namespace
        minSdk = 34
        targetSdk = 36
        versionCode = 4
        versionName = "0.4-page-signal"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets.getByName("main").java.srcDir("../shared/src/main/java")
    sourceSets.getByName("main").java.srcDir("src/legacy/java")
    sourceSets.getByName("main").assets.srcDir(originalReaderAssets.get().asFile)
}
dependencies { compileOnly(project(":os4-api-stubs")) }
