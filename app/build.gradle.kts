import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 릴리스 서명 정보는 repo 밖(~/.everink-release/keystore.properties)에서 읽는다.
// 파일이 없으면 release 빌드는 서명 없이 만들어진다(CI 등).
val keystoreProps = Properties().apply {
    val f = file(System.getProperty("user.home") + "/.everink-release/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
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
        versionName = "0.1.0"
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // v0.1.0: MuPDF JNI 경계의 난독화 리스크를 피하기 위해 minify 비활성.
            // APK 크기 최적화는 이후 릴리스에서 keep 규칙과 함께 검토.
            isMinifyEnabled = false
            if (keystoreProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
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
