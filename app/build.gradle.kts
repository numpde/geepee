plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val runBenchmarksOptIn = providers.gradleProperty("geepee.runBenchmarks")
    .orElse(providers.environmentVariable("GEEPEE_RUN_BENCHMARKS"))
    .orNull

android {
    namespace = "dev.ra.geepee"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.ra.geepee"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    sourceSets {
        getByName("test") {
            resources.srcDir(rootProject.file("routes"))
        }
    }
}

tasks.withType<Test>().configureEach {
    runBenchmarksOptIn?.let { enabled ->
        systemProperty("geepee.runBenchmarks", enabled)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.03.00"))

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    testImplementation("junit:junit:4.13.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
