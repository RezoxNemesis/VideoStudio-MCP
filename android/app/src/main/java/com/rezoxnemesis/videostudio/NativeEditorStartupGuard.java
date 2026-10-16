package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;

/**
 * A crash-loop breaker for the user-facing Android launcher.
 *
 * The only persisted fields are startup lifecycle markers and the name of a
 * failing Java exception class. Never save private media, device IDs, command
 * payloads or a throwable's message. A failed launch never modifies SQLite
 * projects or the native agent's Keystore credentials.
 */
final class NativeEditorStartupGuard {
    private static final String PREFS="videostudio_editor_startup_guard_v457";
    static final long RAPID_WINDOW_MS=3L*60_000L;
    private final SharedPreferences settings;

    static final class Admission {
        final boolean rescueMode;
        final int rapidFailures;
        final String lastStage;
        Admission(boolean rescueMode,int failures,String stage){
            this.rescueMode=rescueMode;this.rapidFailures=failures;this.lastStage=stage;
        }
    }

    NativeEditorStartupGuard(Context context){
        settings=context.getApplicationContext()
                .getSharedPreferences(PREFS,Context.MODE_PRIVATE);
    }

    synchronized Admission begin(long now){
        if(now<=0)throw new IllegalArgumentException("Startup timestamp must be positive");
        boolean previousOpen=settings.getBoolean("initializationInProgress",false);
        long prior=settings.getLong("lastLaunchAt",0);
        int failures=previousOpen && prior>0 && now>=prior && now-prior<=RAPID_WINDOW_MS
                ?Math.min(20,settings.getInt("rapidFailures",0)+1):0;
        boolean bypass=settings.getBoolean("allowNormalOnce",false);
        String last=settings.getString("lastStage","not_started");
        // Persist the intent synchronously; a process death before the first
        // frame must still leave reliable evidence of an unfinished bootstrap.
        settings.edit()
                .putBoolean("initializationInProgress",true)
                .putBoolean("allowNormalOnce",false)
                .putInt("rapidFailures",failures)
                .putLong("lastLaunchAt",now)
                .putString("lastStage","creating_shell")
                .commit();
        return new Admission(failures>=2&&!bypass,failures,last);
    }

    synchronized void stage(String stage){
        if(stage==null||!stage.matches("[a-z0-9_]{1,48}"))
            throw new IllegalArgumentException("Invalid boot stage");
        settings.edit().putString("lastStage",stage).apply();
    }

    synchronized void success(){
        settings.edit().putBoolean("initializationInProgress",false)
                .putInt("rapidFailures",0)
                .putString("lastStage","ready").apply();
    }

    synchronized void failed(Throwable cause){
        String category=cause==null?"Unknown":cause.getClass().getSimpleName();
        if(category.length()>64)category=category.substring(0,64);
        settings.edit().putString("lastJavaFailure",category)
                .putBoolean("initializationInProgress",true).commit();
    }

    synchronized void allowNormalOnce(){
        settings.edit().putBoolean("allowNormalOnce",true)
                .putInt("rapidFailures",0).commit();
    }

    synchronized JSONObject status(){
        JSONObject out=new JSONObject();
        try{
            out.put("safeLaunchAvailable",true)
                    .put("rapidFailures",settings.getInt("rapidFailures",0))
                    .put("lastStage",settings.getString("lastStage",""))
                    .put("lastJavaFailure",settings.getString("lastJavaFailure",""))
                    .put("sourceProjectDeleted",false)
                    .put("localProjectDataPreserved",true)
                    .put("nativeRecoveryNeedsOwnerAction",true);
        }catch(Exception ignored){}
        return out;
    }
}
