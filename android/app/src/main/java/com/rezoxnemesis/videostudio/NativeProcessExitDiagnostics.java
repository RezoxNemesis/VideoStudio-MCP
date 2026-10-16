package com.rezoxnemesis.videostudio;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Build;
import org.json.JSONObject;
import java.util.List;

/** Read-only, source-free, bounded own-process exit cause diagnostics.
 * Never reads traces, Gallery files, user content, or other applications.
 * Available on Android 11+; queried only on demand, never in the splash path. */
final class NativeProcessExitDiagnostics {
    private NativeProcessExitDiagnostics(){}

    static JSONObject recent(Context context){
        JSONObject out=new JSONObject();
        try{
            if(Build.VERSION.SDK_INT<30)return out.put("available",false)
                    .put("reason","Android process-exit diagnostics require API 30");
            ActivityManager am=(ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
            if(am==null)return out.put("available",false);
            List<ApplicationExitInfo> history=am.getHistoricalProcessExitReasons(
                    context.getPackageName(),0,5);
            if(history==null||history.isEmpty())return out.put("available",true).put("hasRecordedExit",false);
            ApplicationExitInfo latest=history.get(0);
            return out.put("available",true).put("hasRecordedExit",true)
                    .put("reasonCode",latest.getReason())
                    .put("reason",reasonName(latest.getReason()))
                    .put("exitAt",latest.getTimestamp())
                    .put("rssKb",Math.max(0,latest.getRss()))
                    .put("pssKb",Math.max(0,latest.getPss()))
                    .put("tracesRead",false)
                    .put("sourceMediaRead",false);
        }catch(Exception inaccessible){
            try{out.put("available",false).put("reason","Process exit history unavailable");}
            catch(Exception ignored){}
            return out;
        }
    }

    static String reasonName(int reason){
        switch(reason){
            case ApplicationExitInfo.REASON_CRASH:return "JAVA_CRASH";
            case ApplicationExitInfo.REASON_CRASH_NATIVE:return "NATIVE_CRASH";
            case ApplicationExitInfo.REASON_ANR:return "ANR";
            case ApplicationExitInfo.REASON_LOW_MEMORY:return "LOW_MEMORY";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE:return "RESOURCE_LIMIT";
            case ApplicationExitInfo.REASON_USER_REQUESTED:return "USER_REQUESTED";
            case ApplicationExitInfo.REASON_SIGNALED:return "SIGNALED";
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED:return "DEPENDENCY_DIED";
            case ApplicationExitInfo.REASON_PERMISSION_CHANGE:return "PERMISSION_CHANGE";
            default:return "OTHER_OR_UNKNOWN";
        }
    }
}
