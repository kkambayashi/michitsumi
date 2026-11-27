// :core — Android に依存しない純 Kotlin モジュール。
// パーツのデータ形式、軌跡→パーツ変換、dyn4j の物理ラッパ、サンプルデータ、SVG 描画を置く。
// アプリとサーバの両方から参照される。JVM のユニットテストだけで検証できる。
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.kotlinx.serialization.json)
    // 物理エンジンは :core の中だけで使う（型は StackWorld の外に出さない。iOS 版でエンジンを替えるため）
    implementation(libs.boks2d)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}
