plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
}

android {
    namespace = "space.svn.svnspace"
    compileSdk = 35

    // Release signing is device-local. Uncomment and set real values only on your machine.
    // signingConfigs {
    //     create("release") {
    //         storeFile = file("/sdcard/Download/my-release-key.keystore")
    //         storePassword = "your_keystore_password"
    //         keyAlias = "my_alias_name"
    //         keyPassword = "your_key_password"
    //     }
    // }

    defaultConfig {
        applicationId = "space.svn.svnspace"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0-alpha"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["appAuthRedirectScheme"] = "space.svn.svnspace"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            manifestPlaceholders["appAuthRedirectScheme"] = "space.svn.svnspace"
            
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            manifestPlaceholders["appAuthRedirectScheme"] = "space.svn.svnspace.debug"
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
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    // Core
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.fragment:fragment-ktx:1.8.3")

    // Lifecycle + ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.5")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.5")

    // Navigation
    implementation("androidx.navigation:navigation-fragment-ktx:2.8.0")
    implementation("androidx.navigation:navigation-ui-ktx:2.8.0")

    // WorkManager for background scanning & backup
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Security - encrypted prefs for credentials & tokens
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Preference / Settings UI
    implementation("androidx.preference:preference-ktx:1.2.1")

    // RecyclerView + expandable trees + swipe + coordinator
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.2.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // AppAuth for proper OAuth (Google / Microsoft / Dropbox class)
    implementation("net.openid:appauth:0.11.1")

    // OkHttp for custom remote (WebDAV, etc.)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Gson for simple serialization
    implementation("com.google.code.gson:gson:2.11.0")

    // DocumentFile / Storage Access Framework helpers
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Room database
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
