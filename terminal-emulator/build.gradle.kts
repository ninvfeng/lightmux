// Vendored from termux/termux-app (Apache-2.0). See ../NOTICE and ./LICENSE.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.termux.terminal"
    compileSdk = 35

    // [lightmux] 与 app 模块同理：不钉死，AGP 会去下它默认的 build-tools 34.0.0。见 app/build.gradle.kts
    buildToolsVersion = "35.0.0"

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    testImplementation(libs.junit)
}
