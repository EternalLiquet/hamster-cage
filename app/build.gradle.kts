plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.hamstercage"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.hamstercage"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-shell"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".preview"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    lint { abortOnError = true; checkReleaseBuilds = true }
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
}

class RoomSchemaArguments(@get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) val schemaDir: File) : CommandLineArgumentProvider {
    override fun asArguments() = listOf("room.schemaLocation=${schemaDir.path}")
}
ksp { arg(RoomSchemaArguments(file("schemas"))) }

tasks.register("writeDependencyInventory") {
    doLast {
        val coordinates = configurations.getByName("debugRuntimeClasspath").resolvedConfiguration.resolvedArtifacts
            .filter { it.id.componentIdentifier is org.gradle.api.artifacts.component.ModuleComponentIdentifier }
            .map { it.moduleVersion.id }.distinctBy { "${it.group}:${it.name}:${it.version}" }
            .sortedBy { "${it.group}:${it.name}" }
        val output = layout.buildDirectory.file("reports/runtime-dependencies.json").get().asFile
        output.parentFile.mkdirs()
        output.writeText(coordinates.joinToString(",", "[", "]") {
            "{\"name\":\"${it.group}:${it.name}\",\"version\":\"${it.version}\"}"
        })
    }
}

dependencies {
    implementation(project(":core-domain"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.preview)
    implementation(libs.activity.compose)
    implementation(libs.play.basement)
    // Play services transitively requests legacy Fragment; Activity Result requires a supported version.
    implementation(libs.fragment)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.play.services)
    implementation(libs.play.location)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.test)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    // 3.7 replaces removed reflective InputManager APIs, including on Android 17.
    androidTestImplementation(libs.espresso.core)
}
