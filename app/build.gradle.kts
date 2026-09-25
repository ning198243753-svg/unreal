import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// AMap Web-service key injected from OUTSIDE the repo (never committed).
// Priority: env AMAP_WEB_KEY > ~/.config/moon/amap-web-key > local.properties.
val amapKey: String = run {
    System.getenv("AMAP_WEB_KEY")?.trim()?.takeIf { it.isNotEmpty() }?.let { return@run it }
    File(System.getProperty("user.home"), ".config/moon/amap-web-key")
        .takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }?.let { return@run it }
    val lp = rootProject.file("local.properties")
    if (lp.isFile) {
        val props = Properties()
        lp.inputStream().use { props.load(it) }
        props.getProperty("amap.web.key")?.trim()?.takeIf { it.isNotEmpty() }?.let { return@run it }
    }
    ""
}

android {
    namespace = "com.moon.location"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.moon.location"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "AMAP_WEB_KEY", "\"$amapKey\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Sign with debug key for personal sideloading; replace with your own for release.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions {
        jvmTarget = "21"
    }

    packaging {
        resources {
            // Required for libxposed module metadata (module.prop / java_init.list).
            merges += "META-INF/xposed/*"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.okhttp)

    // libxposed (modern Xposed API). The API is provided by the framework at runtime.
    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)
}
