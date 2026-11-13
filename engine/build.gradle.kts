plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.striptools.strippdf"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.pdfbox)
    testImplementation(libs.junit)
}
