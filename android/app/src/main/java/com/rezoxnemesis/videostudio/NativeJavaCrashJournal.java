package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Minimal *local* Java crash fingerprint. Saves no exception messages,
 * user projects, filenames, credentials or command payloads. The original
 * Android uncaught-exception handler is always invoked afterward.
 * Native signals / Android process kills are not Java exceptions.
 */
final class NativeJavaCrashJournal {
    private static final String PREFS="videostudio_editor_startup_guard_v457";
    private static boolean installed;
    private NativeJavaCrashJournal(){}

    static synchronized void install(Context context){
        if(installed||context==null)return;
        final Context app=context.getApplicationContext();
        final Thread.UncaughtExceptionHandler original=Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread,error)->{
            try{
                SharedPreferences state=app.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
                state.edit().putString("lastJavaFailure",safeClass(error))
                        .putString("lastAppFrame",safeAppFrame(error))
                        .putLong("lastJavaCrashAt",System.currentTimeMillis())
                        .commit();
            }catch(Throwable ignored){}
            if(original!=null)original.uncaughtException(thread,error);
        });
        installed=true;
    }

    static String safeClass(Throwable cause){
        if(cause==null)return "Unknown";
        String name=cause.getClass().getSimpleName();
        return name.matches("[A-Za-z0-9_$]{1,64}")?name:"Unknown";
    }
    static String safeAppFrame(Throwable cause){
        if(cause==null)return "not_available";
        for(StackTraceElement element:cause.getStackTrace()){
            String name=element.getClassName();
            if(name.startsWith("com.rezoxnemesis.videostudio.")&&
                    name.matches("[A-Za-z0-9_.$]{1,180}")){
                String method=element.getMethodName();
                if(method.matches("[A-Za-z0-9_$<>]{1,80}"))
                    return (name+"#"+method).substring(0,
                            Math.min(235,name.length()+1+method.length()));
            }
        }
        return "outside_app_or_native";
    }
}
