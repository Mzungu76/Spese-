plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "it.spese.core.designsystem"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    api(project(":core:model"))
    api(project(":core:common"))
    val bom = platform(libs.compose.bom)
    api(bom)
    api(libs.compose.ui)
    api(libs.compose.ui.graphics)
    api(libs.compose.ui.tooling.preview)
    api(libs.compose.material3)
    api(libs.compose.material.icons.extended)
    api(libs.vico.compose.m3)
    implementation(libs.androidx.core.ktx)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(bom)
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
