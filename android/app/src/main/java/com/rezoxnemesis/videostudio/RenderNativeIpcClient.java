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

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Optional zero-cloud-change fallback. VideoStudio retains its EXISTING
 * authenticated MCP v3 control plane and acts only as an Android IPC carrier.
 * Blender operations execute in com.rezox.render, NEVER in VideoStudio or
 * Cloudflare. No VideoStudio credential is copied to Render.
 */
public final class RenderNativeIpcClient {
    private static final String RENDER_PACKAGE = "com.rezox.render";
    private static final String RENDER_PREVIEW_PACKAGE = "com.rezox.render.preview";
    private static final String RENDER_SERVICE =
            "com.rezox.render.agent.VideoStudioNativeBridgeService";
    private static final Set<String> ALLOWED = new HashSet<>(Arrays.asList(
            "engine.status", "agent.diagnostics", "agent.capabilities",
            "gpu.info", "projects.list", "projects.inspect",
            "blender.scene.summary", "blender.object.add_cube",
            "blender.scene.set_frame", "blender.object.set_transform",
            "blender.object.keyframe", "blender.project.save_copy",
            "blender.object.add_primitive","blender.object.add_camera",
            "blender.object.add_light","blender.object.modifier_add",
            "blender.material.assign","blender.scene.render_settings",
            "blender.scene.render_still","blender.project.restore_scene",
            "blender.scene.create_lively_project","blender.scene.batch_assembly",
            "blender.scene.visual_audit","blender.scene.populate_lively",
            "blender.scene.render_video"));

    private RenderNativeIpcClient() {}
    /** Prefer the new signed side-by-side Render preview only when installed.
     * Normal Render package is left untouched. We query exact known packages,
     * never other installed apps or the Gallery.
     */
    static String preferredRenderPackage(Context context) {
        try {
            context.getPackageManager().getPackageInfo(RENDER_PREVIEW_PACKAGE, 0);
            return RENDER_PREVIEW_PACKAGE;
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
            return RENDER_PACKAGE;
        }
    }

    public interface Callback {
        void finished(JSONObject response);
    }

    public static boolean supports(String action) {
        return action != null && action.startsWith("render.") &&
                ALLOWED.contains(action.substring(7));
    }

    /** Explicit target name in VideoStudio logs and ChatGPT receipts. */
    public static String displayLabel(String action) {
        if(!supports(action))return "VideoStudio";
        return "Render Studio • "+action.substring(7)
            .replace('.', ' ').replace('_', ' ');
    }

    /** Binder wait must exceed Render's own bounded Blender worker timeouts. */
    static long timeoutMillis(String action) {
        if ("render.blender.scene.render_video".equals(action)) return 1_980_000L;
        if ("render.blender.scene.render_still".equals(action)) return 540_000L;
        if ("render.blender.scene.populate_lively".equals(action)
                || "render.blender.scene.create_lively_project".equals(action)
                || "render.blender.scene.batch_assembly".equals(action))
            return 150_000L;
        if ("render.blender.project.save_copy".equals(action)) return 145_000L;
        if ("render.blender.project.restore_scene".equals(action)) return 120_000L;
        return 40_000L;
    }

    private static JSONObject error(String code) {
        JSONObject out = new JSONObject();
        try {
            out.put("ok", false).put("error", code).put("target", "Render Android");
        } catch (Exception ignored) {}
        return out;
    }

    /**
     * Bind from VideoStudio foreground ControlService to Render's explicit
     * Messenger service. Completion is asynchronous: never block Android UI.
     */
    public static void dispatch(Context context, String commandId, String action,
                                JSONObject args, Callback callback) {
        if (callback == null) return;
        if (commandId == null || !commandId.matches("[A-Za-z0-9_-]{8,128}") ||
                !supports(action) || args == null || args.toString().length() > 32768) {
            callback.finished(error("INVALID_RENDER_IPC_REQUEST"));
            return;
        }
        Context app = context.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        AtomicBoolean done = new AtomicBoolean(false);
        final ServiceConnection[] connection = new ServiceConnection[1];
        final Runnable[] timeout = new Runnable[1];
        final boolean[] bound = new boolean[1];

        java.util.function.Consumer<JSONObject> finish = response -> {
            if (!done.compareAndSet(false, true)) return;
            if (timeout[0] != null) main.removeCallbacks(timeout[0]);
            if (bound[0] && connection[0] != null) {
                try { app.unbindService(connection[0]); }
                catch (Exception ignored) {}
                bound[0] = false;
            }
            callback.finished(response);
        };
        timeout[0] = () -> finish.accept(error("RENDER_NATIVE_OUTCOME_UNCERTAIN_TIMEOUT"));
        Messenger reply = new Messenger(new Handler(Looper.getMainLooper()) {
            @Override public void handleMessage(Message response) {
                if (response.what != 2) return;
                Bundle content = response.getData();
                if (content == null || !commandId.equals(content.getString("commandId")))
                    return;
                try {
                    JSONObject result = new JSONObject(content.getString("result", "{}"));
                    finish.accept(result);
                } catch (Exception e) {
                    finish.accept(error("RENDER_IPC_MALFORMED_RESULT"));
                }
            }
        });
        connection[0] = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName component, IBinder binder) {
                if (done.get()) return;
                try {
                    Message message = Message.obtain(null, 1);
                    message.replyTo = reply;
                    Bundle data = new Bundle();
                    data.putString("commandId", commandId);
                    data.putString("action", action.substring(7));
                    data.putString("args", args.toString());
                    message.setData(data);
                    new Messenger(binder).send(message);
                } catch (Exception e) {
                    finish.accept(error("RENDER_IPC_DELIVERY_FAILED"));
                }
            }
            @Override public void onServiceDisconnected(ComponentName component) {
                finish.accept(error("RENDER_NATIVE_OUTCOME_UNCERTAIN_DISCONNECT"));
            }
            @Override public void onBindingDied(ComponentName component) {
                finish.accept(error("RENDER_NATIVE_OUTCOME_UNCERTAIN_BINDING_DIED"));
            }
            @Override public void onNullBinding(ComponentName component) {
                finish.accept(error("RENDER_NATIVE_IPC_NOT_AVAILABLE"));
            }
        };
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(preferredRenderPackage(app), RENDER_SERVICE));
            // The caller is VideoStudio's already user-visible foreground
            // Native Agent service. No implicit intent or broad package scan.
            boolean ok = app.bindService(intent, connection[0], Context.BIND_AUTO_CREATE);
            bound[0] = ok;
            if (!ok) {
                finish.accept(error("RENDER_APP_NOT_INSTALLED_OR_INCOMPATIBLE"));
                return;
            }
            long waitMs = timeoutMillis(action);
            main.postDelayed(timeout[0], waitMs);
        } catch (Exception e) {
            finish.accept(error("RENDER_IPC_BIND_FAILED"));
        }
    }
}
