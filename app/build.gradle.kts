plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.meni.whitelistfilter"
    compileSdk = 35

    // ב-GitHub Actions משתמשים ב-NDK שכבר מותקן במכונה (עם מספר הגרסה שלו).
    // ב-Android Studio — ברירת המחדל.
    (System.getenv("ANDROID_NDK_HOME") ?: System.getenv("ANDROID_NDK_LATEST_HOME"))
        ?.takeIf { path -> path.isNotBlank() }
        ?.let { path ->
            val dir = file(path)
            val props = dir.resolve("source.properties")
            val revision: String? = if (props.exists()) {
                props.readLines()
                    .firstOrNull { line -> line.trim().startsWith("Pkg.Revision") }
                    ?.substringAfter("=")
                    ?.trim()
            } else {
                null
            }
            if (revision != null) {
                ndkPath = dir.absolutePath
                ndkVersion = revision
            }
        }

    defaultConfig {
        applicationId = "com.meni.whitelistfilter"
        minSdk = 29
        targetSdk = 35
        versionCode = 5
        versionName = "3.2"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        externalNativeBuild {
            ndkBuild {
                // רק הספרייה, בלי קובץ ההרצה הנפרד של hev
                targets += "hev-socks5-tunnel"
            }
        }
    }

    // מנוע ה-VPN (hev-socks5-tunnel, קוד C) — נבנה מתוך src/main/jni
    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }

    // מפתח חתימה קבוע בריפו, כדי שכל APK חדש יתעדכן מעל הקודם בלי להסיר
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
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
    }

    // המודלים נטענים ישירות מהזיכרון, אז אסור לדחוס אותם
    androidResources {
        noCompress += "tflite"
    }
}

// ----- מודלי ה-AI לסינון תמונות -----
// יורדים אוטומטית בזמן הבנייה (ב-GitHub Actions וגם ב-Android Studio), ולא נשמרים בריפו
val modelFiles = mapOf(
    "efficientdet_lite0.tflite" to
        "https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/int8/latest/efficientdet_lite0.tflite",
    "efficientnet_lite0.tflite" to
        "https://storage.googleapis.com/mediapipe-models/image_classifier/efficientnet_lite0/int8/latest/efficientnet_lite0.tflite"
)

val downloadModels by tasks.registering {
    val dir = file("src/main/assets/models")
    outputs.dir(dir)
    doLast {
        dir.mkdirs()
        modelFiles.forEach { (name, url) ->
            val target = File(dir, name)
            if (!target.exists() || target.length() == 0L) {
                logger.lifecycle("Downloading model $name")
                val tmp = File(dir, "$name.part")
                java.net.URI(url).toURL().openStream().use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                }
                tmp.renameTo(target)
            }
        }
    }
}

tasks.named("preBuild") {
    dependsOn(downloadModels)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // מודל AI מקומי לזיהוי תוכן בתמונות
    implementation("com.google.mediapipe:tasks-vision:0.10.14")
}
