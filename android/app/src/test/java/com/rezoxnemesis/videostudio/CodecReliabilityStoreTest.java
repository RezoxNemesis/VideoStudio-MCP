package com.rezoxnemesis.videostudio;

import android.content.Context;
import org.json.JSONArray;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33)
public class CodecReliabilityStoreTest {
    Context context;
    @Before public void clean(){context=RuntimeEnvironment.getApplication();context.deleteDatabase("videostudio_codecs.db");}
    @Test public void namedFailuresSurviveRestartAndKeepConfigurationSeparate()throws Exception{
        try(CodecReliabilityStore store=new CodecReliabilityStore(context)){store.failure("c2.vendor.avc",1280,720,"default",30,"ENCODING_FAILED");store.failure("c2.vendor.avc",1280,720,"default",30,"ENCODING_FAILED");}
        try(CodecReliabilityStore store=new CodecReliabilityStore(context)){assertEquals(2,store.penalty("c2.vendor.avc",1280,720,"default",30));assertEquals(0,store.penalty("c2.vendor.avc",1280,720,"default",60));assertEquals(0,store.penalty("c2.vendor.avc",1920,1080,"default",30));assertEquals(0,store.penalty("c2.vendor.avc",1280,720,"baseline",30));JSONArray rows=store.entries();assertEquals(1,rows.length());assertEquals(2,rows.getJSONObject(0).getInt("failures"));}
    }
    @Test public void verifiedSuccessRequiresWholeFileProof()throws Exception{
        try(CodecReliabilityStore store=new CodecReliabilityStore(context)){try{store.verified("c2.android.avc",1280,720,"baseline",30,"");fail("Missing proof");}catch(IllegalArgumentException expected){}assertEquals(0,store.entries().length());store.failure("c2.android.avc",1280,720,"baseline",30,"ENCODING_FAILED");store.verified("c2.android.avc",1280,720,"baseline",30,"a".repeat(64));assertEquals(0,store.penalty("c2.android.avc",1280,720,"baseline",30));assertEquals(2,store.entries().length());}
    }
    @Test public void unnamedFailuresCannotPenalizeArbitraryCodecs()throws Exception{try(CodecReliabilityStore store=new CodecReliabilityStore(context)){store.failure("",1280,720,"default",30,"DECODING_FAILED");assertEquals(0,store.entries().length());}}
    @Test public void reliabilityHistoryIsBounded()throws Exception{try(CodecReliabilityStore store=new CodecReliabilityStore(context)){for(int i=0;i<140;i++)store.failure("codec"+i,1280,720,"default",30,"ENCODING_FAILED");assertEquals(128,store.entries().length());}}
}
