import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.jvm.JvmLibrary
import org.gradle.language.base.artifact.SourcesArtifact
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSetTree
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    `maven-publish`
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    alias(libs.plugins.kotlinx.atomicfu)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/pebble-dev/libpebblecommon")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}

room {
    schemaDirectory("schema")
}

android {
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    namespace = "io.rebble.libpebblecommon"
    defaultConfig {
        minSdk = 26
        lint.targetSdk = compileSdk
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.valueOf("VERSION_${libs.versions.jvm.toolchain.get()}")
        targetCompatibility = JavaVersion.valueOf("VERSION_${libs.versions.jvm.toolchain.get()}")
    }

    kotlin {
        jvmToolchain(libs.versions.jvm.toolchain.get().toInt())
    }

    buildTypes {
        release {
            consumerProguardFiles("consumer-rules.pro")
        }
    }
}

tasks.register("buildFrameworkLibPebbleSwift", BuildSwiftFramework::class) {
    group = "build"
    description = "Builds the Swift framework for libpebble-swift"
}

// Non-mac machines cannot build iOS targets, so disable them
val enableIosTarget = System.getProperty("os.name").contains("mac", ignoreCase = true)

kotlin {
    targets.configureEach {
        compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions {
                    freeCompilerArgs.add("-Xexpect-actual-classes")
                }
            }
        }
    }

    androidTarget {
        publishLibraryVariants("release", "debug")
        instrumentedTestVariant {
            sourceSetTree.set(KotlinSourceSetTree.test)
        }
    }

    jvm()

    val xcodeExists by lazy { // Define xcodeExists and xcodeDir here to be accessible by iOS targets
        project.providers.exec {
            isIgnoreExitValue = true
            commandLine("which", "xcode-select")
        }.result.get().exitValue == 0
    }
    val xcodeDir by lazy {
        if (xcodeExists) {
            project.providers.exec {
                commandLine("xcode-select", "-p")
            }.standardOutput.asText.get().trim()
        } else {
            ""
        }
    }

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { target ->
        if (enableIosTarget) {
            val osName = when (target.name) {
                "iosX64" -> "iphonesimulator"
                "iosArm64" -> "iphoneos"
                "iosSimulatorArm64" -> "iphonesimulator"
                else -> throw IllegalStateException("Unknown target: ${target.name}")
            }
            val dir = tasks.getByName("buildFrameworkLibPebbleSwift").outputs.files.singleFile.resolve(osName)
            target.binaries.framework {
                baseName = "libpebble3"
            }
            target.compilations.getByName("main") {
                val libPebbleSwift by cinterops.creating {
                    compilerOpts("-framework", "LibPebbleSwift", "-F" + dir.absolutePath)
                }
            }
            target.binaries.all {
                linkerOpts("-framework", "LibPebbleSwift", "-F" + dir.absolutePath)
                if (xcodeExists) {
                    linkerOpts("-L$xcodeDir/Toolchains/XcodeDefault.xctoolchain/usr/lib/swift/$osName")
                }
            }
        }
    }

    sourceSets {
        all {
            languageSettings {
                optIn("kotlin.ExperimentalUnsignedTypes")
                optIn("kotlin.ExperimentalStdlibApi")
                optIn("kotlin.concurrent.atomics.ExperimentalAtomicApi")
                optIn("kotlin.uuid.ExperimentalUuidApi")
                optIn("kotlinx.cinterop.ExperimentalForeignApi")
                optIn("kotlin.time.ExperimentalTime")
                optIn("kotlinx.coroutines.FlowPreview")
                optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
                optIn("kotlinx.serialization.ExperimentalSerializationApi")
                optIn("kotlinx.serialization.ExperimentalSerializationApi")
                optIn("kotlinx.cinterop.BetaInteropApi")
            }
        }
        commonMain {
            kotlin {
                // Include ksp-generated common code (from our :blobdgen processor)
                srcDir("build/generated/ksp/metadata/commonMain/kotlin")
            }
        }
        commonMain.dependencies {
            api(libs.coroutines)
            implementation(libs.serialization)
            implementation(libs.kermit)
            implementation(libs.room.runtime)
            api(libs.room.paging)
            implementation(libs.sqlite.bundled)
            api(libs.kotlinx.io.core)
            implementation(libs.kotlinx.io.okio)
            implementation(libs.okio)
            implementation(libs.kable)
            implementation(libs.kmpio)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.cio)
            implementation(libs.ktor.server.websockets)
            api(libs.kotlinx.datetime)
            implementation(libs.koin.core)
            implementation(compose.ui)
            implementation(project(":blobannotations"))
            implementation(libs.settings)
            implementation(libs.settings.serialization)
            implementation(libs.uri)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.coroutines.test)
        }

        androidMain.dependencies {
            implementation(libs.androidx.core.ktx)
            implementation(libs.pebblekit)
        }

        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.ktor.websockets)
        }

        jvmMain.dependencies {
        }

        jvmTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlin.test.junit)
            implementation(libs.ktor.websockets)
            implementation(libs.ktor.cio)
            implementation(libs.ktor.client.okhttp)
        }

        androidInstrumentedTest.dependencies {
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.rules)
            implementation(libs.androidx.monitor)
        }
    }
}

// Otherwise it doesn't trigger our blobdbgen processor when compiling code
// https://github.com/google/ksp/issues/567
tasks.withType<KotlinCompilationTask<*>>().all {
    if (name != "kspCommonMainKotlinMetadata") {
        dependsOn("kspCommonMainKotlinMetadata")
    }
}
afterEvaluate {
    tasks.named("kspDebugKotlinAndroid") {
        dependsOn("kspCommonMainKotlinMetadata")
    }
    tasks.named("kspReleaseKotlinAndroid") {
        dependsOn("kspCommonMainKotlinMetadata")
    }

    if (enableIosTarget) {
        tasks.named("kspKotlinIosArm64") {
            dependsOn("kspCommonMainKotlinMetadata")
        }
        tasks.named("kspKotlinIosX64") {
            dependsOn("kspCommonMainKotlinMetadata")
        }
        tasks.named("kspKotlinIosSimulatorArm64") {
            dependsOn("kspCommonMainKotlinMetadata")
        }
        tasks.named("cinteropLibPebbleSwiftIosArm64") {
            dependsOn("buildFrameworkLibPebbleSwift")
        }
        tasks.named("cinteropLibPebbleSwiftIosX64") {
            dependsOn("buildFrameworkLibPebbleSwift")
        }
        tasks.named("cinteropLibPebbleSwiftIosSimulatorArm64") {
            dependsOn("buildFrameworkLibPebbleSwift")
        }
        tasks.named("compileKotlinIosArm64") {
            dependsOn("buildFrameworkLibPebbleSwift")
        }
        tasks.named("compileKotlinIosX64") {
            dependsOn("buildFrameworkLibPebbleSwift")
        }
        tasks.named("compileKotlinIosSimulatorArm64") {
            dependsOn("buildFrameworkLibPebbleSwift")
        }
    }
}

dependencies {
//    add("kspCommonMainMetadata", libs.room.compiler)
//    add("kspJvm", libs.room.compiler)
    add("kspCommonMainMetadata", project(":blobdbgen"))
    add("kspAndroid", libs.room.compiler)

    if (enableIosTarget) {
        add("kspIosX64", libs.room.compiler)
        add("kspIosArm64", libs.room.compiler)
        add("kspIosSimulatorArm64", libs.room.compiler)
    }
}

/*
if (Os.isFamily(Os.FAMILY_MAC)) {
    val iosSimulatorFatFramework by tasks.registering(PlatformFatFramework::class) {
        onlyIf {
            Os.isFamily(Os.FAMILY_MAC)
        }
        val iosX64Task = (kotlin.targets.getByName("iosX64") as KotlinNativeTarget).binaries.getFramework("RELEASE")
        val iosSimulatorArm64Task = (kotlin.targets.getByName("iosSimulatorArm64") as KotlinNativeTarget).binaries.getFramework("RELEASE")
        dependsOn(iosX64Task.linkTask)
        dependsOn(iosSimulatorArm64Task.linkTask)
        platform.set("simulator")

        inputFrameworks.setFrom(project.files(iosX64Task.outputFile, iosSimulatorArm64Task.outputFile))
        inputFrameworkDSYMs.setFrom(project.files(iosX64Task.outputFile.path+".dSYM", iosX64Task.outputFile.path+".dSYM"))
    }

    val iosDeviceFatFramework by tasks.registering(PlatformFatFramework::class) {
        onlyIf {
            Os.isFamily(Os.FAMILY_MAC)
        }
        val iosTask = (kotlin.targets.getByName("ios") as KotlinNativeTarget).binaries.getFramework("RELEASE")
        dependsOn(iosTask.linkTask)
        platform.set("device")

        inputFrameworks.setFrom(project.files(iosTask.outputFile))
        inputFrameworkDSYMs.setFrom(project.files(iosTask.outputFile.path+".dSYM"))
    }

    val assembleXCFramework by tasks.registering {
        onlyIf {
            org.apache.tools.ant.taskdefs.condition.Os.isFamily(org.apache.tools.ant.taskdefs.condition.Os.FAMILY_MAC)
        }
        val deviceTask = tasks.getByName("iosDeviceFatFramework")
        val simulatorTask = tasks.getByName("iosSimulatorFatFramework")
        dependsOn(deviceTask)
        dependsOn(simulatorTask)
        outputs.dir(layout.buildDirectory.dir("xcframework")).withPropertyName("outputDir")

        val outputPath = layout.buildDirectory.dir("xcframework").get().asFile.path + "/libpebblecommon.xcframework"

        doLast {
            delete(outputPath)
            exec {
                commandLine (
                    "xcodebuild", "-create-xcframework",
                    "-framework", deviceTask.outputs.files.first { it.name == "libpebblecommon.framework" }.path,
                    "-debug-symbols", deviceTask.outputs.files.first { it.name == "libpebblecommon.framework.dSYM" }.path,
                    "-framework", simulatorTask.outputs.files.first { it.name == "libpebblecommon.framework" }.path,
                    "-debug-symbols", simulatorTask.outputs.files.first { it.name == "libpebblecommon.framework.dSYM" }.path,
                    "-output", outputPath
                )
            }
        }
    }
}
*/
/*project.afterEvaluate {
    tasks.withType(PublishToMavenRepository::class.java) {
        onlyIf {
            !publication.name.contains("ios")
        }
    }
    tasks.withType(Jar::class.java) {
        onlyIf {
            !name.contains("ios")
        }
    }
}*/

abstract class BuildSwiftFramework : DefaultTask() {
    @Inject
    abstract fun getExecOperations(): ExecOperations

    @get:InputFiles
    val inputFiles = project.objects.fileCollection().from(project.fileTree("libpebble-swift") {
        include("LibPebbleSwift.xcodeproj/project.pbxproj")
        include("LibPebbleSwift/*.swift")
        include("LibPebbleSwift/*.h")
        include("LibPebbleSwift/*.m")
    })

    @get:OutputDirectory
    val outputDir =
        project.objects.directoryProperty().convention(project.layout.buildDirectory.dir("libpebble-swift/"))

    @TaskAction
    fun buildSwiftFramework() {
        logging.captureStandardOutput(LogLevel.INFO)
        logging.captureStandardError(LogLevel.ERROR)
        getExecOperations().exec {
            commandLine(
                "xcodebuild", "-project", "LibPebbleSwift.xcodeproj",
                "-scheme", "LibPebbleSwift",
                "-configuration", "Release",
                "-sdk", "iphoneos",
                "CONFIGURATION_BUILD_DIR=${outputDir.get().asFile.resolve("iphoneos/").absolutePath}",
                "ARCHS=arm64",
                "SUPPORTS_MACCATALYST=NO",
            )
            workingDir = project.file("libpebble-swift")
            standardOutput = System.out
            errorOutput = System.err
        }
        getExecOperations().exec {
            commandLine(
                "xcodebuild", "-project", "LibPebbleSwift.xcodeproj",
                "-scheme", "LibPebbleSwift",
                "-configuration", "Release",
                "-sdk", "iphonesimulator",
                "CONFIGURATION_BUILD_DIR=${outputDir.get().asFile.resolve("iphonesimulator/").absolutePath}",
            )
            workingDir = project.file("libpebble-swift")
            standardOutput = System.out
            errorOutput = System.err
        }
    }
}

abstract class PlatformFatFramework : DefaultTask() {
    @get:Input
    abstract val platform: Property<String>

    @get:InputFiles
    val inputFrameworks = project.objects.fileCollection()

    @get:InputFiles
    val inputFrameworkDSYMs = project.objects.fileCollection()

    @Internal
    val platformOutputDir: Provider<Directory> =
        platform.map { project.layout.buildDirectory.dir("platform-fat-framework/${it}").get() }

    @get:OutputDirectory
    val outputDir = project.objects.directoryProperty().convention(platformOutputDir)

    @get:OutputDirectories
    val outputFiles: Provider<Array<File>> = platformOutputDir.map {
        arrayOf(
            it.asFile.toPath().resolve(inputFrameworks.files.first().name).toFile(),
            it.asFile.toPath().resolve(inputFrameworkDSYMs.files.first().name).toFile()
        )
    }

    private fun copyFramework() {
        val file = inputFrameworks.files.first()
        project.copy {
            from(file)
            into(outputDir.get().asFile.toPath().resolve(file.name))
        }
    }

    private fun copyFrameworkDSYM() {
        val file = inputFrameworkDSYMs.first()
        project.copy {
            from(file)
            into(outputDir.get().asFile.toPath().resolve(file.name))
        }
    }

    private fun lipoMergeFrameworks() {
        val inputs = mutableListOf<String>()
        inputFrameworks.forEach {
            inputs.add(it.toPath().resolve("libpebble3").toString())
        }
        val out = outputDir.get().asFile.toPath()
            .resolve(inputFrameworks.files.first().name + "/libpebble3").toString()
        project.exec {
            commandLine("lipo", "-create", *inputs.toTypedArray(), "-output", out)
        }
    }

    private fun lipoMergeFrameworkDSYMs() {
        val inputs = mutableListOf<String>()
        inputFrameworkDSYMs.forEach {
            inputs.add(it.toPath().resolve("Contents/Resources/DWARF/libpebble3").toString())
        }
        val out = outputDir.get().asFile.toPath()
            .resolve(inputFrameworkDSYMs.files.first().name + "/Contents/Resources/DWARF/libpebble3").toString()
        project.exec {
            commandLine("lipo", "-create", *inputs.toTypedArray(), "-output", out)
        }
    }

    @TaskAction
    fun createPlatformFatFramework() {
        copyFramework()
        copyFrameworkDSYM()
        lipoMergeFrameworks()
        lipoMergeFrameworkDSYMs()
    }
}

// Keep provenance for every JVM runtime archive before fat-JAR duplicate handling
// discards colliding META-INF/L* and NOTICE entries.
val dependencyNoticesDir = layout.buildDirectory.dir("reports/dependency-licenses")
val dependencySourcesDir = layout.buildDirectory.dir("reports/dependency-sources")
val jvmRuntimeArtifacts = configurations.named("jvmRuntimeClasspath")
tasks.register("collectJvmDependencyLicenses") {
    val artifacts = jvmRuntimeArtifacts.map { it.resolvedConfiguration.resolvedArtifacts }
    inputs.files(jvmRuntimeArtifacts)
    outputs.dir(dependencyNoticesDir)

    doLast {
        val output = dependencyNoticesDir.get().asFile
        output.deleteRecursively()
        output.mkdirs()
        val jsonEscape: (String) -> String = {
            it.replace("\\", "\\\\").replace("\"", "\\\"")
        }
        val records = artifacts.get().sortedBy {
            "${it.moduleVersion.id.group}:${it.name}:${it.moduleVersion.id.version}"
        }.filter { it.id.componentIdentifier is ModuleComponentIdentifier }.map { artifact ->
            val id = artifact.moduleVersion.id
            val coordinate = "${id.group}:${id.name}:${id.version}"
            val prefix = "${id.group}.${id.name}-${id.version}".replace(Regex("[^A-Za-z0-9._-]"), "_")
            val notices = mutableListOf<String>()
            ZipFile(artifact.file).use { zip ->
                zip.entries().asSequence().filter { entry ->
                    !entry.isDirectory &&
                        entry.name.split('/').last().matches(
                            Regex("(?i)(LICENSE|LICENCE|NOTICE|COPYING)([._-].*)?")
                        ) &&
                        (entry.name.startsWith("META-INF/") || !entry.name.contains('/'))
                }.sortedBy { it.name }.forEach { entry ->
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                        .joinToString("") { "%02x".format(it) }
                    val name = "$prefix-${entry.name.replace('/', '_')}"
                    val target = output.resolve(name)
                    check(!target.exists()) { "Duplicate dependency notice name: $name" }
                    target.writeBytes(bytes)
                    notices += """{"file":"${jsonEscape(name)}","entry":"${jsonEscape(entry.name)}","sha256":"$digest"}"""
                }
            }
            if (notices.isEmpty()) {
                // The JVM archives of many KMP publishers omit license files.
                // Resolve their exact POM through Gradle so strict verification
                // authenticates the declared grant instead of guessing by group.
                val pom = configurations.detachedConfiguration(
                    dependencies.create("$coordinate@pom")
                ).singleFile
                val parser = DocumentBuilderFactory.newInstance().apply {
                    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                    setFeature("http://xml.org/sax/features/external-general-entities", false)
                    setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                    isXIncludeAware = false
                    isExpandEntityReferences = false
                }.newDocumentBuilder()
                val licenses = parser.parse(pom).getElementsByTagName("license")
                val grants = (0 until licenses.length).map { licenses.item(it) as org.w3c.dom.Element }
                    .map {
                        it.getElementsByTagName("name").item(0)?.textContent.orEmpty().trim() to
                            it.getElementsByTagName("url").item(0)?.textContent.orEmpty().trim()
                    }
                if (grants.size == 1 &&
                    grants.single().first.matches(Regex("(?i)(the )?apache( software)? license(, version 2\\.0)?|apache-2\\.0")) &&
                    (grants.single().second.isEmpty() ||
                        grants.single().second.matches(Regex("https?://www\\.apache\\.org/licenses/LICENSE-2\\.0(\\.txt)?")))
                ) {
                    val name = "$prefix-POM-LICENSE.txt"
                    val text = "Artifact: $coordinate\nLicense: ${grants.single().first}\n" +
                        "Publisher's license URL: ${grants.single().second}\n" +
                        "Declared in: $coordinate@pom (SHA-256: " +
                        MessageDigest.getInstance("SHA-256").digest(pom.readBytes())
                            .joinToString("") { "%02x".format(it) } + ")\n" +
                        "Full Apache-2.0 license text: " +
                        "androidx.annotation.annotation-jvm-1.9.1-META-INF_androidx_annotation_annotation_LICENSE.txt\n"
                    output.resolve(name).writeText(text)
                    val hash = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
                        .joinToString("") { "%02x".format(it) }
                    notices += """{"file":"$name","entry":"$coordinate@pom/licenses","sha256":"$hash"}"""
                }
            }
            if (coordinate == "net.java.dev.jna:jna:5.18.1") {
                val archive = dependencySourcesDir.get().file("jna-5.18.1.tar.gz").asFile
                val grant = tarTree(archive).matching {
                    include("*/native/libffi/LICENSE")
                }.singleFile.readBytes()
                val name = "$prefix-native_libffi_LICENSE"
                output.resolve(name).writeBytes(grant)
                val hash = MessageDigest.getInstance("SHA-256").digest(grant)
                    .joinToString("") { "%02x".format(it) }
                notices += """{"file":"$name","entry":"native/libffi/LICENSE","sha256":"$hash"}"""
            }
            """{"artifact":"${jsonEscape(coordinate)}","notices":[${notices.joinToString(",")}]}"""
        }
        val cargoNotices = dependencySourcesDir.get().file("cargo-notice-records.json").asFile
        val cargoRecords = groovy.json.JsonSlurper().parse(cargoNotices) as List<*>
        val cargoDirectory = dependencySourcesDir.get().dir("cargo-notices").asFile
        cargoRecords.forEach { entry ->
            val record = entry as Map<*, *>
            (record["notices"] as List<*>).forEach { notice ->
                val noticeRecord = notice as Map<*, *>
                val name = noticeRecord["file"] as String
                check(name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._+-]*"))) {
                    "Unsafe Cargo notice filename: $name"
                }
                val bytes = cargoDirectory.resolve(name).readBytes()
                val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") { "%02x".format(it) }
                check(hash == noticeRecord["sha256"]) { "Cargo notice hash mismatch: $name" }
                output.resolve(name).writeBytes(bytes)
            }
        }
        val mpl = cargoDirectory.resolve("MPL-2.0.txt").readBytes()
        val mplHash = MessageDigest.getInstance("SHA-256").digest(mpl)
            .joinToString("") { "%02x".format(it) }
        output.resolve("MPL-2.0.txt").writeBytes(mpl)
        output.resolve("dependencies.json").writeText(
            """{"schema_version":1,"license_texts":[{"file":"MPL-2.0.txt","sha256":"$mplHash"}],"artifacts":[""" +
                (records + cargoRecords.map { groovy.json.JsonOutput.toJson(it) }).joinToString(",") +
                "]}\n"
        )
    }
}
val nativeSourcesLock = rootProject.file("gradle/native-sources.json")

tasks.register("collectJvmDependencySources") {
    val artifacts = jvmRuntimeArtifacts.map { it.resolvedConfiguration.resolvedArtifacts }
    inputs.files(jvmRuntimeArtifacts)
    inputs.file(nativeSourcesLock)
    inputs.file(rootProject.file("gradle/source-tree-sha256.py"))
    inputs.file(rootProject.file("gradle/collect-cargo-sources.py"))
    outputs.dir(dependencySourcesDir)

    doLast {
        val output = dependencySourcesDir.get().asFile
        output.deleteRecursively()
        output.mkdirs()
        val modules = artifacts.get().mapNotNull {
            it.id.componentIdentifier as? ModuleComponentIdentifier
        }.distinct()
        val results = dependencies.createArtifactResolutionQuery()
            .forComponents(modules)
            .withArtifacts(JvmLibrary::class.java, SourcesArtifact::class.java)
            .execute()
        val records = results.resolvedComponents.sortedBy { it.id.displayName }.map { component ->
            val id = component.id as ModuleComponentIdentifier
            val coordinate = "${id.group}:${id.module}:${id.version}"
            val files = component.getArtifacts(SourcesArtifact::class.java)
                .filterIsInstance<ResolvedArtifactResult>().sortedBy { it.file.name }
                .map { source ->
                    val name = "${id.group}.${id.module}-${id.version}-${source.file.name}"
                        .replace(Regex("[^A-Za-z0-9._-]"), "_")
                    val dest = output.resolve(name)
                    check(!dest.exists()) { "Duplicate source archive: $name" }
                    source.file.copyTo(dest)
                    val hash = MessageDigest.getInstance("SHA-256").digest(dest.readBytes())
                        .joinToString("") { "%02x".format(it) }
                    """{"file":"$name","sha256":"$hash"}"""
                }
            """{"artifact":"$coordinate","sources":[${files.joinToString(",")}]}"""
        }
        val lockBytes = nativeSourcesLock.readBytes()
        val lock = groovy.json.JsonSlurper().parse(lockBytes.inputStream()) as Map<*, *>
        check(lock["schema_version"] == 1) { "Unsupported native source lock" }
        val nativeArchives = lock["archives"] as List<*>
        val names = mutableSetOf<String>()
        val observed = mutableListOf<Map<String, Any?>>()
        nativeArchives.forEach { item ->
            val archive = item as Map<*, *>
            val name = archive["file"] as String
            val url = archive["url"] as String
            val expected = archive["sha256"] as? String
            val expectedTree = archive["tree_sha256"] as? String
            check(name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*")) && names.add(name)) {
                "Unsafe or duplicate native source filename: $name"
            }
            check(url.startsWith("https://") &&
                listOfNotNull(expected, expectedTree).size == 1 &&
                (expected ?: expectedTree)!!.matches(Regex("[0-9a-f]{64}"))
            ) { "Invalid native source URL or hash for $name" }
            val dest = output.resolve(name)
            val connection = URL(url).openConnection().apply {
                connectTimeout = 30_000
                readTimeout = 180_000
            }
            connection.getInputStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
            val actual = MessageDigest.getInstance("SHA-256").digest(dest.readBytes())
                .joinToString("") { "%02x".format(it) }
            if (expectedTree != null) {
                val process = ProcessBuilder(
                    "python3", rootProject.file("gradle/source-tree-sha256.py").absolutePath,
                    dest.absolutePath
                ).redirectErrorStream(true).start()
                val tree = process.inputStream.bufferedReader().use { it.readText().trim() }
                check(process.waitFor() == 0 && tree == expectedTree) {
                    "Native source tree hash mismatch: $name ($tree)"
                }
            } else {
                check(actual == expected) { "Native source archive hash mismatch: $name" }
            }
            observed += archive.entries.associate { (key, value) -> key.toString() to value } +
                ("sha256" to actual)
        }
        output.resolve("native-sources.json").writeText(
            groovy.json.JsonOutput.prettyPrint(
                groovy.json.JsonOutput.toJson(mapOf("schema_version" to 1, "archives" to observed))
            ) + "\n"
        )
        val cargoProcess = ProcessBuilder(
            "python3", rootProject.file("gradle/collect-cargo-sources.py").absolutePath,
            output.resolve("kable-0.42.0.tar.gz").absolutePath, output.absolutePath
        ).redirectErrorStream(true).start()
        val cargoOutput = cargoProcess.inputStream.bufferedReader().use { it.readText() }
        check(cargoProcess.waitFor() == 0) { "Cargo source collection failed: $cargoOutput" }
        val cargoRecords = groovy.json.JsonSlurper().parse(
            output.resolve("cargo-source-records.json")
        ) as List<*>
        check(cargoRecords.isNotEmpty()) { "No locked Cargo sources collected" }
        output.resolve("sources.json").writeText(
            """{"schema_version":1,"artifacts":[""" +
                (records + cargoRecords.map { groovy.json.JsonOutput.toJson(it) }).joinToString(",") +
                "]}\n"
        )
    }
}
tasks.named("collectJvmDependencyLicenses") {
    dependsOn("collectJvmDependencySources")
}


tasks.register("verifyJvmDependencyLicenses") {
    dependsOn("collectJvmDependencyLicenses", "collectJvmDependencySources")
    doLast {
        val notices = groovy.json.JsonSlurper().parse(
            dependencyNoticesDir.get().file("dependencies.json").asFile
        ) as Map<*, *>
        val sources = groovy.json.JsonSlurper().parse(
            dependencySourcesDir.get().file("sources.json").asFile
        ) as Map<*, *>
        val withSources = (sources["artifacts"] as List<*>).associate {
            val item = it as Map<*, *>
            item["artifact"] to (item["sources"] as List<*>).isNotEmpty()
        }
        val withoutGrant = (notices["artifacts"] as List<*>).map { it as Map<*, *> }
            .filter { (it["notices"] as List<*>).isEmpty() }
            .map { it["artifact"] }
        val withoutSource = (notices["artifacts"] as List<*>).map { it as Map<*, *> }
            .filter { withSources[it["artifact"]] != true }
            .map { it["artifact"] }
        val nativeLock = groovy.json.JsonSlurper().parse(
            dependencySourcesDir.get().file("native-sources.json").asFile
        ) as Map<*, *>
        check(nativeLock["schema_version"] == 1) { "Unsupported native source lock" }
        val nativePins = (nativeLock["archives"] as List<*>).map { it as Map<*, *> }
            .groupBy { it["artifact"] }
        val nativeWithoutSource = jvmRuntimeArtifacts.get().resolvedConfiguration.resolvedArtifacts
            .filter { it.id.componentIdentifier is ModuleComponentIdentifier }
            .mapNotNull { artifact ->
                val id = artifact.moduleVersion.id
                val coordinate = "${id.group}:${id.name}:${id.version}"
                val hasNativeBinary = ZipFile(artifact.file).use { zip ->
                    zip.entries().asSequence().any {
                        it.name.lowercase().endsWith(".so") ||
                            it.name.lowercase().endsWith(".dll") ||
                            it.name.lowercase().endsWith(".dylib")
                    }
                }
                if (!hasNativeBinary) return@mapNotNull null
                val pinned = nativePins[coordinate].orEmpty()
                val complete = pinned.isNotEmpty() && pinned.all {
                    val name = it["file"] as String
                    val bytes = dependencySourcesDir.get().file(name).asFile.readBytes()
                    MessageDigest.getInstance("SHA-256").digest(bytes)
                        .joinToString("") { b -> "%02x".format(b) } == it["sha256"]
                }
                if (complete) null else coordinate
            }
            .distinct()
            .sorted()
        check(nativeWithoutSource.isEmpty()) {
            "Native binaries lack pinned complete upstream source archives: $nativeWithoutSource"
        }
        check(withoutGrant.isEmpty() && withoutSource.isEmpty()) {
            "Unproven JVM redistribution grants: $withoutGrant; " +
                "missing corresponding source archives: $withoutSource"
        }
    }
}



// Create fat JAR for JVM with all dependencies
tasks.register<Jar>("jvmFatJar") {
    group = "build"
    description = "Creates a fat JAR with all dependencies for JVM target"

    archiveClassifier.set("fat")
    archiveBaseName.set("libpebble3-jvm")

    // Include compiled classes from JVM target
    from(kotlin.jvm().compilations.getByName("main").output)

    // Include all runtime dependencies
    val runtimeClasspath = kotlin.jvm().compilations.getByName("main").runtimeDependencyFiles
    from({
        runtimeClasspath.map { if (it.isDirectory) it else zipTree(it) }
    })

    // Merge service files for ServiceLoader
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes["Main-Class"] = "io.rebble.libpebblecommon.test.UartConnectionTestKt"
    }
}
tasks.named<Jar>("jvmFatJar") {
    dependsOn("collectJvmDependencyLicenses")
    from(dependencyNoticesDir) {
        into("META-INF/cyberdeck/dependency-licenses")
    }
}
