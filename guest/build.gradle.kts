plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// App leggera dell'altra persona: vede il budget di spesa e il ritmo di un conto condiviso e manda le spese rilevate dalle notifiche.
android {
    namespace = "it.spese.guest"
    compileSdk = 35

    defaultConfig {
        applicationId = "it.spese.condivisione"
        minSdk = 26
        targetSdk = 35
        val ciRun = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
        versionCode = (System.getenv("VERSION_CODE")?.toIntOrNull()) ?: ciRun ?: 1
        versionName = System.getenv("VERSION_NAME") ?: "1.0.${ciRun ?: 0}"
    }

    signingConfigs {
        // stessa chiave di debug condivisa dell'app madre: le build si installano una sopra l'altra
        getByName("debug") {
            storeFile = rootProject.file("app/debug.keystore")
            storePassword = "android"; keyAlias = "androiddebugkey"; keyPassword = "android"
        }
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    packaging { resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES", "META-INF/INDEX.LIST") }
    lint { abortOnError = false; checkReleaseBuilds = false }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":domain"))
    implementation(project(":core:designsystem"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.coroutines.android)
}
