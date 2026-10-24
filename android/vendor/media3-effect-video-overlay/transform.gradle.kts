import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.transform.CacheableTransform
import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.artifacts.transform.TransformParameters
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.Usage
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.process.ExecOperations
import java.io.File
import javax.inject.Inject

/** Artifact-level marker; never placed on a published component variant. */
object VideoStudioEffectOverlayAttribute {
    val patched: Attribute<Boolean> = Attribute.of("com.videostudio.media3.effect.source-overlay", Boolean::class.javaObjectType)
}

/** Produces one verified copied AAR, retaining its original dependency metadata. */
@CacheableTransform
abstract class VideoStudioEffectOverlayTransform @Inject constructor(
    private val execOperations: ExecOperations
) : TransformAction<VideoStudioEffectOverlayTransform.Parameters> {
    interface Parameters : TransformParameters {
        @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
        val vendorDirectory: DirectoryProperty
        @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
        val androidJar: RegularFileProperty
        @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
        val javac: RegularFileProperty
        @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
        val compilerModule: RegularFileProperty
        @get:Input val javaRuntimeVersion: Property<String>
        @get:Classpath val compilerSupportClasspath: ConfigurableFileCollection
    }

    @get:InputArtifact @get:PathSensitive(PathSensitivity.NONE)
    abstract val inputArtifact: Provider<FileSystemLocation>

    override fun transform(outputs: TransformOutputs) {
        val vendor = parameters.vendorDirectory.get().asFile
        val upstream = inputArtifact.get().asFile
        // The marker belongs to artifact types, so component variant selection
        // stays unchanged. Unrelated AARs pass through with their ORIGINAL file.
        if (!upstream.name.startsWith("media3-effect-")) {
            outputs.file(upstream)
            return
        }
        require(upstream.name == "media3-effect-1.11.1.aar") {
            "Native VIDEO compositor requires the exact pinned EFFECT artifact"
        }
        val output = outputs.file("media3-effect-1.11.1-videostudio-native-video.aar")
        val command = mutableListOf(
            "python3", File(vendor, "build_overlay_aar.py").absolutePath,
            "--input-aar", upstream.absolutePath,
            "--output-aar", output.absolutePath,
            "--vendor-dir", vendor.absolutePath,
            "--android-jar", parameters.androidJar.get().asFile.absolutePath,
            "--javac", parameters.javac.get().asFile.absolutePath,
            "--compiler-module", parameters.compilerModule.get().asFile.absolutePath
        )
        parameters.compilerSupportClasspath.files
            .distinct().sortedBy { it.absolutePath }.forEach {
                command.add("--classpath"); command.add(it.absolutePath)
            }
        execOperations.exec { commandLine(command) }
    }
}

val videoOverlayVendorDirectory = rootProject.layout.projectDirectory.dir("vendor/media3-effect-video-overlay")
@Suppress("UNCHECKED_CAST")
val videoOverlayAndroidJar = extensions.extraProperties.get("videostudioMedia3AndroidJar") as Provider<RegularFile>

// This isolated build-only classpath supplies the annotations intentionally absent
// from EFFECT's API variant. It is never added to the application dependencies.
val vendorCompilerSupport = configurations.create("videostudioEffectCompilerSupport") {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
}
dependencies.add(vendorCompilerSupport.name, "androidx.media3:media3-common:1.11.1")
dependencies.add(vendorCompilerSupport.name, "androidx.annotation:annotation-jvm:1.9.0")
dependencies.add(vendorCompilerSupport.name, "org.checkerframework:checker-qual:3.43.0")
dependencies.add(vendorCompilerSupport.name, "com.google.guava:guava:33.3.1-android")

// All app consumers (compile, runtime, unit tests and APK) request the same copied
// EFFECT artifact. Other modules have no producer attribute and remain unchanged.
configurations.configureEach {
    if (!name.startsWith("videostudioEffect")) {
        attributes.attribute(VideoStudioEffectOverlayAttribute.patched, true)
    }
    resolutionStrategy.eachDependency {
        if (requested.group == "androidx.media3" && requested.name == "media3-effect") {
            require(requested.version == "1.11.1") {
                "Native VIDEO compositor is pinned to media3-effect:1.11.1"
            }
        }
    }
}

// Mark the ARTIFACT TYPE, not releaseApi/runtime component variants. A producer
// variant attribute blocks variant selection before transforms are considered.
dependencies.artifactTypes.maybeCreate("aar").attributes
    .attribute(VideoStudioEffectOverlayAttribute.patched, false)
dependencies.registerTransform(VideoStudioEffectOverlayTransform::class.java) {
    from.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "aar")
        .attribute(VideoStudioEffectOverlayAttribute.patched, false)
    to.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "aar")
        .attribute(VideoStudioEffectOverlayAttribute.patched, true)
    parameters {
        vendorDirectory.set(videoOverlayVendorDirectory)
        androidJar.set(videoOverlayAndroidJar)
        javac.set(layout.file(providers.provider { File(System.getProperty("java.home"), "bin/javac") }))
        compilerModule.set(layout.file(providers.provider { File(System.getProperty("java.home"), "jmods/jdk.compiler.jmod") }))
        javaRuntimeVersion.set(System.getProperty("java.runtime.version"))
        compilerSupportClasspath.from(vendorCompilerSupport)
    }
}

// Fail closed on the actually RESOLVED component/artifact, not a filename alone.
// This also proves that all original non-compositor entry payloads stay identical.
val videoOverlayEffectArtifacts = providers.provider {
    configurations.getByName("debugCompileClasspath").incoming.artifactView {
        componentFilter { it is ModuleComponentIdentifier && it.group == "androidx.media3" && it.module == "media3-effect" }
        attributes {
            attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "aar")
            attribute(VideoStudioEffectOverlayAttribute.patched, true)
        }
    }.artifacts
}
val videoOverlayOriginalArtifacts = providers.provider {
    configurations.getByName("debugCompileClasspath").incoming.artifactView {
        componentFilter { it is ModuleComponentIdentifier && it.group == "androidx.media3" && it.module == "media3-effect" }
        attributes {
            attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "aar")
            attribute(VideoStudioEffectOverlayAttribute.patched, false)
        }
    }.artifacts
}
val verifyVideoStudioEffectOverlay = tasks.register("verifyVideoStudioEffectOverlay") {
    inputs.files(videoOverlayEffectArtifacts.map { it.artifactFiles }, videoOverlayOriginalArtifacts.map { it.artifactFiles })
    doLast {
        val patched = videoOverlayEffectArtifacts.get().artifacts.single()
        val original = videoOverlayOriginalArtifacts.get().artifacts.single()
        for (artifact in listOf(patched, original)) {
            val id = artifact.id.componentIdentifier as? ModuleComponentIdentifier
                ?: error("Expected the pinned EFFECT Maven component")
            require(id.group == "androidx.media3" && id.module == "media3-effect" && id.version == "1.11.1")
        }
        project.exec {
            commandLine("python3", File(videoOverlayVendorDirectory.asFile, "verify_overlay_aar.py").absolutePath,
                "--original-aar", original.file.absolutePath, "--patched-aar", patched.file.absolutePath,
                "--vendor-dir", videoOverlayVendorDirectory.asFile.absolutePath)
        }
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(verifyVideoStudioEffectOverlay) }
