import java.net.URI
import java.security.MessageDigest

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
        versionCode = 26
        versionName = "5.1"

        ndk {
            abiFilters += listOf("arm64-v8a") // Galaxy S26 Ultra — arm64 בלבד
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
    "blaze_face_short_range.tflite" to
        "https://storage.googleapis.com/mediapipe-models/face_detector/blaze_face_short_range/float16/latest/blaze_face_short_range.tflite",
    // NudeNet של notAI-tech, רישיון AGPL-3.0
    "nudenet_320n.onnx" to
        "https://raw.githubusercontent.com/notAI-tech/NudeNet/6ccc81c6c305cccfd46d92b414f8a5c0a816574d/nudenet/320n.onnx",
    // הגרסה הגדולה (640m). ב-GitHub היא דורשת התחברות, אז מורידים עותק מ-Hugging Face ומאמתים לפי SHA256
    "nudenet_640m.onnx" to
        "https://huggingface.co/spaces/Nymbo/Nudity-Censor/resolve/7955acb6e51995555fe4e5f2fcde7437004053a0/640m.onnx"
)

val modelSha256 = mapOf(
    "nudenet_640m.onnx" to "04fe3d77980780c1f8297dc6d7f942fd5b3abe6942a188f742a85241e4f634eb"
)

fun sha256(f: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    f.inputStream().use { input ->
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

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
                // דף אינטרנט במקום מודל (למשל דף התחברות) — עוצרים את הבנייה
                val head = tmp.inputStream().use { it.readNBytes(16) }
                if (tmp.length() < 100_000 || head.isNotEmpty() && head[0] == '<'.code.toByte() ||
                    String(head).trimStart().startsWith("<")
                ) {
                    tmp.delete()
                    throw GradleException("Model download for $name returned a web page, not a model: $url")
                }
                modelSha256[name]?.let { expected ->
                    val actual = sha256(tmp)
                    if (actual != expected) {
                        tmp.delete()
                        throw GradleException("SHA256 mismatch for $name: $actual")
                    }
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
    // סקריפט שרץ בתחילת כל דף (לחסימת תמונות מוטמעות)
    implementation("androidx.webkit:webkit:1.12.1")
    // הרצת NudeNet (מודל ONNX)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
    // מנוע Firefox לדפדפן הניסיוני
    implementation("org.mozilla.geckoview:geckoview:140.0.20250707120347")
}
