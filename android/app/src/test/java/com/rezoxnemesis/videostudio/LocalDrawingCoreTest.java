package com.rezoxnemesis.videostudio;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import org.json.*;
import org.robolectric.RuntimeEnvironment;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class LocalDrawingCoreTest {
 @Test public void schedulerUsesTheSd15TrainingDistributionAndLeadingClock(){
  LocalDrawingSchedule schedule=new LocalDrawingSchedule();
  assertEquals(.99915,schedule.alpha(0),1e-12);
  assertEquals(.0046600985,schedule.alpha(999),1e-9);
  assertArrayEquals(new int[]{401,301,201,101,1},schedule.timesteps(10,.5));
  assertArrayEquals(new int[]{1},schedule.timesteps(4,.1));
  assertThrows(IllegalArgumentException.class,()->schedule.timesteps(31,.5));
  assertThrows(IllegalArgumentException.class,()->schedule.timesteps(10,Double.NaN));
 }
 @Test public void zeroEpsilonStepPreservesPredictedOriginalAndRejectsInvalidTensors(){
  LocalDrawingSchedule s=new LocalDrawingSchedule();float[] x={.25f,-.5f};
  float[] next=s.step(x,new float[2],401,301);double scale=Math.sqrt(s.alpha(301)/s.alpha(401));
  assertEquals(.25*scale,next[0],1e-7);assertEquals(-.5*scale,next[1],1e-7);
  assertThrows(IllegalArgumentException.class,()->s.step(x,new float[]{Float.NaN,0},401,301));
  assertThrows(IllegalArgumentException.class,()->s.step(x,new float[1],401,301));
  assertThrows(IllegalArgumentException.class,()->s.step(x,new float[2],401,501));
 }
 @Test public void realPinnedClipVocabularyMatchesKnownTokensAndDoesNotSilentlyTruncate()throws Exception{
  File root=new File(RuntimeEnvironment.getApplication().getCacheDir(),"pinned_tokenizer");assertTrue(root.mkdirs()||root.isDirectory());
  JSONObject catalogue=new LocalDrawingModels(RuntimeEnvironment.getApplication()).catalogue();
  for(String name:new String[]{"tokenizer/vocab.json","tokenizer/merges.txt"}){
   File file=new File(root,name);assertTrue(file.getParentFile().mkdirs()||file.getParentFile().isDirectory());try(InputStream in=getClass().getResourceAsStream("/local_drawing/"+name)){assertNotNull(in);Files.copy(in,file.toPath());}
   JSONArray files=catalogue.getJSONArray("files");String sha="";for(int i=0;i<files.length();i++)if(name.equals(files.getJSONObject(i).getString("path")))sha=files.getJSONObject(i).getString("sha256");assertEquals(sha,LocalDrawingModels.hash(file));
  }
  LocalDrawingTokenizer tokenizer=new LocalDrawingTokenizer(root);long[] out=tokenizer.encode("a photo of a cat");
  long[] expected={49406,320,1125,539,320,2368,49407};for(int i=0;i<expected.length;i++)assertEquals(expected[i],out[i]);
  for(int i=expected.length;i<77;i++)assertEquals(49407,out[i]);
  assertArrayEquals(out,tokenizer.encode(" A   PHOTO of a cat "));
  long[] punctuation={49406,258,258,320,2368,49407};long[] actual=tokenizer.encode("# # a cat");for(int i=0;i<punctuation.length;i++)assertEquals(punctuation[i],actual[i]);
  long[] unicode={49406,3459,1958,537,2225,49407};actual=tokenizer.encode("éyes and hair");for(int i=0;i<unicode.length;i++)assertEquals(unicode[i],actual[i]);
  assertThrows(IllegalArgumentException.class,()->tokenizer.encode("cat ".repeat(80)));
  assertThrows(IllegalArgumentException.class,()->tokenizer.encode("<|endoftext|>"));
 }
}
