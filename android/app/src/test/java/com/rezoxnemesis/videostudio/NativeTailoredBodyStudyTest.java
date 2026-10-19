package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

/** Gated evidence orchestration: product presets, native Canvas and actual original PNGs. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class NativeTailoredBodyStudyTest {
    private static final String BASE="fcbad2d952ad4ca449678f8762e636becf48b669";
    private static final File REPO=new File("/workspace/VideoStudio-Tailored-Body");
    private static final String MATRIX_TEST="android/app/src/test/java/com/rezoxnemesis/videostudio/NativeHumanCapabilityMatrixTest.java";
    private static final String[] ACTIONS={"reach","kneel","balance","lift"};
    private static final String[] BUILDS={"slender","broad"};
    private static final String[] VIEWS={"front","left","right","back","high","low"};
    private static final int[] PROBES={0,16,32,48,64,80,95};
    private final Map<Path,String> baselineHashes=new LinkedHashMap<>();
    private final JSONArray cases=new JSONArray(),legacyCases=new JSONArray();
    private Context context;
    private File root;
    private String mode,headAtStart,versionName;
    private int versionCode,caseFailures,legacyFailures,legacyAttempted,legacyPassed,archivePassed,renderedFrames;

    /**
     * Required: VIDEOSTUDIO_TAILORED_BODY_READY=true after native/UI/contracts freeze,
     * VIDEOSTUDIO_TAILORED_BODY_BASELINE (original matrix-v3), and
     * VIDEOSTUDIO_TAILORED_BODY_EVIDENCE (fresh external generation directory).
     * MODE defaults to preview: four cases at seven probes, unchanged 96-frame source.
     * Optional VIDEOSTUDIO_TAILORED_BODY_PREVIEW_CASES is a comma-separated case-ID list.
     * MODE=matrix requires VIDEOSTUDIO_TAILORED_BODY_MATRIX_APPROVED=true AFTER preview review;
     * matrix always renders all 48 cases at all 96 frames, with no subset/quality switch.
     */
    @Test public void nativePresetClothingStudyRetainsOriginalLegacyPixelsAndEditableArchives()throws Exception {
        String baselineEnv=env("BASELINE"),evidenceEnv=env("EVIDENCE");
        Assume.assumeTrue("Explicit roots and frozen product source are required",baselineEnv!=null&&evidenceEnv!=null&&"true".equals(env("READY")));
        mode=env("MODE")==null?"preview":env("MODE");assertTrue("MODE must be preview or matrix",mode.equals("preview")||mode.equals("matrix"));
        if(mode.equals("matrix")){
            Assume.assumeTrue("Parent must explicitly authorize matrix after reviewing previews","true".equals(env("MATRIX_APPROVED")));
            assertNull("Matrix must not be reduced by a preview selection",env("PREVIEW_CASES"));
        }
        Path baseline=new File(baselineEnv).getCanonicalFile().toPath();root=new File(evidenceEnv).getCanonicalFile();
        assertFalse("Keep output outside the source worktree",root.toPath().startsWith(REPO.getCanonicalFile().toPath()));
        assertFalse("Do not overwrite/descend into original evidence",root.toPath().startsWith(baseline));
        assertFalse("Do not put output above original evidence",baseline.startsWith(root.toPath()));
        assertTrue("Preserve all earlier generations",!root.exists()||root.isDirectory()&&root.list().length==0);
        Files.createDirectories(root.toPath());context=RuntimeEnvironment.getApplication();
        String gradle=readText(new File(REPO,"android/app/build.gradle.kts").toPath());
        Matcher name=Pattern.compile("(?m)^\\s*versionName\\s*=\\s*\"([^\"]+)\"").matcher(gradle),code=Pattern.compile("(?m)^\\s*versionCode\\s*=\\s*(\\d+)\\s*$").matcher(gradle);
        assertTrue(name.find());assertTrue(code.find());versionName=name.group(1);versionCode=Integer.parseInt(code.group(1));
        assertEquals("4.8.2",AppProtocol.APP_VERSION);assertEquals("4.8.2",versionName);assertEquals(485,versionCode);
        assertEquals("feat/native-tailored-body-20261019",git("branch","--show-current").trim());
        assertEquals("Do not change the historical matrix source","",git("diff",BASE,"--",MATRIX_TEST).trim());
        headAtStart=git("rev-parse","HEAD").trim();Map<Path,String> sourceHashes=snapshotSources();
        JSONObject capabilities=SketchCapabilities.humanAnimation();write(new File(root,"Native-Human-Capabilities.json"),capabilities);
        for(String action:ACTIONS)assertTrue("Study must use a public app-owned preset",contains(capabilities.getJSONArray("presets"),action));
        for(String build:BUILDS)assertTrue(contains(capabilities.getJSONArray("builds"),build));
        for(String view:VIEWS)assertTrue(contains(capabilities.getJSONArray("views"),view));
        JSONObject originalIndex=rememberJson(baseline.resolve("Case-Index.json"));assertEquals(240,originalIndex.getInt("distinctCases"));
        Set<String> selection=previewSelection(),valid=new HashSet<>(),fingerprints=new HashSet<>(),selectedLegacy=new LinkedHashSet<>();
        JSONArray planned=new JSONArray();
        for(String action:ACTIONS)for(String view:VIEWS){
            String legacyId="body_"+action+"_"+view;Path folder=baseline.resolve(legacyId);JSONObject original=rememberJson(folder.resolve("operation.json"));
            assertEquals("add_human_animation",original.getString("type"));assertEquals(action,original.getString("preset"));assertEquals(view,original.getJSONObject("settings").getString("view"));
            assertFalse(original.getJSONObject("settings").has("clothingStyle"));assertFalse(original.has("poses"));assertFalse(original.has("ground"));assertTrue(original.getBoolean("fit"));assertEquals(1.25,original.getJSONObject("settings").getDouble("size"),0);
            JSONObject oldDocument=rememberJson(folder.resolve("document.json"));clock(oldDocument);assertLegacySettings(oldDocument);
            JSONObject evidence=rememberJson(folder.resolve("case-evidence.json"));assertEquals("PASS",evidence.getString("technical"));
            for(int frame:PROBES){Path png=folder.resolve(String.format(Locale.ROOT,"frames/frame-%04d.png",frame));assertEquals("Original PNG receipt",probeHash(evidence,frame),remember(png));}
            for(String build:BUILDS){
                String id="tailored_"+action+"_"+build+"_"+view;valid.add(id);
                JSONObject settings=SketchDocument.copy(original.getJSONObject("settings")).put("build",build).put("clothingStyle","tailored");
                // Native presets supply every pose; this harness authors no geometry or motion keys.
                JSONObject operation=new JSONObject().put("type","add_human_animation").put("layerId",original.getString("layerId")).put("actorId",original.getString("actorId")).put("preset",action).put("settings",settings).put("fit",original.getBoolean("fit"));
                JSONObject expected=SketchDocument.copy(original);expected.getJSONObject("settings").put("build",build).put("clothingStyle","tailored");assertEquals(SketchDocument.canonical(expected),SketchDocument.canonical(operation));
                String hash=hash(operation);assertTrue("Distinct parameter inputs",fingerprints.add(hash));boolean selected=mode.equals("matrix")||selection.contains(id);
                planned.put(new JSONObject().put("caseId",id).put("preset",action).put("build",build).put("view",view).put("legacyCaseId",legacyId).put("operation",operation).put("operationSha256",hash).put("selected",selected).put("allFramesRequested",mode.equals("matrix")));
                if(selected)selectedLegacy.add(legacyId);
            }
        }
        assertEquals(48,planned.length());if(mode.equals("preview")){assertFalse("Select at least one preview",selection.isEmpty());assertTrue("Unknown preview case ID",valid.containsAll(selection));}
        int expectedCases=mode.equals("matrix")?48:selection.size(),expectedFrames=expectedCases*(mode.equals("matrix")?96:PROBES.length),expectedLegacy=selectedLegacy.size()*PROBES.length;
        write(new File(root,"Plan.json"),new JSONObject().put("schema","videostudio.native-tailored-body-study.v1").put("mode",mode).put("baselineRoot",baseline.toString()).put("baseCommit",BASE)
                .put("frameCount",96).put("fps",24).put("width",1280).put("height",720).put("probeFrames",new JSONArray(PROBES)).put("plannedDistinctParameterInputs",48).put("selectedParameterInputs",expectedCases).put("actionFamilies",new JSONArray(ACTIONS))
                .put("expectedNativeFrames",expectedFrames).put("expectedLegacyPixelComparisons",expectedLegacy).put("uniqueLegacyDocuments",selectedLegacy.size()).put("expectedArchivePixelComparisons",expectedCases*2)
                .put("authoringSource","Existing public native humanAnimation presets; historical operation framing/palette retained, only build and clothingStyle selected").put("framing","Original fit=true and size=1.25; native fit translates x/floorY only, never lowers size or changes the clock")
                .put("contactScope","Isolated preset/floor coordinates, no scene ground or actor prop. This capture does not replace independent composed-plane/contact/prop tests.").put("countSemantics","Four action families, 48 build/view/style parameter inputs; not 48 new stories. Frames, legacy checks, archives and retries add no scenarios. Expansion toward 1000+ follows visual acceptance.")
                .put("rollout","Review short captures first. Matrix requires explicit parent approval after review and always renders all 48x96 native frames.").put("renderer","Product native Android Canvas, Robolectric SDK33 NATIVE; no external drawing renderer").put("cases",planned));
        write(new File(root,"Baseline-Input-Hashes.json"),hashRows(baselineHashes));writeIndex("RUNNING",false);
        for(String id:selectedLegacy){cancelled();runLegacy(baseline.resolve(id),id);}
        for(int i=0;i<planned.length();i++)if(planned.getJSONObject(i).getBoolean("selected")){cancelled();runCase(planned.getJSONObject(i));}
        boolean sourceStable=unchanged(sourceHashes)&&sourceHashes.keySet().equals(workingSources())&&headAtStart.equals(git("rev-parse","HEAD").trim()),baselineStable=unchanged(baselineHashes);
        write(new File(root,"Source-Stability.json"),new JSONObject().put("sourceHashesAndFileSetUnchanged",sourceStable).put("baselineInputsUnchanged",baselineStable).put("headAtEnd",git("rev-parse","HEAD").trim()).put("gitStatusAtEnd",git("status","--porcelain")));
        boolean complete=caseFailures==0&&legacyFailures==0&&cases.length()==expectedCases&&legacyPassed==expectedLegacy&&legacyAttempted==expectedLegacy&&archivePassed==expectedCases*2&&renderedFrames==expectedFrames&&sourceStable&&baselineStable;
        writeIndex(complete?"PASS":"FAIL",complete);artifactHashes();
        assertTrue("Source changed; keep this generation and recapture frozen source",sourceStable);assertTrue("Original baseline changed",baselineStable);
        assertEquals("Inspect actual case failures",0,caseFailures);assertEquals("Inspect actual legacy failures",0,legacyFailures);assertEquals(expectedCases,cases.length());assertEquals(expectedFrames,renderedFrames);assertEquals(expectedLegacy,legacyAttempted);assertEquals(expectedLegacy,legacyPassed);assertEquals(expectedCases*2,archivePassed);
    }

    private void runCase(JSONObject item)throws Exception {
        String id=item.getString("caseId");File folder=new File(root,id);Files.createDirectories(folder.toPath());JSONArray errors=new JSONArray(),screenshots=new JSONArray(),comparisons=new JSONArray();
        JSONObject record=new JSONObject().put("caseId",id).put("legacyCaseId",item.getString("legacyCaseId")).put("preset",item.getString("preset")).put("build",item.getString("build")).put("view",item.getString("view")).put("clothingStyle","tailored").put("frameCount",96).put("fps",24).put("width",1280).put("height",720).put("screenshots",screenshots).put("archiveComparisons",comparisons).put("allFramesRendered",false).put("visual","UNREVIEWED").put("liveManualMcpParity","UNVERIFIED");cases.put(record);
        JSONObject operation=SketchDocument.copy(item.getJSONObject("operation"));String originalOperation=SketchDocument.canonical(operation);write(new File(folder,"operation.json"),operation);
        try{
            String sketchId="tailored_"+UUID.randomUUID();JSONObject create=new JSONObject().put("sketchId",sketchId).put("operationId","create").put("width",1280).put("height",720).put("frameCount",96).put("fps",24);
            write(new File(folder,"create-request.json"),create);SketchEditOps.execute(context,"sketch_create",create);
            JSONObject request=new JSONObject().put("sketchId",sketchId).put("operationId","author_tailored").put("expectedRevision",1).put("operations",new JSONArray().put(operation));write(new File(folder,"request.json"),request);record.put("requestSha256",hash(request));SketchEditOps.execute(context,"sketch_edit",request);
            JSONObject document;try(SketchProjectStore store=new SketchProjectStore(context)){document=store.get(sketchId);}clock(document);assertEquals(2,document.getInt("revision"));SketchDocument.validate(document);
            JSONObject actor=human(document),payload=actor.getJSONObject("human");assertEquals("tailored",payload.getJSONObject("settings").getString("clothingStyle"));assertEquals(96,payload.getInt("durationFrames"));assertEquals(24,payload.getInt("fps"));assertEquals(1.25,payload.getJSONObject("settings").getDouble("size"),0);
            assertFixedSkeleton(actor);String before=SketchDocument.canonical(document);write(new File(folder,"document.json"),document);record.put("nativeDocumentSha256",hash(document));
            JSONObject currentLegacy=SketchDocument.copy(document);human(currentLegacy).getJSONObject("human").getJSONObject("settings").remove("clothingStyle");int changedStyleProbes=0;
            // Diagnostic routing check only. Historical parity is runLegacy's ORIGINAL PNG comparison.
            try(SketchFrameRenderer renderer=new SketchFrameRenderer(context,document);SketchFrameRenderer reference=new SketchFrameRenderer(context,currentLegacy)){
                for(int frame=0;frame<96;frame++)if(mode.equals("matrix")||probe(frame)){
                    cancelled();Bitmap bitmap=renderer.render(frame);File png=new File(folder,String.format(Locale.ROOT,"frames/frame-%04d.png",frame));try{
                        if(probe(frame)){Bitmap old=reference.render(frame);try{if(!bitmap.sameAs(old))changedStyleProbes++;}finally{old.recycle();}}
                        png(png,bitmap);
                    }finally{bitmap.recycle();}
                    screenshots.put(new JSONObject().put("frame",frame).put("sha256",SketchProjectStore.fileHash(png)));renderedFrames++;
                }
            }
            assertEquals(mode.equals("matrix")?96:PROBES.length,screenshots.length());record.put("allFramesRendered",mode.equals("matrix")).put("styleRoutingChangedProbeCount",changedStyleProbes);
            if(changedStyleProbes==0)failure(errors,"style routing",new AssertionError("Tailored must change native pixels; this diagnostic is not historical compatibility or artistic acceptance"));
            SketchPaintArchive.Prepared archive=SketchPaintArchive.prepare(context,new JSONObject().put("sketchId",sketchId).put("expectedRevision",2).put("operationId","tailored_archive"));File saved=new File(folder,"editable.vssketch");Files.copy(archive.file.toPath(),saved.toPath());String archiveHash=SketchProjectStore.fileHash(saved);record.put("archiveSha256",archiveHash);write(new File(folder,"archive-metadata.json"),archive.metadata);
            JSONObject restored=SketchPaintArchive.restoreFile(context,saved,archiveHash,"tailored_restore_"+UUID.randomUUID().toString().replace("-",""),"Restored native tailored body");write(new File(folder,"archive-restore-receipt.json"),restored);
            try(SketchProjectStore store=new SketchProjectStore(context)){
                JSONObject restoredDocument=store.get(restored.getString("sketchId"));clock(restoredDocument);assertEquals(SketchDocument.canonical(payload),SketchDocument.canonical(human(restoredDocument).getJSONObject("human")));
                try(SketchFrameRenderer a=new SketchFrameRenderer(context,document);SketchFrameRenderer b=new SketchFrameRenderer(context,restoredDocument)){
                    for(int frame:new int[]{48,95}){Bitmap x=null,y=null;try{x=a.render(frame);y=b.render(frame);boolean same=x.sameAs(y);comparisons.put(new JSONObject().put("frame",frame).put("bitmapSameAs",same));if(same)archivePassed++;else failure(errors,"archive frame "+frame,new AssertionError("Actual restored native pixels differ"));}finally{if(x!=null)x.recycle();if(y!=null)y.recycle();}}
                }
            }
            write(new File(folder,"archive-pixel-comparisons.json"),new JSONObject().put("comparisons",comparisons).put("archiveSha256",archiveHash));assertEquals(before,SketchDocument.canonical(document));assertEquals(originalOperation,SketchDocument.canonical(operation));
            try(SketchProjectStore store=new SketchProjectStore(context)){assertEquals("Archive/render must not change the saved editable source",before,SketchDocument.canonical(store.get(sketchId)));}
            record.put("allFrameValidation","Native constructor/document reach/bounds validation plus unchanged skeleton/grip checks at all 96 frames; visual acceptance remains separate");
        }catch(InterruptedException e){throw e;}catch(Exception|AssertionError e){failure(errors,"native preset author/render/archive",e);}
        record.put("technical",errors.length()==0?"PASS":"FAIL").put("failures",errors);if(errors.length()>0)caseFailures++;
        write(new File(folder,"case-evidence.json"),record);writeIndex("RUNNING",false);System.out.println("TAILORED "+cases.length()+" "+id+" "+record.getString("technical"));
    }

    private void runLegacy(Path baseline,String id)throws Exception {
        File folder=new File(root,"legacy/"+id);Files.createDirectories(folder.toPath());JSONArray comparisons=new JSONArray(),errors=new JSONArray();JSONObject record=new JSONObject().put("caseId",id).put("comparisons",comparisons);legacyCases.put(record);
        Files.copy(baseline.resolve("operation.json"),new File(folder,"original-operation.json").toPath());Files.copy(baseline.resolve("document.json"),new File(folder,"original-document.json").toPath());
        try{
            JSONObject document=read(baseline.resolve("document.json"));clock(document);assertLegacySettings(document);String before=SketchDocument.canonical(document);SketchDocument.validate(document);
            try(SketchFrameRenderer renderer=new SketchFrameRenderer(context,document)){
                for(int frame:PROBES){Bitmap actual=null,original=null;try{
                    cancelled();File golden=baseline.resolve(String.format(Locale.ROOT,"frames/frame-%04d.png",frame)).toFile();BitmapFactory.Options options=new BitmapFactory.Options();options.inPreferredConfig=Bitmap.Config.ARGB_8888;original=BitmapFactory.decodeFile(golden.getAbsolutePath(),options);assertNotNull(original);
                    actual=renderer.render(frame);legacyAttempted++;boolean same=actual.sameAs(original);if(same)legacyPassed++;else failure(errors,"legacy frame "+frame,new AssertionError("Original omitted-style document differs from ORIGINAL historical PNG"));
                    File output=new File(folder,String.format(Locale.ROOT,"frames/frame-%04d.png",frame));png(output,actual);comparisons.put(new JSONObject().put("frame",frame).put("bitmapSameAs",same).put("originalPng",golden.getAbsolutePath()).put("originalSha256",SketchProjectStore.fileHash(golden)).put("newRendererSha256",SketchProjectStore.fileHash(output)));
                }catch(InterruptedException e){throw e;}catch(Exception|AssertionError e){failure(errors,"legacy frame "+frame,e);}finally{if(actual!=null)actual.recycle();if(original!=null)original.recycle();}}
            }
            assertEquals(before,SketchDocument.canonical(document));
        }catch(InterruptedException e){throw e;}catch(Exception|AssertionError e){failure(errors,"original legacy native renderer",e);}
        if(errors.length()>0)legacyFailures++;record.put("technical",errors.length()==0?"PASS":"FAIL").put("failures",errors).put("source","Unchanged original document rendered through NEW product renderer against ORIGINAL PNG; never new-style self-comparison");write(new File(folder,"legacy-pixel-comparisons.json"),record);writeIndex("RUNNING",false);
    }

    private static void assertFixedSkeleton(JSONObject actor)throws Exception {
        JSONObject settings=actor.getJSONObject("human").getJSONObject("settings"),legacy=SketchDocument.copy(settings);legacy.remove("clothingStyle");
        for(int frame=0;frame<96;frame++){
            cancelled();JSONObject pose=SketchHumanAnimation.pose(actor,frame);SketchHumanFigure.Skeleton a=SketchHumanFigure.skeleton(settings,pose),b=SketchHumanFigure.skeleton(legacy,pose);
            for(Field field:SketchHumanFigure.Skeleton.class.getDeclaredFields())if(field.getType()==double[].class)assertArrayEquals("Style must not move "+field.getName(),(double[])field.get(a),(double[])field.get(b),0);
            for(boolean near:new boolean[]{true,false})assertArrayEquals(SketchHumanFigure.grip(settings,pose,near),SketchHumanFigure.grip(legacy,pose,near),0);
            assertEquals(76,length(a.nearShoulder,a.nearElbow),1e-7);assertEquals(70,length(a.nearElbow,a.nearHand),1e-7);assertEquals(76,length(a.farShoulder,a.farElbow),1e-7);assertEquals(70,length(a.farElbow,a.farHand),1e-7);
            assertEquals(90,length(a.leftHip,a.leftKnee),1e-7);assertEquals(86,length(a.leftKnee,a.leftAnkle),1e-7);assertEquals(90,length(a.rightHip,a.rightKnee),1e-7);assertEquals(86,length(a.rightKnee,a.rightAnkle),1e-7);
        }
    }
    private static double length(double[] a,double[] b){double sum=0;for(int i=0;i<3;i++)sum+=(a[i]-b[i])*(a[i]-b[i]);return Math.sqrt(sum);}
    private static void assertLegacySettings(JSONObject document)throws Exception {assertFalse("Original retained settings must omit clothingStyle",human(document).getJSONObject("human").getJSONObject("settings").has("clothingStyle"));}
    private static JSONObject human(JSONObject document)throws Exception {JSONArray layers=document.getJSONArray("layers");for(int l=0;l<layers.length();l++){JSONArray objects=layers.getJSONObject(l).getJSONArray("objects");for(int i=0;i<objects.length();i++)if("human".equals(objects.getJSONObject(i).getString("kind")))return objects.getJSONObject(i);}throw new AssertionError("Missing retained human");}

    private Map<Path,String> snapshotSources()throws Exception {
        write(new File(root,"Source-State.json"),new JSONObject().put("baseCommit",BASE).put("headAtStart",headAtStart).put("branch",git("branch","--show-current").trim()).put("gitStatusAtStart",git("status","--porcelain")).put("appVersion",AppProtocol.APP_VERSION).put("versionName",versionName).put("versionCode",versionCode).put("freezeSignal",env("READY")).put("matrixApprovedSignal",env("MATRIX_APPROVED")).put("sourceSelection","Tracked and nonignored untracked existing files, excluding build/.work/.gradle/node_modules/.git directories. Untracked contents are copied; final hashes, file set and HEAD are rechecked."));
        Files.write(new File(root,"Source-Diff.patch").toPath(),git("diff","--binary",BASE,"--").getBytes(StandardCharsets.UTF_8));Map<Path,String> hashes=new LinkedHashMap<>();
        for(Path source:workingSources()){Path relative=REPO.toPath().relativize(source);File output=new File(root,"source-snapshot/"+relative);Files.createDirectories(output.toPath().getParent());String hash=SketchProjectStore.fileHash(source.toFile());Files.copy(source,output.toPath());assertEquals("Snapshot changed "+relative,hash,SketchProjectStore.fileHash(output));hashes.put(source,hash);}
        write(new File(root,"Source-Hashes.json"),hashRows(hashes));return hashes;
    }
    private static Set<Path> workingSources()throws Exception {Set<Path> files=new TreeSet<>();for(String name:git("ls-files","--cached","--others","--exclude-standard","-z").split("\\x00")){if(name.isEmpty())continue;Path relative=new File(name).toPath();boolean excluded=false;for(Path part:relative)if(Arrays.asList("build",".work",".gradle","node_modules",".git").contains(part.toString())){excluded=true;break;}Path file=REPO.toPath().resolve(relative);if(!excluded&&Files.isRegularFile(file))files.add(file);}return files;}
    private void writeIndex(String status,boolean complete)throws Exception {write(new File(root,status.equals("RUNNING")?"Case-Index-in-progress.json":"Case-Index.json"),new JSONObject().put("schema","videostudio.native-tailored-body-study.v1").put("mode",mode).put("technical",status).put("captureComplete",complete).put("fullMatrixComplete",complete&&mode.equals("matrix")).put("cases",cases).put("legacyCases",legacyCases).put("distinctParameterInputsExecuted",cases.length()).put("plannedDistinctParameterInputs",48).put("creativeStoriesAdded",0).put("technicalFailures",caseFailures+legacyFailures).put("nativeFramesWritten",renderedFrames).put("legacyPixelComparisonsAttempted",legacyAttempted).put("legacyPixelComparisonsPassed",legacyPassed).put("archivePixelComparisonsPassed",archivePassed).put("visual","UNREVIEWED").put("motionReview","UNREVIEWED").put("liveManualMcpParity","UNVERIFIED").put("phoneExecution",false).put("professionalQualityCertified",false).put("renderer","Product Android Canvas / Robolectric SDK33 NATIVE").put("appVersion",AppProtocol.APP_VERSION).put("versionCode",versionCode));}
    private static Set<String> previewSelection(){String selected=env("PREVIEW_CASES");return new LinkedHashSet<>(Arrays.asList(selected==null?new String[]{"tailored_reach_slender_front","tailored_kneel_broad_right","tailored_balance_broad_back","tailored_lift_slender_high"}:selected.split(",")));}
    private static String env(String name){return System.getenv("VIDEOSTUDIO_TAILORED_BODY_"+name);}
    private static boolean contains(JSONArray values,String value)throws Exception{for(int i=0;i<values.length();i++)if(value.equals(values.getString(i)))return true;return false;}
    private static void cancelled()throws InterruptedException{if(Thread.currentThread().isInterrupted())throw new InterruptedException("Tailored body study cancelled");}
    private static void failure(JSONArray errors,String stage,Throwable error)throws Exception{cancelled();errors.put(new JSONObject().put("stage",stage).put("type",error.getClass().getSimpleName()).put("message",String.valueOf(error.getMessage())));}
    private JSONObject rememberJson(Path path)throws Exception{remember(path);return read(path);}
    private String remember(Path path)throws Exception{String hash=SketchProjectStore.fileHash(path.toFile());baselineHashes.put(path,hash);return hash;}
    private static boolean unchanged(Map<Path,String> hashes)throws Exception{for(Map.Entry<Path,String> row:hashes.entrySet())if(!Files.isRegularFile(row.getKey())||!row.getValue().equals(SketchProjectStore.fileHash(row.getKey().toFile())))return false;return true;}
    private static JSONObject hashRows(Map<Path,String> hashes)throws Exception{JSONObject rows=new JSONObject();for(Map.Entry<Path,String> row:hashes.entrySet())rows.put(row.getKey().toString(),row.getValue());return rows;}
    private void artifactHashes()throws Exception{JSONObject hashes=new JSONObject();try(Stream<Path> walk=Files.walk(root.toPath())){for(Path path:(Iterable<Path>)walk.filter(Files::isRegularFile).sorted()::iterator)if(!path.getFileName().toString().equals("Artifact-Hashes.json"))hashes.put(root.toPath().relativize(path).toString(),SketchProjectStore.fileHash(path.toFile()));}write(new File(root,"Artifact-Hashes.json"),hashes);}
    private static boolean probe(int frame){for(int p:PROBES)if(p==frame)return true;return false;}
    private static String probeHash(JSONObject evidence,int frame)throws Exception{JSONArray shots=evidence.getJSONArray("screenshots");for(int i=0;i<shots.length();i++)if(shots.getJSONObject(i).getInt("frame")==frame)return shots.getJSONObject(i).getString("sha256");throw new AssertionError("Missing original probe "+frame);}
    private static void clock(JSONObject document)throws Exception{assertEquals(1280,document.getInt("width"));assertEquals(720,document.getInt("height"));assertEquals(96,document.getInt("frameCount"));assertEquals(24,document.getInt("fps"));}
    private static String readText(Path path)throws Exception{return new String(Files.readAllBytes(path),StandardCharsets.UTF_8);}
    private static JSONObject read(Path path)throws Exception{return new JSONObject(readText(path));}
    private static String hash(JSONObject json)throws Exception{return SketchDocument.sha256(SketchDocument.canonical(json).getBytes(StandardCharsets.UTF_8));}
    private static void write(File file,JSONObject json)throws Exception{Files.createDirectories(file.toPath().getParent());Files.write(file.toPath(),json.toString(2).getBytes(StandardCharsets.UTF_8));}
    private static void png(File file,Bitmap bitmap)throws Exception{Files.createDirectories(file.toPath().getParent());try(FileOutputStream out=new FileOutputStream(file)){assertTrue("Native PNG encoding failed",bitmap.compress(Bitmap.CompressFormat.PNG,100,out));}}
    private static String git(String... args)throws Exception{List<String> command=new ArrayList<>();command.add("git");command.addAll(Arrays.asList(args));Process process=new ProcessBuilder(command).directory(REPO).redirectErrorStream(true).start();ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(InputStream in=process.getInputStream()){byte[] buffer=new byte[8192];for(int count;(count=in.read(buffer))!=-1;)bytes.write(buffer,0,count);}String output=new String(bytes.toByteArray(),StandardCharsets.UTF_8);assertEquals("Git provenance failed: "+command+"\n"+output,0,process.waitFor());return output;}
}
