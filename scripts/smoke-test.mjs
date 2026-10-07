import fs from "node:fs";

const app = fs.readFileSync(new URL("../src/app.html", import.meta.url), "utf8");
const worker = fs.readFileSync(new URL("../src/index.js", import.meta.url), "utf8");
const wrangler = fs.readFileSync(new URL("../wrangler.jsonc", import.meta.url), "utf8");
const nativeMain = fs.readFileSync(new URL("../android/app/src/main/java/com/rezoxnemesis/videostudio/MainActivity.java", import.meta.url), "utf8");
const nativeProtocol = fs.readFileSync(new URL("../android/app/src/main/java/com/rezoxnemesis/videostudio/AppProtocol.java", import.meta.url), "utf8");
const nativeJobs = fs.readFileSync(new URL("../android/app/src/main/java/com/rezoxnemesis/videostudio/JobManager.java", import.meta.url), "utf8");
const nativeRender = fs.readFileSync(new URL("../android/app/src/main/java/com/rezoxnemesis/videostudio/NativeRenderEngine.java", import.meta.url), "utf8");
const promptVideo = fs.readFileSync(new URL("../android/app/src/main/java/com/rezoxnemesis/videostudio/PromptVideoEngine.java", import.meta.url), "utf8");
const nativeAnalyzer = fs.readFileSync(new URL("../android/app/src/main/java/com/rezoxnemesis/videostudio/NativeMediaAnalyzer.java", import.meta.url), "utf8");
const creatorCatalog = fs.readFileSync(new URL("../android/app/src/main/java/com/rezoxnemesis/videostudio/CreatorCatalog.java", import.meta.url), "utf8");
const androidBuild = fs.readFileSync(new URL("../android/app/build.gradle.kts", import.meta.url), "utf8");
const androidManifest = fs.readFileSync(new URL("../android/app/src/main/AndroidManifest.xml", import.meta.url), "utf8");
const controlService = fs.readFileSync(new URL("../android/app/src/main/java/com/rezoxnemesis/videostudio/ControlService.java", import.meta.url), "utf8");
const commandJournal = fs.readFileSync(new URL("../android/app/src/main/java/com/rezoxnemesis/videostudio/CommandJournal.java", import.meta.url), "utf8");
const projectStore = fs.readFileSync(new URL("../android/app/src/main/java/com/rezoxnemesis/videostudio/ProjectStore.java", import.meta.url), "utf8");
const portraitMotion = fs.readFileSync(new URL("../android/app/src/main/java/com/rezoxnemesis/videostudio/NativePortraitMotionAnalyzer.java", import.meta.url), "utf8");
const animatedDirector = fs.readFileSync(new URL("../android/app/src/main/java/com/rezoxnemesis/videostudio/AnimatedSceneDirector.java", import.meta.url), "utf8");
const motionMatrix = fs.readFileSync(new URL("../android/app/src/main/java/com/rezoxnemesis/videostudio/MotionMatrixEffect.java", import.meta.url), "utf8");
const atmosphere = fs.readFileSync(new URL("../android/app/src/main/java/com/rezoxnemesis/videostudio/AtmosphereOverlay.java", import.meta.url), "utf8");
const creativeWorkspace = fs.readFileSync(new URL("../android/app/src/main/java/com/rezoxnemesis/videostudio/CreativeWorkspace.java", import.meta.url), "utf8");
const motionScriptCompiler = fs.readFileSync(new URL("../android/app/src/main/java/com/rezoxnemesis/videostudio/MotionScriptCompiler.java", import.meta.url), "utf8");
const activityLog = fs.readFileSync(new URL("../android/app/src/main/java/com/rezoxnemesis/videostudio/ActivityLog.java", import.meta.url), "utf8");
const recoveryPlans = fs.readFileSync(new URL("../android/app/src/main/java/com/rezoxnemesis/videostudio/RecoveryPlanStore.java", import.meta.url), "utf8");
const capabilityRegistry = fs.readFileSync(new URL("../android/app/src/main/java/com/rezoxnemesis/videostudio/CapabilityRegistry.java", import.meta.url), "utf8");
const modelPackManager = fs.readFileSync(new URL("../android/app/src/main/java/com/rezoxnemesis/videostudio/ModelPackManager.java", import.meta.url), "utf8");
const computeProfile = fs.readFileSync(new URL("../android/app/src/main/java/com/rezoxnemesis/videostudio/DeviceComputeProfile.java", import.meta.url), "utf8");

const scriptMatch = app.match(/<script>([\s\S]*?)<\/script>/);
let appScriptParses = false;
try {
  if (!scriptMatch) throw new Error("Inline script not found");
  new Function(scriptMatch[1]);
  appScriptParses = true;
} catch (error) {
  console.error("APP SCRIPT SYNTAX ERROR:", error.message);
}

const checks = [
  ["inline app JavaScript parses", appScriptParses],
  ["app has Connect to ChatGPT control", app.includes("Connect to ChatGPT")],
  ["app has media import", app.includes('id="fileInput"')],
  ["app has timeline", app.includes('id="timeline"')],
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
  ["native app uses MCP v3 endpoint only for pairing", nativeProtocol.includes('MCP_PATH = "/app-mcp-v3/"') && nativeProtocol.includes('API_PREFIX = "/api/v3/app"') && nativeProtocol.includes("PROTOCOL_VERSION = 3") && nativeProtocol.includes("AndroidKeyStore")],
  ["native app identifies as VideoStudio 3.3.0 while retaining MCP v3", nativeProtocol.includes('APP_VERSION = "3.3.0"') && nativeProtocol.includes("PROTOCOL_VERSION = 3") && androidBuild.includes('versionName = "3.3.0"') && androidBuild.includes("versionCode = 330")],
  ["pairing message explicitly says VideoStudio v3", nativeProtocol.includes("VideoStudio v3 Android Native Agent MCP") && nativeProtocol.includes("MCP v3 endpoint")],
  ["native v3 command cursor advances only after completion", nativeProtocol.includes('KEY_SEQ = "native_v3_last_seq"') && nativeProtocol.includes("advanceSequence")],
  ["native app has bounded heavy-work scheduler", nativeJobs.includes("Semaphore") && nativeJobs.includes("THERMAL_STATUS_SEVERE")],
  ["worker exposes canonical VideoStudio App MCP v3", worker.includes('"VideoStudio-App-MCP-v3"') && worker.includes('version:isV3?"3.3.0"') && worker.includes("appMcpV3") && worker.includes("serverForApp(env,ownerKey,3)")],
  ["worker has dedicated v3 API namespace", worker.includes('"/api/v3/app/register"') && worker.includes('"/api/v3/app/commands"') && worker.includes("appCompleteV3")],
  ["worker uses an isolated v3 command queue", worker.includes('"app-v3-seq:"') && worker.includes('"app-v3-cl:"') && worker.includes("protocolVersion:3")],
  ["worker rejects unbound native credentials", worker.includes("Private App MCP credential rejected")],
  ["worker leases native commands", worker.includes('status:"claimed"') && worker.includes("leaseUntil")],
  ["v3 leases track retries", worker.includes("claimCount:Number(c.claimCount||0)+1")],
  ["worker keeps legacy private chat handoff as fallback", worker.includes("appCreateHandoff") && worker.includes("app_import_chat_file")],
  ["v3 primary attachment path is direct app ingest", worker.includes('"app_import_attachment"') && worker.includes('"import_attachment"') && controlService.includes('case "import_attachment"') && controlService.includes("queueDirectAttachmentImport")],
  ["v3 direct attachment bytes bypass Worker", worker.includes('"openai/fileParams":["file"]') && worker.includes("download_url") && controlService.includes("MCPv3-SafeIngest")],
  ["private inline still-frame fallback crosses stale connector schemas", worker.includes('"app_import_inline_base64"') && worker.includes('"import_inline_base64"') && controlService.includes('case "import_inline_base64"') && controlService.includes("MAX_INLINE_IMAGE_BYTES") && controlService.includes("owner-authenticated-inline-mcp")],
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
  ["portrait AI builds app-private foreground/background layers", portraitMotion.includes("SelfieSegmenterOptions.SINGLE_IMAGE_MODE") && portraitMotion.includes("FaceMeshDetection.getClient") && portraitMotion.includes("animation_layers/") && portraitMotion.includes("buildReconstructedBackground")],
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
  ["remote ingest validates redirects DNS and byte limits", controlService.includes("openSafeRemote") && controlService.includes("InetAddress.getAllByName") && controlService.includes("MAX_REMOTE_IMPORT_BYTES") && controlService.includes("MAX_REMOTE_REDIRECTS")],
  ["native app has prompt-to-video pipeline", promptVideo.includes("class PromptVideoEngine") && nativeMain.includes('case "prompt_video"')],
  ["native prompt video exports a real MP4", nativeMain.includes("runExportBlocking") && nativeMain.includes("Movies/VideoStudio")],
  ["native app has on-device visual analyser", nativeAnalyzer.includes("contactSheet") && nativeMain.includes('case "analyse_media"')],
  ["native app creator catalog is expanded", creatorCatalog.includes("camera_shutter") && creatorCatalog.includes("caption_pop") && creatorCatalog.includes("shorts_recut")],
  ["native app has hard Gallery MCP boundary", nativeMain.includes("galleryAccess") && nativeMain.includes('lower.contains("gallery")')],
  ["Android manifest requests no Gallery read permission", !androidManifest.includes("READ_MEDIA_IMAGES") && !androidManifest.includes("READ_MEDIA_VIDEO") && !androidManifest.includes("READ_EXTERNAL_STORAGE")],
  ["native app has stop ChatGPT control", nativeMain.includes("STOP CHATGPT CONTROL") && nativeProtocol.includes("chatgpt_control_paused")],
  ["native jobs persist crash recovery checkpoints", nativeJobs.includes("job_recovery_snapshot") && nativeJobs.includes("interrupted")],
  ["worker hard-blocks Gallery MCP actions", worker.includes('a.includes("gallery")') && worker.includes("Gallery privacy boundary")],
  ["worker exposes v3 prompt video tool", worker.includes('"app_create_prompt_video"') && worker.includes('"prompt_video"')],
  ["worker exposes native autonomous edit/export", worker.includes('"app_autonomous_edit"') && worker.includes('"app_export_project"')],
  ["worker exposes v3 creator catalog", worker.includes('"app_catalog"') && worker.includes("camera_shutter")],
  ["native v3 project state is app-private SQLite", projectStore.includes("extends SQLiteOpenHelper") && projectStore.includes('DB_NAME = "videostudio_v3.db"') && projectStore.includes("migrateLegacyProjectsOnce") && projectStore.includes('return "sqlite-v3"')],
  ["v3 keeps existing projects through migration", projectStore.includes("LEGACY_PROJECTS") && projectStore.includes("CONFLICT_IGNORE") && projectStore.includes("META_MIGRATED")],
  ["native v3 has durable command idempotency", commandJournal.includes("terminal(String commandId)") && commandJournal.includes("finish(JSONObject command") && controlService.includes("MCP v3 command replay prevented")],
  ["generated renders are first-class project assets", projectStore.includes("registerGeneratedAsset") && projectStore.includes('role = "source"') && projectStore.includes("generated = false") && controlService.includes('"final_render"')],
  ["editor exposes a real project Media Bin", nativeMain.includes('section("Media Bin")') && nativeMain.includes("asset.generated") && nativeMain.includes("+ Timeline") && nativeMain.includes("scheduleEditorRefresh")],
  ["background export registers generated MP4 in Media Bin", controlService.includes("Registering generated media") && controlService.includes("Generated video available") && controlService.includes("registerGeneratedAsset")],
  ["native agent can insert a media-bin asset into timeline", controlService.includes('case "insert_asset_timeline"') && projectStore.includes("appendAssetToTimeline") && worker.includes('"app_insert_asset_timeline"')],
  ["heavy jobs persist named recovery stages", nativeJobs.includes('public volatile String stage = "queued"') && nativeJobs.includes("lastCheckpointAt") && nativeJobs.includes("recoverable")],
  ["thermal pressure pauses instead of timing out heavy work", nativeJobs.includes('"waiting_thermal"') && nativeJobs.includes("awaitSafeCheckpoint") && !nativeJobs.includes('for (int i = 0; i < 90; i++)')],
  ["animation pipeline checks thermal safety between expensive stages", controlService.includes('awaitSafeCheckpoint(state, "portrait_analysis_') && controlService.includes('awaitSafeCheckpoint(state, "layered_render")')],
  ["Activity progress is stage-linked and coalesced", activityLog.includes("public static synchronized void progress") && controlService.includes("state.checkpoint(action, progress, detail)")],
  ["creative workspace uses app-private structured storage", creativeWorkspace.includes('"creative_workspace"') && creativeWorkspace.includes('"projects"') && creativeWorkspace.includes('"models"') && creativeWorkspace.includes("cleanupRegenerable")],
  ["creative workspace cleanup preserves durable project/export layers", creativeWorkspace.includes('"preservedProjectState"') && creativeWorkspace.includes('"preservedExports"') && creativeWorkspace.includes('"preservedModelPacks"')],
  ["MotionScript 0.1 compiles to versioned CreativeIR", motionScriptCompiler.includes('MOTION_SCRIPT_VERSION = "0.1"') && motionScriptCompiler.includes('CREATIVE_IR_VERSION = "0.1"') && motionScriptCompiler.includes('"safeRuntime"') && motionScriptCompiler.includes('"arbitraryCodeExecution"')],
  ["MotionScript supports scene timing camera motion atmosphere and render directives", motionScriptCompiler.includes('case "shot"') && motionScriptCompiler.includes('case "camera"') && motionScriptCompiler.includes('case "motion"') && motionScriptCompiler.includes('case "atmosphere"') && motionScriptCompiler.includes('case "render"')],
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
  ["Native Agent exposes compute profile and planning", controlService.includes('case "compute_profile"') && controlService.includes('case "plan_compute"') && controlService.includes("computeProfile.plan")),
  ["MCP v3 exposes typed compute planning tools", worker.includes('"app_compute_profile"') && worker.includes('"app_plan_compute"')],
  ["native v3 exposes a self-test", controlService.includes('case "self_test"') && worker.includes('"app_self_test"') && controlService.includes("privateStorageWritable")],
  ["native state reports v3 architecture", controlService.includes('out.put("mcpEndpointVersion", "v3")') && controlService.includes('out.put("localEngineOwnsProjects", true)')],
  ["cached connector compatibility routes v3 devices to v3 queue", worker.includes("enqueueNative") && worker.includes("appEnqueueV3") && worker.includes("commandNative")],
  ["native analysis results render as MCP images", worker.includes("safeResult") && worker.includes('type:"image"')],
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
