import java.util.Base64
import java.net.URL

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
  alias(libs.plugins.secrets)
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  // Pinned so the CMake ExternalProject build of hev-socks5-tunnel (see
  // src/main/cpp/CMakeLists.txt) uses a consistent, known-good NDK/toolchain
  // instead of whatever happens to be default on a given machine or runner.
  ndkVersion = "27.0.12077973"

  defaultConfig {
    applicationId = "com.aistudio.v2raydan.kqtxwp"
    minSdk = 24
    targetSdk = 36
    versionCode = 3
    versionName = "1.2"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    // Only building for arm64-v8a for now — matches the existing Xray-core
    // download task, which only fetches the arm64-v8a binary. If you add
    // other ABIs for Xray, add them here too or the app will crash on those
    // devices when it can't find libhev-socks5-tunnel.so for their ABI.
    ndk {
      abiFilters += "arm64-v8a"
    }

    externalNativeBuild {
      cmake {
        cppFlags += ""
      }
    }
  }

  externalNativeBuild {
    cmake {
      path = file("src/main/cpp/CMakeLists.txt")
      version = "3.22.1"
    }
  }

  signingConfigs {
    create("release") {
      val base64Keystore = System.getenv("KEYSTORE_BASE64")
      val keystorePathEnv = System.getenv("KEYSTORE_PATH")
      val keystorePath = if (!base64Keystore.isNullOrBlank()) {
        val tempKeystore = file("${rootDir}/build/release.keystore")
        tempKeystore.parentFile.mkdirs()
        tempKeystore.writeBytes(Base64.getMimeDecoder().decode(base64Keystore.trim()))
        tempKeystore.absolutePath
      } else {
        if (!keystorePathEnv.isNullOrBlank()) keystorePathEnv else "${rootDir}/my-upload-key.jks"
      }
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = System.getenv("KEY_ALIAS") ?: "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      val keystore = file("${rootDir}/debug.keystore")
      if (keystore.exists()) {
        storeFile = keystore
        storePassword = "android"
        keyAlias = "androiddebugkey"
        keyPassword = "android"
      } else {
        // AGP 9 moved its built-in debug keystore to ~/.config/.android;
        // don't rely on it — CI workflow and Termux both use ~/.android.
        storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
        storePassword = "android"
        keyAlias = "androiddebugkey"
        keyPassword = "android"
      }
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      
      val base64Keystore = System.getenv("KEYSTORE_BASE64")
      val keystorePathEnv = System.getenv("KEYSTORE_PATH")
      val keystorePath = if (!keystorePathEnv.isNullOrBlank()) keystorePathEnv else "${rootDir}/my-upload-key.jks"
      val keystoreFile = file(keystorePath)
      if ((keystoreFile.exists() || !base64Keystore.isNullOrBlank()) && !System.getenv("STORE_PASSWORD").isNullOrBlank()) {
        signingConfig = signingConfigs.getByName("release")
      } else {
        signingConfig = signingConfigs.getByName("debugConfig")
      }
    }
    debug {
      signingConfig = signingConfigs.getByName("debugConfig")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  packaging {
    jniLibs {
      useLegacyPackaging = true
      keepDebugSymbols.add("**/libxray.so")
      doNotStrip.add("**/libxray.so")
    }
  }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
}

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // FIX: androidx.activity.compose alone does not reliably bring in
  // androidx.activity.result.contract.ActivityResultContracts / ActivityResultLauncher
  // on this version catalog setup. MainActivity.kt uses these classes directly,
  // so we add the base activity artifact explicitly.
  implementation("androidx.activity:activity-ktx:1.10.1")
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  // implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  // implementation(libs.coil.compose)
  implementation(libs.converter.moshi)
  // implementation(libs.firebase.ai)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  // implementation(libs.play.services.location)
  implementation(libs.retrofit)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
}

tasks.register("downloadXrayCore") {
    group = "build setup"
    description = "Downloads Xray core and assets, placing them in jniLibs and assets"

    val xrayUrl = "https://github.com/XTLS/Xray-core/releases/download/v26.3.27/Xray-android-arm64-v8a.zip"
    val jniLibFile = file("src/main/jniLibs/arm64-v8a/libxray.so")
    val geoipFile = file("src/main/assets/geoip.dat")
    val geositeFile = file("src/main/assets/geosite.dat")

    inputs.property("xrayUrl", xrayUrl)
    outputs.files(jniLibFile, geoipFile, geositeFile)

    doLast {
        val needsDownload = !jniLibFile.exists() || !geoipFile.exists() || !geositeFile.exists()
        if (needsDownload) {
            jniLibFile.parentFile.mkdirs()
            geoipFile.parentFile.mkdirs()
            val tempZip = file("build/tmp/Xray-android-arm64-v8a.zip")
            tempZip.parentFile.mkdirs()
            
            println("Downloading Xray package from $xrayUrl ...")
            try {
                URL(xrayUrl).openStream().use { input ->
                    tempZip.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                println("Download complete. Extracting files...")
                zipTree(tempZip).forEach { extractedFile ->
                    when (extractedFile.name) {
                        "xray" -> {
                            extractedFile.copyTo(jniLibFile, overwrite = true)
                            println("Extracted xray core binary to $jniLibFile")
                        }
                        "geoip.dat" -> {
                            extractedFile.copyTo(geoipFile, overwrite = true)
                            println("Extracted geoip.dat to $geoipFile")
                        }
                        "geosite.dat" -> {
                            extractedFile.copyTo(geositeFile, overwrite = true)
                            println("Extracted geosite.dat to $geositeFile")
                        }
                    }
                }
            } catch (e: java.lang.Exception) {
                println("Error during download or extraction of Xray: ${e.message}")
                throw e
            } finally {
                if (tempZip.exists()) {
                    tempZip.delete()
                }
            }
        } else {
            println("Xray core binary and geodata files already exist. Skipping download.")
        }
    }
}

tasks.named("preBuild") {
    dependsOn("downloadXrayCore")
}
