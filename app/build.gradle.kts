import com.android.build.api.variant.impl.VariantOutputImpl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

data class GitInfo(
    val commitId: String,
    val commitTime: String,
    val tagName: String?,
) {
    val versionNameSuffix get() = if (tagName == null) ("-" + commitId.take(7)) else null
}

val gitInfo = GitInfo(
    commitId = "fok0014",
    commitTime = "1789290000000",
    tagName = null,
)

val debugSuffixPairList by lazy {
    javax.xml.parsers.DocumentBuilderFactory
        .newInstance()
        .newDocumentBuilder()
        .parse(file("$projectDir/src/main/res/values/strings.xml"))
        .documentElement.getElementsByTagName("string").run {
            (0 until length).mapNotNull { i ->
                val node = item(i)
                if (node.attributes.getNamedItem("debug_suffix") != null) {
                    val key = node.attributes.getNamedItem("name").nodeValue
                    val value = node.textContent
                    key to value
                } else {
                    null
                }
            }
        }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.androidx.room)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlinx.atomicfu)
    alias(libs.plugins.google.ksp)
    alias(libs.plugins.remap)
    alias(libs.plugins.loc)
}

android {
    namespace = rootProject.ext["android.namespace"].toString()
    compileSdk = rootProject.ext["android.compileSdk"] as Int
    buildToolsVersion = rootProject.ext["android.buildToolsVersion"].toString()

    defaultConfig {
        minSdk = rootProject.ext["android.minSdk"] as Int
        targetSdk = rootProject.ext["android.targetSdk"] as Int

        applicationId = "li.songe.gkd"
        versionCode = 107
        versionName = "1.12.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        androidResources {
            localeFilters += listOf("zh", "en")
        }
        ndk {
            // noinspection ChromeOsAbiSupport
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        // 只写 AndroidManifest.xml 真正消费的 3 个占位符(commitId/commitTime/tagName),
        // GitInfo.versionNameSuffix 不在这里下发 —— 它由 buildTypes.versionNameSuffix 直接使用
        manifestPlaceholders["commitId"] = gitInfo.commitId
        manifestPlaceholders["commitTime"] = gitInfo.commitTime
        manifestPlaceholders["tagName"] = gitInfo.tagName ?: ""
    }

    buildFeatures {
        compose = true
        aidl = true
        resValues = true
    }

    val gkdSigningConfig = if (project.hasProperty("GKD_STORE_FILE")) {
        signingConfigs.create("gkd") {
            storeFile = file(project.properties["GKD_STORE_FILE"] as String)
            storePassword = project.findProperty("GKD_STORE_PASSWORD")?.toString()
            keyAlias = project.findProperty("GKD_KEY_ALIAS")?.toString()
            keyPassword = project.findProperty("GKD_KEY_PASSWORD")?.toString()
        }
    } else {
        signingConfigs.getByName("debug")
    }

    buildTypes {
        all {
            versionNameSuffix = gitInfo.versionNameSuffix
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            signingConfig = gkdSigningConfig
            applicationIdSuffix = ".debug"
            resValue("color", "better_black", "#FF5D92")
            debugSuffixPairList.onEach { (key, value) ->
                resValue("string", key, "$value-debug")
            }
        }
    }
    productFlavors {
        flavorDimensions += "channel"
        // 上游原有的 play 渠道 flavor 已删除: 本 fork 不发布到 Google Play(与上游同包名但签名不同,
        // 根本不可能上架), 该 flavor 从未被构建过 —— 它唯一的产物差异是 is_accessibility_tool=false
        // 与 AboutPage 里那几处"从 Play 安装"的提示分支, 属于永远走不到的死代码。
        create("gkd") {
            isDefault = true
            signingConfig = gkdSigningConfig
            resValue("bool", "is_accessibility_tool", "true")
        }
        all {
            dimension = flavorDimensions.first()
            manifestPlaceholders["channel"] = name
        }
    }
    compileOptions {
        sourceCompatibility = rootProject.ext["android.javaVersion"] as JavaVersion
        targetCompatibility = rootProject.ext["android.javaVersion"] as JavaVersion
    }
    dependenciesInfo.includeInApk = false
    packaging.resources.excludes += setOf(
        // https://github.com/Kotlin/kotlinx.coroutines/issues/2023
        "META-INF/**", "**/attach_hotspot_windows.dll",

        "**.properties", "**.bin", "**/*.proto",
        "**/kotlin-tooling-metadata.json",

        // ktor
        "**/custom.config.conf",
        "**/custom.config.yaml",
    )
}

if (project.hasProperty("GKD_RENAME_APK_FLAG")) {
    androidComponents.onVariants { variant ->
        variant.outputs.onEach { output ->
            output as VariantOutputImpl
            output.outputFileName = "gkd-v${output.versionName.get()}.apk"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(rootProject.ext["kotlin.jvmTarget"] as JvmTarget)
        freeCompilerArgs.addAll(
            "-opt-in=kotlin.contracts.ExperimentalContracts",
            "-opt-in=kotlinx.coroutines.FlowPreview",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.serialization.ExperimentalSerializationApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.animation.graphics.ExperimentalAnimationGraphicsApi",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "-Xcontext-parameters",
            "-Xexplicit-backing-fields",
            "-XXLanguage:+MultiDollarInterpolation",
        )
    }
}

// https://developer.android.com/jetpack/androidx/releases/room?hl=zh-cn#compiler-options
room {
    schemaDirectory("$projectDir/schemas")
}

composeCompiler {
    reportsDestination = layout.buildDirectory.dir("compose_compiler")
    stabilityConfigurationFiles.addAll(
        rootProject.layout.projectDirectory.file("stability_config.conf"),
    )
}

loc {
    template = "{packageName}.{methodName}({fileName}:{lineNumber})"
}

dependencies {
    // 未显式声明 kotlin-stdlib: Kotlin 插件默认注入(gradle.properties 未关闭 kotlin.stdlib.default.dependency)

    implementation(project(":selector"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)

    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.animation)
    implementation(libs.compose.animation.graphics)
    implementation(libs.compose.icons)
    implementation(libs.compose.preview)
    debugImplementation(libs.compose.tooling)

    implementation(libs.compose.activity)
    implementation(libs.compose.material3)

    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    // androidTest 里没有 Compose 规则/Espresso 的使用点(只有 ExampleInstrumentedTest),
    // 因此不声明 compose.junit4 与 androidx.espresso

    compileOnly(project(":hidden_api"))
    implementation(libs.rikka.shizuku.api)
    implementation(libs.rikka.shizuku.provider)
    implementation(libs.lsposed.hiddenapibypass)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    implementation(libs.google.accompanist.drawablepainter)

    implementation(libs.kotlinx.serialization.core)
    implementation(libs.kotlinx.serialization.json)
    // https://github.com/Kotlin/kotlinx-atomicfu/issues/145
    implementation(libs.kotlinx.atomicfu)

    implementation(libs.activityResultLauncher)

    implementation(libs.reorderable)

    implementation(libs.androidx.splashscreen)

    implementation(libs.coil.compose)
    implementation(libs.coil.network)
    implementation(libs.coil.gif)
    implementation(libs.telephoto.zoomable)

    implementation(libs.exp4j)

    implementation(libs.toaster)
    implementation(libs.permissions)
    implementation(libs.device)

    implementation(libs.json5)
    compileOnly(libs.loc.annotation)

    implementation(libs.kevinnzouWebview)
}
