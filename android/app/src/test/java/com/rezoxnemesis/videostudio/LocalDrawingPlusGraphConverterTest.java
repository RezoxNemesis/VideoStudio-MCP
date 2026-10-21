package com.rezoxnemesis.videostudio;

import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.io.*;
import java.lang.reflect.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.LongConsumer;
import static org.junit.Assert.*;

/** Native model engineering fixtures only: no model execution, source artwork or generation. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class LocalDrawingPlusGraphConverterTest {
 private static final String CLIP="clip_penultimate_v1",UNET="sd15_ipkv16_v1";
 private static final String[] LAYERS={
  "down_blocks.0/attentions.0","down_blocks.0/attentions.1","down_blocks.1/attentions.0","down_blocks.1/attentions.1","down_blocks.2/attentions.0","down_blocks.2/attentions.1",
  "up_blocks.1/attentions.0","up_blocks.1/attentions.1","up_blocks.1/attentions.2","up_blocks.2/attentions.0","up_blocks.2/attentions.1","up_blocks.2/attentions.2","up_blocks.3/attentions.0","up_blocks.3/attentions.1","up_blocks.3/attentions.2","mid_block/attentions.0"};
 private static final int[] INNER={320,320,640,640,1280,1280,1280,1280,1280,640,640,640,320,320,320,1280};
 private static final long[][] RESIDUALS={{1,320,64,64},{1,320,64,64},{1,320,64,64},{1,320,32,32},{1,640,32,32},{1,640,32,32},{1,640,16,16},{1,1280,16,16},{1,1280,16,16},{1,1280,8,8},{1,1280,8,8},{1,1280,8,8},{1,1280,8,8}};
 private File directory;private byte[] weights;
 @Before public void fixture()throws Exception {directory=Files.createTempDirectory("native-plus-graph-").toFile();weights=new byte[2*1024*1024+13];for(int i=0;i<weights.length;i++)weights[i]=(byte)(i*31+7);}
 @Test public void clipAddsActualLayer30OutputAndPreservesOriginalGraphAndWeightBytes()throws Exception {
  byte[] source=clip(false,false),expected=clip(true,false);File input=file("clip.onnx",source),target=new File(directory,"prepared-clip.onnx");List<Long> writes=new ArrayList<>();JSONObject result=convert(CLIP,input,target,sha(source),sha(expected),writes::add);
  assertArrayEquals(expected,Files.readAllBytes(target.toPath()));assertArrayEquals(source,Files.readAllBytes(input.toPath()));assertEquals(sha(expected),result.getString("resultSha256"));assertEquals(sha(source),result.getString("sourceSha256"));
  Map<String,byte[]> outputs=namedFields(graph(Files.readAllBytes(target.toPath())),12);assertEquals(3,outputs.size());assertTrue(outputs.containsKey("penultimate_hidden_states"));assertArrayEquals(value("penultimate_hidden_states",10,1,257,1280),outputs.get("penultimate_hidden_states"));assertBoundedWrites(writes,target.length());
 }
 @Test public void unetHasExactly48TypedInputsWithExplicitHeadOrderAndOnlyIpTokenReshapesChanged()throws Exception {
  byte[] source=unet(false,""),expected=unet(true,"");File input=file("unet.onnx",source),target=new File(directory,"prepared-unet.onnx");List<Long> writes=new ArrayList<>();convert(UNET,input,target,sha(source),sha(expected),writes::add);assertArrayEquals(expected,Files.readAllBytes(target.toPath()));assertArrayEquals(source,Files.readAllBytes(input.toPath()));
  byte[] convertedGraph=graph(expected);Map<String,byte[]> inputs=namedFields(convertedGraph,11),nodes=namedNodes(convertedGraph),initializers=namedTensors(convertedGraph);assertEquals(48,inputs.size());assertFalse(inputs.containsKey("image_embeds"));assertArrayEquals(value("sample",10,1,4,64,64),inputs.get("sample"));assertArrayEquals(value("timestep",10,1),inputs.get("timestep"));assertArrayEquals(value("encoder_hidden_states",10,1,77,768),inputs.get("encoder_hidden_states"));
  for(int i=0;i<13;i++)assertArrayEquals("Existing residual dtype/layout retained "+i,value(residual(i),10,RESIDUALS[i]),inputs.get(residual(i)));
  for(int i=0;i<16;i++){String prefix=prefix(i);assertArrayEquals(value("ipk_"+i,10,1,INNER[i],16),inputs.get("ipk_"+i));assertArrayEquals(value("ipv_"+i,10,1,16,INNER[i]),inputs.get("ipv_"+i));assertArrayEquals("Key layout must actually transpose, not alias",kvNode(i,true),nodes.get(prefix+"to_k_ip.0/MatMul"));assertArrayEquals(kvNode(i,false),nodes.get(prefix+"to_v_ip.0/MatMul"));assertArrayEquals(shapeTensor(i,16),initializers.get("videostudio_ip_shape_"+i));}
  assertFalse(nodes.keySet().stream().anyMatch(n->n.startsWith("/unet/encoder_hid_proj/")));assertFalse("Generic-named image-input shape dependencies must also be pruned",nodes.containsKey("/unet/Shape"));assertFalse(nodes.containsKey("/unet/Gather"));assertArrayEquals("Text attention reshape is unchanged",node("text_keep","Reshape",new String[]{"encoder_hidden_states","text_shape"},new String[]{"text_kept"}),nodes.get("text_keep"));assertBoundedWrites(writes,target.length());
 }
 @Test public void wrongPinMalformedGraphMissingPenultimateAndChangedResidualOrDuplicateSitesNeverReplaceTarget()throws Exception {
  File target=file("keep.onnx",new byte[]{9,8,7});byte[] before=Files.readAllBytes(target.toPath());
  File clip=file("clip.onnx",clip(false,false));assertThrows(Exception.class,()->convert(CLIP,clip,target,"0".repeat(64),sha(clip(true,false)),null));assertArrayEquals(before,Files.readAllBytes(target.toPath()));
  for(byte[] source:new byte[][]{clip(false,true),new byte[]{58,(byte)255,(byte)255,(byte)255,(byte)255,(byte)127},unet(false,"residual_dtype"),unet(false,"duplicate_k"),unet(false,"missing_k")}){
   File input=file("bad.onnx",source);assertThrows(Exception.class,()->convert(source.length<100?CLIP:has(source,"vision_model")?CLIP:UNET,input,target,sha(source),"1".repeat(64),null));assertArrayEquals(before,Files.readAllBytes(target.toPath()));assertNoPrivateFiles();
  }
 }
 @Test public void cancellationWhileStreamingOpaqueWeightsLeavesSourceAndExistingTargetUnchanged()throws Exception {
  byte[] source=unet(false,""),expected=unet(true,"");File input=file("cancel-source.onnx",source),target=file("keep.onnx",new byte[]{6,5,4});
  Exception error=assertThrows(Exception.class,()->convert(UNET,input,target,sha(source),sha(expected),n->{if(n>256*1024)throw new CancellationException("test native installation cancellation");}));assertTrue(error.toString(),error.toString().contains("cancel"));assertArrayEquals(new byte[]{6,5,4},Files.readAllBytes(target.toPath()));assertArrayEquals(source,Files.readAllBytes(input.toPath()));assertNoPrivateFiles();
 }
 @Test public void mismatchedDerivedHashCannotPublishAndIndependentConversionsAreByteDeterministic()throws Exception {
  byte[] source=unet(false,""),expected=unet(true,"");File input=file("source.onnx",source),target=file("keep.onnx",new byte[]{1,2,3});assertThrows(Exception.class,()->convert(UNET,input,target,sha(source),"f".repeat(64),null));assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(target.toPath()));assertNoPrivateFiles();
  convert(UNET,input,target,sha(source),sha(expected),null);File second=new File(directory,"second.onnx");convert(UNET,input,second,sha(source),sha(expected),null);assertArrayEquals(Files.readAllBytes(target.toPath()),Files.readAllBytes(second.toPath()));assertArrayEquals(source,Files.readAllBytes(input.toPath()));
 }
 private JSONObject convert(String recipe,File source,File target,String sourceSha,String resultSha,LongConsumer progress)throws Exception {
  Class<?> converter;try{converter=Class.forName("com.rezoxnemesis.videostudio.LocalDrawingPlusGraphConverter");}catch(ClassNotFoundException missing){throw new AssertionError("Native deterministic pinned streaming Plus graph converter must exist",missing);}
  Method method=converter.getDeclaredMethod("convert",String.class,File.class,File.class,String.class,String.class,LongConsumer.class);method.setAccessible(true);try{return(JSONObject)method.invoke(null,recipe,source,target,sourceSha,resultSha,progress);}catch(InvocationTargetException failure){if(failure.getCause() instanceof Exception)throw(Exception)failure.getCause();throw failure;}
 }
 private byte[] clip(boolean prepared,boolean wrongLayer)throws Exception {
  String penultimate="/vision_model/encoder/layers.30/Add_1_output_0",last="onnx::Gather_4496";List<byte[]> g=new ArrayList<>();g.add(field(1,node(wrongLayer?"/vision_model/encoder/layers.29/Add_1":"/vision_model/encoder/layers.30/Add_1","Add",new String[]{"pixels","weights"},new String[]{wrongLayer?"wrong_hidden":penultimate})));g.add(field(1,node("/vision_model/encoder/layers.31/Add_1","Add",new String[]{wrongLayer?"wrong_hidden":penultimate,"weights"},new String[]{last})));g.add(field(5,opaque()));g.add(field(11,value("pixel_values",10,1,3,224,224)));g.add(field(12,value("image_embeds",10,1,1024)));g.add(field(12,value(last,10,1,257,1280)));
  if(prepared){g.add(field(1,node("videostudio/penultimate_hidden_states","Identity",new String[]{penultimate},new String[]{"penultimate_hidden_states"})));g.add(field(12,value("penultimate_hidden_states",10,1,257,1280)));}return model(join(g));
 }
 private byte[] unet(boolean prepared,String bad)throws Exception {
  List<byte[]> g=new ArrayList<>();if(!prepared){g.add(field(1,node("/unet/Shape","Shape",new String[]{"image_embeds"},new String[]{"legacy_image_shape"})));g.add(field(1,node("/unet/Gather","Identity",new String[]{"legacy_image_shape"},new String[]{"legacy_image_size"})));g.add(field(1,node("/unet/encoder_hid_proj/Reshape","Identity",new String[]{"image_embeds"},new String[]{"legacy_tokens"})));}
  // Physical source order is down/mid/up; head contract order is down/up/mid.
  int[] physical={0,1,2,3,4,5,15,6,7,8,9,10,11,12,13,14};for(int i:physical){String p=prefix(i);for(boolean key:new boolean[]{true,false}){if(!key||!bad.equals("missing_k")||i!=15){byte[] n=prepared?kvNode(i,key):node(p+(key?"to_k_ip.0/MatMul":"to_v_ip.0/MatMul"),"MatMul",new String[]{"legacy_tokens","weights"},new String[]{p+(key?"to_k_ip.0/MatMul_output_0":"to_v_ip.0/MatMul_output_0")});g.add(field(1,n));if(!prepared&&key&&i==0&&bad.equals("duplicate_k"))g.add(field(1,n));}}
   g.add(field(1,node(p+"Reshape_4","Reshape",new String[]{p+"to_k_ip.0/MatMul_output_0",prepared?"videostudio_ip_shape_"+i:"legacy_shape_"+i},new String[]{p+"Reshape_4_output_0"})));g.add(field(1,node(p+"Reshape_5","Reshape",new String[]{p+"to_v_ip.0/MatMul_output_0",prepared?"videostudio_ip_shape_"+i:"legacy_shape_"+i},new String[]{p+"Reshape_5_output_0"})));}
  g.add(field(1,node("text_keep","Reshape",new String[]{"encoder_hidden_states","text_shape"},new String[]{"text_kept"})));g.add(field(1,node("timestep_keep","Identity",new String[]{"timestep"},new String[]{"timestep_kept"})));for(int i=0;i<13;i++)g.add(field(1,node("res_use_"+i,"Identity",new String[]{residual(i)},new String[]{"used_"+i})));g.add(field(1,node("output","Identity",new String[]{"sample"},new String[]{"noise_pred"})));g.add(field(5,opaque()));
  g.add(field(11,value("sample",10,1,4,64,64)));g.add(field(11,value("timestep",10,1)));g.add(field(11,value("encoder_hidden_states",10,1,77,768)));if(!prepared)g.add(field(11,value("image_embeds",10,1,1,1024)));for(int i=0;i<13;i++)g.add(field(11,value(residual(i),i==12&&bad.equals("residual_dtype")?1:10,RESIDUALS[i])));g.add(field(12,value("noise_pred",10,1,4,64,64)));
  if(prepared){for(int i=0;i<16;i++)g.add(field(5,shapeTensor(i,16)));for(int i=0;i<16;i++){g.add(field(11,value("ipk_"+i,10,1,INNER[i],16)));g.add(field(11,value("ipv_"+i,10,1,16,INNER[i])));}}return model(join(g));
 }
 private static byte[] kvNode(int i,boolean key)throws Exception {String p=prefix(i);if(!key)return node(p+"to_v_ip.0/MatMul","Identity",new String[]{"ipv_"+i},new String[]{p+"to_v_ip.0/MatMul_output_0"});byte[] attribute=join(text(1,"perm"),number(20,7),field(8,join(varint(0),varint(2),varint(1))));return join(node(p+"to_k_ip.0/MatMul","Transpose",new String[]{"ipk_"+i},new String[]{p+"to_k_ip.0/MatMul_output_0"}),field(5,attribute));}
 private static String prefix(int i){return"/unet/"+LAYERS[i]+"/transformer_blocks.0/attn2/";}
 private static String residual(int i){return i<12?"down_residual_"+i:"mid_residual";}
 private byte[] opaque()throws Exception {return join(number(1,weights.length),number(2,2),text(8,"opaque_weights"),field(9,weights));}
 private static byte[] shapeTensor(int i,int tokens)throws Exception {ByteBuffer data=ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);for(long v:new long[]{1,tokens,8,INNER[i]/8})data.putLong(v);return join(number(1,4),number(2,7),text(8,"videostudio_ip_shape_"+i),field(9,data.array()));}
 private static byte[] model(byte[] graph)throws Exception{return join(number(1,8),text(2,"fixture-public-model"),text(6,"Preserve unknown top metadata"),field(7,graph),field(8,number(2,17)));}
 private static byte[] value(String name,int type,long...shape)throws Exception {List<byte[]> dimensions=new ArrayList<>();for(long n:shape)dimensions.add(field(1,number(1,n)));return join(text(1,name),field(2,field(1,join(number(1,type),field(2,join(dimensions))))));}
 private static byte[] node(String name,String op,String[] inputs,String[] outputs)throws Exception {List<byte[]> fields=new ArrayList<>();for(String in:inputs)fields.add(text(1,in));for(String out:outputs)fields.add(text(2,out));fields.add(text(3,name));fields.add(text(4,op));return join(fields);}
 private static byte[] field(int tag,byte[] data)throws Exception {return join(varint(((long)tag<<3)|2),varint(data.length),data);}
 private static byte[] text(int tag,String data)throws Exception{return field(tag,data.getBytes(StandardCharsets.UTF_8));}
 private static byte[] number(int tag,long value)throws Exception{return join(varint((long)tag<<3),varint(value));}
 private static byte[] varint(long value){ByteArrayOutputStream out=new ByteArrayOutputStream();while((value&~127L)!=0){out.write((int)(value&127)|128);value>>>=7;}out.write((int)value);return out.toByteArray();}
 private static byte[] join(byte[]...items)throws Exception{return join(Arrays.asList(items));}
 private static byte[] join(List<byte[]> items)throws Exception {ByteArrayOutputStream out=new ByteArrayOutputStream();for(byte[] item:items)out.write(item);return out.toByteArray();}
 private File file(String name,byte[] bytes)throws Exception {File f=new File(directory,name);Files.write(f.toPath(),bytes);return f;}
 private static String sha(byte[] bytes)throws Exception{return SketchDocument.sha256(bytes);}
 private static boolean has(byte[] bytes,String text){return new String(bytes,StandardCharsets.ISO_8859_1).contains(text);}
 private void assertNoPrivateFiles(){assertFalse("Only private conversion temp files may be cleaned",Arrays.stream(directory.listFiles()).anyMatch(f->f.getName().startsWith(".plus_")));}
 private static void assertBoundedWrites(List<Long> writes,long length){assertFalse(writes.isEmpty());long last=0;for(long n:writes){assertTrue(n>=last);assertTrue("Native output must stream <=64KiB chunks",n-last<=65536);last=n;}assertEquals(length,last);}
 private static byte[] graph(byte[] model)throws Exception {for(Part p:parts(model))if(p.tag==7)return p.bytes;throw new AssertionError("Missing graph");}
 private static Map<String,byte[]> namedFields(byte[] graph,int tag)throws Exception {Map<String,byte[]> out=new LinkedHashMap<>();for(Part p:parts(graph))if(p.tag==tag)out.put(string(p.bytes,1),p.bytes);return out;}
 private static Map<String,byte[]> namedNodes(byte[] graph)throws Exception {Map<String,byte[]> out=new LinkedHashMap<>();for(Part p:parts(graph))if(p.tag==1)out.put(string(p.bytes,3),p.bytes);return out;}
 private static Map<String,byte[]> namedTensors(byte[] graph)throws Exception {Map<String,byte[]> out=new LinkedHashMap<>();for(Part p:parts(graph))if(p.tag==5)out.put(string(p.bytes,8),p.bytes);return out;}
 private static String string(byte[] message,int tag)throws Exception {for(Part p:parts(message))if(p.tag==tag)return new String(p.bytes,StandardCharsets.UTF_8);return"";}
 private static final class Part{int tag;byte[] bytes;Part(int t,byte[] b){tag=t;bytes=b;}}
 private static List<Part> parts(byte[] data)throws Exception {List<Part> out=new ArrayList<>();ByteArrayInputStream in=new ByteArrayInputStream(data);while(in.available()>0){long key=read(in);int wire=(int)(key&7),tag=(int)(key>>>3);if(wire==0){read(in);continue;}if(wire!=2)throw new IOException("Unsupported fixture field");int length=(int)read(in);if(length<0||length>in.available())throw new IOException("Invalid fixture length");byte[] bytes=new byte[length];assertEquals(length,in.read(bytes));out.add(new Part(tag,bytes));}return out;}
 private static long read(InputStream in)throws Exception {long value=0;for(int shift=0;shift<64;shift+=7){int next=in.read();if(next<0)throw new EOFException();value|=(long)(next&127)<<shift;if(next<128)return value;}throw new IOException("Fixture varint overflow");}
}
