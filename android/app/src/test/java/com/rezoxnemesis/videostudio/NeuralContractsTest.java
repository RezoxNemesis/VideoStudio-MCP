package com.rezoxnemesis.videostudio;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class NeuralContractsTest {
 @Test public void clipBpeGoldenMergeHasBosEosAndStablePadding() throws Exception {
  JSONObject vocab=new JSONObject().put("h",1).put("i</w>",2).put("hi</w>",42);
  ClipBpeTokenizer tokenizer=new ClipBpeTokenizer(vocab,"#version: 0.2\nh i</w>\n",49406,49407,49407);
  long[] ids=tokenizer.encode(" HI ");assertEquals(77,ids.length);assertEquals(49406,ids[0]);assertEquals(42,ids[1]);assertEquals(49407,ids[2]);assertEquals(49407,ids[76]);
 }
 @Test public void truncationAlwaysLeavesEndToken() throws Exception {
  ClipBpeTokenizer tokenizer=new ClipBpeTokenizer(new JSONObject().put("a</w>",1),"",2,3,0);
  StringBuilder prompt=new StringBuilder();for(int i=0;i<100;i++) prompt.append("a ");
  long[] ids=tokenizer.encode(prompt.toString());assertEquals(2,ids[0]);for(int i=1;i<76;i++) assertEquals(1,ids[i]);assertEquals(3,ids[76]);
 }
 @Test public void unknownVocabularyDoesNotInventTokenIds() throws Exception {
  ClipBpeTokenizer tokenizer=new ClipBpeTokenizer(new JSONObject().put("a</w>",1),"",2,3,0);
  try {tokenizer.encode("b");fail("Unknown token accepted");}catch(IllegalArgumentException expected) {}
 }
 @Test public void noiseIsSeededAndHasExpectedGaussianVariance() {
  float[] a=NativeNeuralEngine.noise(7,20000,14.6146f),b=NativeNeuralEngine.noise(7,20000,14.6146f),c=NativeNeuralEngine.noise(8,20000,14.6146f);
  assertArrayEquals(a,b,0);assertNotEquals(a[0],c[0],0);
  double sum=0,ss=0;for(float f:a) {sum+=f;ss+=f*f;}assertEquals(0,sum/a.length,.4);assertEquals(14.6146,Math.sqrt(ss/a.length),.4);
 }
 @Test public void unsupportedModelsCannotClaimNativeDiffusionAdapter() throws Exception {
  assertFalse(NativeNeuralEngine.compatible(new JSONObject().put("backend","onnx")));
  assertFalse(NativeNeuralEngine.compatible(new JSONObject().put("backend","onnx-sd-turbo-v1")));
 }
}
