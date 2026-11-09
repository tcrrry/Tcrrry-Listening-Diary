import java.util.zip.ZipFile
plugins { java }
repositories { mavenCentral(); google() }
val sdkRoot = providers.gradleProperty("androidSdk").orElse(providers.environmentVariable("ANDROID_SDK_ROOT")).orElse(providers.environmentVariable("ANDROID_HOME"))
val androidJar = file("${sdkRoot.get()}/platforms/android-37.0/android.jar")
val sdkJar = file("lib/ampp-plugin-api-v1.jar")
dependencies {
    compileOnly(files(androidJar, sdkJar))
    testImplementation("junit:junit:4.13.2")
    testImplementation(files(androidJar, sdkJar))
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("org.json:json:20240303")
}
java { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }
tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
tasks.withType<Test>().configureEach {
    jvmArgs("--add-opens=java.base/java.io=ALL-UNNAMED", "--add-opens=java.base/java.lang=ALL-UNNAMED", "--add-opens=java.base/java.nio=ALL-UNNAMED")
    systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
    providers.gradleProperty("diaryPreviewDir").orNull?.let { systemProperty("diary.preview.dir", it) }
    providers.gradleProperty("diaryPreviewArt").orNull?.let { systemProperty("diary.preview.art", it) }
    doFirst {
        val extracted = layout.buildDirectory.dir("test-aar").get().asFile
        extracted.mkdirs()
        val jars = configurations.testRuntimeClasspath.get().files.filter { it.extension == "aar" }.map { aar ->
            val target = file("$extracted/${aar.nameWithoutExtension}-classes.jar")
            ZipFile(aar).use { zip -> zip.getInputStream(zip.getEntry("classes.jar")).use { input -> target.outputStream().use { input.copyTo(it) } } }
            target
        }
        classpath += files(jars)
    }
}
tasks.withType<AbstractArchiveTask>().configureEach { isPreserveFileTimestamps = false; isReproducibleFileOrder = true }
tasks.jar { exclude("**/ImmersiveDiaryPanel*", "**/NativeDiaryPanel*", "**/DiaryArtwork*", "**/DiaryMotion*", "**/DiaryGlass*", "**/DailyRows*") }
val dexDir = layout.buildDirectory.dir("dex")
val dex = tasks.register<JavaExec>("dex") {
    dependsOn(tasks.jar)
    classpath = files("${sdkRoot.get()}/build-tools/37.0.0/lib/d8.jar")
    mainClass = "com.android.tools.r8.D8"
    inputs.file(tasks.jar.flatMap { it.archiveFile }); outputs.dir(dexDir)
    doFirst { dexDir.get().asFile.mkdirs() }
    args("--release", "--min-api", "31", "--lib", androidJar.absolutePath,
        "--classpath", sdkJar.absolutePath, "--output", dexDir.get().asFile.absolutePath,
        tasks.jar.get().archiveFile.get().asFile.absolutePath)
}
val codeJar = tasks.register<Zip>("codeJar") {
    dependsOn(dex); from(dexDir) { include("classes*.dex") }
    archiveFileName = "code.jar"; destinationDirectory = layout.buildDirectory.dir("plugin-code")
}
tasks.register<Zip>("pluginZip") {
    dependsOn(codeJar, tasks.test)
    from(codeJar.flatMap { it.archiveFile }); from("plugin.json")
    from(listOf("README.md", "THIRD_PARTY_NOTICES.md")) { into("assets/documentation") }
    from("LICENSE") { into("assets/licenses") }
    // Native UI; do not package or initialize the earlier WebView report.
    archiveFileName = "Listening-Diary-Collector-v1.0.zip"
    destinationDirectory = layout.buildDirectory.dir("dist")
}
