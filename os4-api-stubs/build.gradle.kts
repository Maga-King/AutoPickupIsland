import java.util.Properties

plugins { `java-library` }
val sdkProperties = Properties().apply {
    rootProject.file("local.properties").inputStream().use { load(it) }
}
dependencies { compileOnly(files("${sdkProperties.getProperty("sdk.dir")}/platforms/android-36/android.jar")) }
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
// Compile-only signatures verified against this OS4 firmware. NEVER package these
// classes into an Android APK; the actual implementations come from the boot classpath.
