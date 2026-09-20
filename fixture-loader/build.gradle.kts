plugins { alias(libs.plugins.android.application) }
android {
    namespace = "io.github.mio.fixtureloader"
    compileSdk = 36
    defaultConfig {
        applicationId = namespace
        minSdk = 34
        targetSdk = 36
        versionCode = 2
        versionName = "0.2-gated-collector"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies { compileOnly(libs.xposed.api) }
