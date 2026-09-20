import java.util.Properties

plugins { `java-library` }
val sdkProperties = Properties().apply {
    val local = rootProject.file("local.properties")
    if (local.isFile) local.inputStream().use { load(it) }
}
val sdkDirectory = sdkProperties.getProperty("sdk.dir")
    ?: providers.environmentVariable("ANDROID_HOME").orNull
    ?: providers.environmentVariable("ANDROID_SDK_ROOT").orNull
    ?: error("Set ANDROID_HOME or sdk.dir in local.properties to your Android SDK directory")
dependencies { compileOnly(files("$sdkDirectory/platforms/android-36/android.jar")) }
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
// Compile-only signatures verified against this OS4 firmware. NEVER package these
// classes into an Android APK; the actual implementations come from the boot classpath.
