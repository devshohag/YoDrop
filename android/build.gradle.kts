import java.security.MessageDigest
import java.io.*
import java.util.zip.*
import org.gradle.api.artifacts.transform.*
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.attributes.Attribute
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.yodrop.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.yodrop.android"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    androidResources { noCompress += "task" }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.cbor)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mediapipe.tasks.vision) {
        exclude(group = "com.google.android.datatransport") // Only used by the removed remote metrics logger.
    }

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

val verifyHandModel by tasks.registering {
    group = "verification"
    description = "Verify the pinned on-device hand model before packaging."
    doLast {
        val model = layout.projectDirectory.file("src/main/assets/hand_landmarker.task").asFile
        check(model.isFile) { "Missing hand model. Run scripts/prepare-p6-model.ps1 before building." }
        val digest = MessageDigest.getInstance("SHA-256")
        model.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        check(actual == "fbc2a30080c3c557093b5ddfc334698132eb341044ccee322ccf8bcf3607cde1") { "Hand model checksum mismatch. Run scripts/prepare-p6-model.ps1." }
    }
}
tasks.named("preBuild").configure { dependsOn(verifyHandModel) }

// The published Tasks AAR enables remote usage metrics. Use its bundled no-op
// logger from the first TaskRunner invocation, before any session can be logged.
abstract class LocalOnlyMediaPipe : TransformAction<TransformParameters.None> {
    @get:InputArtifact
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val inputArtifact: Provider<FileSystemLocation>

    override fun transform(outputs: TransformOutputs) {
        val source = inputArtifact.get().asFile
        if (!source.name.startsWith("tasks-core-")) { outputs.file(source); return }
        check(source.name == "tasks-core-0.10.32.aar") { "Review MediaPipe privacy transform before changing SDK version" }
        val result = outputs.file("local-only-" + source.name)
        var modified = false
        var removed = 0
        ZipFile(source).use { aar ->
            ZipOutputStream(result.outputStream()).use { out ->
                val entries = aar.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    out.putNextEntry(ZipEntry(entry.name))
                    if (!entry.isDirectory) aar.getInputStream(entry).use { input ->
                        if (entry.name == "classes.jar") {
                            ZipInputStream(input).use { jar ->
                                // A separate buffer avoids closing the outer AAR output.
                                val bytes = ByteArrayOutputStream()
                                ZipOutputStream(bytes).use { rewritten ->
                                    while (true) {
                                        val item = jar.nextEntry ?: break
                                        if (item.name in setOf(
                                            "com/google/mediapipe/tasks/core/logging/TasksStatsProtoLogger.class",
                                            "com/google/mediapipe/tasks/core/logging/RemoteLoggingClient.class")) {
                                            removed++; continue
                                        }
                                        rewritten.putNextEntry(ZipEntry(item.name))
                                        if (!item.isDirectory) {
                                            if (item.name == "com/google/mediapipe/tasks/core/logging/TasksStatsLoggerFactory.class") {
                                                check(!modified)
                                                rewritten.write(noOpFactory(jar.readBytes()))
                                                modified = true
                                            } else jar.copyTo(rewritten)
                                        }
                                        rewritten.closeEntry()
                                    }
                                }
                                out.write(bytes.toByteArray())
                            }
                        } else input.copyTo(out)
                    }
                    out.closeEntry()
                }
            }
        }
        check(modified && removed == 2) { "MediaPipe logging classes changed; refusing an unreviewed SDK" }
    }

    private fun noOpFactory(original: ByteArray): ByteArray {
        val hash = MessageDigest.getInstance("SHA-256").digest(original).joinToString("") { "%02x".format(it) }
        check(hash == "fc88676ca1ca1543d6e0b495bbe9c948161f87f51c369eeb6d7478e094aa8944") { "MediaPipe logger factory changed; review privacy transform" }
        val bytes = ByteArrayOutputStream()
        DataInputStream(original.inputStream()).use { input ->
            DataOutputStream(bytes).use { out ->
                check(input.readInt() == 0xCAFEBABE.toInt())
                out.writeInt(0xCAFEBABE.toInt()); out.writeShort(input.readUnsignedShort()); out.writeShort(input.readUnsignedShort())
                val count = input.readUnsignedShort(); out.writeShort(count)
                var index = 1; var replacements = 0
                while (index < count) {
                    val tag = input.readUnsignedByte(); out.writeByte(tag)
                    when (tag) {
                        1 -> {
                            val value = input.readUTF()
                            val replaced = value.replace("TasksStatsProtoLogger", "TasksStatsDummyLogger")
                            if (replaced != value) replacements++
                            out.writeUTF(replaced)
                        }
                        3, 4 -> out.writeInt(input.readInt())
                        5, 6 -> { out.writeLong(input.readLong()); index++ }
                        7, 8, 16, 19, 20 -> out.writeShort(input.readUnsignedShort())
                        9, 10, 11, 12, 17, 18 -> { out.writeShort(input.readUnsignedShort()); out.writeShort(input.readUnsignedShort()) }
                        15 -> { out.writeByte(input.readUnsignedByte()); out.writeShort(input.readUnsignedShort()) }
                        else -> error("Unexpected Java constant-pool tag $tag")
                    }
                    index++
                }
                check(replacements == 2) { "Unexpected MediaPipe factory; review no-op logger binding" }
                input.copyTo(out)
            }
        }
        return bytes.toByteArray()
    }
}

val localOnlyTasks = Attribute.of("com.yodrop.local-only-tasks", Boolean::class.javaObjectType)
dependencies {
    attributesSchema.attribute(localOnlyTasks)
    artifactTypes.maybeCreate("aar").attributes.attribute(localOnlyTasks, false)
    registerTransform(LocalOnlyMediaPipe::class) {
        from.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "aar").attribute(localOnlyTasks, false)
        to.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "aar").attribute(localOnlyTasks, true)
    }
}
configurations.configureEach { attributes.attribute(localOnlyTasks, true) }

val privateMediaPipeCheck by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "aar")
}
dependencies {
    add(privateMediaPipeCheck.name, "com.google.mediapipe:tasks-core:${libs.versions.mediapipe.get()}") { isTransitive = false }
}
val verifyPrivateMediaPipe by tasks.registering {
    group = "verification"
    doLast {
        val aar = privateMediaPipeCheck.singleFile
        check(aar.name == "local-only-tasks-core-0.10.32.aar") { "MediaPipe privacy transform was bypassed" }
        var factoryFound = false
        ZipFile(aar).use { archive ->
            ZipInputStream(archive.getInputStream(archive.getEntry("classes.jar"))).use { jar ->
                while (true) {
                    val entry = jar.nextEntry ?: break
                    check(entry.name != "com/google/mediapipe/tasks/core/logging/TasksStatsProtoLogger.class")
                    check(entry.name != "com/google/mediapipe/tasks/core/logging/RemoteLoggingClient.class")
                    if (entry.name == "com/google/mediapipe/tasks/core/logging/TasksStatsLoggerFactory.class") {
                        val symbols = jar.readBytes().toString(Charsets.ISO_8859_1)
                        check("TasksStatsDummyLogger" in symbols && "TasksStatsProtoLogger" !in symbols)
                        factoryFound = true
                    }
                }
            }
        }
        check(factoryFound) { "MediaPipe no-op factory missing" }
        logger.lifecycle("MediaPipe local-only logger verified; remote metrics classes removed")
    }
}
tasks.named("preBuild").configure { dependsOn(verifyPrivateMediaPipe) }
