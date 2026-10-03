plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.compose)
}

// 离线许可直接从现有文档生成，限定输入范围，不复制整个工作区或认证目录。
val prepareL7Licenses by tasks.registering(Sync::class) {
    into(layout.buildDirectory.dir("generated/l7-license-assets/third-party"))
    from(rootProject.file("docs/第三方许可.md")) { rename { "NOTICE.md" } }
    from(rootProject.file("docs/licenses")) {
        include("**/*.txt")
        into("licenses")
        eachFile { relativePath = RelativePath(true, "licenses", name) }
        includeEmptyDirs = false
    }
    from(rootProject.file("LICENSE")) { into("licenses"); rename { "GPL-3.0.txt" } }
    from(rootProject.file("shared/src/main/assets/byd-hud-icons/LICENSE-BYDMate.txt")) { into("licenses") }
}

android {
    namespace = "com.shilapi.xcertplay.host"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }
    // 原生组件回归使用实际合并资源，不能用单测占位 R 代替主题。
    testOptions { unitTests.isIncludeAndroidResources = true }
    // 测试集中存放，源集映射保留模块依赖与 internal 可见性。
    sourceSets {
        getByName("main").assets.directories.add(layout.buildDirectory.dir("generated/l7-license-assets").get().asFile.path)
        getByName("test").setRoot(rootProject.file("e2e/common/test").path)
        getByName("androidTest").setRoot(rootProject.file("e2e/common/androidTest").path)
    }
}

tasks.named("preBuild") { dependsOn(prepareL7Licenses) }

dependencies {
    api(project(":shared"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.mockito)
}
