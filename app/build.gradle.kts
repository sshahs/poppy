import com.android.build.api.variant.FilterConfiguration

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Versioning: CI passes POPPY_VERSION_NAME / POPPY_VERSION_CODE (derived from the git tag).
val poppyVersionName: String = providers.environmentVariable("POPPY_VERSION_NAME").getOrElse("0.1.0")
val poppyVersionCode: Int = providers.environmentVariable("POPPY_VERSION_CODE").map { it.toInt() }.getOrElse(1)

// Release signing: provided by CI secrets, falls back to the debug key for local builds.
val keystorePath: String? = providers.environmentVariable("POPPY_KEYSTORE_PATH").orNull
val hasReleaseKey = keystorePath != null && file(keystorePath).exists()

android {
    namespace = "app.poppy"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.sshahs.poppy"
        minSdk = 26
        targetSdk = 36
        versionCode = poppyVersionCode
        versionName = poppyVersionName
        // Only ship the locales we actually translate; keeps resources.arsc small.
        androidResources.localeFilters += listOf("en")
        manifestPlaceholders["oauthScheme"] = "io.github.sshahs.poppy"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = providers.environmentVariable("POPPY_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("POPPY_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("POPPY_KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    // One APK per ABI (the Rust core is ~40-60 MB per ABI) plus a universal fallback.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/*.version", "DebugProbesKt.bin", "kotlin-tooling-metadata.json")
        // Native libs stay uncompressed & page-aligned: mmap'd straight from the APK (faster start,
        // no extracted copy on disk).
        jniLibs.useLegacyPackaging = false
        // The Rust SDK also ships 32-bit x86, which no real device needs.
        jniLibs.excludes += "lib/x86/**"
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    dependenciesInfo {
        // Not needed outside Google Play and it is opaque to users.
        includeInApk = false
        includeInBundle = false
    }
}

// Unique versionCode per ABI so Android can upgrade between split APKs cleanly.
val abiCodes = mapOf("armeabi-v7a" to 1, "arm64-v8a" to 2, "x86_64" to 4)
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val abi = output.filters.find { it.filterType == FilterConfiguration.FilterType.ABI }?.identifier
            val offset = abiCodes[abi] ?: 0
            output.versionCode.set(poppyVersionCode * 10 + offset)
        }
    }
}

composeCompiler {
    stabilityConfigurationFiles.add(project.layout.projectDirectory.file("compose-stability.conf"))
}

dependencies {
    implementation(libs.matrix.sdk)
    implementation(libs.jna) { artifact { type = "aar" } }
    implementation(libs.unifiedpush)

    implementation(libs.coroutines.android)
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.profileinstaller)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)

    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
}
