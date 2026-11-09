import java.util.Properties
plugins { id("com.android.application"); id("org.jetbrains.kotlin.plugin.compose") }
val signing = Properties().apply { rootProject.file(".private/signing.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }
android {
    namespace = "com.tcrrry.diary"
    compileSdk = 37
    defaultConfig { applicationId = "com.tcrrry.listeningdiary.app"; minSdk = 31; targetSdk = 37; versionCode = 141; versionName = "1.0" }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    // The published source never contains the release signing key. Debug builds use the standard debug key;
    // release builds are unsigned unless the maintainer provides their private signing configuration.
    signingConfigs { if (rootProject.file(".private/diary-preview.keystore").exists()) create("preview") { storeFile=rootProject.file(".private/diary-preview.keystore"); storePassword=signing.getProperty("storePassword"); keyAlias="diary-preview"; keyPassword=signing.getProperty("keyPassword") } }
    buildTypes { getByName("release") { isMinifyEnabled=false; signingConfig=signingConfigs.findByName("preview") } }
    testOptions { unitTests.isIncludeAndroidResources=true }
}
dependencies {
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("org.jetbrains.compose.foundation:foundation:1.12.0")
    implementation("org.jetbrains.compose.ui:ui:1.12.0")
    implementation("org.jetbrains.compose.ui:ui-graphics:1.12.0")
    implementation("org.jetbrains.compose.material3:material3:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("io.github.kyant0:backdrop:2.0.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.compose.ui:ui-test-junit4:1.12.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.12.0")
}
tasks.withType<Test>().configureEach {
    providers.gradleProperty("appleImportFixture").orNull?.let { systemProperty("diary.apple.fixture",it) }
    jvmArgs("--add-opens=java.base/java.io=ALL-UNNAMED", "--add-opens=java.base/java.lang=ALL-UNNAMED")
    systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
    providers.gradleProperty("diaryPreviewDir").orNull?.let { systemProperty("diary.preview.dir",it) }
    providers.gradleProperty("diaryPreviewTemplate").orNull?.let { systemProperty("diary.preview.template",it) }
    providers.gradleProperty("diaryPreviewArt").orNull?.let { systemProperty("diary.preview.art",it) }
    providers.gradleProperty("diaryPreviewArtist").orNull?.let { systemProperty("diary.preview.artist",it); systemProperty("diary.artist.fixture", "$it/artist5.html") }
}
