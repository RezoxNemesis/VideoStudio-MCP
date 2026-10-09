package com.rezoxnemesis.videostudio;

import androidx.media3.common.Format;
import androidx.media3.common.Metadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.container.Mp4OrientationData;
import androidx.media3.muxer.BufferInfo;
import androidx.media3.muxer.Mp4Muxer;
import androidx.media3.muxer.Muxer;
import androidx.media3.muxer.MuxerException;
import com.google.common.collect.ImmutableList;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Fresh held-FD output; full encoder data remains intact beneath gapless presentation edits. */
final class GaplessAudioMuxer implements Muxer {
    static final class Factory implements Muxer.Factory {
        final ProjectStore.Project originals;final AtomicReference<AacDrainCodec.State> state;final BooleanSupplier cancelled;final AtomicReference<org.json.JSONObject> finalizedBytes;
        Factory(ProjectStore.Project originals,AtomicReference<AacDrainCodec.State> state,BooleanSupplier cancelled){this(originals,state,cancelled,new AtomicReference<>());}
        Factory(ProjectStore.Project originals,AtomicReference<AacDrainCodec.State> state,BooleanSupplier cancelled,AtomicReference<org.json.JSONObject> finalizedBytes){this.originals=originals;this.state=state;this.cancelled=cancelled;this.finalizedBytes=finalizedBytes;}
        public Muxer create(String path)throws MuxerException{
            SegmentMediaMuxer.OutputWorkspace workspace=null;FileOutputStream stream=null;
            try{workspace=SegmentMediaMuxer.prepareOutput(originals,new File(path));stream=new FileOutputStream(android.system.Os.dup(workspace.descriptor));Mp4Muxer muxer=new Mp4Muxer.Builder(stream).setAttemptStreamableOutputEnabled(true).build();return new GaplessAudioMuxer(muxer,stream,workspace,state,cancelled,finalizedBytes);}
            catch(Exception failure){if(stream!=null)try{stream.close();}catch(Exception cleanup){failure.addSuppressed(cleanup);}if(workspace!=null){try{workspace.discard();}catch(Exception cleanup){failure.addSuppressed(cleanup);}try{workspace.close();}catch(Exception cleanup){failure.addSuppressed(cleanup);}}throw new MuxerException("Could not create guarded MP4 workspace",failure);}
        }
        public ImmutableList<String> getSupportedSampleMimeTypes(int type){return type==androidx.media3.common.C.TRACK_TYPE_AUDIO?Mp4Muxer.SUPPORTED_AUDIO_SAMPLE_MIME_TYPES:Mp4Muxer.SUPPORTED_VIDEO_SAMPLE_MIME_TYPES;}
        public boolean supportsWritingNegativeTimestampsInEditList(){return true;}
    }
    final Mp4Muxer delegate;final FileOutputStream stream;final SegmentMediaMuxer.OutputWorkspace workspace;final AtomicReference<AacDrainCodec.State> state;final BooleanSupplier cancelled;final AtomicReference<org.json.JSONObject> finalizedBytes;boolean closed;
    GaplessAudioMuxer(Mp4Muxer delegate,FileOutputStream stream,SegmentMediaMuxer.OutputWorkspace workspace,AtomicReference<AacDrainCodec.State> state,BooleanSupplier cancelled,AtomicReference<org.json.JSONObject> finalizedBytes){this.delegate=delegate;this.stream=stream;this.workspace=workspace;this.state=state;this.cancelled=cancelled;this.finalizedBytes=finalizedBytes;}
    public int addTrack(Format format)throws MuxerException{int id=delegate.addTrack(format);if(MimeTypes.isVideo(format.sampleMimeType))delegate.addMetadataEntry(new Mp4OrientationData(format.rotationDegrees));return id;}
    public void writeSampleData(int id,ByteBuffer bytes,BufferInfo info)throws MuxerException{delegate.writeSampleData(id,bytes,info);}
    public void addMetadataEntry(Metadata.Entry entry){delegate.addMetadataEntry(entry);}
    public void close()throws MuxerException{
        if(closed)return;closed=true;Exception failure=null;
        try{delegate.close();workspace.ensureCurrent();AacDrainCodec.State audio=state.get();if(audio!=null){if(!audio.eosForwarded||audio.appendedFrames!=AacDrainCodec.DRAIN_FRAMES)throw new IllegalStateException("AAC encoder drain did not complete");audio.trim=GaplessAudioMp4.trim(workspace.descriptor,audio.programmeUs(),maxPaddingUs(audio.sampleRate),cancelled);}workspace.ensureCurrent();android.system.Os.fsync(workspace.descriptor);org.json.JSONObject proof=PlayableMediaVerifier.descriptorBytes(workspace.descriptor,cancelled);workspace.ensureCurrent();finalizedBytes.set(proof);}
        catch(Exception error){failure=error;try{workspace.discard();}catch(Exception cleanup){error.addSuppressed(cleanup);}}
        finally{try{stream.close();}catch(Exception cleanup){if(failure==null)failure=cleanup;else failure.addSuppressed(cleanup);}try{workspace.close();}catch(Exception cleanup){if(failure==null)failure=cleanup;else failure.addSuppressed(cleanup);}}
        if(failure!=null)throw new MuxerException("Could not finalize complete gapless AAC programme",failure);
    }
    static long maxPaddingUs(int rate){if(rate<=0)throw new IllegalArgumentException("Invalid AAC sample rate");return Math.addExact(Math.multiplyExact((long)AacDrainCodec.DRAIN_FRAMES+1024,1_000_000L)/rate,1000);}
    static org.json.JSONObject bindVerifiedBytes(org.json.JSONObject finalized,org.json.JSONObject verified)throws Exception{
        if(finalized==null||verified==null||!finalized.optString("sha256").matches("[0-9a-f]{64}")||!finalized.optString("sha256").equals(verified.optString("sha256"))||finalized.optLong("sizeBytes",-1)<=0||finalized.optLong("sizeBytes",-1)!=verified.optLong("sizeBytes",-2))throw new java.io.IOException("Rendered output changed after writer finalization");return verified;
    }
}
