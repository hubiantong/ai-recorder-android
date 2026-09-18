import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

// Load local secrets (app/secrets.properties, gitignored). Missing file -> empty map, no crash.
val secretsFile = project.file("secrets.properties")
val secrets = Properties().apply {
    if (secretsFile.exists()) {
        secretsFile.inputStream().use { load(it) }
    }
}

// Read a secret key as an escaped Kotlin string literal; missing keys default to "".
fun secret(key: String): String =
    secrets.getProperty(key, "").replace("\\", "\\\\").replace("\"", "\\\"")

// Release signing (keystore.properties is gitignored; missing file -> unsigned release).
val keystoreProperties = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) {
        f.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.example.ai_recorder_android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.ai_recorder_android"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Secrets injected as BuildConfig String fields ("" when missing).
        buildConfigField("String", "SILICONFLOW_API_KEY", "\"${secret("SILICONFLOW_API_KEY")}\"")
        buildConfigField("String", "DASHSCOPE_API_KEY", "\"${secret("DASHSCOPE_API_KEY")}\"")
        buildConfigField("String", "DEEPSEEK_API_KEY", "\"${secret("DEEPSEEK_API_KEY")}\"")
        buildConfigField("String", "VOLC_APP_ID", "\"${secret("VOLC_APP_ID")}\"")
        buildConfigField("String", "VOLC_ACCESS_TOKEN", "\"${secret("VOLC_ACCESS_TOKEN")}\"")
        buildConfigField("String", "VOLC_SECRET_KEY", "\"${secret("VOLC_SECRET_KEY")}\"")
        buildConfigField("String", "AUDIO_HOST_HOST", "\"${secret("AUDIO_HOST_HOST")}\"")
        buildConfigField("String", "AUDIO_HOST_PORT", "\"${secret("AUDIO_HOST_PORT")}\"")
        buildConfigField("String", "AUDIO_HOST_USER", "\"${secret("AUDIO_HOST_USER")}\"")
        buildConfigField("String", "AUDIO_HOST_REMOTE_DIR", "\"${secret("AUDIO_HOST_REMOTE_DIR")}\"")
        buildConfigField("String", "AUDIO_HOST_PUBLIC_BASE", "\"${secret("AUDIO_HOST_PUBLIC_BASE")}\"")
        buildConfigField("String", "AUDIO_HOST_PRIVATE_KEY", "\"${secret("AUDIO_HOST_PRIVATE_KEY")}\"")
        buildConfigField("String", "ZHIPU_API_KEY", "\"${secret("ZHIPU_API_KEY")}\"")
    }

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }
    packaging {
        resources {
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// Room schema 导出：app/schemas/ 入 git（迁移审查用，Metis V4.4）
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Network
    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)

    // Coroutines & serialization
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Coil
    implementation(libs.coil.compose)

    // JSch (SFTP upload)
    implementation(libs.jsch)

    testImplementation(libs.junit)
    // Room in-memory 单测需要 ApplicationProvider（androidx.test:core）
    testImplementation(libs.androidx.test.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}