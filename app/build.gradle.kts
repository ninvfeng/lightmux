import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// android.kotlinOptions 在 Kotlin 2.4 已经是硬错误，jvmTarget 只能从这里配。
kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

android {
    namespace = "net.lighttools.lightmux"
    compileSdk = 35

    // 必须钉死，且必须和 scripts/android-env.dockerfile 装的那个版本一致。
    // 不写这行，AGP 8.7 会按自己的默认值要 build-tools 34.0.0——镜像里只有 35.0.0，
    // 于是每次 CI 都去 dl.google.com 现下一份 34.0.0。平时看不出来，网络一抖就是
    // 「Archive is not a ZIP archive」（下回来的是代理的错误页），构建直接红。
    // 本机两个版本都装了，所以这个坑只在 CI 里炸。
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "net.lighttools.lightmux"
        minSdk = 24
        targetSdk = 35
        versionCode = 67
        versionName = "0.1.66"
        vectorDrawables { useSupportLibrary = true }
        // 本 app 只有 values/ 和 values-zh/ 两套文案，但 androidx / material3 各自带了
        // 89 种语言的字符串，全塞进 resources.arsc（该文件按对齐要求是**不压缩存储**的，
        // 有多大占多大）。只留 en + zh 能把 arsc 从 276KB 砍到 51KB。
        resourceConfigurations += setOf("en", "zh")
    }

    signingConfigs {
        // 稳定发布签名：CI 通过 LIGHTMUX_* 环境变量注入 keystore。
        // 本地无环境变量时不配置，release 构建回退 debug 签名，方便本地出包。
        create("release") {
            val ksPath = System.getenv("LIGHTMUX_KEYSTORE")
            if (!ksPath.isNullOrBlank() && file(ksPath).exists()) {
                storeFile = file(ksPath)
                storePassword = System.getenv("LIGHTMUX_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("LIGHTMUX_KEY_ALIAS")
                keyPassword = System.getenv("LIGHTMUX_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
        release {
            // sshj + bouncycastle + material-icons-extended 三家把包撑到 15MB。
            // keep 规则早就写好了（见 proguard-rules.pro），只是一直没打开开关。
            isMinifyEnabled = true
            isShrinkResources = true
            // 只留两个真机 ABI。datastore 与 graphics-path 各带一个 .so，四个 ABI 全打
            // 白吃 37KB。x86 / x86_64 这边站着的是模拟器和 x86 Chromebook：
            // 模拟器跑的是 debug 包（debug 不设 abiFilters，调试路径不受影响），
            // Chromebook 是明知的取舍，理由写在下面 lint 的 ChromeOsAbiSupport 那条旁边。
            ndk { abiFilters += setOf("arm64-v8a", "armeabi-v7a") }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (!System.getenv("LIGHTMUX_KEYSTORE").isNullOrBlank())
                signingConfigs.getByName("release")
            else
                signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        // lint 的版本已经由 gradle.properties 的 `android.experimental.lint.version` 顶到
        // AGP 之上，androidx 那些 lint.jar 不再被跳过——原先为此关掉的
        // NullSafeMutableLiveData 也就没必要再关了（本项目一处 LiveData 都没有，开着零成本）。

        // 唯一还要关的一条：release 只打 arm64-v8a / armeabi-v7a（见上面的 abiFilters），
        // lint 会提醒「x86 的 Chromebook 装不上」。这是明知代价的取舍：
        // 本项目走直链 APK 分发，不经 Play Store 的 ABI 过滤；而 x86 那两份 .so 白吃 37KB，
        // 对一个把「3.6MB」写在首页上的 app 来说不划算。
        // 哪天真要照顾 Chrome OS，把 abiFilters 里的两条加回来、连这行一起删即可。
        disable += "ChromeOsAbiSupport"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            // sshj / bouncycastle 各自带 META-INF 元数据，不排除会因重复条目打包失败。
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-notice.md",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/NOTICE.md",
                "META-INF/INDEX.LIST",
                "META-INF/*.kotlin_module",
                // androidx 各 artifact 都往这里塞一份 **一模一样**的 Apache-2.0 全文，
                // 8 份合计 25KB。许可要求的是「随分发提供一份副本」，不是八份：
                // 权威副本改放 assets/licenses/Apache-2.0.txt，About 页指过去（同 OFL 的处理）。
                "META-INF/androidx/**",
                // 协程的调试探针元数据，只有 kotlinx-coroutines-debug 的 agent 会读它。
                "DebugProbesKt.bin",
                // BC 1.85 除了 versions/9 又多了一份 versions/17 的 OSGi 清单，
                // 通配整段版本号，省得每次升 BC 都得回来补一行。
                "META-INF/versions/*/OSGI-INF/MANIFEST.MF",
                // 下面这几条是 jar 里的 **Java 资源**，不是 res/：shrinkResources 只管
                // res/ 和 arsc，R8 只管 class，两边都碰不到它们，只能在打包阶段排掉。
                // picnic 是后量子签名 Picnic 的 LowMC 常量表，压缩后仍有 1.16MB——
                // 只被 PicnicEngine 惰性读取，而 SSH 不存在任何走到 Picnic 的路径。
                // BC 1.84 把它从 pqc/crypto/ 挪到了 pqc/legacy/，两条路径都排掉，
                // 免得哪天版本回退又漏进来（漏了一眼就能看出来：APK 直接涨 1MB+）。
                "org/bouncycastle/pqc/crypto/picnic/*.properties",
                "org/bouncycastle/pqc/legacy/picnic/*.properties",
                // PKIXCertPathReviewer 的证书链校验错误文案（含德语版），sshj 校验主机密钥
                // 走的是自己的 HostKeyVerifier，从不碰 PKIX 证书链复核。
                "org/bouncycastle/x509/CertPathReviewerMessages*.properties",
                "org/bouncycastle/pkix/CertPathReviewerMessages*.properties"
            )
        }
    }
}

dependencies {
    implementation(project(":terminal-view"))
    implementation(project(":terminal-emulator"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.datastore.preferences)
    // 显式声明：SSH 的阻塞 IO 全靠 Dispatchers.IO，不能指望 lifecycle 把它当 api 传递过来
    implementation(libs.kotlinx.coroutines.android)

    // SSH 传输 + SFTP：一个依赖两用
    implementation(libs.sshj)
    // sshj 0.39 起 ed25519 不再走 net.i2p:eddsa，改成 SecurityUtils.getSignature/getKeyFactory("Ed25519")，
    // 也就是落到我们在 SshConnection.ensureSecurityProvider() 里装的那个完整 BouncyCastle 上。
    // 0.40.0 的 jar 里对 net/i2p 的引用是 0 处，留着 eddsa 只是被 keep 规则钉死的死代码。
    implementation(libs.bouncycastle.prov)
    implementation(libs.bouncycastle.pkix)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
}
