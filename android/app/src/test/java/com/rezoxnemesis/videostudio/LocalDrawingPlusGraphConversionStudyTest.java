package com.rezoxnemesis.videostudio;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.*;
import static org.junit.Assert.*;

/** Opt-in actual public-weight conversion by app code. No model runtime or artwork. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class LocalDrawingPlusGraphConversionStudyTest {
 @Test public void convertAlreadyPinnedPublicGraphsWithSeparateDiscoveryAndFrozenVerification()throws Exception {
  String configPath=System.getenv("VIDEOSTUDIO_PLUS_GRAPH_CONVERSION");Assume.assumeTrue("Explicit public model conversion study only",configPath!=null&&!configPath.isEmpty());JSONObject config=new JSONObject(new String(Files.readAllBytes(new File(configPath).toPath()),StandardCharsets.UTF_8));String mode=config.getString("mode");assertTrue(mode,mode.equals("discover")||mode.equals("verify"));boolean discover=mode.equals("discover");File directory=new File(config.getString("outputDirectory"));assertTrue(directory.mkdirs()||directory.isDirectory());File evidence=new File(directory,"native-conversion-evidence.json");assertFalse("Preserve previous study evidence: choose a fresh directory",evidence.exists());
  JSONObject study=new JSONObject().put("mode",mode).put("status","RUNNING").put("sourceCommit",config.getString("sourceCommit")).put("sourceState",config.getString("sourceState")).put("actualAppProtocolVersion",AppProtocol.APP_VERSION).put("intendedNextVersion","4.10.8 work in progress; not signed/installed").put("inferenceExecuted",false).put("modelRuntimeLoaded",false).put("artworkProcessed",false).put("phoneExecutionVerified",false).put("discoveryIsVerification",false).put("startedAt",System.currentTimeMillis()).put("models",new JSONArray());write(evidence,study);
  String[][] models=config.optBoolean("legacyAuditOnly",false)?new String[][]{{"sd15_legacy_ipkv_probe_v1","unetSource","a0287f119d85b8028d9673850322247b5978ed9b504077bc04d433f4c9fadcb7","legacyAuditSha256","sd15-original-kv-audit-fp16.onnx"}}:new String[][]{{"clip_penultimate_v1","clipSource","038078ba22b67e57ec2ca5b466e0699f30e14a9613054be8c7fb24e537987689","clipSha256","clip-vit-h-14-penultimate-fp16.onnx"},{"sd15_ipkv16_v1","unetSource","a0287f119d85b8028d9673850322247b5978ed9b504077bc04d433f4c9fadcb7","unetSha256","sd15-ipkv16-controlnet-fp16.onnx"}};
  try{
   for(String[] item:models){File source=new File(config.getString(item[1])),target=new File(directory,item[4]);assertFalse("Never overwrite an earlier study graph",target.exists());assertEquals("Original pinned public input",item[2],LocalDrawingModels.hash(source));String expected=discover?"0".repeat(64):config.getString(item[3]);if(!discover)assertTrue("Freeze an observed expected digest before verification",expected.matches("[a-f0-9]{64}")&&!expected.equals("0".repeat(64)));JSONObject row=new JSONObject().put("recipe",item[0]).put("sourcePath",source.getAbsolutePath()).put("sourceBytes",source.length()).put("sourceSha256",item[2]).put("expectedDerivedSha256",expected).put("targetPath",target.getAbsolutePath()).put("status","RUNNING");study.getJSONArray("models").put(row);write(evidence,study);long start=System.currentTimeMillis();final long[] progress={0,0};
    try{
     JSONObject result=LocalDrawingPlusGraphConverter.convert(item[0],source,target,item[2],expected,n->{if(n<progress[0])throw new AssertionError("Nonmonotonic model conversion");progress[1]=Math.max(progress[1],n-progress[0]);progress[0]=n;});assertFalse("Discovery cannot publish",discover);assertEquals(expected,LocalDrawingModels.hash(target));row.put("status","TECHNICAL_CONVERSION_PASS").put("converterReceipt",result).put("verifiedDerivedSha256",expected).put("derivedBytes",target.length());
    }catch(Exception failure){row.put("error",failure.toString());Matcher observed=Pattern.compile("^PLUS_CONVERSION_RESULT_SHA_DIFFERS: expected=0{64} actual=([a-f0-9]{64})$").matcher(failure.getMessage()==null?"":failure.getMessage());if(!discover||!observed.matches()){row.put("status","FAIL");throw failure;}assertFalse("Discovery SHA is evidence, never a published model",target.exists());row.put("status","DISCOVERED_UNVERIFIED").put("observedDerivedSha256",observed.group(1)).put("candidateBytesNotPublished",progress[0]);}
    finally{row.put("elapsedMs",System.currentTimeMillis()-start).put("outputBytesStreamed",progress[0]).put("observedMaxWriteChunkBytes",progress[1]).put("sourceSha256After",LocalDrawingModels.hash(source));write(evidence,study);}
    assertEquals("Original model bytes remain unchanged",item[2],row.getString("sourceSha256After"));assertTrue(progress[1]>0&&progress[1]<=65536);assertFalse("Failed/private conversion files do not become retained model files",java.util.Arrays.stream(directory.listFiles()).anyMatch(f->f.getName().startsWith(".plus_")));
   }
   study.put("status",discover?"DISCOVERY_ONLY_NOT_VERIFIED":"TECHNICAL_CONVERSION_PASS_RUNTIME_UNVERIFIED");
  }catch(Exception|AssertionError failure){study.put("status","FAIL").put("error",failure.toString());throw failure;}
  finally{study.put("finishedAt",System.currentTimeMillis()).put("hostProcessMemory",memory());write(evidence,study);}
 }
 private static JSONObject memory(){JSONObject out=new JSONObject();try{for(String line:Files.readAllLines(new File("/proc/self/status").toPath(),StandardCharsets.UTF_8))if(line.startsWith("VmRSS:")||line.startsWith("VmHWM:"))out.put(line.substring(0,line.indexOf(':')),line.substring(line.indexOf(':')+1).trim());out.put("scope","Host test process; not phone or inference memory");}catch(Exception unavailable){}return out;}
 private static void write(File file,JSONObject object)throws Exception {Files.write(file.toPath(),object.toString(2).getBytes(StandardCharsets.UTF_8));}
}
