plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.getcapacitor.android"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("proguard-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.activity)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.coordinatorlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.webkit)
    implementation(libs.cordova.android)

    testImplementation(libs.junit)
}
