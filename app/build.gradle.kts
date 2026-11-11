import java.util.Properties
plugins { id("com.android.application"); id("org.jetbrains.kotlin.plugin.compose") }
val appVersion = Properties().apply { rootProject.file("version.properties").inputStream().use(::load) }
android {
    namespace = "io.github.sodarx.biliplay"
    compileSdk = 37
    defaultConfig {
        applicationId = "io.github.sodarx.biliplay"
        minSdk = 29
        targetSdk = 37
        versionCode = appVersion.getProperty("versionCode").toInt()
        versionName = appVersion.getProperty("versionName")
    }
    flavorDimensions += "environment"
    productFlavors {
        create("development") {
            dimension = "environment"
            applicationIdSuffix = ".development"
            buildConfigField("boolean", "DEVELOPMENT_BUILD", "true")
        }
        create("production") {
            dimension = "environment"
            buildConfigField("boolean", "DEVELOPMENT_BUILD", "false")
        }
    }
    signingConfigs.getByName("debug") {
        storeFile = file(providers.environmentVariable("BILIPLAY_DEVELOPMENT_KEYSTORE").orElse(
            "${System.getProperty("user.home")}/.biliplay-android-signing/development/debug.jks"
        ).get())
    }
    buildTypes { release { isMinifyEnabled = true; isShrinkResources = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") } }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    lint { abortOnError = true; checkReleaseBuilds = true; fatal.add("UnusedResources"); fatal.add("UnusedIds") }
}
abstract class VerifyBuildMode : DefaultTask() {
    @get:Input abstract val requested: Property<String>
    @get:Input abstract val expected: Property<String>
    @TaskAction fun verify() {
        check(requested.get() == expected.get()) { "必须显式传入 -PDevelopmentBuild=${expected.get()}" }
    }
}
val verifyDevelopmentBuild=tasks.register<VerifyBuildMode>("verifyDevelopmentBuild") {
    requested.set(providers.gradleProperty("DevelopmentBuild").orElse("unspecified"));expected.set("true")
}
val verifyProductionBuild=tasks.register<VerifyBuildMode>("verifyProductionBuild") {
    requested.set(providers.gradleProperty("DevelopmentBuild").orElse("unspecified"));expected.set("false")
}
tasks.configureEach {
    if (name.matches(Regex("(assemble|bundle|test|lint|compile|package).*Development.*"))) dependsOn(verifyDevelopmentBuild)
    if (name.matches(Regex("(assemble|bundle|package).*Production.*"))) dependsOn(verifyProductionBuild)
}
dependencies {
    implementation("androidx.webkit:webkit:1.18.0")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation(project(":core"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui:1.12.1")
    implementation("androidx.compose.foundation:foundation:1.12.1")
    implementation("androidx.compose.material3:material3:1.5.0-rc01")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")
}
