import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.services)
}

// Optional Play upload-key signing (never commit keystore / passwords).
// Create keystore.properties at repo root (gitignored) OR pass:
//   ALETHEIA_STORE_FILE / ALETHEIA_STORE_PASSWORD / ALETHEIA_KEY_ALIAS / ALETHEIA_KEY_PASSWORD
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
fun propOrEnv(propKey: String, envKey: String): String? =
    keystoreProperties.getProperty(propKey)?.takeIf { it.isNotBlank() }
        ?: System.getenv(envKey)?.takeIf { it.isNotBlank() }

val uploadStoreFilePath = propOrEnv("storeFile", "ALETHEIA_STORE_FILE")
val uploadStorePassword = propOrEnv("storePassword", "ALETHEIA_STORE_PASSWORD")
val uploadKeyAlias = propOrEnv("keyAlias", "ALETHEIA_KEY_ALIAS")
val uploadKeyPassword = propOrEnv("keyPassword", "ALETHEIA_KEY_PASSWORD")
val hasUploadSigning = !uploadStoreFilePath.isNullOrBlank() &&
    !uploadStorePassword.isNullOrBlank() &&
    !uploadKeyAlias.isNullOrBlank() &&
    !uploadKeyPassword.isNullOrBlank() &&
    rootProject.file(uploadStoreFilePath!!).isFile

val packageReleaseNativeSymbolFallback by tasks.registering(Zip::class) {
    group = "build"
    description = "Packages exported symbols from pre-stripped native dependencies for Play Console."
    dependsOn("mergeReleaseNativeLibs")
    from(layout.buildDirectory.dir("intermediates/merged_native_libs/release/mergeReleaseNativeLibs/out/lib"))
    archiveFileName.set("native-debug-symbols.zip")
    destinationDirectory.set(layout.buildDirectory.dir("outputs/native-debug-symbols/release"))
}

tasks.matching { it.name == "bundleRelease" }.configureEach {
    finalizedBy(packageReleaseNativeSymbolFallback)
}

android {
    // Founder 17/11/2026: the Aletheia 1.5B offline model ships as an install-time asset pack.
    assetPacks.add(":aletheia_model")
    namespace = "it.grg.flighttimeapp"
    compileSdk = 36

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (hasUploadSigning) {
            create("release") {
                storeFile = rootProject.file(uploadStoreFilePath!!)
                storePassword = uploadStorePassword
                keyAlias = uploadKeyAlias
                keyPassword = uploadKeyPassword
            }
        }
    }

    defaultConfig {
        applicationId = "it.grg.flighttimeapp"
        minSdk = 24
        targetSdk = 36

        // Must exceed every code already on Play: production 35 (1.12.13),
        // internal test 37 (1.12.15). 1.12.16/38 = API 36 production line + Aletheia
        // Crew Tools aligned with iOS (Billing 8.3.0).
        versionCode = 38
        versionName = "1.12.16"

        // Aletheia Beta (Crew Tools). Product ships enabled.
        // Rollback: set this AND every buildType override below to false (buildType fields win).
        buildConfigField("boolean", "ALETHEIA_ENABLED", "true")
        // 1.7 = same Aletheia feature version as iOS (AletheiaBrand.betaVersion): Crew Tools aligned
        // (Aletheia 1.5B import, server status, delete my data, provenance). Bump both apps together.
        buildConfigField("String", "ALETHEIA_VERSION", "\"1.7\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Aletheia Offline: llama.cpp JNI (GGUF).
        // Do NOT set ndk.abiFilters here — that would drop armeabi-v7a (~large % of Play devices).
        // Compile llama only for 64-bit; 32-bit devices still install via AAR .so + no GGUF lib.
        // CI can pass -PaletheiaSkipNative=true for a faster billing-unlock AAB (still has BILLING).
        if (project.findProperty("aletheiaSkipNative") != "true") {
            externalNativeBuild {
                cmake {
                    abiFilters += listOf("arm64-v8a", "x86_64")
                    cppFlags += listOf("-O3", "-fvisibility=hidden", "-ffunction-sections", "-fdata-sections")
                    arguments += listOf(
                        "-DANDROID_STL=c++_shared",
                        "-DLLAMA_CURL=OFF",
                        "-DLLAMA_BUILD_COMMON=ON",
                        "-DLLAMA_BUILD_TESTS=OFF",
                        "-DLLAMA_BUILD_EXAMPLES=OFF",
                        "-DLLAMA_BUILD_SERVER=OFF",
                        "-DGGML_OPENMP=OFF",
                        "-DGGML_LLAMAFILE=OFF",
                        "-DGGML_NATIVE=OFF",
                        "-DBUILD_SHARED_LIBS=OFF",
                        "-DCMAKE_BUILD_TYPE=Release"
                    )
                }
            }
        }
    }

    if (project.findProperty("aletheiaSkipNative") != "true") {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
        ndkVersion = "27.0.12077973"
    }

    buildTypes {
        debug {
            // Intentionally enabled for product; override to false together with defaultConfig to roll back.
            buildConfigField("boolean", "ALETHEIA_ENABLED", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            // Package native symbols automatically whenever dependencies provide them.
            // AndroidX DataStore currently ships its shared-counter library already stripped.
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Product wants Aletheia on in release. Changing only defaultConfig does NOT disable this.
            buildConfigField("boolean", "ALETHEIA_ENABLED", "true")
            // Sign with Play upload key when keystore.properties / env is present; otherwise
            // leave unsigned so parent can jarsigner with the Console upload key (see docs).
            if (hasUploadSigning) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                signingConfig = null
            }
        }
    }

    testOptions {
        // android.util.Log & co. return defaults in JVM unit tests instead of throwing.
        unitTests.isReturnDefaultValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.gson)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.security.crypto)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.database)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.storage)
    implementation(libs.firebase.appcheck)
    implementation(libs.firebase.appcheck.playintegrity)
    debugImplementation(libs.firebase.appcheck.debug)
    implementation(libs.firebase.functions)
    implementation(libs.billing.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.play.services.location)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

// Founder 17/11/2026: a release bundle without the Aletheia 1.5B model would ship a broken Offline mode.
val aletheiaModelAsset = rootProject.file("aletheia_model/src/main/assets/aletheia/aletheia-1_5b-qwen32-factualrepair-v3-ggufdetail-q4_k_m.gguf")
tasks.matching { it.name == "bundleRelease" }.configureEach {
    doFirst {
        check(aletheiaModelAsset.isFile && aletheiaModelAsset.length() == 986_048_000L) {
            "Aletheia 1.5B model missing or wrong size at " + aletheiaModelAsset
        }
    }
}
