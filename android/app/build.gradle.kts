plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.autotasker"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.autotasker"
        minSdk = 26
        targetSdk = 35
        // Incrémenté par la CI (numéro de run) pour que chaque build soit une vraie mise à jour.
        versionCode = (System.getenv("AUTOTASKER_VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("AUTOTASKER_VERSION_NAME") ?: "1.0"
    }

    signingConfigs {
        // Clé stable et volontairement publique : sans elle, chaque build de la CI serait signé
        // par une clé de debug différente et Android refuserait l'installation par-dessus la
        // version précédente. Voir la note « Signature » du README.
        create("shared") {
            storeFile = rootProject.file("keystore/autotasker-signing.jks")
            storePassword = "autotasker"
            keyAlias = "autotasker"
            keyPassword = "autotasker"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            // Pas de minification : l'appli est minuscule et distribuée hors Play Store.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("shared")
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

    lint {
        // Les appels Bluetooth sont gardés à l'exécution par BtDevices.hasPermission(),
        // que lint ne sait pas suivre.
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Partage de connexion : exécute l'appel système sous l'identité du shell.
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    // Lève les restrictions d'accès aux API non-SDK (Android 9+) pour la réflexion ci-dessus.
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
}
