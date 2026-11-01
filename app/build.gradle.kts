import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.ggumtak.pentastic"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ggumtak.pentastic"
        minSdk = 30 // AccessibilityService.takeScreenshot
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    // 서명 키는 저장소에 넣지 않는다. CI(.github/workflows/build.yml)가 한 번 만든 고정 키를 넘겨준다.
    // 로컬 빌드처럼 키가 없으면 디버그 키로 서명한다.
    val keystorePath = System.getenv("SIGNING_KEYSTORE_PATH")?.takeIf { file(it).exists() }
    signingConfigs {
        if (keystorePath != null) {
            create("shared") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("shared") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/licenses/**",
                "META-INF/native-image/**",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("com.anthropic:anthropic-java:3.3.0")
    // 한자 손글씨 인식 (기기 안에서 동작, 일본어 모델은 처음 한 번 내려받음)
    implementation("com.google.mlkit:digital-ink-recognition:18.1.0")
}
