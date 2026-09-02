plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Push для подтверждений включается, только если положен google-services.json
// (см. README). Без него проект собирается и работает — нет лишь подтверждений.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

// Единый источник версии для агента и приложения — файл VERSION в корне
// репозитория; поднимается скриптом tools/bump-version.sh.
val productVersion: String = rootProject.file("../VERSION").let { file ->
    require(file.exists()) { "Не найден ${file.absolutePath} — версия берётся оттуда" }
    file.readText().trim()
}
// versionCode должен монотонно расти: 1.2.3 -> 10203.
val productVersionCode: Int = productVersion.split(".").let { (major, minor, patch) ->
    major.toInt() * 10000 + minor.toInt() * 100 + patch.toInt()
}

android {
    namespace = "com.rfidunlock.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.rfidunlock.app"
        minSdk = 29
        targetSdk = 34
        versionCode = productVersionCode
        versionName = productVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        buildConfig = true  // BuildConfig.VERSION_NAME — версия на экране настроек
    }
    packaging {
        resources {
            excludes += setOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/LICENSE.md",
                "META-INF/NOTICE.md",
            )
        }
    }
}

dependencies {
    // Встроенный ZeroTier (libzt, userspace, arm64) — собран из
    // github.com/zerotier/libzt (pkg/android), см. Паспорт-libzt.md
    implementation(files("libs/libzt-release.aar"))

    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // DataStore (настройки)
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // ZXing — сканирование QR-кода профиля ПК
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // Firebase Cloud Messaging — «звонок» с ПК: разбудить телефон запросом
    // подтверждения. Сам вердикт идёт по своему каналу, не через Google.
    implementation(platform("com.google.firebase:firebase-bom:33.1.2"))
    implementation("com.google.firebase:firebase-messaging")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // Bouncy Castle для генерации self-signed сертификата KDE Connect
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
