plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.everink"
    compileSdk = 35

    defaultConfig {
        // ⚠️ 패키지명(applicationId)은 F-Droid 등재 후 변경 불가 — 정식 출시 전 확정할 것
        applicationId = "app.everink"
        minSdk = 26          // PRD 오픈퀘스천 #3: API 26 채택(SAF 성숙도 기준)
        targetSdk = 35
        versionCode = 1
        versionName = "0.5.0-spike"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // MuPDF fitz — Artifex 공식 AAR (AGPL-3.0). 코어 렌더링/파싱 엔진.
    implementation("com.artifex.mupdf:fitz:1.28.0")
}
