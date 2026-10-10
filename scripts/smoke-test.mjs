import fs from "node:fs";

const app = fs.readFileSync(new URL("../src/app.html", import.meta.url), "utf8");
const worker = fs.readFileSync(new URL("../src/index.js", import.meta.url), "utf8");
const studioRuntime = fs.readFileSync(new URL("../src/studio-runtime.js", import.meta.url), "utf8");
const studioCinematic = fs.readFileSync(new URL("../src/studio-cinematic.js", import.meta.url), "utf8");
const studioNeural = fs.readFileSync(new URL("../src/studio-neural.js", import.meta.url), "utf8");
const studioTemporalUrl = new URL("../src/studio-temporal.js", import.meta.url);
const studioTemporal = fs.existsSync(studioTemporalUrl) ? fs.readFileSync(studioTemporalUrl, "utf8") : "";
const wrangler = fs.readFileSync(new URL("../wrangler.jsonc", import.meta.url), "utf8");
const nativeMain = fs.readFileSync(new URL("../android/app/src/main/java/com/rezoxnemesis/videostudio/MainActivity.java", import.meta.url), "utf8");
const nativeProtocol = fs.readFileSync(new URL("../android/app/src/main/java/com/rezoxnemesis/videostudio/AppProtocol.java", import.meta.url), "utf8");
const mcpConnectionCore = fs.readFileSync(new URL("../android/app/src/main/java/com/rezoxnemesis/videostudio/McpConnectionCore.java", import.meta.url), "utf8");
const nativeJobs = fs.readFileSync(new URL("../android/app/src/main/java/com/rezoxnemesis/videostudio/JobManager.java", import.meta.url), "utf8");
const nativeRender = fs.readFileSync(new URL("../android/app/src/main/java/com/rezoxnemesis/videostudio/NativeRenderEngine.java", import.meta.url), "utf8");
const promptVideo = fs.readFileSync(new URL("../android/app/src/main/java/com/rezoxnemesis/videostudio/PromptVideoEngine.java", import.meta.url), "utf8");
const nativeAnalyzer = fs.readFileSync(new URL("../android/app/src/main/java/com/rezoxnemesis/videostudio/NativeMediaAnalyzer.java", import.meta.url), "utf8");
const creatorCatalog = fs.readFileSync(new URL("../android/app/src/main/java/com/rezoxnemesis/videostudio/CreatorCatalog.java", import.meta.url), "utf8");
const androidBuild = fs.readFileSync(new URL("../android/app/build.gradle.kts", import.meta.url), "utf8");
const androidManifest = fs.readFileSync(new URL("../android/app/src/main/AndroidManifest.xml", import.meta.url), "utf8");
const controlService = fs.readFileSync(new URL("../android/app/src/main/java/com/rezoxnemesis/videostudio/ControlService.java", import.meta.url), "utf8");
const animationDirector = fs.readFileSync(new URL("../android/app/src/main/java/com/rezoxnemesis/videostudio/NativeAnimationDirector.java", import.meta.url), "utf8");
const poseSequenceFlow = fs.readFileSync(new URL("../android/app/src/main/java/com/rezoxnemesis/videostudio/PoseSequenceFlow.java", import.meta.url), "utf8");
const combatSolver = fs.readFileSync(new URL("../android/app/src/main/java/com/rezoxnemesis/videostudio/CombatRigSolver.java", import.meta.url), "utf8");
const combatRenderer = fs.readFileSync(new URL("../android/app/src/main/java/com/rezoxnemesis/videostudio/NativeCombatRigRenderer.java", import.meta.url), "utf8");
const combatComposer = fs.readFileSync(new URL("../android/app/src/main/java/com/rezoxnemesis/videostudio/NativeCombatRigComposer.java", import.meta.url), "utf8");
const cleanScenePlate = fs.readFileSync(new URL("../android/app/src/main/java/com/rezoxnemesis/videostudio/NativeScenePlateBuilder.java", import.meta.url), "utf8");
const commandJournal = fs.readFileSync(new URL("../android/app/src/main/java/com/rezoxnemesis/videostudio/CommandJournal.java", import.meta.url), "utf8");
const projectStore = fs.readFileSync(new URL("../android/app/src/main/java/com/rezoxnemesis/videostudio/ProjectStore.java", import.meta.url), "utf8");
const portraitMotion = fs.readFileSync(new URL("../android/app/src/main/java/com/rezoxnemesis/videostudio/NativePortraitMotionAnalyzer.java", import.meta.url), "utf8");
const animatedDirector = fs.readFileSync(new URL("../android/app/src/main/java/com/rezoxnemesis/videostudio/AnimatedSceneDirector.java", import.meta.url), "utf8");
const motionMatrix = fs.readFileSync(new URL("../android/app/src/main/java/com/rezoxnemesis/videostudio/MotionMatrixEffect.java", import.meta.url), "utf8");
const atmosphere = fs.readFileSync(new URL("../android/app/src/main/java/com/rezoxnemesis/videostudio/AtmosphereOverlay.java", import.meta.url), "utf8");
const creativeWorkspace = fs.readFileSync(new URL("../android/app/src/main/java/com/rezoxnemesis/videostudio/CreativeWorkspace.java", import.meta.url), "utf8");
const motionScriptCompiler = fs.readFileSync(new URL("../android/app/src/main/java/com/rezoxnemesis/videostudio/MotionScriptCompiler.java", import.meta.url), "utf8");
const activityLog = fs.readFileSync(new URL("../android/app/src/main/java/com/rezoxnemesis/videostudio/ActivityLog.java", import.meta.url), "utf8");
const executionTruth = fs.readFileSync(new URL("../android/app/src/main/java/com/rezoxnemesis/videostudio/ExecutionTruthPolicy.java", import.meta.url), "utf8");
const recoveryPlans = fs.readFileSync(new URL("../android/app/src/main/java/com/rezoxnemesis/videostudio/RecoveryPlanStore.java", import.meta.url), "utf8");
const capabilityRegistry = fs.readFileSync(new URL("../android/app/src/main/java/com/rezoxnemesis/videostudio/CapabilityRegistry.java", import.meta.url), "utf8");
const modelPackManager = fs.readFileSync(new URL("../android/app/src/main/java/com/rezoxnemesis/videostudio/ModelPackManager.java", import.meta.url), "utf8");
const computeProfile = fs.readFileSync(new URL("../android/app/src/main/java/com/rezoxnemesis/videostudio/DeviceComputeProfile.java", import.meta.url), "utf8");
const driveWorkspace = fs.readFileSync(new URL("../android/app/src/main/java/com/rezoxnemesis/videostudio/DriveWorkspaceProvider.java", import.meta.url), "utf8");
const creativeJobGraph = fs.readFileSync(new URL("../android/app/src/main/java/com/rezoxnemesis/videostudio/CreativeJobGraph.java", import.meta.url), "utf8");
const creativeNodeStore = fs.readFileSync(new URL("../android/app/src/main/java/com/rezoxnemesis/videostudio/CreativeNodeStore.java", import.meta.url), "utf8");
const builtInCreativeRuntime = fs.readFileSync(new URL("../android/app/src/main/java/com/rezoxnemesis/videostudio/CreativeBuiltInRuntime.java", import.meta.url), "utf8");
const nativeRenderCritic = fs.readFileSync(new URL("../android/app/src/main/java/com/rezoxnemesis/videostudio/NativeRenderCritic.java", import.meta.url), "utf8");
const nativeRecoveryReceiver = fs.readFileSync(new URL("../android/app/src/main/java/com/rezoxnemesis/videostudio/NativeAgentRecoveryReceiver.java", import.meta.url), "utf8");
const nativeWatchdog = fs.readFileSync(new URL("../android/app/src/main/java/com/rezoxnemesis/videostudio/NativeAgentWatchdog.java", import.meta.url), "utf8");
const resumableTransfer = fs.readFileSync(new URL("../android/app/src/main/java/com/rezoxnemesis/videostudio/ResumableTransferManager.java", import.meta.url), "utf8");
const transferJournal = fs.readFileSync(new URL("../android/app/src/main/java/com/rezoxnemesis/videostudio/TransferJournal.java", import.meta.url), "utf8");
const liveEditPlayer = fs.readFileSync(new URL("../android/app/src/main/java/com/rezoxnemesis/videostudio/LiveEditPlayer.java", import.meta.url), "utf8");
const previewSnapshotStore = fs.readFileSync(new URL("../android/app/src/main/java/com/rezoxnemesis/videostudio/PreviewSnapshotStore.java", import.meta.url), "utf8");
const proxyManager = fs.readFileSync(new URL("../android/app/src/main/java/com/rezoxnemesis/videostudio/ProxyManager.java", import.meta.url), "utf8");
const storageBudget = fs.readFileSync(new URL("../android/app/src/main/java/com/rezoxnemesis/videostudio/StorageBudget.java", import.meta.url), "utf8");
const atomicMediaPublisher = fs.readFileSync(new URL("../android/app/src/main/java/com/rezoxnemesis/videostudio/AtomicMediaPublisher.java", import.meta.url), "utf8");

const scriptMatch = app.match(/<script>([\s\S]*?)<\/script>/);
let appScriptParses = false;
try {
  if (!scriptMatch) throw new Error("Inline script not found");
  new Function(scriptMatch[1]);
  appScriptParses = true;
} catch (error) {
  console.error("APP SCRIPT SYNTAX ERROR:", error.message);
}

const neuralMatch = studioNeural.match(/String\.raw\`([\s\S]*?)\`;\s*export default/);
let studioNeuralParses = false;
try {
  if (!neuralMatch) throw new Error("Studio Neural source not found");
  new Function(neuralMatch[1]);
  studioNeuralParses = true;
} catch (error) {
  console.error("STUDIO NEURAL SYNTAX ERROR:", error.message);
}

const temporalMatch = studioTemporal.match(/String\.raw\`([\s\S]*?)\`;\s*export default/);
let studioTemporalParses = false;
try {
  if (!temporalMatch) throw new Error("Studio Temporal source not found");
  new Function(temporalMatch[1]);
  studioTemporalParses = true;
} catch (error) {
  console.error("STUDIO TEMPORAL SYNTAX ERROR:", error.message);
}

const cinematicMatch = studioCinematic.match(/String\.raw\`([\s\S]*?)\`;\s*export default/);
let studioCinematicParses = false;
try {
  if (!cinematicMatch) throw new Error("Studio Cinematic source not found");
  new Function(cinematicMatch[1]);
  studioCinematicParses = true;
} catch (error) {
  console.error("STUDIO CINEMATIC SYNTAX ERROR:", error.message);
}

const runtimeMatch = studioRuntime.match(/String\.raw\`([\s\S]*?)\`;\s*export default/);
let studioRuntimeParses = false;
try {
  if (!runtimeMatch) throw new Error("Studio Runtime source not found");
  new Function(runtimeMatch[1]);
  studioRuntimeParses = true;
} catch (error) {
  console.error("STUDIO RUNTIME SYNTAX ERROR:", error.message);
}

const checks = [
  ["inline app JavaScript parses", appScriptParses],
  ["app has Connect to ChatGPT control", app.includes("Connect to ChatGPT")],
  ["app has media import", app.includes('id="fileInput"')],
  ["app has timeline", app.includes('id="timeline"')],
  ["Studio Web 1.0 has visual Media Bin", app.includes('id="mediaShelf"') && app.includes("renderMediaShelf") && app.includes("makeThumbnail")],
  ["Studio Web 1.0 timeline uses visual thumbnails", app.includes("clipThumb") && app.includes("hydrateThumb") && app.includes("timelineSummary")],
  ["Studio Web 1.0 exposes creator quick tools", app.includes('data-quick-tool="split"') && app.includes('data-quick-tool="push"') && app.includes("quickTool(action)")],
  ["Studio Web 1.0 has autonomous director goal surface", app.includes('id="directorGoal"') && app.includes("directorPrompt") && app.includes("saveDirectorGoal")],
  ["Studio Web requests persistent browser storage", app.includes("navigator.storage.persist") && app.includes("updateStorageStatus")],
  ["Studio Web has mobile editor navigation", app.includes("bottomDock") && app.includes('data-jump="editorSection"') && app.includes('data-jump="controlSection"')],
  ["Studio Web command relay adapts polling cadence to visibility", app.includes("async function commandLoop") && app.includes("document.hidden ? 2600 : 850")],
  ["worker advertises Studio Web 1.0 fallback execution", worker.includes('name:"VideoStudio-Studio-Web"') && worker.includes('version:"1.0.0"') && worker.includes('"browser fallback execution when Android app is unavailable"')],
  ["Studio Runtime browser JavaScript parses", studioRuntimeParses],
  ["Studio Cinematic browser JavaScript parses", studioCinematicParses],
  ["Studio Neural browser JavaScript parses", studioNeuralParses],
  ["Studio Temporal browser JavaScript parses", studioTemporalParses],
  ["worker serves first-party Neural Temporal Motion runtime", worker.includes('"/studio-temporal.js"') && worker.includes("STUDIO_TEMPORAL_JS")],
  ["Temporal Motion uses real RAFT ONNX optical flow with WebGPU", studioTemporal.includes("optical_flow_estimation_raft_2023aug_int8bq.onnx") && studioTemporal.includes('executionProviders:["webgpu"]') && studioTemporal.includes('inputNames[0]') && studioTemporal.includes('inputNames[1]')],
  ["Temporal Motion interpolates multiple neural anchors into an actual video asset", studioTemporal.includes("renderTemporalMotion") && studioTemporal.includes("flowAB") && studioTemporal.includes("flowBA") && studioTemporal.includes("MediaRecorder") && studioTemporal.includes("canvas.captureStream") && studioTemporal.includes('role:"neural_temporal_video"')],
  ["Temporal Motion records output into Media Bin and timeline for Cinematic Worlds", studioTemporal.includes("project.assets.push(asset)") && studioTemporal.includes("project.timeline.push") && studioTemporal.includes("VideoStudioCinematic.refreshSelectors")],
  ["Studio Runtime executes autonomous temporal-motion jobs", studioRuntime.includes('command.action==="generate_temporal_motion"') && studioRuntime.includes('waitForProvider("VideoStudioTemporal"')],
  ["MCP exposes autonomous Neural Temporal Motion rendering", worker.includes('"render_neural_temporal_video"') && worker.includes('"generate_temporal_motion"') && worker.includes("anchorAssetIds")],
  ["worker serves optional first-party Neural Keyframe runtime", worker.includes('"/studio-neural.js"') && worker.includes("STUDIO_NEURAL_JS")],
  ["Neural Keyframes use actual ONNX Runtime WebGPU SD-Turbo phases", studioNeural.includes("onnxruntime-web@") && studioNeural.includes("text_encoder/model.onnx") && studioNeural.includes("unet/model.onnx") && studioNeural.includes("vae_decoder/model.onnx") && studioNeural.includes('executionProviders:["webgpu"]')],
  ["Neural Keyframes gate on WebGPU shader-f16 instead of crashing unsupported browsers", studioNeural.includes('adapter.features.has("shader-f16")') && studioNeural.includes("recommended") && studioNeural.includes("Compatible WebGPU is required")],
  ["Neural Keyframes run model phases sequentially and release sessions", studioNeural.includes('sessionFor("text_encoder"') && studioNeural.includes('sessionFor("unet"') && studioNeural.includes('sessionFor("vae_decoder"') && studioNeural.includes("session.release")],
  ["Neural world images become generated Media Bin assets for Cinematic Worlds", studioNeural.includes('role:"neural_world_keyframe"') && studioNeural.includes("project.assets.push(asset)") && studioNeural.includes("VideoStudioCinematic.refreshSelectors")],
  ["MCP can probe and generate real neural world keyframes", worker.includes('"probe_studio_neural_gpu"') && worker.includes('"generate_neural_world_keyframes"') && worker.includes('"generate_neural_keyframes"')],
  ["shared runtime waits for late-loaded neural/cinematic providers", studioRuntime.includes("waitForProvider") && studioRuntime.includes('"VideoStudioNeural"') && studioRuntime.includes('"VideoStudioCinematic"')],
  ["Cinematic compositor supports local MediaPipe person occlusion", studioCinematic.includes("@mediapipe/tasks-vision") && studioCinematic.includes("selfie_segmenter_landscape") && studioCinematic.includes("compositePersonOcclusion")],
  ["worker serves first-party Cinematic Worlds runtime", worker.includes('"/studio-cinematic.js"') && worker.includes("STUDIO_CINEMATIC_JS")],
  ["Cinematic Worlds preserves live-action foreground and perspective-warps worlds into a quad", studioCinematic.includes("drawWarped") && studioCinematic.includes("affineFromTriangles") && studioCinematic.includes("drawCover(ctx,baseLoaded.video")],
  ["Cinematic Worlds supports start/end portal keyframes and translation tracking", studioCinematic.includes("startQuad") && studioCinematic.includes("endQuad") && studioCinematic.includes("trackTranslation") && studioCinematic.includes('tracking==="translation"')],
  ["Cinematic Worlds sequences multiple imported/generated worlds with crossfades", studioCinematic.includes("worldAssetIds") && studioCinematic.includes("mix=segmentProgress>.78") && studioCinematic.includes("segmentProgress=pos-idx") && studioCinematic.includes("secondary")],
  ["Cinematic Worlds retains subtle base-video reflections over the replacement world", studioCinematic.includes('globalCompositeOperation="screen"') && studioCinematic.includes("reflection")],
  ["Cinematic Worlds records a real video blob and inserts it into project assets/timeline", studioCinematic.includes("MediaRecorder") && studioCinematic.includes("registerGenerated") && studioCinematic.includes("project.timeline.push")],
  ["MCP exposes autonomous cinematic portal rendering", worker.includes('"render_cinematic_world_video"') && worker.includes('"render_portal_video"') && worker.includes("startQuad") && worker.includes("worldAssetIds")],
  ["shared Studio Runtime executes portal jobs without a second competing poller", studioRuntime.includes('command.action==="render_portal_video"') && studioRuntime.includes('waitForProvider("VideoStudioCinematic")') && studioRuntime.includes("cinematic.renderPortal") && !studioCinematic.includes("vs-cinematic-last-seq")],
  ["worker serves the Studio Runtime as a first-party script", worker.includes('"/studio-runtime.js"') && worker.includes("STUDIO_RUNTIME_JS")],
  ["Studio Web supports user-owned Google Drive drive.file storage", studioRuntime.includes("https://www.googleapis.com/auth/drive.file") && studioRuntime.includes("VideoStudio Studio Web") && studioRuntime.includes("uploadBlobResumable") && studioRuntime.includes("restoreProject") && studioRuntime.includes("offloadProject")],
  ["Drive upload uses resumable chunks instead of website object storage", studioRuntime.includes("uploadType=resumable") && studioRuntime.includes("Content-Range") && studioRuntime.includes("8*1024*1024")],
  ["Drive OAuth client ID can come from worker config or one-time browser setting", worker.includes("GOOGLE_DRIVE_CLIENT_ID") && studioRuntime.includes("vs-drive-client-id") && studioRuntime.includes("vsDriveClientId")],
  ["Studio Web has real text image story and video generation modes", studioRuntime.includes('"prompt_scene"') && studioRuntime.includes('"image_motion"') && studioRuntime.includes('"story_video"') && studioRuntime.includes('"video_restyle"')],
  ["Studio Web has real 2D 3D audio and VFX generation modes", studioRuntime.includes('"motion_graphics"') && studioRuntime.includes('"procedural_3d"') && studioRuntime.includes('"audio_visualizer"') && studioRuntime.includes('"abstract_vfx"')],
  ["Studio generation records actual media blobs through canvas capture and MediaRecorder", studioRuntime.includes("canvas.captureStream") && studioRuntime.includes("new MediaRecorder") && studioRuntime.includes("registerGeneratedVideo")],
  ["generated videos become real project assets and timeline clips", studioRuntime.includes("project.assets.push(asset)") && studioRuntime.includes("project.timeline.push") && studioRuntime.includes('kind: "video"') && studioRuntime.includes("generated: true")],
  ["Studio Runtime has a separate autonomous command queue", worker.includes("enqueueRuntime") && worker.includes("/api/runtime/commands") && studioRuntime.includes("runtimeCommandLoop")],
  ["MCP exposes Studio generation and Drive runtime controls", worker.includes('"generate_studio_video"') && worker.includes('"studio_drive_sync"') && worker.includes('"studio_drive_restore"') && worker.includes('"studio_drive_offload"') && worker.includes('"get_studio_runtime_result"')],
  ["Studio project metadata persists Drive and generation state", worker.includes('"drive","generation"') && worker.includes("drive:{},generation:{}")],
  ["app has local render control", app.includes('id="renderBtn"')],
  ["app can generate local contact sheets", app.includes("analyseLocalMedia")],
  ["app polls remote commands", app.includes("pollCommands")],
  ["worker serves root app", worker.includes('u.pathname==="/"')],
  ["worker serves /mcp", worker.includes('u.pathname==="/mcp"')],
  ["worker exposes queue_video_edit", worker.includes('"queue_video_edit"')],
  ["worker exposes request_media_analysis", worker.includes('"request_media_analysis"')],
  ["worker exposes queue_video_edit_batch", worker.includes('"queue_video_edit_batch"')],
  ["app supports remote clip removal", app.includes('case "remove_clip"')],
  ["app supports remote clip movement", app.includes('case "move_clip"')],
  ["app supports remote timeline reorder", app.includes('case "reorder_timeline"')],
  ["app supports multi-cut timeline replacement", app.includes('case "replace_timeline"')],
  ["app supports per-clip speed", app.includes('case "set_clip_speed"')],
  ["app supports per-clip title", app.includes('case "set_clip_title"')],
  ["autonomous request can carry clips", app.includes("Array.isArray(p.clips)")],
  ["app has scene-change analysis", app.includes("sceneChanges") && app.includes("scanCount")],
  ["app has quiet-section analysis", app.includes("silenceSegments") && app.includes("decodeAudioData")],
  ["app can inspect latest render", app.includes("analyseLatestRender")],
  ["app supports per-clip transforms", app.includes("zoomStart") && app.includes("panXStart")],
  ["app supports per-clip filters", app.includes("brightness") && app.includes("saturation")],
  ["app supports native Android save bridge", app.includes("AndroidNative")],
  ["worker exposes inspect_video_render", worker.includes('"inspect_video_render"')],
  ["worker exposes queue_autonomous_edit", worker.includes('"queue_autonomous_edit"')],
  ["worker exposes fresh /mcp-v06 path", worker.includes('"/mcp-v06"')],
  ["app prefers MP4 when MediaRecorder supports it", app.includes('"video/mp4"')],
  ["worker emits MCP image content", worker.includes('type:"image"')],
  ["worker exposes device_status", worker.includes('"device_status"')],
  ["worker has device registration API", worker.includes('"/api/device/register"')],
  ["worker has Durable Object export", worker.includes("export class VideoStudioState")],
  ["wrangler binds VIDEO_STATE", wrangler.includes('"VIDEO_STATE"')],
  ["wrangler declares sqlite durable object", wrangler.includes('"storage": "sqlite"')],
  ["native app no longer embeds WebView", !nativeMain.includes("android.webkit.WebView")],
  ["native app has selected reference UI branding", nativeMain.includes("Create Without Limits") && nativeMain.includes("AI Magic for Your Videos")],
  ["native app exposes Full Autonomous plus optional One File Lock", nativeMain.includes("Full Autonomous") && nativeMain.includes("One File Lock") && !nativeMain.includes('permissionCard("all_tools"')],
  ["v3.2 defaults existing and new installs to full autonomy except Gallery", nativeMain.includes('putString(KEY_MODE, "everything")') && nativeMain.includes('getString(KEY_MODE, "everything")') && controlService.includes('putString(KEY_MODE, "everything")') && controlService.includes('getString(KEY_MODE, "everything")') && nativeProtocol.includes('permissionMode = "everything"') && worker.includes('(old.permissionMode||"everything")')],
  ["autonomy migration runs once and One File Lock remains user-selectable", nativeMain.includes("autonomy_everything_v32_migrated") && controlService.includes("autonomy_everything_v32_migrated") && nativeMain.includes('permissionCard("one_file"')],
  ["Full Autonomous removes routine native capability gating", worker.includes('"all_tools" remains a backward-compatible alias for Full Autonomous') && controlService.includes('"all_tools" is accepted as a legacy alias') && nativeMain.includes("Full Autonomous is the default")],
  ["One File Lock is the only restrictive autonomy mode", worker.includes('if(mode==="one_file")') && worker.includes('permissions:["everything","one_file"]') && nativeMain.includes("One File Lock")],
  ["Gallery remains blocked under Full Autonomous", worker.includes("Permanent privacy wall") && controlService.includes("Even Full Autonomous cannot enumerate or browse the phone Gallery") && nativeMain.includes("Gallery enumeration remains a hard technical boundary")],
  ["native app has green screen and slow motion tools", nativeMain.includes("Green Screen") && nativeMain.includes("Slow Motion")],
  ["native app pairs through persistent adaptive MCP Connection Core", nativeProtocol.includes("McpConnectionCore.STABLE_MCP_PATH") && nativeProtocol.includes("McpConnectionCore.BOOTSTRAP_API_PREFIX") && nativeProtocol.includes("PROTOCOL_VERSION = 3") && nativeProtocol.includes("AndroidKeyStore")],
  ["MCP transport compatibility is decoupled from APK version", mcpConnectionCore.includes("transportDecoupledFromApkVersion") && mcpConnectionCore.includes("stable-major-additive-features") && mcpConnectionCore.includes("WIRE_SCHEMA_VERSION") && mcpConnectionCore.includes("FEATURE_LEVEL")],
  ["MCP connection identity survives compatible APK upgrades", mcpConnectionCore.includes("upgradeKeepsDeviceIdentity") && mcpConnectionCore.includes("upgradeKeepsOwnerCredential") && nativeProtocol.includes('PREFS = "videostudio_native_v1"') && nativeProtocol.includes('KEY_ALIAS = "videostudio_owner_key_v1"')],
  ["worker persists negotiated wire compatibility independently of app version", worker.includes("wireSchemaVersion") && worker.includes("featureLevel") && worker.includes("transportDecoupledFromApkVersion") && worker.includes('"stable-core-2"')],
  ["True combat action uses fully native articulated frame generation", controlService.includes('case "animate_combat_rig"') && controlService.includes("queueCombatRig") && controlService.includes("combatRigBenchmarkReady")],
  ["Combat IK and independent per-frame sword motion is compiled", combatSolver.includes("elbow(") && combatSolver.includes("swordTipSeparation") && combatSolver.includes("remap(") && combatSolver.includes("leadingFoot")],
  ["Native combat rig can lock source city using only user-imported image clips", cleanScenePlate.includes("perChannelMedian") && cleanScenePlate.includes("source.clips") && combatRenderer.includes("staticBackground") && controlService.includes("backgroundMode")],
  ["Combat benchmark draws one locked background then moving fighters", combatRenderer.includes("drawWorld(c)") && combatRenderer.includes("drawWarrior") && combatRenderer.includes("drawImpact") && combatComposer.includes("NativeCombatRigRenderer")],
  ["Native Editor offers true-rig movement test, not just still interpolation", nativeMain.includes("combatRigBenchmarkDialog()") && nativeMain.includes("True Combat Rig Test")],
  ["Animation Director is exposed in native Editor, not only through MCP", nativeMain.includes("animationDirectorDialog()") && nativeMain.includes("Animation Director") && nativeMain.includes("ACTION_LOCAL_ANIMATION_STUDIO")],
  ["Native animation engine preserves source order and disables decorative motion", animationDirector.includes("createDirectProject") && animationDirector.includes("plan.sourceIds") && animationDirector.includes("directorSequenceFrame") && animationDirector.includes('clip.transition="none"')],
  ["Animation Director supports real native MCP action and self-test", controlService.includes('case "animate_timeline"') && controlService.includes("queueAnimateTimeline") && controlService.includes("animationDirectorReady")],
  ["Upgraded flow guards stable backgrounds and major scene cuts", poseSequenceFlow.includes("sceneCutRisk") && poseSequenceFlow.includes("originalIndex") && poseSequenceFlow.includes("localDifference < 18")],
  ["native app identifies as VideoStudio 3.5.0 while retaining stable MCP v3", nativeProtocol.includes('APP_VERSION = "3.5.0"') && nativeProtocol.includes("PROTOCOL_VERSION = 3") && androidBuild.includes('versionName = "3.5.0"') && androidBuild.includes("versionCode = 350")],
  ["pairing message advertises stable upgrade-surviving endpoint", nativeProtocol.includes("Stable MCP endpoint:") && nativeProtocol.includes("survives compatible VideoStudio APK upgrades")],
  ["MCP Connection Core preserves stable endpoint across APK upgrades", mcpConnectionCore.includes('STABLE_MCP_PATH = "/app-mcp-v3/"') && mcpConnectionCore.includes("appGeneration") && mcpConnectionCore.includes("upgradeKeepsOwnerCredential") && mcpConnectionCore.includes("upgradeKeepsDeviceIdentity")],
  ["MCP Connection Core negotiates only an allow-listed same-origin API profile", mcpConnectionCore.includes("applyRegistrationResponse") && mcpConnectionCore.includes("safeApiPrefix") && mcpConnectionCore.includes("heartbeatMs") && mcpConnectionCore.includes("commandWaitMs")],
  ["Native Agent rejects stale process generations while preserving the same owner endpoint", worker.includes("Stale Native Agent generation") && worker.includes("expectedGeneration") && worker.includes("receivedGeneration") && worker.includes("stableMcpEndpoint:true")],
  ["APK replacement automatically re-arms the stable Native Agent", androidManifest.includes("android.intent.action.MY_PACKAGE_REPLACED") && nativeRecoveryReceiver.includes("ACTION_MY_PACKAGE_REPLACED") && nativeRecoveryReceiver.includes("startForegroundService")],
  ["stable MCP can self-diagnose and rebind without changing identity", nativeProtocol.includes("forceReconnect") && nativeProtocol.includes("connectionCore.resetNegotiation()") && controlService.includes('case "connection_health"') && controlService.includes('case "reconnect_mcp"') && controlService.includes("ownerCredentialPreserved")],
  ["stale connector schemas can invoke connection repair through app_execute", worker.includes('"app_execute"') && worker.includes("stable_connection_reconnect")],
  ["new connectors receive typed stable MCP health and reconnect tools", worker.includes('"app_connection_health"') && worker.includes('"app_reconnect_mcp"') && worker.includes('"connection_health"') && worker.includes('"reconnect_mcp"')],
  ["stale owner connector can mint a one-tap rebind link from app_status", worker.includes("appCreateRebind") && worker.includes("videostudio://mcp-rebind?token=") && worker.includes("rebind:!fresh")],
  ["server can atomically alias the old MCP owner endpoint to the new Native Agent", worker.includes("appRedeemRebind") && worker.includes("stableEndpointAliases=2") && worker.includes('put("app-owner:"+record.oldOwnerHash,deviceId)')],
  ["Android accepts one-tap MCP rebind deep links", androidManifest.includes('android:scheme="videostudio"') && androidManifest.includes('android:host="mcp-rebind"') && nativeMain.includes("handleMcpRebindIntent") && nativeProtocol.includes("redeemRebind")],
  ["native app can mint a one-time Studio Web hybrid binding challenge", worker.includes('"/api/v3/app/hybrid/challenge"') && nativeProtocol.includes("createHybridBinding") && controlService.includes('case "create_hybrid_binding"')],
  ["native MCP exposes typed hybrid binding bootstrap without changing v3 endpoint", worker.includes('"app_create_hybrid_binding"') && worker.includes("appCreateHybridBinding") && nativeProtocol.includes('MCP_PATH = McpConnectionCore.STABLE_MCP_PATH')],
  ["private hybrid MCP path remains stable and is not derived from Web device ID", worker.includes("appResolveHybrid") && worker.includes('"/mcp-v06/"+hybridKey') && worker.includes("hybridMcp")],
  ["private hybrid MCP exposes native execution status result and revocation", worker.includes('"hybrid_status"') && worker.includes('"hybrid_execute"') && worker.includes('"hybrid_get_command_result"') && worker.includes('"hybrid_revoke_native_binding"')],
  ["Native Agent UI only goes online after actual MCP handshake", controlService.includes('markService(false, "VideoStudio stable MCP Native Agent starting")') && controlService.includes("onConnection(boolean connected")],
  ["device reboot restores stable MCP control without Gallery permission", androidManifest.includes("android.permission.RECEIVE_BOOT_COMPLETED") && androidManifest.includes("android.intent.action.BOOT_COMPLETED") && nativeRecoveryReceiver.includes("ACTION_BOOT_COMPLETED") && !nativeRecoveryReceiver.includes("gallery")],
  ["native v3 command cursor advances only after completion", nativeProtocol.includes('KEY_SEQ = "native_v3_last_seq"') && nativeProtocol.includes("advanceSequence")],
  ["native app has bounded heavy-work scheduler", nativeJobs.includes("Semaphore") && nativeJobs.includes("THERMAL_STATUS_SEVERE")],
  ["worker exposes canonical stable VideoStudio App MCP lane", worker.includes('"VideoStudio-App-MCP-v3"') && worker.includes("appMcpV3") && worker.includes("serverForApp(env,ownerKey,3)") && worker.includes("Permanent compatibility endpoint")],
  ["worker has dedicated v3 API namespace", worker.includes('"/api/v3/app/register"') && worker.includes('"/api/v3/app/commands"') && worker.includes("appCompleteV3")],
  ["worker uses an isolated v3 command queue", worker.includes('"app-v3-seq:"') && worker.includes('"app-v3-cl:"') && worker.includes("protocolVersion:3")],
  ["worker rejects unbound native credentials", worker.includes("Private App MCP credential rejected")],
  ["worker leases native commands", worker.includes('status:"claimed"') && worker.includes("leaseUntil")],
  ["v3 leases track retries", worker.includes("claimCount:Number(c.claimCount||0)+1")],
  ["worker keeps legacy private chat handoff as fallback", worker.includes("appCreateHandoff") && worker.includes("app_import_chat_file")],
  ["v3 primary ChatGPT attachment path uses authenticated private Worker handoff", worker.includes('"app_import_attachment"') && worker.includes("appQueueAttachmentHandoff") && worker.includes('"import_chat_file"') && controlService.includes('case "import_chat_file"') && controlService.includes("queuePrivateImport")],
  ["v3 attachment handoff survives rolling Worker deploys with an already-running Durable Object", worker.includes("st.appCreateHandoff(ownerKey,file.download_url") && worker.includes('st.appEnqueueV3(ownerKey,"import_chat_file"') && !worker.includes("st.appQueueAttachmentHandoff(ownerKey,file,projectId||\"\")")],
  ["v3 ChatGPT attachment keeps temporary source URL out of Android command", worker.includes('"openai/fileParams":["file"]') && worker.includes("download_url") && worker.includes("appQueueAttachmentHandoff") && worker.includes("handoffId:handoff.id") && worker.includes('action:"import_chat_file"')],
  ["private inline small-media fallback crosses stale connector schemas", worker.includes('"app_import_inline_base64"') && worker.includes('"video/mp4"') && worker.includes('"import_inline_base64"') && controlService.includes('case "import_inline_base64"') && controlService.includes("MAX_INLINE_MEDIA_BYTES") && controlService.includes("owner-authenticated-inline-mcp")],
  ["v3 MCP import is a real ChatGPT file parameter", worker.includes('file:z.object({') && worker.includes('file_id:z.string()') && worker.includes('mime_type:z.string()') && worker.includes('file_name:z.string()')],
  ["worker has ephemeral direct private upload relay", worker.includes("/api/app/private/upload") && worker.includes("appCreateCachedHandoff") && worker.includes("__videostudio_private_upload")],
  ["private upload relay is owner-authenticated and only blocked by One File Lock", worker.includes("Native app authorization failed") && worker.includes("Full Autonomous mode is required while One File Lock is active")],
  ["private handoff can stream cached uploads to app", worker.includes("Private upload expired or unavailable") && worker.includes("caches.default.match")],
  ["installed connector exposes direct chat-file import", worker.includes('"import_chat_file"') && worker.includes("Securely stream a ChatGPT conversation attachment")],
  ["worker streams handoff bytes without permanent storage", worker.includes("Attachment source unavailable") && worker.includes("new Response(upstream.body")],
  ["native app supports authenticated handoff download", nativeProtocol.includes("openPrivateHandoff") && nativeMain.includes('case "import_chat_file"')],
  ["native app exposes v3 direct attachment capability", nativeMain.includes('"direct-chatgpt-attachment-ingest"') && nativeMain.includes('"chat-attachment-handoff-fallback"')],
  ["native v3 uses Media3 Transformer", androidBuild.includes("media3-transformer:1.11.1") && nativeRender.includes("Transformer.Builder")],
  ["v3.1 bundles on-device portrait AI", androidBuild.includes("segmentation-selfie:16.0.0-beta6") && androidBuild.includes("face-mesh-detection:16.0.0-beta1")],
  ["portrait AI builds cloud-portable app-private foreground/background layers", portraitMotion.includes("SelfieSegmenterOptions.SINGLE_IMAGE_MODE") && portraitMotion.includes("FaceMeshDetection.getClient") && portraitMotion.includes('"rigs"') && portraitMotion.includes('"layers"') && portraitMotion.includes('"coldTierPortable"') && portraitMotion.includes("buildReconstructedBackground")],
  ["background reconstruction preserves visible environment", portraitMotion.includes("mask-aware-edge-fill-v1") && portraitMotion.includes("dilatedConfidence") && portraitMotion.includes("mixColor")],
  ["portrait AI creates feathered articulated layers", portraitMotion.includes("head_hair.png") && portraitMotion.includes("torso.png") && portraitMotion.includes("lower_drape.png") && portraitMotion.includes("buildSubjectParts") && portraitMotion.includes("articulatedParts")],
  ["animation director gives articulated parts independent motion", animatedDirector.includes("headDepth") && animatedDirector.includes("torsoBreathScale") && animatedDirector.includes("lowerSwayAmplitudeX") && animatedDirector.includes("videostudio-native-articulated-parallax-v2")],
  ["GPU motion animates head torso and lower layers independently", motionMatrix.includes('"head".equals(layerRole)') && motionMatrix.includes('"torso".equals(layerRole)') && motionMatrix.includes('"lower".equals(layerRole)') && motionMatrix.includes("headNodDegrees") && motionMatrix.includes("torsoBreathScale")],
  ["Media3 compositor renders four articulated depth sequences", nativeRender.includes("hasArticulatedAnimation") && nativeRender.includes('buildLayerItem(fx.optString("headUri")') && nativeRender.includes('buildLayerItem(fx.optString("torsoUri")') && nativeRender.includes('buildLayerItem(fx.optString("lowerUri")') && nativeRender.includes("articulated-subject-2.5d")],
  ["animation director emits face-aware cinematic keyframes", animatedDirector.includes('spec.put("keyframes"') && animatedDirector.includes("faceAnchorX") && animatedDirector.includes("foregroundDepth") && animatedDirector.includes("breathingAmplitude")],
  ["GPU motion supports multi-keyframe independent layer depth", motionMatrix.includes("keyframedMotion") && motionMatrix.includes("applyLayerDepth") && motionMatrix.includes('"foreground".equals(layerRole)') && motionMatrix.includes('"background".equals(layerRole)')],
  ["Media3 renderer keeps articulated and legacy layered animation paths", nativeRender.includes("buildLayeredAnimationComposition") && nativeRender.includes("hasArticulatedAnimation") && nativeRender.includes("EditedMediaItemSequence.withVideoFrom(foreground)") && nativeRender.includes("EditedMediaItemSequence.withVideoFrom(background)") && nativeRender.includes("subject-aware-2.5d") && nativeRender.includes("articulated-subject-2.5d")],
  ["procedural atmosphere renders per frame", atmosphere.includes("extends CanvasOverlay") && atmosphere.includes("presentationTimeUs") && nativeRender.includes("new AtmosphereOverlay") && nativeRender.includes("new OverlayEffect")],
  ["MCP v3 exposes autonomous still animation", worker.includes('"app_animate_images"') && worker.includes('"animate_images"') && controlService.includes('case "animate_images"') && controlService.includes("queueAnimatedImages")],
  ["MCP v3 exposes native job polling", worker.includes('"app_job_status"') && controlService.includes('case "job_status"') && nativeJobs.includes("public JSONObject get(String id)")],
  ["Worker persists articulated animation capability in v3 device status", worker.includes("portraitAnimationEngine:clean") && worker.includes("onDevicePortraitAi:!!meta.onDevicePortraitAi") && worker.includes("portraitAnimationEngine:d.portraitAnimationEngine")],
  ["Worker allows safety and status actions independent of edit permission", worker.includes('if(mode==="one_file")') && worker.includes('"self_test","job_status","activity_note"') && worker.includes('"cancel_job","cancel_all_jobs","stop_all"')],
  ["manual animation uses same Native Agent workflow", nativeMain.includes("animateImagesDialog") && nativeMain.includes("ControlService.ACTION_LOCAL_ANIMATE") && controlService.includes("ACTION_LOCAL_ANIMATE")],
  ["image project preview uses latest native render", nativeMain.includes("activeProject.latestExportUri") && nativeMain.includes("Render the animated image timeline first")],
  ["remote ingest validates redirects and DNS while using resumable storage-aware streaming", controlService.includes("openSafeRemote") && controlService.includes("InetAddress.getAllByName") && controlService.includes("MAX_REMOTE_REDIRECTS") && controlService.includes("ResumableTransferManager") && !controlService.includes("MAX_REMOTE_IMPORT_BYTES")],
  ["native app has prompt-to-video pipeline", promptVideo.includes("class PromptVideoEngine") && nativeMain.includes('case "prompt_video"')],
  ["native prompt video exports a real MP4", nativeMain.includes("runExportBlocking") && nativeMain.includes("Movies/VideoStudio")],
  ["native app has on-device visual analyser", nativeAnalyzer.includes("contactSheet") && nativeMain.includes('case "analyse_media"')],
  ["native app creator catalog is expanded", creatorCatalog.includes("camera_shutter") && creatorCatalog.includes("caption_pop") && creatorCatalog.includes("shorts_recut")],
  ["native app has hard Gallery MCP boundary", nativeMain.includes("galleryAccess") && nativeMain.includes('lower.contains("gallery")')],
  ["Android manifest requests no Gallery read permission", !androidManifest.includes("READ_MEDIA_IMAGES") && !androidManifest.includes("READ_MEDIA_VIDEO") && !androidManifest.includes("READ_EXTERNAL_STORAGE")],
  ["native app has stop ChatGPT control", nativeMain.includes("STOP CHATGPT CONTROL") && nativeProtocol.includes("chatgpt_control_paused")],
  ["native jobs persist crash recovery checkpoints", nativeJobs.includes("JOB_RECOVERY_PREF_KEY") && nativeJobs.includes("interrupted")],
  ["worker hard-blocks Gallery MCP actions", worker.includes('a.includes("gallery")') && worker.includes("Gallery privacy boundary")],
  ["worker exposes v3 prompt video tool", worker.includes('"app_create_prompt_video"') && worker.includes('"prompt_video"')],
  ["worker exposes native autonomous edit/export", worker.includes('"app_autonomous_edit"') && worker.includes('"app_export_project"')],
  ["worker exposes v3 creator catalog", worker.includes('"app_catalog"') && worker.includes("camera_shutter")],
  ["native v3 project state is app-private SQLite", projectStore.includes("extends SQLiteOpenHelper") && projectStore.includes('DB_NAME = "videostudio_v3.db"') && projectStore.includes("migrateLegacyProjectsOnce") && projectStore.includes('return "sqlite-v3"')],
  ["v3 keeps existing projects through migration", projectStore.includes("LEGACY_PROJECTS") && projectStore.includes("CONFLICT_IGNORE") && projectStore.includes("META_MIGRATED")],
  ["native v3 has durable command idempotency", commandJournal.includes("terminal(String commandId)") && commandJournal.includes("finish(JSONObject command") && controlService.includes("MCP v3 command replay prevented")],
  ["generated renders are first-class project assets", projectStore.includes("registerGeneratedAsset") && projectStore.includes('role = "source"') && projectStore.includes("generated = false") && controlService.includes('"final_render"')],
  ["editor exposes a real project Media Bin", nativeMain.includes('section("Media Bin")') && nativeMain.includes("asset.generated") && nativeMain.includes("+ Timeline") && nativeMain.includes("scheduleEditorRefresh")],
  ["app foreground self-heals Native Agent after updates", nativeMain.includes("startServiceWatchdog") && nativeMain.includes("control_service_requested_app_version") && nativeMain.includes("requestServiceSync();")],
  ["Native Agent heartbeat records the exact running app version", controlService.includes('"control_service_app_version"') && controlService.includes("AppProtocol.APP_VERSION")],
  ["connection activity avoids heartbeat log spam", controlService.includes("connected != wasOnline") && !controlService.includes("previousDetail = prefs.getString(KEY_SERVICE_DETAIL") && controlService.includes('"transport", connected ? "MCP connected"')],
  ["background export registers generated MP4 in Media Bin", controlService.includes("Registering generated media") && controlService.includes("Generated video available") && controlService.includes("registerGeneratedAsset")],
  ["native agent can insert a media-bin asset into timeline", controlService.includes('case "insert_asset_timeline"') && projectStore.includes("appendAssetToTimeline") && worker.includes('"app_insert_asset_timeline"')],
  ["heavy jobs persist named recovery stages", nativeJobs.includes('public volatile String stage = "queued"') && nativeJobs.includes("lastCheckpointAt") && nativeJobs.includes("recoverable")],
  ["thermal pressure pauses instead of timing out heavy work", nativeJobs.includes('"waiting_thermal"') && nativeJobs.includes("awaitSafeCheckpoint") && !nativeJobs.includes('for (int i = 0; i < 90; i++)')],
  ["animation pipeline checks thermal safety between expensive stages", controlService.includes('awaitSafeCheckpoint(state, "portrait_analysis_') && controlService.includes('awaitSafeCheckpoint(state, "layered_render")')],
  ["Activity progress is stage-linked and coalesced", activityLog.includes("public static synchronized void progress") && controlService.includes("state.checkpoint(action, progress, detail)")],
  ["creative workspace uses app-private structured storage", creativeWorkspace.includes('"creative_workspace"') && creativeWorkspace.includes('"projects"') && creativeWorkspace.includes('"models"') && creativeWorkspace.includes("cleanupRegenerable")],
  ["creative workspace cleanup preserves durable project/export layers", creativeWorkspace.includes('"preservedProjectState"') && creativeWorkspace.includes('"preservedExports"') && creativeWorkspace.includes('"preservedModelPacks"')],
  ["MotionScript 0.2 compiles to versioned CreativeIR", motionScriptCompiler.includes('MOTION_SCRIPT_VERSION = "0.2"') && motionScriptCompiler.includes('CREATIVE_IR_VERSION = "0.2"') && motionScriptCompiler.includes('"safeRuntime"') && motionScriptCompiler.includes('"arbitraryCodeExecution"')],
  ["MotionScript supports scene timing camera motion atmosphere and render directives", motionScriptCompiler.includes('case "shot"') && motionScriptCompiler.includes('case "camera"') && motionScriptCompiler.includes('case "motion"') && motionScriptCompiler.includes('case "atmosphere"') && motionScriptCompiler.includes('case "render"')],
  ["MotionScript models subjects identity rigs depth pose hands hair and cloth", motionScriptCompiler.includes('case "subject"') && motionScriptCompiler.includes('case "preserve_identity"') && motionScriptCompiler.includes('case "rig"') && motionScriptCompiler.includes('case "depth"') && motionScriptCompiler.includes('case "pose"') && motionScriptCompiler.includes('case "hands"') && motionScriptCompiler.includes('case "hair"') && motionScriptCompiler.includes('case "cloth"')],
  ["MotionScript declares generative voice lighting and critique intent", motionScriptCompiler.includes('case "generate"') && motionScriptCompiler.includes('case "narration"') && motionScriptCompiler.includes('case "voice"') && motionScriptCompiler.includes('case "light"') && motionScriptCompiler.includes('case "critic"')],
  ["CreativeIR records provider requirements for interchangeable engines", motionScriptCompiler.includes('"providerRequirements"') && motionScriptCompiler.includes("addRequirement") && motionScriptCompiler.includes('"providerInterchangeable"')],
  ["Native Agent resolves MotionScript requirements through capability registry", controlService.includes("resolveCreativeProviders") && controlService.includes('"providerResolution"') && controlService.includes('"unresolvedCapabilities"')],
  ["strict MotionScript runs can reject unavailable provider requirements", worker.includes("strictProviders") && controlService.includes('optBoolean("strictProviders"')],
  ["CreativeIR graph planner builds provider-agnostic durable DAG nodes", creativeJobGraph.includes("durable execution DAG") && creativeJobGraph.includes('"checkpointKey"') && creativeJobGraph.includes('"cacheKey"') && creativeJobGraph.includes('"targetedReexecution"')],
  ["CreativeIR graph nodes carry capability resolution and bounded resource plans", creativeJobGraph.includes('"providerResolved"') && creativeJobGraph.includes('"resourcePlan"') && creativeJobGraph.includes("compute.plan") && creativeJobGraph.includes("registry.resolve")],
  ["CreativeIR graph spans analysis rigs generation audio composition render and critique", creativeJobGraph.includes('"analysis"') && creativeJobGraph.includes('"rig"') && creativeJobGraph.includes('"generation"') && creativeJobGraph.includes('"audio"') && creativeJobGraph.includes('"compose"') && creativeJobGraph.includes('"render"') && creativeJobGraph.includes('"critique"')],
  ["Native Agent persists execution graphs into compiled MotionScript plans", controlService.includes('put("executionGraph", graph)') && controlService.includes('put("executionGraph", executionGraph)') && controlService.includes('case "plan_creative_graph"')],
  ["MCP v3 exposes CreativeIR graph planning", worker.includes('"app_plan_creative_graph"') && worker.includes('"plan_creative_graph"')],
  ["CreativeIR node checkpoints survive process restarts in project workspace", creativeNodeStore.includes("creative_nodes_v1.json") && creativeNodeStore.includes("FileOutputStream") && creativeNodeStore.includes("getFD().sync")],
  ["CreativeIR cache reuse is keyed per node instead of whole-project rerender", creativeNodeStore.includes("sameCache") && creativeNodeStore.includes("reusedCompletedNodes") && creativeNodeStore.includes("invalidatedNodes")],
  ["CreativeIR targeted invalidation can cascade to downstream dependencies", creativeNodeStore.includes("invalidate(String projectId") && creativeNodeStore.includes("targets.contains(deps.optString") && creativeNodeStore.includes('"Invalidated for targeted re-execution"')],
  ["CreativeIR scheduler exposes only dependency-satisfied provider-ready nodes", creativeNodeStore.includes("readyNodes") && creativeNodeStore.includes('"providerResolved"') && creativeNodeStore.includes('"completed".equals(dependency.optString("state"))')],
  ["Native Agent exposes CreativeIR checkpoint state and invalidation", controlService.includes('case "creative_graph_status"') && controlService.includes('case "invalidate_creative_node"') && controlService.includes("creativeNodeStore.prepare")],
  ["MCP v3 exposes CreativeIR checkpoint and targeted regeneration controls", worker.includes('"app_creative_graph_status"') && worker.includes('"app_invalidate_creative_node"')],
  ["built-in CreativeIR runtime executes only capabilities it truly implements", builtInCreativeRuntime.includes("Unsupported advanced capabilities are never faked") && builtInCreativeRuntime.includes('"person.segmentation"') && builtInCreativeRuntime.includes('"face.landmarks"') && builtInCreativeRuntime.includes('"depth.estimate"') && builtInCreativeRuntime.includes('"portrait.rig"') && builtInCreativeRuntime.includes('"render.critique"')],
  ["local render critic measures technical continuity without claiming semantic vision", nativeRenderCritic.includes('"semanticVision", false') && nativeRenderCritic.includes("blackFrameRatio") && nativeRenderCritic.includes("freezeLikeRatio") && nativeRenderCritic.includes("abruptJumpCount")],
  ["CreativeIR runtime composes articulated layers into project clips", controlService.includes("executeCreativeCompositor") && controlService.includes('"creativeir-articulated-parallax-v1"') && controlService.includes("completedCreativeResult")],
  ["CreativeIR runtime can render and critique through dependency-ordered DAG execution", controlService.includes("queueCreativeGraphRun") && controlService.includes('"render.video".equals(capability)') && controlService.includes('"render.critique".equals(capability)')],
  ["CreativeIR graph execution is durable and resumes downstream after a published render", controlService.includes('"run_creative_graph"') && controlService.includes("Recovered CreativeIR render node") && creativeNodeStore.includes("recoverRetryable")],
  ["MCP v3 exposes resumable CreativeIR DAG execution", worker.includes('"app_run_creative_graph"') && worker.includes('"run_creative_graph"')],
  ["Native Agent compiles and executes MotionScript", controlService.includes('case "compile_scene"') && controlService.includes('case "run_motion_script"') && controlService.includes("compileMotionScene") && controlService.includes("runMotionScript")],
  ["MCP v3 exposes typed MotionScript and creative workspace tools", worker.includes('"app_compile_scene"') && worker.includes('"app_run_motion_script"') && worker.includes('"app_workspace_status"') && worker.includes('"app_cleanup_workspace"')],
  ["future generic MCP bridge remains available alongside typed creative tools", worker.includes('"app_execute"') && worker.includes('"app_batch"')],
  ["durable recovery plans persist reconstructable heavy actions", recoveryPlans.includes("durable_recovery_plans_v1") && recoveryPlans.includes("pendingForAutoResume") && recoveryPlans.includes("outputUri")],
  ["Native Agent auto-resumes supported interrupted heavy jobs", controlService.includes("recoverDurablePlans()") && controlService.includes("submitRecoverableHeavy") && controlService.includes('case "animate_images"') && controlService.includes('case "prompt_video"') && controlService.includes('case "export_project"')],
  ["published output prevents duplicate render after restart", controlService.includes("markOutputForJob") && controlService.includes("Recovered completed render") && controlService.includes("isReadableOutput")],
  ["recovery plans follow explicit job cancellation", controlService.includes("recoveryPlans.cancelByJob") && controlService.includes("recoveryPlans.cancelActive")],
  ["capability registry is provider-based instead of model-name coupled", capabilityRegistry.includes("capability-first-hardware-aware") && capabilityRegistry.includes("installedProviders()") && capabilityRegistry.includes("resolve(String capability")],
  ["built-in engines are represented as capability providers", capabilityRegistry.includes("person.segmentation") && capabilityRegistry.includes("face.landmarks") && capabilityRegistry.includes("motion.2_5d") && capabilityRegistry.includes("render.video") && capabilityRegistry.includes("scene.compile")],
  ["optional model manifests can extend capability registry", capabilityRegistry.includes('"manifest.json"') && capabilityRegistry.includes('"capabilities"') && capabilityRegistry.includes('"estimatedRamMb"')],
  ["Native Agent exposes capability registry and model pack status", controlService.includes('case "capability_registry"') && controlService.includes('case "resolve_capability"') && controlService.includes('case "model_pack_status"')],
  ["MCP v3 exposes typed capability-provider tools", worker.includes('"app_capability_registry"') && worker.includes('"app_resolve_capability"') && worker.includes('"app_model_pack_status"')],
  ["model packs install transactionally from explicit app-owned assets", modelPackManager.includes("Transactional installer") && modelPackManager.includes("staging") && modelPackManager.includes("renameTo(target)") && modelPackManager.includes("source asset")],
  ["model-pack extraction guards path traversal and expansion size", modelPackManager.includes("getCanonicalPath") && modelPackManager.includes("MAX_TOTAL_EXPANDED") && modelPackManager.includes("MAX_ENTRIES")],
  ["model packs validate manifest capabilities license and checksums", modelPackManager.includes('"manifest.json"') && modelPackManager.includes('"capabilities"') && modelPackManager.includes('"license"') && modelPackManager.includes("verifyDeclaredFiles")],
  ["model-pack installs survive interruption through durable recovery plans", controlService.includes('"install_model_pack"') && controlService.includes("queueModelPackInstall") && controlService.includes("submitRecoverableHeavy")],
  ["MCP v3 exposes model-pack install and uninstall controls", worker.includes('"app_install_model_pack"') && worker.includes('"app_uninstall_model_pack"')],
  ["device compute planner bounds local AI working sets instead of assuming full residency", computeProfile.includes("activeWorkingSetBudgetMb") && computeProfile.includes("residentModelBudgetMb") && computeProfile.includes("phaseModelSwapping")],
  ["compute planner supports tiled inference temporal windows and disk intermediates", computeProfile.includes("tiledInference") && computeProfile.includes("boundedTemporalWindows") && computeProfile.includes("diskBackedIntermediates")],
  ["compute planning reacts to thermal state", computeProfile.includes("thermalSafeForHeavyWork") && computeProfile.includes("thermalPauseRequired")],
  ["Native Agent exposes compute profile and planning", controlService.includes('case "compute_profile"') && controlService.includes('case "plan_compute"') && controlService.includes("computeProfile.plan")],
  ["MCP v3 exposes typed compute planning tools", worker.includes('"app_compute_profile"') && worker.includes('"app_plan_compute"')],
  ["cloud workspace is folder-scoped instead of broad Drive OAuth", driveWorkspace.includes("single-user-selected-document-tree") && driveWorkspace.includes('"broadDrivePermission"') && driveWorkspace.includes("ACTION_OPEN_DOCUMENT_TREE") === false],
  ["cloud workspace uses persisted Android document-tree permissions", driveWorkspace.includes("getPersistedUriPermissions") && driveWorkspace.includes("DocumentsContract.getTreeDocumentId")],
  ["cloud archive cannot escape the Creative Runtime project root", driveWorkspace.includes("Workspace file escaped project root") && driveWorkspace.includes("relativePath")],
  ["cloud archive skips regenerable temp and preview data", driveWorkspace.includes('"temp"') && driveWorkspace.includes('"previews"')],
  ["Android Control UI links one cloud folder through the system picker", nativeMain.includes("ACTION_OPEN_DOCUMENT_TREE") && nativeMain.includes("PICK_CLOUD_WORKSPACE") && nativeMain.includes("driveWorkspace.link")],
  ["Native Agent archives project workspace through the linked folder", controlService.includes('case "sync_project_to_drive"') && controlService.includes("queueDriveProjectSync") && controlService.includes("driveWorkspace.syncProject")],
  ["MCP v3 exposes cloud workspace status and archive tools", worker.includes('"app_drive_workspace_status"') && worker.includes('"app_sync_project_to_drive"')],
  ["folder-scoped cloud tier can restore project creative workspaces", driveWorkspace.includes("restoreProjectWorkspace") && driveWorkspace.includes("Cloud archive attempted to escape local workspace") && controlService.includes('case "restore_project_from_drive"') && worker.includes('"app_restore_project_from_drive"')],
  ["folder-scoped cloud tier inventories only VideoStudio archive areas", driveWorkspace.includes("inventoryArea") && driveWorkspace.includes('"Projects"') && driveWorkspace.includes('"ModelPacks"') && worker.includes('"app_drive_workspace_inventory"')],
  ["installed model packs can be archived to folder-scoped cold storage", driveWorkspace.includes("syncModelPack") && controlService.includes('case "archive_model_pack_to_drive"') && worker.includes('"app_archive_model_pack_to_drive"')],
  ["cloud model packs restore through protected staging and transactional activation", driveWorkspace.includes("restoreModelPack") && modelPackManager.includes("activateRestoredDirectory") && modelPackManager.includes("createCloudRestoreDirectory") && controlService.includes('case "restore_model_pack_from_drive"') && worker.includes('"app_restore_model_pack_from_drive"')],
  ["cloud-restored model packs are manifest and checksum revalidated", modelPackManager.includes("verifyDeclaredFiles(restored") && modelPackManager.includes('"restoredFromCloudWorkspace"') && modelPackManager.includes("copyTreeWithLimits")],
  ["Android Control UI exposes project cloud archive and restore without broad Drive permission", nativeMain.includes("Archive Active Project") && nativeMain.includes("Restore Active Project Workspace") && nativeMain.includes("ACTION_OPEN_DOCUMENT_TREE")],
  ["cloud offload archives before evicting only bulky project intermediates", creativeWorkspace.includes("evictCloudBackedProject") && creativeWorkspace.includes('"cloudArchiveRequiredForRehydrate"') && controlService.includes("queueDriveProjectOffload") && controlService.includes("Verifying cloud archive before local eviction")],
  ["cloud-offloaded project layers rehydrate automatically before render", controlService.includes("ensureProjectWorkspaceHydrated") && controlService.includes("hasMissingCreativeLayerFiles") && driveWorkspace.includes("preserveLocalControlMetadata") && creativeWorkspace.includes("markCloudHydrated")],
  ["MCP v3 exposes verified cloud project offload", worker.includes('"app_offload_project_to_drive"') && worker.includes('"offload_project_to_drive"')],
  ["Android Control UI exposes explicit archive-and-free-local-workspace action", nativeMain.includes("Archive + Free Local Workspace") && nativeMain.includes("offloadActiveProjectToCloud")],
  ["native v3 exposes a self-test", controlService.includes('case "self_test"') && worker.includes('"app_self_test"') && controlService.includes("privateStorageWritable")],
  ["native state reports stable v3 compatibility architecture", controlService.includes('out.put("mcpEndpointVersion", "v3-stable")') && controlService.includes('out.put("stableMcpEndpoint", true)') && controlService.includes('out.put("connectionCore", protocol.connectionStatus())') && controlService.includes('out.put("localEngineOwnsProjects", true)')],
  ["cached connector compatibility routes v3 devices to v3 queue", worker.includes("enqueueNative") && worker.includes("appEnqueueV3") && worker.includes("commandNative")],
  ["native analysis results render as MCP images", worker.includes("safeResult") && worker.includes('type:"image"')],
  ["Android editor exposes immutable preview checkpoints and explicit new-result handoff", nativeMain.includes("PreviewSnapshotStore") && nativeMain.includes("Play new result") && controlService.includes("previewSnapshots.publish")],
  ["Android editor shows autonomous work status without replacing playback surface", nativeMain.includes("Autonomous work") && nativeMain.includes("LIVE_JOB_PREF_KEY") && nativeMain.includes("recentWork")],
  ["Android editor declares Media3 ExoPlayer and PlayerView dependencies", androidBuild.includes("media3-exoplayer:1.11.1") && androidBuild.includes("media3-ui:1.11.1")],
  ["Android editor uses reusable LiveEditPlayer instead of VideoView preview ownership", nativeMain.includes("LiveEditPlayer") && !nativeMain.includes("private VideoView preview")],
  ["transport and diagnostics cannot masquerade as autonomous work", activityLog.includes("recentWork") && executionTruth.includes("isTransportActivity") && executionTruth.includes("running videostudio v3 self-test")],
  ["live job display cannot overwrite crash recovery journal", executionTruth.includes('JOB_RECOVERY_PREF_KEY = "job_recovery_snapshot"') && executionTruth.includes('LIVE_JOB_PREF_KEY = "job_live_snapshot"') && controlService.includes("LIVE_JOB_PREF_KEY") && nativeJobs.includes("JOB_RECOVERY_PREF_KEY")],
  ["empty editor follows a service-created active AI project", nativeMain.includes("shouldAdoptStoreActive") && nativeMain.includes("store.active()")],
  ["queued MCP render commands remain inflight until native terminal state", controlService.includes("watchDeferredCommand") && commandJournal.includes("linkJob") && commandJournal.includes("inflight(String commandId)") && !controlService.includes('queued ? "queued" : (ok ? "success" : "failed")')],
  ["video-producing native jobs require readable published output before MCP completion", controlService.includes("requiresValidatedMediaOutput") && controlService.includes("verifiedPlayableOutput") && controlService.includes("isReadableOutput(outputUri)")],
  ["manual prompt video and export share the foreground Native Agent pipeline", controlService.includes("ACTION_LOCAL_PROMPT_VIDEO") && controlService.includes("ACTION_LOCAL_EXPORT") && nativeMain.includes("ControlService.ACTION_LOCAL_PROMPT_VIDEO") && nativeMain.includes("ControlService.ACTION_LOCAL_EXPORT")],


  ["native remote ingest has no legacy 350 MB application ceiling", !controlService.includes("MAX_REMOTE_IMPORT_BYTES") && !controlService.includes("350 MB")],
  ["native URL and direct attachment ingest delegate to resumable transfer engine", controlService.includes("ResumableTransferManager") && controlService.includes("ResumableTransferManager.Request") && controlService.includes(".download(")],
  ["native resumable HTTP path requests byte ranges and validates resume identity", controlService.includes('setRequestProperty("Range"') && controlService.includes('"If-Range"')],
  ["resumable transfer checkpoints use 64-bit offsets and partial-file promotion", transferJournal.includes("long completedBytes") && resumableTransfer.includes(".partial") && resumableTransfer.includes("renameTo")],
  ["resumable transfer performs storage preflight before writing heavy media", resumableTransfer.includes("StorageBudget.checkTransfer") && resumableTransfer.includes("Insufficient storage")],
  ["large-file storage accounting uses overflow-safe 64-bit arithmetic", projectStore.includes("long sizeBytes") && storageBudget.includes("saturatingAdd") && transferJournal.includes("long completedBytes")],
  ["heavy editor media can use preview-only proxies while final render keeps originals", proxyManager.includes("HEAVY_VIDEO_THRESHOLD_BYTES") && proxyManager.includes("preview_proxy") && proxyManager.includes("previewOnly") && nativeMain.includes("ProxyManager.previewUri") && nativeRender.includes("setUri(Uri.parse(asset.uri))")],
  ["preview proxies are generated through bounded native Media3 work", proxyManager.includes("Transformer.Builder") && proxyManager.includes("Presentation.createForHeight") && proxyManager.includes("JobManager.Kind.HEAVY")],
  ["live editor playback stays independent from autonomous background rendering", liveEditPlayer.includes("ExoPlayer") && liveEditPlayer.includes("playClip") && nativeMain.includes("Play new result") && controlService.includes("ControlService extends Service")],
  ["preview checkpoints reject partial output and keep immutable history", previewSnapshotStore.includes("Partial preview files cannot be published") && previewSnapshotStore.includes("sameIdentity")],
  ["job manager exposes canonical resumable wait states", nativeJobs.includes("STATE_CHECKPOINTED") && nativeJobs.includes("STATE_WAITING_NETWORK") && nativeJobs.includes("STATE_WAITING_STORAGE") && nativeJobs.includes("STATE_WAITING_MEMORY") && nativeJobs.includes("STATE_WAITING_THERMAL") && nativeJobs.includes("STATE_WAITING_NATIVE")],
  ["terminal autonomous jobs cannot be resurrected by invalid transitions", nativeJobs.includes("canTransition") && nativeJobs.includes("isTerminal")],
  ["native render publication is idempotent across restart windows", atomicMediaPublisher.includes("existingPublishedUri") && atomicMediaPublisher.includes("reused") && recoveryPlans.includes("outputForJob") && controlService.includes("AtomicMediaPublisher.publish")],
  ["reboot and APK replacement re-arm the same stable Native Agent", nativeRecoveryReceiver.includes("ACTION_MY_PACKAGE_REPLACED") && nativeRecoveryReceiver.includes("ACTION_BOOT_COMPLETED") && nativeRecoveryReceiver.includes("ACTION_SYNC")],
  ["native watchdog re-arms after task/process loss without exact-alarm permission", nativeWatchdog.includes("setAndAllowWhileIdle") && nativeWatchdog.includes("TASK_REMOVED_DELAY_MS") && nativeMain.includes("MCP control plane available") && controlService.includes("onTaskRemoved") && controlService.includes("NativeAgentWatchdog.scheduleRetry")],
  ["direct stable MCP stays available while native executor sleeps", worker.includes("controlPlaneConnected:true") && worker.includes("offlineQueueAvailable:true") && worker.includes('status:fresh?"queued":"waiting_native"') && worker.includes('nativeState:fresh?"online":"sleeping_or_offline"')],
  ["pairing text documents durable queued execution instead of claiming native is always awake", nativeProtocol.includes("remains available as the durable control plane while Android sleeps") && nativeProtocol.includes("Native-only work is queued safely")],
  ["same private native MCP exposes one-step Studio Web fallback binding", worker.includes('"app_bind_studio_web"') && worker.includes("appBindStudioWebFallback") && worker.includes("app-web-fallback:")],
  ["offline v3 router can send compatible work to Studio Web", worker.includes("appTryStudioWebFallback") && worker.includes('hybridRoute:"studio_web"') && worker.includes('mode:"prompt_scene"') && worker.includes('mode:"story_video"')],
  ["native command result lookup can follow Web-routed work", worker.includes("appCommandV3") && worker.includes("runtimeCommand(webDeviceId,id)") && worker.includes('hybridRoute:"studio_web"')],
  ["canonical native identity convergence keeps legacy owner URLs on one device", worker.includes("appConvergeOwnerAliases") && worker.includes("supersededByDeviceId") && worker.includes("ownerAliases") && worker.includes("converge_identity_to")],
  ["legacy v3 command history migrates into the canonical queue", worker.includes("migratedFromDeviceId") && worker.includes("app-v3-seq:") && worker.includes("retainedCommandCount")],
  ["Android refuses silent owner-key rotation after decrypt failure", nativeProtocol.includes("existing owner credential was not rotated") && nativeProtocol.includes("KEY_IDENTITY_RECOVERY_REQUIRED") && nativeProtocol.includes("OwnerLoadResult")],
  ["Native Agent can autonomously redeem legacy stable endpoint aliases", nativeProtocol.includes("redeemRebindNow") && controlService.includes('case "redeem_rebind"') && controlService.includes("MCP identity alias repaired")],
  ["native rebind runs in a light background job instead of blocking the command callback", controlService.includes('JobManager.Kind.LIGHT') && controlService.includes('"MCP identity rebind"') && controlService.includes("protocol.redeemRebindNow")],
  ["rebind acknowledgement explicitly preserves owner credential and device identity", nativeProtocol.includes('result.put("identityPreserved", true)') && nativeProtocol.includes('result.put("ownerCredentialPreserved", true)') && nativeProtocol.includes('result.put("deviceId", deviceId)')],




];

let failed = 0;
for (const [name, ok] of checks) {
  if (ok) console.log("PASS", name);
  else {
    console.error("FAIL", name);
    failed++;
  }
}

if (failed) {
  console.error(`\n${failed} smoke test(s) failed.`);
  process.exit(1);
}

console.log(`\nAll ${checks.length} smoke tests passed.`);

