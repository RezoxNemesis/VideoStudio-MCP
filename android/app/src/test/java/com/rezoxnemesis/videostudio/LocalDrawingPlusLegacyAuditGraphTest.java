package com.rezoxnemesis.videostudio;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.io.*;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

/** Append-only model audit graph. Synthetic protobuf contract, not model execution. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class LocalDrawingPlusLegacyAuditGraphTest {
 private static final String[] LAYERS={"down_blocks.0/attentions.0","down_blocks.0/attentions.1","down_blocks.1/attentions.0","down_blocks.1/attentions.1","down_blocks.2/attentions.0","down_blocks.2/attentions.1","up_blocks.1/attentions.0","up_blocks.1/attentions.1","up_blocks.1/attentions.2","up_blocks.2/attentions.0","up_blocks.2/attentions.1","up_blocks.2/attentions.2","up_blocks.3/attentions.0","up_blocks.3/attentions.1","up_blocks.3/attentions.2","mid_block/attentions.0"};
 private static final int[] INNER={320,320,640,640,1280,1280,1280,1280,1280,640,640,640,320,320,320,1280};
 @Test public void auditOnlyAppends32TypedFourTokenOutputsAndRetainsEveryOriginalGraphField()throws Exception {
  LocalDrawingPlusGraphConverterTest fixture=new LocalDrawingPlusGraphConverterTest();fixture.fixture();Method method=fixture.getClass().getDeclaredMethod("unet",boolean.class,String.class);method.setAccessible(true);byte[] original=(byte[])method.invoke(fixture,false,"");List<Part> top=parts(original);byte[] graph=null;for(Part part:top)if(part.tag==7)graph=part.data;assertNotNull(graph);ByteArrayOutputStream appended=new ByteArrayOutputStream();for(int i=0;i<16;i++){appended.write(field(12,value(output(i,true),INNER[i])));appended.write(field(12,value(output(i,false),INNER[i])));}byte[] preparedGraph=join(graph,appended.toByteArray());ByteArrayOutputStream expectedModel=new ByteArrayOutputStream();for(Part part:top)expectedModel.write(part.tag==7?field(7,preparedGraph):part.raw);byte[] expected=expectedModel.toByteArray();File directory=Files.createTempDirectory("native-legacy-kv-audit").toFile(),source=new File(directory,"original.onnx"),target=new File(directory,"audit.onnx");Files.write(source.toPath(),original);
  try{LocalDrawingPlusGraphConverter.convert("sd15_legacy_ipkv_probe_v1",source,target,SketchDocument.sha256(original),SketchDocument.sha256(expected),null);}catch(Exception missing){throw new AssertionError("Native append-only old-KV audit recipe must exist without altering the original graph",missing);}
  assertArrayEquals(expected,Files.readAllBytes(target.toPath()));assertArrayEquals(original,Files.readAllBytes(source.toPath()));List<Part> sourceParts=parts(graph),afterParts=parts(preparedGraph);assertEquals(sourceParts.size()+32,afterParts.size());for(int i=0;i<sourceParts.size();i++)assertArrayEquals("All old nodes,weights,17inputs and noise output stay byte-identical",sourceParts.get(i).raw,afterParts.get(i).raw);assertEquals(17,sourceParts.stream().filter(p->p.tag==11).count());assertEquals(33,afterParts.stream().filter(p->p.tag==12).count());
 }
 static String output(int i,boolean key){return"/unet/"+LAYERS[i]+"/transformer_blocks.0/attn2/to_"+(key?"k":"v")+"_ip.0/MatMul_output_0";}
 private static byte[] value(String name,int inner)throws Exception {ByteArrayOutputStream dims=new ByteArrayOutputStream();for(long n:new long[]{1,1,4,inner})dims.write(field(1,number(1,n)));return join(text(1,name),field(2,field(1,join(number(1,10),field(2,dims.toByteArray())))));}
 private static byte[] field(int tag,byte[] data)throws Exception{return join(varint(((long)tag<<3)|2),varint(data.length),data);}
 private static byte[] text(int tag,String text)throws Exception{return field(tag,text.getBytes(StandardCharsets.UTF_8));}
 private static byte[] number(int tag,long n)throws Exception{return join(varint((long)tag<<3),varint(n));}
 private static byte[] varint(long n){ByteArrayOutputStream out=new ByteArrayOutputStream();while((n&~127L)!=0){out.write((int)(n&127)|128);n>>>=7;}out.write((int)n);return out.toByteArray();}
 private static byte[] join(byte[]...arrays)throws Exception {ByteArrayOutputStream out=new ByteArrayOutputStream();for(byte[] bytes:arrays)out.write(bytes);return out.toByteArray();}
 private static final class Part{final int tag;final byte[] raw,data;Part(int t,byte[] r,byte[] d){tag=t;raw=r;data=d;}}
 private static List<Part> parts(byte[] data)throws Exception {List<Part> parts=new ArrayList<>();ByteArrayInputStream in=new ByteArrayInputStream(data);while(in.available()>0){int start=data.length-in.available();long key=read(in);int wire=(int)(key&7);byte[] payload=new byte[0];if(wire==0)read(in);else if(wire==2){int length=(int)read(in);if(length<0||length>in.available())throw new IOException("Invalid fixture");payload=new byte[length];assertEquals(length,in.read(payload));}else throw new IOException("Unsupported fixture wire");parts.add(new Part((int)(key>>>3),Arrays.copyOfRange(data,start,data.length-in.available()),payload));}return parts;}
 private static long read(InputStream in)throws Exception {long n=0;for(int s=0;s<64;s+=7){int b=in.read();if(b<0)throw new EOFException();n|=(long)(b&127)<<s;if(b<128)return n;}throw new IOException("Invalid fixture varint");}
}
