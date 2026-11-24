import com.google.gms.googleservices.GoogleServicesPlugin.GoogleServicesPluginConfig
import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.services) apply false
}

if (file("google-services.json").isFile || file("src/debug/google-services.json").isFile ||
    file("src/release/google-services.json").isFile) {
    apply(plugin = "com.google.gms.google-services")
    extensions.configure<GoogleServicesPluginConfig> {
        missingGoogleServicesStrategy = MissingGoogleServicesStrategy.IGNORE
    }
}

android {
    namespace = "com.txl.mebot"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.txl.mebot"
        minSdk = 26
        targetSdk = 36
        versionCode = 102
        versionName = "0.21.23"
        val localConfig = Properties().apply {
            rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
        }
        val decisionEndpoint = localConfig.getProperty("mebot.decisionEndpoint", "").replace("\\", "\\\\").replace("\"", "\\\"")
        buildConfigField("String", "DEFAULT_DECISION_ENDPOINT", "\"$decisionEndpoint\"")
        val probeEndpoint = localConfig.getProperty("mebot.probeEndpoint", "").replace("\\", "\\\\").replace("\"", "\\\"")
        buildConfigField("String", "DEFAULT_PROBE_ENDPOINT", "\"$probeEndpoint\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.okhttp)
    implementation(libs.firebase.messaging)
    implementation(libs.androidx.core)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.room.runtime)

    ksp(libs.androidx.room.compiler)
    kspTest(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
