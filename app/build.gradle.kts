plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.magnate.compass"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.magnate.compass"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
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
        // minSdk 24 上提供 java.time（TimeFormat 用它做本地时区格式化）
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            // 纯 JVM 测试中若意外触到 android.jar 桩类，返回默认值而非抛异常。
            // 生产代码的纯函数层（CompassMath / LocationResolver / RecordQueryBuilder）
            // 本身不依赖任何 Android 类，此处只是兜底。
            isReturnDefaultValues = true
        }
    }

    // Room 导出的 schema JSON 同时作为 androidTest 的 assets，
    // 供 MigrationTestHelper 读取（design.md §11）。
    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")

        // 测试夹具（record/scene 构造器、固定时区与时间戳）两个测试源集共用。
        // JVM 测试验证「坐标语义」，androidTest 验证「这些语义在真实 SQLite 上的表现」，
        // 两边必须用同一套夹具，否则断言的前提就不一致了。
        getByName("test").java.srcDir("$projectDir/src/testFixtures/java")
        getByName("androidTest").java.srcDir("$projectDir/src/testFixtures/java")

        // 注意：androidTest 里的 Kotlin 反引号方法名**不能带空格**。
        // DEX 040（Android 11）才允许名字含空格，本项目 minSdk 24 会 dex 到 037，
        // dexBuilderDebugAndroidTest 直接报 "Space characters in SimpleName"。
        // src/test 的 JVM 单测不经过 D8，同样带空格的名字在那里完全正常——
        // 这个不对称最容易踩空：单测跑绿了，androidTest 才编译失败。
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
