import java.util.Properties
import java.net.URI
import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// 产品版本与上游核心来源分别维护，避免同步上游时改变 L7 的发布版本。
val l7Version = Properties().apply {
    load(providers.fileContents(rootProject.layout.projectDirectory.file("gradle/l7-version.properties"))
        .asText.get().reader())
}

val diplayDisplayVersion = l7Version.getProperty("diplayCoreVersion")

// .env 只按字面量读取，不执行 shell 展开；环境变量可覆盖文件中的打包默认值。
val logDefaults = providers.fileContents(rootProject.layout.projectDirectory.file(".env"))
    .asText.orElse("").get().lineSequence().map(String::trim)
    .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
    .associate { line ->
        val key = line.substringBefore('=').trim()
        val raw = line.substringAfter('=').trim()
        key to if (raw.length >= 2 && raw.first() == raw.last() && raw.first() in "\"'") raw.substring(1, raw.length - 1) else raw
    }
val logServerUrl = providers.environmentVariable("L7_LOG_SERVER_URL").getOrElse(logDefaults["L7_LOG_SERVER_URL"].orEmpty())
val logAuthorization = providers.environmentVariable("L7_LOG_AUTHORIZATION").getOrElse(logDefaults["L7_LOG_AUTHORIZATION"].orEmpty())
check(logServerUrl.isEmpty() || runCatching {
    val uri = URI(logServerUrl)
    logServerUrl.length <= 2048 && uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank() &&
        uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
        (uri.port == -1 || uri.port in 1..65535) &&
        Regex("(?:/[^/]+)*/api/[^/]+/[^/]+/_json").matches(uri.path)
}.getOrDefault(false)) { "L7_LOG_SERVER_URL 格式无效" }
check(logAuthorization.isEmpty() || runCatching {
    check(logAuthorization.length <= 4096 && logAuthorization.matches(Regex("Basic [A-Za-z0-9+/]+={0,2}")))
    val decoded = Base64.getDecoder().decode(logAuthorization.removePrefix("Basic ")).toString(Charsets.UTF_8)
    val colon = decoded.indexOf(':')
    colon > 0 && colon < decoded.lastIndex && decoded.none { it.code < 32 || it.code == 127 }
}.getOrDefault(false)) { "L7_LOG_AUTHORIZATION 格式无效" }
fun logResource(value: String): String = "\"" + value.replace("&", "&amp;").replace("<", "&lt;")
    .replace(">", "&gt;").replace("\\", "\\\\").replace("\"", "\\\"") + "\""

// 认证材料由 Docker 脚本显式挂载；未提供环境变量的源码构建不包含身份。
val localAuthenticationAssets = providers.environmentVariable("DIPLAY_AUTH_ASSETS_DIR")
    .orNull?.let { file(it).canonicalFile }

android {
    namespace = "com.shilapi.xcertplay"
    androidResources {
        // 同时过滤依赖自带的其他语言，APK 仅保留中英文资源。
        localeFilters += listOf("en", "zh-rCN")
    }
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.ecarx.carplay"
        // L7 专用应用最低支持 Android 10，实车验证以 Flyme OS / Android 11 为准。
        minSdk = 29
        targetSdk = 37
        versionCode = l7Version.getProperty("versionCode").toInt()
        versionName = l7Version.getProperty("versionName")
        manifestPlaceholders["diplayCoreVersion"] = l7Version.getProperty("diplayCoreVersion")
        manifestPlaceholders["diplayCoreCommit"] = l7Version.getProperty("diplayCoreCommit")
        resValue("string", "l7_core_source_info",
            "DiPlay $diplayDisplayVersion · ${l7Version.getProperty("diplayCoreCommit").take(7)}")
        // 核心版本只来源于官方发布基线。
        resValue("string", "l7_source_version_diplay", diplayDisplayVersion)
        resValue("string", "l7_log_default_url", logResource(logServerUrl))
        resValue("string", "l7_log_default_authorization", logResource(logAuthorization))

    }


    localAuthenticationAssets?.let { sourceSets.getByName("main").assets.directories.add(it.path) }

    signingConfigs {
        create("release") {
            storeFile = file(
                providers.environmentVariable("ANDROID_KEYSTORE_PATH")
                    .getOrElse("missing-release-keystore.jks"),
            )
            storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").getOrElse("")
            keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").getOrElse("")
            keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").getOrElse("")
        }
    }

    buildTypes {
        debug {
            // 调试与实车构建使用相同包名，便于核对 L7 的包名权限策略。
        }
        release {
            optimization {
                enable = false
            }
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        resValues = true
    }
    // 新增 L7 入口测试时统一放入 e2e，不在模块 src 下重新分散存放。
    sourceSets {
        getByName("test").setRoot(rootProject.file("e2e/mobile/test").path)
        getByName("androidTest").setRoot(rootProject.file("e2e/mobile/androidTest").path)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    // DiPlay 核心源码依赖；L7 的入口、品牌和发布版本由 mobile 单独维护。
    implementation(project(":common"))
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.app.projected)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// 仅允许指定目录中的配套私钥和证书，禁止其他凭据混入 APK 资产。
val credentialAssets = files(android.sourceSets.flatMap { source ->
    source.assets.directories.map { directory ->
        fileTree(directory) {
            include("**/offline-mfi/**", "**/*.pk8", "**/*.p7b", "**/*.key",
                "**/*.pem", "**/*.p12", "**/*.pfx", "**/*.jks", "**/*.keystore")
        }
    }
})
val rejectBundledCredentials by tasks.registering {
    group = "verification"
    description = "Reject unexpected credential files in APK assets."
    val filesToCheck = credentialAssets
    val allowed = localAuthenticationAssets?.let { dir ->
        listOf("identity.pk8", "certificate.p7b").map { dir.resolve("offline-mfi/$it").canonicalFile }.toSet()
    } ?: emptySet()
    inputs.files(filesToCheck)
    doLast {
        check(allowed.all { it.isFile }) { "Explicit local authentication assets are incomplete" }
        val unexpected = filesToCheck.files.filter { it.canonicalFile !in allowed }
        check(unexpected.isEmpty()) { "Unexpected credential files in APK assets" }
    }
}
tasks.named("preBuild") { dependsOn(rejectBundledCredentials) }

// 实车打包要求预置认证；显式源码构建可省略认证材料。
val verifyStandaloneAuthentication by tasks.registering {
    group = "verification"
    description = "Require the explicit runtime authentication input for a standalone car-test APK."
    val directory = localAuthenticationAssets
    doLast {
        check(directory != null) {
            "Standalone car builds require DIPLAY_AUTH_ASSETS_DIR; assembleDebug alone is source-only."
        }
        check(listOf("identity.pk8", "certificate.p7b").all {
            directory.resolve("offline-mfi/$it").let { file -> file.isFile && file.length() > 0 }
        }) { "Standalone CarPlay authentication files are missing or empty" }
    }
}
tasks.named("preBuild") { mustRunAfter(verifyStandaloneAuthentication) }
tasks.register("assembleStandaloneDebug") {
    group = "build"
    description = "Build a standalone car-test APK with explicitly provisioned authentication."
    dependsOn(verifyStandaloneAuthentication, "assembleDebug")
}

tasks.register("assembleStandaloneRelease") {
    group = "build"
    description = "使用外部认证材料和外部 Android 签名构建 L7 发布包。"
    dependsOn(verifyStandaloneAuthentication, "assembleRelease")
}
