package com.rezoxnemesis.videostudio;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Real PCM reads through the owned-asset production entrypoint; no codec shadow. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class PcmWaveformTest {
    private final Context context=RuntimeEnvironment.getApplication();

    @Test public void rectangularBucketsUseAllStereoChannelsAndRmsNotSignedChannelMix()throws Exception {
        // Right-only energy and opposite polarity must not disappear. This also
        // distinguishes true RMS from average absolute amplitude (bucket 2).
        short[] pcm=new short[8000*2];
        for(int frame=2000;frame<4000;frame++)pcm[frame*2+1]=16384;
        for(int frame=4000;frame<6000;frame++)if((frame&1)==0)pcm[frame*2]=16384;
        for(int frame=6000;frame<8000;frame++){pcm[frame*2]=8192;pcm[frame*2+1]=-8192;}
        File file=file(wav(8000,2,pcm,false,false));
        assertArrayEquals(new float[]{0,1,(float)Math.sqrt(.5), (float)Math.sqrt(.5)},
                analyze(own(Uri.fromFile(file),1000),0,1000,4),.00001f);
    }

    @Test public void sourceOffsetUsesAbsoluteFrameClockAcrossFractionalMillisecondBoundaries()throws Exception {
        // At 11025 Hz, a 1 ms source offset lies between frames 11 and 12.
        // Fill independently computed intervals [1,26), [26,51), [51,76), [76,101) ms.
        short[] pcm=new short[2205];Arrays.fill(pcm,(short)30000);
        int[] boundaries={12,287,563,838,1114};
        short[] values={0,8192,16384,4096};
        for(int i=0;i<4;i++)Arrays.fill(pcm,boundaries[i],boundaries[i+1],values[i]);
        assertArrayEquals(new float[]{0,.5f,1,.25f},
                analyze(own(Uri.fromFile(file(wav(11025,1,pcm,false,false))),200),1,100,4),.00001f);
    }

    @Test public void dataBeforeFmtAndPaddedUnknownChunksKeepTheSameEnergy()throws Exception {
        short[] pcm=new short[8000];Arrays.fill(pcm,2000,4000,(short)8192);
        Arrays.fill(pcm,4000,6000,(short)16384);
        byte[] wave=wav(8000,1,pcm,true,true);File f=file(wave);
        assertEquals(8000,PcmWaveFile.inspect(f).getLong("sampleFrames"));
        assertArrayEquals(new float[]{0,.5f,1,0},analyze(own(Uri.fromFile(f),1000),0,1000,4),.00001f);
    }

    @Test public void selectedContentDescriptorHonorsItsOffsetAndLengthWithoutQueryingGallery()throws Exception {
        short[] pcm=new short[8000];Arrays.fill(pcm,4000,6000,(short)10000);
        byte[] wave=wav(8000,1,pcm,true,true);
        ByteArrayOutputStream backing=new ByteArrayOutputStream();backing.write(new byte[37]);
        backing.write(wave);backing.write(new byte[91]);
        SelectedProvider provider=new SelectedProvider(file(backing.toByteArray()),37,wave.length);
        registerSelected(provider);
        assertArrayEquals(new float[]{0,0,1,0},analyze(own(Uri.parse("content://selected-pcm/chosen"),1000),0,1000,4),.00001f);
        assertEquals("Open only the selected descriptor once",1,provider.opened);
        assertEquals("Every selected descriptor must close",provider.opened,provider.closed);
        assertEquals("Never query Gallery or enumerate other sources",0,provider.queries);
    }

    @Test public void malformedPcmRejectsInsteadOfReturningAnInventedSilentSuccess()throws Exception {
        byte[] good=wav(8000,1,new short[8000],false,false);
        byte[] wrongRiff=good.clone();put32(wrongRiff,4,wrongRiff.length-9);
        byte[] truncated=Arrays.copyOf(good,good.length-1);put32(truncated,4,truncated.length-8);
        byte[] badAlign=good.clone();badAlign[32]=3;
        byte[] pcm24=good.clone();pcm24[34]=24;
        byte[] missingPadding=container(chunk("fmt ",format(8000,1)),rawChunk("JUNK",new byte[]{1},false));
        byte[] duplicateFmt=container(chunk("fmt ",format(8000,1)),chunk("fmt ",format(8000,1)),chunk("data",new byte[16000]));
        byte[] duplicateData=container(chunk("fmt ",format(8000,1)),chunk("data",new byte[16000]),chunk("data",new byte[2]));
        for(byte[] invalid:new byte[][]{wrongRiff,truncated,badAlign,pcm24,missingPadding,duplicateFmt,duplicateData}){
            File f=file(invalid);assertThrows(IllegalArgumentException.class,()->PcmWaveFile.inspect(f));
            Owned selected=own(Uri.fromFile(f),1000);
            assertThrows(IllegalArgumentException.class,()->analyze(selected,0,1000,4));
        }
    }

    @Test public void genuineSilenceStaysZeroAndWindowPastActualPcmFailsDespiteStaleMetadata()throws Exception {
        Owned selected=own(Uri.fromFile(file(wav(8000,1,new short[8000],false,false))),3000);
        assertArrayEquals(new float[4],analyze(selected,0,1000,4),0);
        assertThrows(IllegalArgumentException.class,()->analyze(selected,1100,500,4));
        assertThrows(IllegalArgumentException.class,()->analyze(selected,900,500,4));
        assertThrows(IllegalArgumentException.class,()->analyze(selected,Long.MAX_VALUE,100,4));
    }

    @Test public void mainThreadAndForgedIdentityStillRejectBeforeOpeningSelectedContent()throws Exception {
        SelectedProvider provider=new SelectedProvider(file(wav(8000,1,new short[8000],false,false)),0,16044);
        registerSelected(provider);
        Owned selected=own(Uri.parse("content://selected-pcm/chosen"),1000);
        assertThrows(IllegalStateException.class,()->StudioAudioWaveforms.analyzeOwnedDialogue(context,selected.project,selected.asset,0,1000,4));
        selected.asset.uri="content://selected-pcm/foreign";
        assertThrows(IllegalArgumentException.class,()->analyze(selected,0,1000,4));
        assertEquals(0,provider.opened);
    }

    @Test public void validNonPcmWaveIsAnExplicitCodecFallbackButStrictPcmInspectionStillRejectsIt()throws Exception {
        byte[] floating=wav(8000,1,new short[8000],false,false);
        floating[20]=3;floating[34]=32;floating[32]=4;put32(floating,28,32000);
        File f=file(floating);
        assertThrows(IllegalArgumentException.class,()->PcmWaveFile.inspect(f));
        try(FileInputStream stream=new FileInputStream(f);FileChannel channel=stream.getChannel()){
            assertNull("Valid unsupported WAV must reach the existing codec, not a silent PCM result",
                    PcmWaveFile.class.getDeclaredMethod("readPcm16",FileChannel.class,long.class,long.class)
                            .invoke(null,channel,0L,channel.size()));
        }
    }

    @Test public void englishAndHindiProductSpeechDecodeThroughOwnedNativePathFromPortableResources()throws Exception {
        for(String language:new String[]{"english","hindi"}){
            File f=new File(context.getCacheDir(),language+"-"+UUID.randomUUID()+".wav");
            try(InputStream resource=getClass().getResourceAsStream("/speech/"+language+".wav")){
                assertNotNull("Checked-in original product speech fixture",resource);
                Files.copy(resource,f.toPath());
            }
            assertEquals(6000,PcmWaveFile.inspect(f).getLong("durationMs"));
            float[] levels=analyze(own(Uri.fromFile(f),6000),0,6000,180);
            assertEquals(180,levels.length);int active=0;
            for(int i=0;i<180;i++){
                assertTrue(Float.isFinite(levels[i]));
                if(i<30||i>=101)assertEquals("Known silence at frame "+i,0,levels[i],0);
                if(levels[i]>.01f)active++;
            }
            assertTrue("Actual PCM speech energy must be decoded",active>20);
            assertTrue("Speech is bounded inside the authored slot",active<75);
        }
    }

    private void registerSelected(SelectedProvider provider){
        ProviderInfo info=new ProviderInfo();info.authority="selected-pcm";
        info.name=SelectedProvider.class.getName();info.packageName=context.getPackageName();
        info.applicationInfo=context.getApplicationInfo();info.exported=false;info.grantUriPermissions=true;
        provider.attachInfo(context,info);
        ShadowContentResolver.registerProviderInternal(info.authority,provider);
    }
    private Owned own(Uri uri,long duration)throws Exception {
        ProjectStore store=new ProjectStore(context);ProjectStore.Project p=store.create("PCM "+UUID.randomUUID());
        ProjectStore.Asset a=store.registerImportedAsset(p.id,uri,"Explicit selected PCM","audio/wav",duration,1);
        return new Owned(store.get(p.id),a);
    }
    private static final class Owned {
        final ProjectStore.Project project;final ProjectStore.Asset asset;
        Owned(ProjectStore.Project p,ProjectStore.Asset a){project=p;asset=a;}
    }
    private float[] analyze(Owned selected,long source,long duration,int frames)throws Exception {
        ExecutorService io=Executors.newSingleThreadExecutor();
        try{return io.submit(()->StudioAudioWaveforms.analyzeOwnedDialogue(context,selected.project,selected.asset,source,duration,frames)).get(15,TimeUnit.SECONDS);}
        catch(ExecutionException e){if(e.getCause() instanceof Exception)throw (Exception)e.getCause();throw new AssertionError(e.getCause());}
        finally{io.shutdownNow();}
    }
    private File file(byte[] bytes)throws Exception {
        File f=File.createTempFile("pcm-wave-",".wav",context.getCacheDir());Files.write(f.toPath(),bytes);return f;
    }
    private static byte[] format(int rate,int channels){
        return ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putShort((short)1).putShort((short)channels)
                .putInt(rate).putInt(rate*channels*2).putShort((short)(channels*2)).putShort((short)16).array();
    }
    private static byte[] wav(int rate,int channels,short[] samples,boolean dataFirst,boolean junk)throws Exception {
        ByteBuffer pcm=ByteBuffer.allocate(samples.length*2).order(ByteOrder.LITTLE_ENDIAN);for(short s:samples)pcm.putShort(s);
        byte[] fmt=chunk("fmt ",format(rate,channels)),data=chunk("data",pcm.array());
        return container(dataFirst?data:fmt,junk?chunk("JUNK",new byte[]{1,2,3}):new byte[0],dataFirst?fmt:data);
    }
    private static byte[] chunk(String tag,byte[] payload)throws Exception {return rawChunk(tag,payload,true);}
    private static byte[] rawChunk(String tag,byte[] payload,boolean padding)throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(tag.getBytes(StandardCharsets.US_ASCII));
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(payload.length).array());out.write(payload);
        if(padding&&(payload.length&1)!=0)out.write(0);return out.toByteArray();
    }
    private static byte[] container(byte[]... chunks)throws Exception {
        ByteArrayOutputStream body=new ByteArrayOutputStream();body.write("WAVE".getBytes(StandardCharsets.US_ASCII));for(byte[] c:chunks)body.write(c);
        return rawChunk("RIFF",body.toByteArray(),false);
    }
    private static void put32(byte[] bytes,int offset,int value){ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(offset,value);}

    public static final class SelectedProvider extends ContentProvider {
        final File file;final long offset,length;int opened,closed,queries;
        SelectedProvider(File f,long start,long count){file=f;offset=start;length=count;}
        @Override public boolean onCreate(){return true;}
        @Override public AssetFileDescriptor openAssetFile(Uri uri,String mode)throws FileNotFoundException {
            if(!"/chosen".equals(uri.getPath()))throw new FileNotFoundException("Not the chosen asset");opened++;
            ParcelFileDescriptor descriptor=new ParcelFileDescriptor(ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY)){
                private boolean countedClosed;
                @Override public synchronized void close()throws IOException{
                    super.close();
                    // FileChannel shutdown can re-enter AutoCloseInputStream.close.
                    // Count released descriptors, not idempotent close invocations.
                    if(!countedClosed){countedClosed=true;closed++;}
                }
            };
            return new AssetFileDescriptor(descriptor,offset,length);
        }
        @Override public String getType(Uri uri){return "audio/wav";}
        @Override public Cursor query(Uri uri,String[] p,String s,String[] args,String order){queries++;throw new AssertionError("No Gallery queries");}
        @Override public Uri insert(Uri uri,ContentValues v){throw new UnsupportedOperationException();}
        @Override public int delete(Uri uri,String s,String[] args){throw new UnsupportedOperationException();}
        @Override public int update(Uri uri,ContentValues v,String s,String[] args){throw new UnsupportedOperationException();}
    }
}
