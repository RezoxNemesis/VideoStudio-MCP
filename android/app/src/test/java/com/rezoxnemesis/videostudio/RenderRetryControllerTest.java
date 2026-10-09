package com.rezoxnemesis.videostudio;

import androidx.media3.transformer.ExportException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import static org.junit.Assert.*;

/** Codec initialization is a platform boundary; the real retry lifecycle is exercised. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class RenderRetryControllerTest {
    static final class Queue implements Executor {
        final ArrayDeque<Runnable> tasks=new ArrayDeque<>();
        public void execute(Runnable task){tasks.add(task);}
        void drain(){while(!tasks.isEmpty())tasks.remove().run();}
    }
    static final class Fixture implements RenderRetryController.Observer {
        final Queue main=new Queue(),verify=new Queue();
        final List<RenderRetryController.Route> routes=new ArrayList<>();
        final List<RenderRetryController.Callback> callbacks=new ArrayList<>();
        int released,completed,failed,progress,verified; String error=""; boolean badProof,cancelProgress,brokenProgress,brokenCompletion;
        final RenderRetryController controller=new RenderRetryController(main,verify,(route,callback)->{
            routes.add(route);callbacks.add(callback);return ()->released++;
        },()->{verified++;if(badProof)throw new IllegalStateException("Truncated output");return new JSONObject().put("playable",true).put("sha256","a".repeat(64));},this);
        public void progress(int percent,String detail){if(cancelProgress)throw new java.util.concurrent.CancellationException("Owner scope changed");if(brokenProgress)throw new IllegalStateException("Checkpoint journal failed");progress++;}
        public void completed(JSONObject result){if(brokenCompletion)throw new IllegalStateException("Terminal journal failed");completed++;assertTrue(result.optBoolean("playable"));}
        public void failed(String detail){failed++;error=detail;}
        void start(){controller.start(RenderRetryController.Route.DEFAULT);main.drain();}
        void fail(int code){callbacks.get(callbacks.size()-1).failed(new RenderRetryController.Failure(code,"Codec failed"));main.drain();}
        void encode(){callbacks.get(callbacks.size()-1).encoded(new JSONObject());main.drain();}
    }
    @Test public void codecFailureMovesThroughConservativeAndSoftwareBeforeVerifiedSuccess() {
        Fixture f=new Fixture();f.start();f.fail(ExportException.ERROR_CODE_ENCODER_INIT_FAILED);f.fail(ExportException.ERROR_CODE_DECODING_FAILED);
        assertEquals(List.of(RenderRetryController.Route.DEFAULT,RenderRetryController.Route.CONSERVATIVE,RenderRetryController.Route.SOFTWARE_DECODER),f.routes);
        f.encode();assertEquals(0,f.completed);f.verify.drain();f.main.drain();assertEquals(1,f.completed);assertEquals(1,f.verified);assertEquals(3,f.released);
    }
    @Test public void exhaustedCodecsFailOnceAtFourAttempts(){Fixture f=new Fixture();f.start();for(int i=0;i<4;i++)f.fail(ExportException.ERROR_CODE_ENCODING_FAILED);assertEquals(4,f.routes.size());assertEquals(1,f.failed);assertEquals(0,f.completed);assertEquals(4,f.released);}
    @Test public void sourcePermissionFailureDoesNotRetry(){Fixture f=new Fixture();f.start();f.fail(ExportException.ERROR_CODE_IO_NO_PERMISSION);assertEquals(1,f.routes.size());assertEquals(1,f.failed);}
    @Test public void muxerAndShaderFailuresDoNotRetry(){for(int code:new int[]{ExportException.ERROR_CODE_MUXING_FAILED,ExportException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED}){Fixture f=new Fixture();f.start();f.fail(code);assertEquals(1,f.routes.size());assertEquals(1,f.failed);}}
    @Test public void corruptEncodedOutputCannotBecomeSuccessful(){Fixture f=new Fixture();f.badProof=true;f.start();f.encode();f.verify.drain();f.main.drain();assertEquals(0,f.completed);assertEquals(1,f.failed);assertTrue(f.error.contains("Truncated output"));assertEquals(1,f.routes.size());}
    @Test public void retiredAttemptCannotCompleteNewAttempt(){Fixture f=new Fixture();f.start();RenderRetryController.Callback old=f.callbacks.get(0);f.fail(ExportException.ERROR_CODE_DECODING_FAILED);old.encoded(new JSONObject());old.failed(new RenderRetryController.Failure(ExportException.ERROR_CODE_ENCODING_FAILED,"Old"));old.progress(100,"Old");f.main.drain();assertEquals(2,f.routes.size());assertEquals(0,f.verified);assertEquals(0,f.completed);assertEquals(0,f.failed);}
    @Test public void duplicateEncodedCallbackVerifiesOnlyOnce(){Fixture f=new Fixture();f.start();f.encode();f.encode();f.verify.drain();f.main.drain();assertEquals(1,f.verified);assertEquals(1,f.completed);}
    @Test public void cancelBeforePostedStartAllocatesNoCodec(){Fixture f=new Fixture();f.controller.start(RenderRetryController.Route.DEFAULT);f.controller.cancel();f.main.drain();assertEquals(0,f.routes.size());assertEquals(0,f.failed);}
    @Test public void cancellationBetweenFailureAndRetrySuppressesNextAttempt(){Fixture f=new Fixture();f.start();f.callbacks.get(0).failed(new RenderRetryController.Failure(ExportException.ERROR_CODE_DECODING_FAILED,"Failed"));f.controller.cancel();f.main.drain();assertEquals(1,f.routes.size());assertEquals(1,f.released);assertEquals(0,f.failed);}
    @Test public void cancellationDuringVerificationSuppressesQueuedSuccess(){Fixture f=new Fixture();f.start();f.encode();f.verify.drain();f.controller.cancel();f.main.drain();assertEquals(0,f.completed);assertEquals(0,f.failed);}
    @Test public void lateFailureAndProgressAfterCancellationAreIgnored(){Fixture f=new Fixture();f.start();f.controller.cancel();f.callbacks.get(0).progress(90,"Late");f.callbacks.get(0).failed(new RenderRetryController.Failure(ExportException.ERROR_CODE_ENCODING_FAILED,"Late"));f.callbacks.get(0).encoded(new JSONObject());f.main.drain();f.verify.drain();f.main.drain();assertEquals(1,f.released);assertEquals(0,f.completed);assertEquals(0,f.failed);assertEquals(0,f.verified);}
    @Test public void initialOwnerCheckpointCancellationCannotCrashOrStartCodecs(){Fixture f=new Fixture();f.cancelProgress=true;f.start();assertEquals(0,f.routes.size());assertEquals(0,f.failed);}
    @Test public void runningOwnerCheckpointCancellationRetiresCodecAndSuppressesRetry(){Fixture f=new Fixture();f.start();f.cancelProgress=true;f.callbacks.get(0).progress(40,"Rendering");f.main.drain();f.fail(ExportException.ERROR_CODE_DECODING_FAILED);assertEquals(1,f.released);assertEquals(1,f.routes.size());assertEquals(0,f.failed);}
    @Test public void durableCheckpointFailureEndsRenderWithExplicitFailure(){Fixture f=new Fixture();f.start();f.brokenProgress=true;f.callbacks.get(0).progress(40,"Rendering");f.main.drain();assertEquals(1,f.released);assertEquals(1,f.failed);assertTrue(f.error.contains("Checkpoint journal failed"));assertEquals(0,f.completed);}
    @Test public void terminalCheckpointFailureStillSignalsErrorToWaitingWorker(){Fixture f=new Fixture();f.brokenCompletion=true;f.start();f.encode();f.verify.drain();f.main.drain();assertEquals(0,f.completed);assertEquals(1,f.failed);assertTrue(f.error.contains("Terminal journal failed"));}
}
