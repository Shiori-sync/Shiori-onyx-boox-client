import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val signingFile =
    File(
        System.getProperty("user.home"),
        ".local/share/aragonite-powersync/signing/client.properties",
    )
val signingProps =
    Properties().apply { if (signingFile.exists()) signingFile.inputStream().use { load(it) } }

android {
    namespace = "dev.aragonite.powersync.client"
    compileSdk = 35
    testBuildType = "release"
    defaultConfig {
        applicationId = "dev.aragonite.powersync.client"
        minSdk = 30
        targetSdk = 35
        versionCode = 16
        versionName = "0.3.5"
        testInstrumentationRunner = "dev.aragonite.powersync.client.NativeReadCompatibilityTest"
    }
    // Per-app language (Android Settings → App languages) lists every values-<locale> folder.
    androidResources { generateLocaleConfig = true }
    // New user-facing text belongs in strings.xml, and every language must cover every string.
    lint {
        error +=
            listOf(
                "HardcodedText",
                "SetTextI18n",
                "MissingTranslation",
                "StringFormatInvalid",
                "StringFormatMatches",
                "MissingQuantity",
            )
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    signingConfigs {
        if (signingFile.exists())
            create("persistent") {
                storeFile = File(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
    }
    buildTypes {
        getByName("release") {
            if (signingFile.exists()) signingConfig = signingConfigs.getByName("persistent")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
