plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "uk.wakbijok.belanja"
    compileSdk = 35

    defaultConfig {
        applicationId = "uk.wakbijok.belanja"
        minSdk = 28
        targetSdk = 35
        versionCode = 32
        versionName = "1.2.0-beta10"

        // Legacy BuildConfig field kept so remaining sync stubs compile.
        // Product path is standalone; URL is unused at runtime.
        buildConfigField("String", "SYNC_URL", "\"\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.appcompat)
    implementation(libs.okhttp)
    // Solo mode: on-device PDF text extraction for statement parsing.
    implementation(libs.pdfbox.android)
    // Play Services document scanner: edge detect + deskew + lighting cleanup → PDF.
    implementation(libs.mlkit.doc.scanner)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    // Ujian unit sahaja — org.json memberi pelaksanaan sebenar di JVM,
    // menggantikan stub android.jar yang membuang UnsupportedOperationException.
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
