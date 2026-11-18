import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Firma release: letta da variabili d'ambiente (GitHub Secrets) oppure da keystore.properties locale (non versionato).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(env: String, prop: String): String? = System.getenv(env)?.takeIf { it.isNotBlank() } ?: keystoreProps.getProperty(prop)
val releaseStoreFile = signingValue("KEYSTORE_PATH", "storeFile")

android {
    namespace = "it.spese.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "it.spese.gestione"
        minSdk = 26
        targetSdk = 35
        // Android aggiorna un'app già installata solo se il versionCode AUMENTA e la firma è la stessa.
        // Su GitHub Actions il numero progressivo dell'esecuzione (GITHUB_RUN_NUMBER) cresce a ogni build; in locale resta 1.
        val ciRun = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
        versionCode = (System.getenv("VERSION_CODE")?.toIntOrNull()) ?: ciRun ?: 1
        versionName = System.getenv("VERSION_NAME") ?: "1.0.${ciRun ?: 0}"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Chiave di debug CONDIVISA e versionata (app/debug.keystore): tutte le build debug, anche su runner diversi, hanno la stessa firma
        // e quindi si installano una sopra l'altra. (Quella generata da Android è diversa a ogni runner: gli aggiornamenti venivano rifiutati.)
        // Non è una chiave di rilascio: serve solo per l'APK di debug, che ha un suo applicationId (.debug).
        getByName("debug") {
            storeFile = rootProject.file("app/debug.keystore")
            storePassword = "android"; keyAlias = "androiddebugkey"; keyPassword = "android"
        }
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = signingValue("KEYSTORE_PASSWORD", "storePassword")
                keyAlias = signingValue("KEY_ALIAS", "keyAlias")
                keyPassword = signingValue("KEY_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Se i segreti non sono configurati la build produce un APK non firmato (mai una firma debug per la distribuzione).
            if (releaseStoreFile != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES", "META-INF/INDEX.LIST") }
    testOptions { unitTests.isIncludeAndroidResources = true }
    lint { abortOnError = false; checkReleaseBuilds = false }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
tasks.withType<Test>().configureEach { maxHeapSize = "2g" }   // i test Robolectric sono pesanti: con i 512 MB di default finiscono la memoria

dependencies {
    implementation(project(":domain"))
    implementation(project(":data"))
    implementation(project(":core:designsystem"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)
    ksp("org.jetbrains.kotlin:kotlin-reflect:2.1.21")
    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.mlkit.text.recognition)

    testImplementation(libs.junit)
    testImplementation(libs.hilt.testing)
    kspTest(libs.hilt.compiler)
    kspTest("org.jetbrains.kotlin:kotlin-reflect:2.1.21")
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.junit)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.kotlinx.coroutines.test)
}
