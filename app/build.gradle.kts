import java.net.URI

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
        versionCode = 12
        versionName = "3.9"

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
        noCompress += listOf("tflite", "onnx")
    }
}

// ----- מודלי ה-AI לסינון תמונות -----
// יורדים אוטומטית בזמן הבנייה (ב-GitHub Actions וגם ב-Android Studio), ולא נשמרים בריפו
val modelFiles = mapOf(
    "efficientdet_lite2.tflite" to
        "https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite2/int8/latest/efficientdet_lite2.tflite",
    "efficientnet_lite0.tflite" to
        "https://storage.googleapis.com/mediapipe-models/image_classifier/efficientnet_lite0/int8/latest/efficientnet_lite0.tflite",
    "blaze_face_short_range.tflite" to
        "https://storage.googleapis.com/mediapipe-models/face_detector/blaze_face_short_range/float16/latest/blaze_face_short_range.tflite",
    // מודל מגדר: Age-Gender_Estimation_TF-Android של Shubham Panchal, רישיון MIT
    "gender.tflite" to
        "https://raw.githubusercontent.com/shubham0204/Age-Gender_Estimation_TF-Android/48365bc1a541f3ac47b08bf85537849c5fc323e9/app/src/main/assets/model_gender_nonq.tflite",
    // NudeNet של notAI-tech, רישיון AGPL-3.0
    "nudenet_320n.onnx" to
        "https://raw.githubusercontent.com/notAI-tech/NudeNet/6ccc81c6c305cccfd46d92b414f8a5c0a816574d/nudenet/320n.onnx"
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
                URI(url).toURL().openStream().use { input ->
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
    implementation("androidx.compose.material:material-icons-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // מודל AI מקומי לזיהוי תוכן בתמונות
    implementation("com.google.mediapipe:tasks-vision:0.10.14")
    // הרצת מודל המגדר
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    // סקריפט שרץ בתחילת כל דף (לחסימת תמונות מוטמעות)
    implementation("androidx.webkit:webkit:1.12.1")
    // הרצת NudeNet (מודל ONNX)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
}
