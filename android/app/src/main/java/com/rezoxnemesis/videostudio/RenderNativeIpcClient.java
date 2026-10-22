package com.rezoxnemesis.videostudio;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Existing MCP v3 carrier only. All Blender computation stays in Render on Android. */
public final class RenderNativeIpcClient {
    private static final String RENDER_PACKAGE="com.rezox.render";
    private static final String RENDER_PREVIEW_PACKAGE="com.rezox.render.preview";
    private static final String RENDER_SERVICE="com.rezox.render.agent.VideoStudioNativeBridgeService";
    private static final Set<String> ALLOWED=new HashSet<>(Arrays.asList(
        "engine.status","agent.diagnostics","agent.capabilities","production.execute",
        "gpu.info","projects.list","projects.inspect","blender.scene.summary",
        "blender.object.add_cube","blender.scene.set_frame","blender.object.set_transform",
        "blender.object.keyframe","blender.project.save_copy","blender.object.add_primitive",
        "blender.object.add_camera","blender.object.add_light","blender.object.modifier_add",
        "blender.material.assign","blender.scene.render_settings","blender.scene.render_still",
        "blender.project.restore_scene","blender.scene.create_lively_project",
        "blender.scene.batch_assembly","blender.scene.visual_audit",
        "blender.scene.populate_lively","blender.scene.render_video"));
    private static final RenderSerialQueue QUEUE=new RenderSerialQueue(32,
        task->new Handler(Looper.getMainLooper()).post(task));
    private RenderNativeIpcClient(){}
    public interface Callback{void finished(JSONObject response);}
    public static boolean supports(String action){return action!=null&&action.startsWith("render.")&&ALLOWED.contains(action.substring(7));}
    static String preferredRenderPackage(Context ignored){return RENDER_PACKAGE;}
    static String targetPackage(JSONObject args)throws Exception{
        String target=args.optString("target_package",RENDER_PACKAGE);
        if(!target.equals(RENDER_PACKAGE)&&!target.equals(RENDER_PREVIEW_PACKAGE))throw new IllegalArgumentException("INVALID_RENDER_TARGET_PACKAGE");
        return target;
    }
    public static String displayLabel(String action){return supports(action)?"Render Studio • "+action.substring(7).replace('.',' ').replace('_',' '):"VideoStudio";}
    static long timeoutMillis(String action){
        if("render.production.execute".equals(action)||"render.blender.scene.render_video".equals(action))return 1_980_000L;
        if("render.blender.scene.render_still".equals(action))return 540_000L;
        if("render.blender.scene.populate_lively".equals(action)||"render.blender.scene.create_lively_project".equals(action)||"render.blender.scene.batch_assembly".equals(action))return 150_000L;
        if("render.blender.project.save_copy".equals(action))return 145_000L;
        if("render.blender.project.restore_scene".equals(action))return 120_000L;
        return 40_000L;
    }
    private static JSONObject error(String code){
        JSONObject out=new JSONObject();try{out.put("ok",false).put("error",code).put("target","Render Android");}catch(Exception ignored){}return out;
    }
    public static void dispatch(Context context,String commandId,String action,JSONObject args,Callback callback){
        if(callback==null)return;
        if(context==null||commandId==null||!commandId.matches("[A-Za-z0-9_-]{8,128}")||!supports(action)||args==null||args.toString().getBytes(StandardCharsets.UTF_8).length>131072){callback.finished(error("INVALID_RENDER_IPC_REQUEST"));return;}
        final JSONObject snapshot;
        try{snapshot=new JSONObject(args.toString());targetPackage(snapshot);}catch(Exception invalid){callback.finished(error("INVALID_RENDER_TARGET_PACKAGE"));return;}
        boolean accepted=QUEUE.submit(()->dispatchNow(context.getApplicationContext(),commandId,action,snapshot,result->{
            try{callback.finished(result);}finally{QUEUE.completed();}
        }));
        if(!accepted)callback.finished(error("RENDER_CARRIER_QUEUE_FULL"));
    }
    private static void dispatchNow(Context app,String commandId,String action,JSONObject args,Callback callback){
        if(app.getSharedPreferences("videostudio_native_v1",Context.MODE_PRIVATE).getBoolean("chatgpt_control_paused",false)){
            callback.finished(error("VIDEOSTUDIO_CONTROL_PAUSED"));return;
        }
        final String target;final JSONObject payload;
        try{target=targetPackage(args);payload=new JSONObject(args.toString());payload.remove("target_package");}
        catch(Exception e){callback.finished(error("INVALID_RENDER_TARGET_PACKAGE"));return;}
        Handler main=new Handler(Looper.getMainLooper());AtomicBoolean done=new AtomicBoolean(false);
        final ServiceConnection[] connection=new ServiceConnection[1];final Runnable[] timeout=new Runnable[1];final boolean[] bound=new boolean[1];
        java.util.function.Consumer<JSONObject> finish=response->{
            if(!done.compareAndSet(false,true))return;
            if(timeout[0]!=null)main.removeCallbacks(timeout[0]);
            if(bound[0]&&connection[0]!=null){try{app.unbindService(connection[0]);}catch(Exception ignored){}bound[0]=false;}
            callback.finished(response);
        };
        timeout[0]=()->finish.accept(error("RENDER_NATIVE_OUTCOME_UNCERTAIN_TIMEOUT"));
        Messenger reply=new Messenger(new Handler(Looper.getMainLooper()){
            @Override public void handleMessage(Message response){
                if(response.what!=2)return;
                Bundle content=response.getData();if(content==null||!commandId.equals(content.getString("commandId")))return;
                try{
                    String[] callers=app.getPackageManager().getPackagesForUid(response.sendingUid);
                    if(callers==null||!Arrays.asList(callers).contains(target)){finish.accept(error("RENDER_REPLY_SENDER_MISMATCH"));return;}
                    String raw=content.getString("result","{}");if(raw.getBytes(StandardCharsets.UTF_8).length>131072){finish.accept(error("RENDER_RESULT_TOO_LARGE"));return;}
                    JSONObject result=new JSONObject(raw);
                    if(result.optBoolean("ok",false)){
                        if(!target.equals(result.optString("target_package"))||!"Render Studio".equals(result.optString("target_app"))){finish.accept(error("RENDER_RESULT_TARGET_MISMATCH"));return;}
                        String expected=payload.optString("target_id","");
                        if(!expected.isEmpty()&&!expected.equals(result.optString("render_public_id"))){finish.accept(error("RENDER_RESULT_IDENTITY_MISMATCH"));return;}
                    }
                    finish.accept(result);
                }catch(Exception malformed){finish.accept(error("RENDER_IPC_MALFORMED_RESULT"));}
            }
        });
        connection[0]=new ServiceConnection(){
            @Override public void onServiceConnected(ComponentName component,IBinder binder){
                if(done.get())return;
                if(!target.equals(component.getPackageName())){finish.accept(error("RENDER_BINDER_TARGET_MISMATCH"));return;}
                if(app.getSharedPreferences("videostudio_native_v1",Context.MODE_PRIVATE).getBoolean("chatgpt_control_paused",false)){finish.accept(error("VIDEOSTUDIO_CONTROL_PAUSED"));return;}
                try{
                    Message message=Message.obtain(null,1);message.replyTo=reply;Bundle data=new Bundle();
                    data.putString("commandId",commandId);data.putString("action",action.substring(7));data.putString("args",payload.toString());message.setData(data);new Messenger(binder).send(message);
                }catch(Exception e){finish.accept(error("RENDER_IPC_DELIVERY_FAILED"));}
            }
            @Override public void onServiceDisconnected(ComponentName component){finish.accept(error("RENDER_NATIVE_OUTCOME_UNCERTAIN_DISCONNECT"));}
            @Override public void onBindingDied(ComponentName component){finish.accept(error("RENDER_NATIVE_OUTCOME_UNCERTAIN_BINDING_DIED"));}
            @Override public void onNullBinding(ComponentName component){finish.accept(error("RENDER_NATIVE_IPC_NOT_AVAILABLE"));}
        };
        try{
            Intent intent=new Intent().setComponent(new ComponentName(target,RENDER_SERVICE));
            boolean ok=app.bindService(intent,connection[0],Context.BIND_AUTO_CREATE);bound[0]=ok;
            if(!ok){finish.accept(error("RENDER_APP_NOT_INSTALLED_OR_INCOMPATIBLE"));return;}
            main.postDelayed(timeout[0],timeoutMillis(action));
        }catch(Exception e){finish.accept(error("RENDER_IPC_BIND_FAILED"));}
    }
}
