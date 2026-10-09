package com.rezoxnemesis.videostudio;

import org.json.JSONObject;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;
import androidx.media3.transformer.ExportException;

final class RenderRetryController {
    enum Route {DEFAULT,CONSERVATIVE,SOFTWARE_DECODER,SOFTWARE_CODECS}
    interface Attempt {void cancel();}
    interface Callback {void progress(int percent,String detail);void encoded(JSONObject result);void failed(Failure error);}
    interface Driver {Attempt start(Route route,Callback callback)throws Exception;}
    interface Verifier {JSONObject verify()throws Exception;}
    interface Observer {void progress(int percent,String detail);void completed(JSONObject result);void failed(String detail);}
    static final class Failure extends Exception {final int code;Failure(int code,String detail){super(detail);this.code=code;}}
    private enum Phase {IDLE,RUNNING,VERIFYING,TERMINAL}
    private final Executor main,verification;
    private final Driver driver;
    private final Verifier verifier;
    private final Observer observer;
    private final AtomicBoolean cancelled=new AtomicBoolean(),started=new AtomicBoolean();
    private volatile FutureTask<Void> verificationTask;
    private Phase phase=Phase.IDLE;
    private int generation;
    private Attempt active;

    /** main must serialize posted work; driver callbacks may arrive from any thread. */
    RenderRetryController(Executor main,Executor verification,Driver driver,Verifier verifier,Observer observer){
        this.main=main;this.verification=verification;this.driver=driver;this.verifier=verifier;this.observer=observer;
    }
    void start(Route route){
        if(!started.compareAndSet(false,true))throw new IllegalStateException("Render already started");
        main.execute(()->begin(route));
    }
    void cancel(){
        cancelled.set(true);
        FutureTask<Void> task=verificationTask;if(task!=null)task.cancel(true);
        main.execute(()->{phase=Phase.TERMINAL;release();});
    }
    private void begin(Route route){
        if(cancelled.get()||phase==Phase.TERMINAL)return;
        int token=++generation;phase=Phase.RUNNING;
        notifyProgress(0,"Codec route: "+route.name().toLowerCase(java.util.Locale.ROOT));
        if(cancelled.get()||phase==Phase.TERMINAL)return;
        try{
            active=driver.start(route,new Callback(){
                public void progress(int percent,String detail){main.execute(()->{if(current(token,Phase.RUNNING))notifyProgress(Math.max(0,Math.min(99,percent)),detail);});}
                public void encoded(JSONObject result){main.execute(()->verify(token,result));}
                public void failed(Failure error){main.execute(()->attemptFailed(token,route,error));}
            });
        }catch(Exception error){attemptFailed(token,route,error instanceof Failure?(Failure)error:new Failure(ExportException.ERROR_CODE_UNSPECIFIED,message(error)));}
    }
    private boolean current(int token,Phase expected){return !cancelled.get()&&generation==token&&phase==expected;}
    private void attemptFailed(int token,Route route,Failure error){
        if(!current(token,Phase.RUNNING))return;
        release();
        if(codecFailure(error.code)&&route.ordinal()<Route.values().length-1){
            phase=Phase.IDLE;
            main.execute(()->begin(Route.values()[route.ordinal()+1]));
        }else fail(error.getMessage());
    }
    private void verify(int token,JSONObject encoded){
        if(!current(token,Phase.RUNNING))return;
        phase=Phase.VERIFYING;release();
        notifyProgress(99,"Verifying encoded media");
        if(cancelled.get()||phase==Phase.TERMINAL)return;
        final JSONObject metadata;
        try{metadata=encoded==null?new JSONObject():new JSONObject(encoded.toString());}
        catch(Exception invalid){fail(message(invalid));return;}
        FutureTask<Void> task=new FutureTask<>(()->{
            if(cancelled.get())return null;
            try{
                JSONObject proof=verifier.verify();
                if(!proof.optBoolean("playable")||!proof.optString("sha256").matches("[0-9a-f]{64}"))throw new IllegalStateException("Output verification proof is incomplete");
                java.util.Iterator<String> keys=proof.keys();while(keys.hasNext()){String key=keys.next();metadata.put(key,proof.get(key));}
                metadata.put("verification",new JSONObject(proof.toString()));
                main.execute(()->{if(current(token,Phase.VERIFYING)){phase=Phase.TERMINAL;try{observer.completed(metadata);}catch(java.util.concurrent.CancellationException interrupted){cancel();}catch(RuntimeException error){notifyFailure(message(error));}}});
            }catch(Exception error){main.execute(()->{if(current(token,Phase.VERIFYING))fail(message(error));});}
            return null;
        });
        verificationTask=task;
        if(cancelled.get()){task.cancel(true);return;}
        try{verification.execute(task);}catch(RuntimeException unavailable){fail(message(unavailable));}
    }
    private void release(){Attempt old=active;active=null;if(old!=null)try{old.cancel();}catch(RuntimeException ignored){}}
    private void notifyProgress(int percent,String detail){try{observer.progress(percent,detail);}catch(java.util.concurrent.CancellationException interrupted){cancel();}catch(RuntimeException error){fail(message(error));}}
    private void notifyFailure(String detail){try{observer.failed(detail);}catch(java.util.concurrent.CancellationException interrupted){cancel();}catch(RuntimeException unavailable){android.util.Log.e("VideoStudioRender","Render error listener failed",unavailable);}}
    private void fail(String detail){if(cancelled.get()||phase==Phase.TERMINAL)return;phase=Phase.TERMINAL;release();notifyFailure(detail);}
    static boolean codecFailure(int code){return code==ExportException.ERROR_CODE_DECODER_INIT_FAILED||code==ExportException.ERROR_CODE_DECODING_FAILED||code==ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED||code==ExportException.ERROR_CODE_ENCODER_INIT_FAILED||code==ExportException.ERROR_CODE_ENCODING_FAILED||code==ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED;}
    private static String message(Throwable error){return error.getMessage()==null?error.getClass().getSimpleName():error.getMessage();}
}
