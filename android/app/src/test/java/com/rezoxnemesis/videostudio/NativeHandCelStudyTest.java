package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import static org.junit.Assert.*;

/** Evidence orchestration only: native authoring, Canvas, saved archives and real old PNGs. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class NativeHandCelStudyTest {
    private static final String BASE="d76edec280cab5b222accc1002c28ee4729af9f0";
    private static final String MATRIX_TEST="android/app/src/test/java/com/rezoxnemesis/videostudio/NativeHumanCapabilityMatrixTest.java";
    private static final String SELF="android/app/src/test/java/com/rezoxnemesis/videostudio/NativeHandCelStudyTest.java";
    private static final String[] PATTERNS={"open_close","grasp","finger_cascade","thumb_opposition"};
    private static final String[] HANDS={"right","left"};
    private static final String[] VIEWS={"front","left","right","back","high","low"};
    private static final int[] PROBES={0,16,32,48,64,80,95};
    private final JSONArray records=new JSONArray();
    private final Map<Path,String> baselineHashes=new LinkedHashMap<>();
    private Context context;
    private File root;
    private String versionName;
    private int versionCode;
    private int failedCases,legacyAttempted,legacyPassed,archiveCompared;

    /**
     * Required: VIDEOSTUDIO_HAND_CEL_READY=true, VIDEOSTUDIO_HAND_CEL_BASELINE,
     * VIDEOSTUDIO_HAND_CEL_EVIDENCE. READY is set only after settings/geometry freeze.
     * Optional VIDEOSTUDIO_HAND_CEL_MOTION_CASES lists baseline case IDs (or "all").
     * Default full captures: hand_grasp_right_front and hand_grasp_right_right.
     * Each run needs an empty evidence root; retries are preserved as new generations.
     */
    @Test public void originalHandInputsProduceFortyEightCelCasesAndPreserveAllLegacyProbePixels()throws Exception {
        String baselineEnv=System.getenv("VIDEOSTUDIO_HAND_CEL_BASELINE"),outputEnv=System.getenv("VIDEOSTUDIO_HAND_CEL_EVIDENCE");
        Assume.assumeTrue("Set explicit baseline/evidence roots and wait for the product freeze",baselineEnv!=null&&outputEnv!=null&&"true".equals(System.getenv("VIDEOSTUDIO_HAND_CEL_READY")));
        Path baseline=new File(baselineEnv).getCanonicalFile().toPath();root=new File(outputEnv).getCanonicalFile();
        assertFalse("Keep the original evidence read-only",root.toPath().startsWith(baseline));
        assertFalse("Do not place output above the original evidence",baseline.startsWith(root.toPath()));
        assertTrue("Use a fresh immutable generation",!root.exists()||root.isDirectory()&&root.list().length==0);
        Files.createDirectories(root.toPath());context=RuntimeEnvironment.getApplication();
        File repo=new File("/workspace/VideoStudio-Hand-Cel-Polish");
        String gradle=readText(new File(repo,"android/app/build.gradle.kts").toPath());
        Matcher name=Pattern.compile("(?m)^\\s*versionName\\s*=\\s*\"([^\"]+)\"").matcher(gradle);
        Matcher code=Pattern.compile("(?m)^\\s*versionCode\\s*=\\s*(\\d+)\\s*$").matcher(gradle);
        assertTrue("Missing app versionName assignment",name.find());assertTrue("Missing app versionCode assignment",code.find());
        versionName=name.group(1);versionCode=Integer.parseInt(code.group(1));
        assertEquals("4.8.1",AppProtocol.APP_VERSION);assertEquals("4.8.1",versionName);assertEquals(484,versionCode);
        assertEquals("feat/native-hand-cel-polish-20261018",git(repo,"branch","--show-current").trim());
        assertEquals("Keep the original 240-case matrix source unchanged","",git(repo,"diff",BASE,"--",MATRIX_TEST).trim());
        Map<Path,String> sourceHashes=snapshotSources(repo);
        Set<String> motion=motionCases();JSONArray planned=new JSONArray();Set<String> distinctInputs=new HashSet<>();
        JSONArray productExamples=SketchCapabilities.handAnimation().getJSONArray("studyExamples");
        assertEquals("All four study recipes must be available without workspace files",4,productExamples.length());
        write(new File(root,"Native-Study-Examples.json"),new JSONObject().put("source","sketch_capabilities.handAnimation.studyExamples").put("examples",productExamples));
        JSONObject baselineIndex=rememberJson(baseline.resolve("Case-Index.json"));assertEquals(240,baselineIndex.getInt("distinctCases"));
        for(String pattern:PATTERNS)for(String hand:HANDS)for(String view:VIEWS){
            String id="hand_"+pattern+"_"+hand+"_"+view;Path folder=baseline.resolve(id);
            JSONObject original=rememberJson(folder.resolve("operation.json"));assertEquals("add_hand_animation",original.getString("type"));
            assertEquals(hand,original.getJSONObject("settings").getString("handedness"));assertEquals(view,original.getJSONObject("settings").getString("view"));
            assertFalse("Old inputs must really omit the new style",original.getJSONObject("settings").has("renderStyle"));
            JSONObject oldDocument=rememberJson(folder.resolve("document.json"));clock(oldDocument);
            JSONObject oldEvidence=rememberJson(folder.resolve("case-evidence.json"));assertEquals("PASS",oldEvidence.getString("technical"));
            for(int frame:PROBES){Path png=folder.resolve(String.format(Locale.ROOT,"frames/frame-%04d.png",frame));String hash=remember(png);assertEquals("Original fixture PNG must match its original receipt",probeHash(oldEvidence,frame),hash);}
            JSONObject operation=celOperation(original,productExamples,pattern);String fingerprint=hash(operation);assertTrue("Each new case must have a different native style input",distinctInputs.add(fingerprint));
            planned.put(new JSONObject().put("caseId","cel_"+id).put("baselineCaseId",id).put("studyExampleId",pattern).put("operation",operation).put("operationSha256",fingerprint).put("allFramesRequested",motion.contains(id)));
        }
        assertEquals(48,planned.length());Set<String> valid=new HashSet<>();for(int i=0;i<planned.length();i++)valid.add(planned.getJSONObject(i).getString("baselineCaseId"));assertTrue("Unknown requested motion case",valid.containsAll(motion));
        assertTrue("Capture at least one complete front grasp",motion.stream().anyMatch(id->id.matches("hand_grasp_(right|left)_front")));
        assertTrue("Capture at least one complete profile",motion.stream().anyMatch(id->id.endsWith("_left")||id.endsWith("_right")));
        write(new File(root,"Plan.json"),new JSONObject().put("schema","videostudio.native-hand-cel-study.v1").put("baselineRoot",baseline.toString()).put("baselineCommit",BASE)
                .put("newRenderStyle","cel").put("authoringSource","Actual native sketch_capabilities.handAnimation.studyExamples; original private requests are compatibility oracles only").put("frameCount",96).put("fps",24).put("width",1280).put("height",720).put("probeFrames",new JSONArray(PROBES))
                .put("renderer","VideoStudio native Android Canvas").put("executionLocation","Workspace Robolectric SDK33 NATIVE; no phone or external drawing renderer")
                .put("historicalDistinctCases",240).put("newDistinctStyleCases",48).put("combinedDistinctCasesWhenComplete",288).put("expectedLegacyPixelComparisons",336).put("expectedArchivePixelComparisons",96)
                .put("countSemantics","Only the 48 different renderStyle=cel operation inputs are new cases; legacy comparisons, frames, assertions and retries are not new cases").put("cases",planned));
        write(new File(root,"Baseline-Input-Hashes.json"),hashRows(baselineHashes));
        writeIndex("RUNNING",false);
        for(int i=0;i<planned.length();i++){
            if(Thread.currentThread().isInterrupted())throw new InterruptedException("Hand cel study cancelled");
            JSONObject item=planned.getJSONObject(i);runCase(baseline.resolve(item.getString("baselineCaseId")),item);
        }
        boolean sourceStable=unchanged(sourceHashes)&&sourceHashes.keySet().equals(workingSources(repo)),baselineStable=unchanged(baselineHashes);
        write(new File(root,"Source-Stability.json"),new JSONObject().put("sourceHashesUnchanged",sourceStable).put("baselineInputsUnchanged",baselineStable).put("headAtEnd",git(repo,"rev-parse","HEAD").trim()).put("gitStatusAtEnd",git(repo,"status","--porcelain")));
        boolean complete=records.length()==48&&failedCases==0&&legacyAttempted==336&&legacyPassed==336&&archiveCompared==96&&sourceStable&&baselineStable;
        writeIndex(complete?"PASS":"FAIL",complete);
        artifactHashes();
        assertTrue("Source changed during capture; keep this generation and retest frozen source",sourceStable);assertTrue("Original evidence changed during capture",baselineStable);
        assertEquals("Inspect retained case failures; never reduce the requested matrix",0,failedCases);assertEquals(48,records.length());assertEquals(336,legacyAttempted);assertEquals(336,legacyPassed);assertEquals(96,archiveCompared);
    }

    private void runCase(Path baseline,JSONObject item)throws Exception {
        String id=item.getString("caseId");File folder=new File(root,id);Files.createDirectories(folder.toPath());JSONArray errors=new JSONArray();
        JSONObject record=new JSONObject().put("caseId",id).put("baselineCaseId",item.getString("baselineCaseId")).put("studyExampleId",item.getString("studyExampleId")).put("authoringSource","Native handAnimation.studyExamples").put("renderStyle","cel").put("frameCount",96).put("fps",24).put("durationSeconds",4).put("width",1280).put("height",720)
                .put("appVersion",AppProtocol.APP_VERSION).put("versionCode",versionCode).put("renderer","VideoStudio native Android Canvas / Robolectric SDK33 NATIVE")
                .put("visual","UNREVIEWED").put("manualMcpParity","UNVERIFIED").put("liveManualMcpParity","UNVERIFIED").put("phoneExecution",false).put("professionalQualityCertified",false);
        records.put(record);write(new File(folder,"operation.json"),item.getJSONObject("operation"));
        Files.copy(baseline.resolve("operation.json"),new File(folder,"baseline-operation.json").toPath());
        Files.copy(baseline.resolve("document.json"),new File(folder,"baseline-document.json").toPath());
        try{legacyPixels(baseline,folder,record,errors);}catch(InterruptedException e){throw e;}catch(Exception|AssertionError e){failure(errors,"legacy renderer compatibility",e);}
        try{
            JSONObject old=read(baseline.resolve("document.json")),operation=SketchDocument.copy(item.getJSONObject("operation"));String originalOperation=SketchDocument.canonical(operation);
            String sketchId="hand_cel_"+UUID.randomUUID();JSONObject create=new JSONObject().put("sketchId",sketchId).put("operationId","create").put("width",1280).put("height",720).put("frameCount",96).put("fps",24).put("background",old.getString("background"));
            write(new File(folder,"create-request.json"),create);SketchEditOps.execute(context,"sketch_create",create);
            JSONObject request=new JSONObject().put("sketchId",sketchId).put("operationId","author_cel").put("expectedRevision",1).put("operations",new JSONArray().put(operation));
            write(new File(folder,"request.json"),request);record.put("nativeRequestSha256",hash(request));SketchEditOps.execute(context,"sketch_edit",request);
            JSONObject document;try(SketchProjectStore store=new SketchProjectStore(context)){document=store.get(sketchId);}clock(document);assertEquals(2,document.getInt("revision"));
            SketchDocument.validate(document);JSONObject hand=hand(document);assertEquals("cel",hand.getJSONObject("settings").getString("renderStyle"));assertEquals(96,hand.getInt("durationFrames"));assertEquals(24,hand.getInt("fps"));
            String before=SketchDocument.canonical(document);write(new File(folder,"document.json"),document);JSONArray screenshots=new JSONArray();int changedStyleProbes=0;
            boolean full=item.getBoolean("allFramesRequested");try(SketchFrameRenderer renderer=new SketchFrameRenderer(context,document)){
                for(int frame=0;frame<96;frame++)if(full||probe(frame)){
                    Bitmap bitmap=renderer.render(frame);File output=new File(folder,String.format(Locale.ROOT,"frames/frame-%04d.png",frame));try{
                        if(probe(frame)){
                            BitmapFactory.Options options=new BitmapFactory.Options();options.inPreferredConfig=Bitmap.Config.ARGB_8888;
                            Bitmap original=BitmapFactory.decodeFile(baseline.resolve(String.format(Locale.ROOT,"frames/frame-%04d.png",frame)).toString(),options);assertNotNull(original);
                            try{if(!bitmap.sameAs(original))changedStyleProbes++;}finally{original.recycle();}
                        }
                        png(output,bitmap);
                    }finally{bitmap.recycle();}
                    screenshots.put(new JSONObject().put("frame",frame).put("sha256",SketchProjectStore.fileHash(output)));
                }
            }
            record.put("screenshots",screenshots).put("allFramesRendered",full).put("nativeDocumentSha256",hash(document)).put("celStyleChangedProbeCount",changedStyleProbes)
                    .put("allFrameValidation","Native sketch_edit constructor plus document validation checks all 96 unchanged frames, pose reach and actual ink bounds; sparse PNGs are probes, not complete motion review");
            if(changedStyleProbes==0)failure(errors,"cel style routing",new AssertionError("renderStyle=cel must affect actual native pixels; this is separate from legacy compatibility or artistic acceptance"));
            SketchPaintArchive.Prepared archive=SketchPaintArchive.prepare(context,new JSONObject().put("sketchId",sketchId).put("expectedRevision",2).put("operationId","cel_study_archive"));
            File archived=new File(folder,"editable.vssketch");Files.copy(archive.file.toPath(),archived.toPath());String archiveHash=SketchProjectStore.fileHash(archived);write(new File(folder,"archive-metadata.json"),archive.metadata);
            JSONObject restored=SketchPaintArchive.restoreFile(context,archived,archiveHash,"cel_restore_"+UUID.randomUUID().toString().replace("-",""),"Restored native cel hand study");write(new File(folder,"archive-restore-receipt.json"),restored);
            JSONArray comparisons=new JSONArray();try(SketchProjectStore store=new SketchProjectStore(context)){
                JSONObject restoredDoc=store.get(restored.getString("sketchId"));clock(restoredDoc);assertEquals("cel",hand(restoredDoc).getJSONObject("settings").getString("renderStyle"));
                try(SketchFrameRenderer a=new SketchFrameRenderer(context,document);SketchFrameRenderer b=new SketchFrameRenderer(context,restoredDoc)){
                    for(int frame:new int[]{48,95}){Bitmap x=null,y=null;try{x=a.render(frame);y=b.render(frame);boolean same=x.sameAs(y);comparisons.put(new JSONObject().put("frame",frame).put("nativePixelsIdentical",same));if(!same)failure(errors,"archive frame "+frame,new AssertionError("Restored cel native pixels differ"));else archiveCompared++;}finally{if(x!=null)x.recycle();if(y!=null)y.recycle();}}
                }
            }
            record.put("archiveSha256",archiveHash).put("archivePixelComparisons",comparisons.length()).put("archiveComparisonFrames",comparisons);write(new File(folder,"archive-pixel-comparisons.json"),new JSONObject().put("comparisons",comparisons).put("archiveSha256",archiveHash));
            assertEquals("Rendering must not mutate editable source",before,SketchDocument.canonical(document));assertEquals(originalOperation,SketchDocument.canonical(operation));
        }catch(InterruptedException e){throw e;}catch(Exception|AssertionError e){failure(errors,"cel native author/render/archive",e);}
        record.put("failures",errors).put("technical",errors.length()==0?"PASS":"FAIL");if(errors.length()>0)failedCases++;
        write(new File(folder,"case-evidence.json"),record);writeIndex("RUNNING",false);System.out.println("HAND CEL "+records.length()+"/48 "+id+" "+record.getString("technical"));
    }

    private void legacyPixels(Path baseline,File folder,JSONObject record,JSONArray errors)throws Exception {
        JSONObject document=read(baseline.resolve("document.json"));clock(document);assertFalse("Render the actual old document without adding a style override",hand(document).getJSONObject("settings").has("renderStyle"));
        String before=SketchDocument.canonical(document);SketchDocument.validate(document);JSONArray comparisons=new JSONArray();
        try(SketchFrameRenderer renderer=new SketchFrameRenderer(context,document)){
            for(int frame:PROBES){
                Bitmap current=null,original=null;try{
                    File golden=baseline.resolve(String.format(Locale.ROOT,"frames/frame-%04d.png",frame)).toFile();
                    BitmapFactory.Options options=new BitmapFactory.Options();options.inPreferredConfig=Bitmap.Config.ARGB_8888;original=BitmapFactory.decodeFile(golden.getAbsolutePath(),options);assertNotNull("Cannot decode original native baseline PNG",original);
                    current=renderer.render(frame);legacyAttempted++;boolean same=current.sameAs(original);if(same)legacyPassed++;else failure(errors,"legacy frame "+frame,new AssertionError("Old document rendered by new product differs from the ORIGINAL matrix-v3 PNG"));
                    File output=new File(folder,String.format(Locale.ROOT,"legacy/frames/frame-%04d.png",frame));png(output,current);
                    comparisons.put(new JSONObject().put("frame",frame).put("originalPng",golden.getAbsolutePath()).put("originalPngSha256",SketchProjectStore.fileHash(golden)).put("newRendererPngSha256",SketchProjectStore.fileHash(output)).put("bitmapSameAs",same)
                            .put("baselineConfig",String.valueOf(original.getConfig())).put("newRendererConfig",String.valueOf(current.getConfig())));
                }catch(InterruptedException e){throw e;}catch(Exception|AssertionError e){failure(errors,"legacy frame "+frame,e);}finally{if(current!=null)current.recycle();if(original!=null)original.recycle();}
            }
        }
        record.put("legacyPixelComparisons",comparisons.length()).put("legacyComparisonFrames",comparisons);write(new File(folder,"legacy-pixel-comparisons.json"),new JSONObject().put("source","Original baseline document.json rendered unchanged through the NEW native renderer; Bitmap.sameAs against ORIGINAL baseline PNG files, never new cel artwork")
                .put("originalDocumentSha256",SketchProjectStore.fileHash(baseline.resolve("document.json").toFile())).put("comparisons",comparisons));
        assertEquals("The old editable document must remain untouched",before,SketchDocument.canonical(document));
    }

    private Map<Path,String> snapshotSources(File repo)throws Exception {
        JSONObject state=new JSONObject().put("baseCommit",BASE).put("headAtStart",git(repo,"rev-parse","HEAD").trim()).put("branch",git(repo,"branch","--show-current").trim()).put("gitStatusAtStart",git(repo,"status","--porcelain"))
                .put("appVersion",AppProtocol.APP_VERSION).put("versionName",versionName).put("versionCode",versionCode).put("freezeSignal",System.getenv("VIDEOSTUDIO_HAND_CEL_READY"))
                .put("sourceSelection","git ls-files --cached --others --exclude-standard; existing files excluding build, .work, .gradle, node_modules and .git directories; untracked contents included in source-snapshot");
        write(new File(root,"Source-State.json"),state);Files.write(new File(root,"Source-Diff.patch").toPath(),git(repo,"diff","--binary",BASE,"--").getBytes(StandardCharsets.UTF_8));
        Map<Path,String> hashes=new LinkedHashMap<>();
        for(Path source:workingSources(repo)){Path relative=repo.toPath().relativize(source);File output=new File(root,"source-snapshot/"+relative);Files.createDirectories(output.toPath().getParent());String hash=SketchProjectStore.fileHash(source.toFile());Files.copy(source,output.toPath());assertEquals("Source changed while snapshotting "+relative,hash,SketchProjectStore.fileHash(output));hashes.put(source,hash);}
        write(new File(root,"Source-Hashes.json"),hashRows(hashes));return hashes;
    }

    private static Set<Path> workingSources(File repo)throws Exception {
        Set<Path> files=new TreeSet<>();
        for(String name:git(repo,"ls-files","--cached","--others","--exclude-standard","-z").split("\\x00")){
            if(name.isEmpty())continue;Path relative=new File(name).toPath();boolean excluded=false;
            for(Path part:relative)if(Arrays.asList("build",".work",".gradle","node_modules",".git").contains(part.toString())){excluded=true;break;}
            Path source=repo.toPath().resolve(relative);if(!excluded&&Files.isRegularFile(source))files.add(source);
        }
        return files;
    }

    private void writeIndex(String status,boolean complete)throws Exception {
        JSONObject index=new JSONObject().put("technical",status).put("cases",records).put("distinctCases",records.length()).put("requestedDistinctStyleCases",48).put("historicalDistinctCases",240)
                .put("combinedDistinctCases",complete?288:JSONObject.NULL).put("technicalFailures",failedCases).put("legacyPixelComparisonsAttempted",legacyAttempted).put("legacyPixelComparisonsPassed",legacyPassed).put("expectedLegacyPixelComparisons",336)
                .put("archivePixelComparisonsPassed",archiveCompared).put("expectedArchivePixelComparisons",96).put("visual","UNREVIEWED").put("manualMcpParity","UNVERIFIED").put("liveManualMcpParity","UNVERIFIED")
                .put("phoneExecution",false).put("professionalQualityCertified",false).put("countSemantics","48 new cel style inputs; 240 historical scenarios retained. Legacy comparisons, frames, assertions and retries add no cases.");
        write(new File(root,status.equals("RUNNING")?"Case-Index-in-progress.json":"Case-Index.json"),index);
    }

    private static JSONObject celOperation(JSONObject original,JSONArray examples,String pattern)throws Exception{
        JSONObject example=null;for(int i=0;i<examples.length();i++)if(pattern.equals(examples.getJSONObject(i).getString("id")))example=examples.getJSONObject(i);
        assertNotNull("Missing app-owned study recipe "+pattern,example);assertEquals(1280,example.getInt("width"));assertEquals(720,example.getInt("height"));assertEquals(96,example.getInt("frameCount"));assertEquals(24,example.getInt("fps"));
        JSONObject result=SketchDocument.copy(example.getJSONObject("operation")),settings=result.getJSONObject("settings"),old=original.getJSONObject("settings");
        settings.put("view",old.getString("view")).put("handedness",old.getString("handedness"));
        JSONObject expected=SketchDocument.copy(original);expected.getJSONObject("settings").put("renderStyle","cel");
        assertEquals("The actual native example must reproduce the unchanged original study input, apart from the explicit style",SketchDocument.canonical(expected),SketchDocument.canonical(result));
        return result;
    }
    private static Set<String> motionCases(){String env=System.getenv("VIDEOSTUDIO_HAND_CEL_MOTION_CASES");Set<String> result=new HashSet<>();if("all".equals(env)){for(String p:PATTERNS)for(String h:HANDS)for(String v:VIEWS)result.add("hand_"+p+"_"+h+"_"+v);}else result.addAll(Arrays.asList(env==null?new String[]{"hand_grasp_right_front","hand_grasp_right_right"}:env.split(",")));return result;}
    private JSONObject rememberJson(Path path)throws Exception{remember(path);return read(path);}
    private String remember(Path path)throws Exception{String hash=SketchProjectStore.fileHash(path.toFile());baselineHashes.put(path,hash);return hash;}
    private static boolean unchanged(Map<Path,String> hashes)throws Exception{for(Map.Entry<Path,String> entry:hashes.entrySet())if(!Files.isRegularFile(entry.getKey())||!entry.getValue().equals(SketchProjectStore.fileHash(entry.getKey().toFile())))return false;return true;}
    private static JSONObject hashRows(Map<Path,String> hashes)throws Exception{JSONObject rows=new JSONObject();for(Map.Entry<Path,String> row:hashes.entrySet())rows.put(row.getKey().toString(),row.getValue());return rows;}
    private void artifactHashes()throws Exception{JSONObject hashes=new JSONObject();try(Stream<Path> walk=Files.walk(root.toPath())){for(Path path:(Iterable<Path>)walk.filter(Files::isRegularFile).sorted()::iterator)if(!path.getFileName().toString().equals("Artifact-Hashes.json"))hashes.put(root.toPath().relativize(path).toString(),SketchProjectStore.fileHash(path.toFile()));}write(new File(root,"Artifact-Hashes.json"),hashes);}
    private static String readText(Path path)throws Exception{return new String(Files.readAllBytes(path),StandardCharsets.UTF_8);}
    private static JSONObject read(Path path)throws Exception{return new JSONObject(readText(path));}
    private static String hash(JSONObject json)throws Exception{return SketchDocument.sha256(SketchDocument.canonical(json).getBytes(StandardCharsets.UTF_8));}
    private static String probeHash(JSONObject evidence,int frame)throws Exception{JSONArray screenshots=evidence.getJSONArray("screenshots");for(int i=0;i<screenshots.length();i++)if(screenshots.getJSONObject(i).getInt("frame")==frame)return screenshots.getJSONObject(i).getString("sha256");throw new AssertionError("Missing original probe receipt "+frame);}
    private static boolean probe(int frame){for(int value:PROBES)if(value==frame)return true;return false;}
    private static void clock(JSONObject doc)throws Exception{assertEquals(96,doc.getInt("frameCount"));assertEquals(24,doc.getInt("fps"));assertEquals(1280,doc.getInt("width"));assertEquals(720,doc.getInt("height"));}
    private static JSONObject hand(JSONObject doc)throws Exception{JSONArray layers=doc.getJSONArray("layers");for(int l=0;l<layers.length();l++){JSONArray objects=layers.getJSONObject(l).getJSONArray("objects");for(int i=0;i<objects.length();i++)if("hand".equals(objects.getJSONObject(i).getString("kind")))return objects.getJSONObject(i).getJSONObject("hand");}throw new AssertionError("Missing native retained hand");}
    private static void failure(JSONArray errors,String stage,Throwable error)throws Exception{errors.put(new JSONObject().put("stage",stage).put("type",error.getClass().getSimpleName()).put("message",String.valueOf(error.getMessage())));}
    private static void png(File output,Bitmap bitmap)throws Exception{Files.createDirectories(output.toPath().getParent());try(FileOutputStream out=new FileOutputStream(output)){assertTrue("Native PNG encoding failed",bitmap.compress(Bitmap.CompressFormat.PNG,100,out));}}
    private static void write(File output,JSONObject json)throws Exception{Files.createDirectories(output.toPath().getParent());Files.write(output.toPath(),json.toString(2).getBytes(StandardCharsets.UTF_8));}
    private static String git(File repo,String... args)throws Exception{List<String> command=new ArrayList<>();command.add("git");command.addAll(Arrays.asList(args));Process process=new ProcessBuilder(command).directory(repo).redirectErrorStream(true).start();String result=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);assertEquals("Git provenance command failed: "+command+"\n"+result,0,process.waitFor());return result;}
}
