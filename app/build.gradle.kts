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

    // 서명 키는 저장소에 넣지 않는다. GitHub Secrets(SIGNING_KEYSTORE_B64 등)가 있으면 그 키로,
    // 없으면 빌드마다 새로 만들어지는 디버그 키로 서명한다 (이 경우 업데이트 시 재설치 필요).
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
}
