import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.a3ro.wirefree"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.a3ro.wirefree"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    buildFeatures { compose = true }

    val signingFile = rootProject.file("keystore.properties")
    if (signingFile.isFile) {
        val credentials = Properties().apply { signingFile.inputStream().use(::load) }
        signingConfigs.create("release") {
            storeFile = rootProject.file(credentials.getProperty("storeFile"))
            storePassword = credentials.getProperty("storePassword")
            keyAlias = credentials.getProperty("keyAlias")
            keyPassword = credentials.getProperty("keyPassword")
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("release")
    }

    sourceSets.getByName("test").resources.srcDir(rootProject.file("../docs"))
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("io.github.jaredmdobson:concentus:1.0.2")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    testImplementation("junit:junit:4.13.2")
}
