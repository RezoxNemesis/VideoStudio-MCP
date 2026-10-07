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
  ["native app has three permission modes", nativeMain.includes("Allow one file") && nativeMain.includes("Allow all tools") && nativeMain.includes("Allow everything")],
  ["native app has green screen and slow motion tools", nativeMain.includes("Green Screen") && nativeMain.includes("Slow Motion")],
  ["native app uses MCP v3 endpoint only for pairing", nativeProtocol.includes('MCP_PATH = "/app-mcp-v3/"') && nativeProtocol.includes('API_PREFIX = "/api/v3/app"') && nativeProtocol.includes("PROTOCOL_VERSION = 3") && nativeProtocol.includes("AndroidKeyStore")],
  ["native app identifies as VideoStudio 3.2.0 while retaining MCP v3", nativeProtocol.includes('APP_VERSION = "3.2.0"') && nativeProtocol.includes("PROTOCOL_VERSION = 3") && androidBuild.includes('versionName = "3.2.0"') && androidBuild.includes("versionCode = 320")],
  ["pairing message explicitly says VideoStudio v3", nativeProtocol.includes("VideoStudio v3 Android Native Agent MCP") && nativeProtocol.includes("MCP v3 endpoint")],
  ["native v3 command cursor advances only after completion", nativeProtocol.includes('KEY_SEQ = "native_v3_last_seq"') && nativeProtocol.includes("advanceSequence")],
  ["native app has bounded heavy-work scheduler", nativeJobs.includes("Semaphore") && nativeJobs.includes("THERMAL_STATUS_SEVERE")],
  ["worker exposes canonical VideoStudio App MCP v3", worker.includes('"VideoStudio-App-MCP-v3"') && worker.includes('version:isV3?"3.2.0"') && worker.includes("appMcpV3") && worker.includes("serverForApp(env,ownerKey,3)")],
  ["worker has dedicated v3 API namespace", worker.includes('"/api/v3/app/register"') && worker.includes('"/api/v3/app/commands"') && worker.includes("appCompleteV3")],
  ["worker uses an isolated v3 command queue", worker.includes('"app-v3-seq:"') && worker.includes('"app-v3-cl:"') && worker.includes("protocolVersion:3")],
  ["worker rejects unbound native credentials", worker.includes("Private App MCP credential rejected")],
  ["worker leases native commands", worker.includes('status:"claimed"') && worker.includes("leaseUntil")],
  ["v3 leases track retries", worker.includes("claimCount:Number(c.claimCount||0)+1")],
  ["worker keeps legacy private chat handoff as fallback", worker.includes("appCreateHandoff") && worker.includes("app_import_chat_file")],
  ["v3 primary attachment path is direct app ingest", worker.includes('"app_import_attachment"') && worker.includes('"import_attachment"') && controlService.includes('case "import_attachment"') && controlService.includes("queueDirectAttachmentImport")],
  ["v3 direct attachment bytes bypass Worker", worker.includes('"openai/fileParams":["file"]') && worker.includes("download_url") && controlService.includes("MCPv3-SafeIngest")],
  ["v3 MCP import is a real ChatGPT file parameter", worker.includes('file:z.object({') && worker.includes('file_id:z.string()') && worker.includes('mime_type:z.string()') && worker.includes('file_name:z.string()')],
  ["worker has ephemeral direct private upload relay", worker.includes("/api/app/private/upload") && worker.includes("appCreateCachedHandoff") && worker.includes("__videostudio_private_upload")],
  ["private upload relay is owner-authenticated", worker.includes("Native app authorization failed") && worker.includes("Allow everything mode is required")],
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
  ["Media3 renderer composites foreground/background animation sequences", nativeRender.includes("buildLayeredAnimationComposition") && nativeRender.includes("foregroundSequence") && nativeRender.includes("backgroundSequence") && nativeRender.includes("subject-aware-2.5d")],
  ["procedural atmosphere renders per frame", atmosphere.includes("extends CanvasOverlay") && atmosphere.includes("presentationTimeUs") && nativeRender.includes("new AtmosphereOverlay") && nativeRender.includes("new OverlayEffect")],
  ["MCP v3 exposes autonomous still animation", worker.includes('"app_animate_images"') && worker.includes('"animate_images"') && controlService.includes('case "animate_images"') && controlService.includes("queueAnimatedImages")],
  ["MCP v3 exposes native job polling", worker.includes('"app_job_status"') && controlService.includes('case "job_status"') && nativeJobs.includes("public JSONObject get(String id)")],
  ["Worker persists articulated animation capability in v3 device status", worker.includes("portraitAnimationEngine:clean") && worker.includes("onDevicePortraitAi:!!meta.onDevicePortraitAi") && worker.includes("portraitAnimationEngine:d.portraitAnimationEngine")],
  ["Worker allows safety and status actions independent of edit permission", worker.includes('"self_test","job_status","activity_note","cancel_job","cancel_all_jobs","stop_all"')],
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
