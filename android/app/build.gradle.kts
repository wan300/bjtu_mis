plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val packagedAbis = providers.gradleProperty("targetAbis")
    .map { value ->
        value.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }
    .getOrElse(listOf("arm64-v8a"))

val openWebUiDir = rootProject.layout.projectDirectory.dir("open-webui")
val openWebUiBuildDir = openWebUiDir.dir("build")
val openWebUiAssetsDir = layout.projectDirectory.dir("src/main/assets/public")
val npmExecutable = if (System.getProperty("os.name").lowercase().contains("windows")) {
    "npm.cmd"
} else {
    "npm"
}

val npmCiOpenWebUi by tasks.registering(Exec::class) {
    group = "openwebui"
    description = "Install Open WebUI frontend dependencies."
    workingDir = openWebUiDir.asFile
    commandLine(npmExecutable, "ci")

    inputs.files(
        openWebUiDir.file("package.json"),
        openWebUiDir.file("package-lock.json"),
    )
    outputs.dir(openWebUiDir.dir("node_modules"))
}

val buildOpenWebUi by tasks.registering(Exec::class) {
    group = "openwebui"
    description = "Build Open WebUI frontend assets for the Android WebView."
    dependsOn(npmCiOpenWebUi)
    workingDir = openWebUiDir.asFile
    commandLine(npmExecutable, "run", "build")
    environment("ENABLE_MOBILE_CLIENT", "true")
    environment("ENABLE_MOBILE_NATIVE_FEATURES", "true")

    inputs.property("ENABLE_MOBILE_CLIENT", "true")
    inputs.property("ENABLE_MOBILE_NATIVE_FEATURES", "true")
    inputs.files(
        openWebUiDir.file("package.json"),
        openWebUiDir.file("package-lock.json"),
        openWebUiDir.file("svelte.config.js"),
        openWebUiDir.file("vite.config.ts"),
        openWebUiDir.file("tsconfig.json"),
        openWebUiDir.file("postcss.config.js"),
        openWebUiDir.file("tailwind.config.js"),
    )
    inputs.dir(openWebUiDir.dir("src"))
    inputs.files(fileTree(openWebUiDir.dir("static")) {
        exclude("pyodide/**")
    })
    outputs.dir(openWebUiBuildDir)
    outputs.dir(openWebUiDir.dir("static/pyodide"))
}

val syncOpenWebUiAssets by tasks.registering(Copy::class) {
    group = "openwebui"
    description = "Sync generated Open WebUI assets into Android packaged assets."
    dependsOn(buildOpenWebUi)

    from(openWebUiBuildDir) {
        exclude("**/*.map")
    }
    into(openWebUiAssetsDir)

    doFirst {
        delete(openWebUiAssetsDir.asFile)
    }
    doLast {
        val outputDir = openWebUiAssetsDir.asFile
        outputDir.mkdirs()
        listOf("cordova.js", "cordova_plugins.js").forEach { shim ->
            outputDir.resolve(shim).writeBytes(ByteArray(0))
        }
    }
}

android {
    namespace = "cn.edu.bjtu.mis"
    compileSdk = 35

    defaultConfig {
        applicationId = "cn.edu.bjtu.mis"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += packagedAbis
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    androidResources {
        ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:.*:!CVS:!thumbs.db:!picasa.ini:!*~"
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    implementation(project(":capacitor-android"))
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.coordinatorlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.jsoup)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.pytorch.android)
    implementation(libs.androidx.javascriptengine)
    implementation(libs.pdfbox.android)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.cordova.android)
    implementation(libs.commons.compress)

    ksp(libs.androidx.room.compiler)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.jsoup)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.androidx.room.testing)
}

tasks.named("preBuild") {
    dependsOn(syncOpenWebUiAssets)
}
