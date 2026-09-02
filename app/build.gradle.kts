import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
}

/**
 * The BGG bearer token lives in local.properties, which is git-ignored. When it is
 * absent the app still builds and runs; BGG features simply report themselves as
 * unconfigured. See Settings > BoardGameGeek in the app.
 */
val bggToken: String = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}.getProperty("BGG_API_TOKEN", "").trim()

/**
 * The version an APK claims. CI builds a release from a tag and passes it here, as
 * -PversionName=v0.2.0 or VERSION_NAME in the environment; every other build -- local, and
 * every CI debug build -- gets the value below, which is what the app has always reported.
 */
val appVersionName: String = (providers.gradleProperty("versionName").orNull
    ?: providers.environmentVariable("VERSION_NAME").orNull)
    ?.trim()?.removePrefix("v")?.takeIf { it.isNotEmpty() }
    ?: "0.1.0-alpha"

/**
 * versionCode is the number Android compares to decide an install is an upgrade, so it has
 * to rise with the version rather than stay the constant 1 it was while nothing was handed
 * out. It is derived from the version instead of from a run counter so that the same tag
 * rebuilt is the same build; a counter resets when a workflow is renamed, and a code lower
 * than the one already installed is an install Android refuses.
 *
 * major * 10000 + minor * 100 + patch, so 0.1.0 is 100 and 1.2.3 is 10203. A version that
 * is not a semantic one keeps 1, since there is nothing in it to order by.
 */
val appVersionCode: Int = Regex("""^(\d+)\.(\d+)\.(\d+)""").find(appVersionName)
    ?.destructured?.let { (major, minor, patch) ->
        major.toInt() * 10_000 + minor.toInt() * 100 + patch.toInt()
    } ?: 1

/**
 * The release key, unlike the debug one below, is the thing that stops anybody else
 * publishing an update over a real install -- so it is never in the repository. CI decodes
 * it out of a secret onto the runner and points RELEASE_KEYSTORE at the file. With nothing
 * supplied the release build still assembles; it just comes out unsigned, and an unsigned
 * APK will not install.
 */
val releaseKeystore: String? = providers.environmentVariable("RELEASE_KEYSTORE")
    .orNull?.takeIf { it.isNotBlank() }

android {
    namespace = "com.boardgamenation.tracker"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.boardgamenation.tracker"
        minSdk = 26
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "BGG_API_TOKEN", "\"$bggToken\"")
        buildConfigField("boolean", "BGG_CONFIGURED", bggToken.isNotEmpty().toString())
        buildConfigField("String", "BGG_BASE_URL", "\"https://boardgamegeek.com/xmlapi2/\"")
    }

    /**
     * The debug key is checked in rather than generated per machine. An
     * auto-generated ~/.android/debug.keystore differs on every developer's box and
     * on every CI runner, and Android refuses to install an APK over one signed with
     * a different key — so a reviewer could not update the app with a fresh CI build
     * without uninstalling and losing their data first. A debug key guards nothing,
     * so committing it leaks no secret.
     */
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = providers.environmentVariable("RELEASE_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("RELEASE_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("RELEASE_KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Null when no key was supplied, which is the unsigned build described above.
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/LICENSE.md",
            "/META-INF/LICENSE-notice.md",
        )
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.documentfile)

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    // hiltViewModel() lives here since Hilt 1.3; hilt-navigation-compose still
    // supplies the navigation integration that scopes a ViewModel to a back-stack
    // entry, which is what feeds route arguments into SavedStateHandle.
    implementation(libs.hilt.lifecycle.viewmodel.compose)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.ext.compiler)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.retrofit)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    implementation(libs.coil.compose)

    testImplementation(libs.junit)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.okhttp.mockwebserver)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
