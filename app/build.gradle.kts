plugins {
    alias(libs.plugins.android.application)
}

val appVersionCode = (findProperty("versionCode") as String?)?.toInt() ?: 1
val appVersionName = (findProperty("versionName") as String?) ?: "0.1.0"
val configUrl = (findProperty("configUrl") as String?)
    ?: "https://raw.githubusercontent.com/arnabry11/cypher-tv/dist/config.json"

android {
    namespace = "tv.cypher.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "tv.cypher.app"
        minSdk = 23
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "CONFIG_URL", "\"$configUrl\"")
    }

    buildFeatures { buildConfig = true }

    signingConfigs {
        // Release signing comes from the environment (CI secrets, or a local keystore outside the repo).
        System.getenv("CYPHER_KEYSTORE")?.let { path ->
            create("release") {
                storeFile = file(path)
                storePassword = System.getenv("CYPHER_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("CYPHER_KEY_ALIAS")
                keyPassword = System.getenv("CYPHER_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        // Installs next to the released app (which is signed with the release key), for trying changes on the TV.
        debug { applicationIdSuffix = ".dev" }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint { abortOnError = false }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.androidx.recyclerview)
}
