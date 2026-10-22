plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Automatically generate razor-sharp pure white Material Vector Drawables for the Media3 notification
// in both drawable-anydpi-v21 (highest DPI priority) and drawable so no blurry Holo PNGs are ever used.
val vectorIcons = mapOf(
    "ic_notif_heart_outline.xml" to """<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="M16.5,3C14.76,3 13.09,3.81 12,5.09C10.91,3.81 9.24,3 7.5,3C4.42,3 2,5.42 2,8.5C2,12.28 5.4,15.36 10.55,20.04L12,21.35L13.45,20.03C18.6,15.36 22,12.28 22,8.5C22,5.42 19.58,3 16.5,3ZM12.1,18.55L12,18.65L11.9,18.55C7.14,14.24 4,11.39 4,8.5C4,6.5 5.5,5 7.5,5C9.04,5 10.54,5.99 11.07,7.36H12.94C13.46,5.99 14.96,5 16.5,5C18.5,5 20,6.5 20,8.5C20,11.39 16.86,14.24 12.1,18.55Z" />
</vector>""",
    "ic_notif_heart_filled.xml" to """<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="M12,21.35L10.55,20.03C5.4,15.36 2,12.28 2,8.5C2,5.42 4.42,3 7.5,3C9.24,3 10.91,3.81 12,5.09C13.09,3.81 14.76,3 16.5,3C19.58,3 22,5.42 22,8.5C22,12.28 18.6,15.36 13.45,20.04L12,21.35Z" />
</vector>""",
    "ic_notif_prev.xml" to """<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="M6,6h2v12H6V6zM9.5,12l8.5,6V6L9.5,12z" />
</vector>""",
    "ic_notif_pause.xml" to """<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="M5.5,3.5h4.5v17H5.5V3.5zM14,3.5h4.5v17H14V3.5z" />
</vector>""",
    "ic_notif_play.xml" to """<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="M6.5,3.5v17l13,-8.5L6.5,3.5z" />
</vector>""",
    "ic_notif_next.xml" to """<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="M6,18l8.5,-6L6,6v12zM16,6v12h2V6h-2z" />
</vector>""",
    "ic_notif_close.xml" to """<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="M18.3,7.11L16.89,5.7 12,10.59 7.11,5.7 5.7,7.11 10.59,12 5.7,16.89 7.11,18.3 12,13.41 16.89,18.3 18.3,16.89 13.41,12 18.3,7.11z" />
</vector>""",
    "ic_notif_music_note.xml" to """<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="M12,3v10.55c-0.59,-0.34 -1.27,-0.55 -2,-0.55 -2.21,0 -4,1.79 -4,4s1.79,4 4,4 4,-1.79 4,-4V7h4V3h-6z" />
</vector>"""
)

val drawableDir = file("src/main/res/drawable").apply { mkdirs() }
val anyDpiDir = file("src/main/res/drawable-anydpi-v21").apply { mkdirs() }
val valuesDir = file("src/main/res/values").apply { mkdirs() }

vectorIcons.forEach { (name, xml) ->
    file("src/main/res/drawable/$name").writeText(xml)
    file("src/main/res/drawable-anydpi-v21/$name").writeText(xml)
}

val media3Aliases = mapOf(
    "media3_notification_play.xml" to vectorIcons.getValue("ic_notif_play.xml"),
    "media3_notification_pause.xml" to vectorIcons.getValue("ic_notif_pause.xml"),
    "media3_notification_seek_to_previous.xml" to vectorIcons.getValue("ic_notif_prev.xml"),
    "media3_notification_seek_to_next.xml" to vectorIcons.getValue("ic_notif_next.xml"),
    "media3_notification_small_icon.xml" to vectorIcons.getValue("ic_notif_music_note.xml")
)
media3Aliases.forEach { (name, xml) ->
    file("src/main/res/drawable/$name").writeText(xml)
    file("src/main/res/drawable-anydpi-v21/$name").writeText(xml)
}

android {
    namespace = "com.example.otomusic"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.otomusic"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "2.1-OtoPro-UltraLowRAM"
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
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.media3:media3-exoplayer:1.2.1")
    implementation("androidx.media3:media3-session:1.2.1")
    implementation("io.coil-kt:coil-compose:2.5.0")
}
