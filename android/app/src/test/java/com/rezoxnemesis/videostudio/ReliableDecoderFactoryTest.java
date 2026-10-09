package com.rezoxnemesis.videostudio;

import android.content.Context;
import androidx.media3.common.Format;
import androidx.media3.transformer.Codec;
import androidx.media3.transformer.ExportException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class ReliableDecoderFactoryTest {
    @Test public void failedInitializationRecordsActualInputBeforeAnyCodecStarts()throws Exception{
        Context context=RuntimeEnvironment.getApplication();context.deleteDatabase("videostudio_codecs.db");
        Format input=new Format.Builder().setSampleMimeType("video/avc").setCodecs("avc1.640028").setWidth(3840).setHeight(2160).setFrameRate(24).build();
        ExportException error=ExportException.createForCodec(new IllegalStateException("Cannot initialize"),ExportException.ERROR_CODE_DECODER_INIT_FAILED,new ExportException.CodecInfo("{width=3840,height=2160}",true,true,"c2.failed.avc"));
        ReliableDecoderFactory factory=new ReliableDecoderFactory(context,RenderRetryController.Route.CONSERVATIVE,30,(format,selector,listener)->new Codec.DecoderFactory(){
            public Codec createForAudioDecoding(Format format,android.media.metrics.LogSessionId session)throws ExportException{throw error;}
            public Codec createForVideoDecoding(Format format,android.view.Surface surface,boolean toneMap,android.media.metrics.LogSessionId session)throws ExportException{throw error;}
        });
        try{factory.createForVideoDecoding(input,null,false,android.media.metrics.LogSessionId.LOG_SESSION_ID_NONE);fail("Must preserve codec failure");}catch(ExportException actual){assertSame(error,actual);}
        factory.record(error); // same error surfaced by Transformer must not be counted twice
        try(CodecReliabilityStore history=new CodecReliabilityStore(context)){assertEquals(1,history.penalty("c2.failed.avc",3840,2160,"source:avc1.640028",24));assertEquals(0,history.penalty("c2.failed.avc",1280,720,"baseline",30));}
    }
}
