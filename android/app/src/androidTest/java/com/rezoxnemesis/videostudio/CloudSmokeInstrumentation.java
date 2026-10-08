package com.rezoxnemesis.videostudio;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Device smoke test. An explicitly supplied fixture stays outside source control and release APKs. */
public final class CloudSmokeInstrumentation extends Instrumentation {
    private Bundle arguments;
    private File output;
    private final JSONObject report = new JSONObject();

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        arguments = args == null ? new Bundle() : args;
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Context target = getTargetContext();
            output = new File(target.getFilesDir(), "cloud-validation");
            if (!output.isDirectory() && !output.mkdirs()) throw new IllegalStateException("No validation folder");
            File source = new File(target.getFilesDir(), "validation-source.jpg");
            if (!source.isFile()) throw new IllegalArgumentException("Push the explicit source fixture into the app files first");
            ProjectStore store = new ProjectStore(target);
            ProjectStore.Project project = store.create("Cloud cartoon validation");
            long started = SystemClock.elapsedRealtime();
            ProjectStore.Asset asset = store.importUri(project, Uri.fromFile(source));
            if (store.get(project.id).assets.size() != 1 || store.get(project.id).clips.size() != 1)
                throw new AssertionError("Imported image did not enter the shared project");
            report.put("importMs", SystemClock.elapsedRealtime() - started);
            report.put("projectId", project.id);
            report.put("assetId", asset.id);
            if(arguments.containsKey("cropBounds")){
                EditorCommands.execute(store,project.id,new JSONObject().put("operation","effects")
                    .put("clipId",project.clips.get(0).id).put("effects",new JSONObject()
                    .put("cropBounds",new JSONArray(arguments.getString("cropBounds")))));
                project=store.get(project.id);
                report.put("cropBounds",new JSONArray(arguments.getString("cropBounds")));
            }
            report.put("sourceMime", asset.mime);
            report.put("version", AppProtocol.APP_VERSION);
            report.put("deviceAbi", android.os.Build.SUPPORTED_ABIS[0]);
            report.put("modelWeightsInstalled", false);

            Activity editor = startActivitySync(new Intent(target, NativeEditorActivity.class)
                    .putExtra("projectId", project.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForIdleSync();
            SystemClock.sleep(6000);
            screenshot("editor.png");

            startActivitySync(new Intent(target, AnimateImageActivity.class)
                    .putExtra("projectId", project.id).putExtra("assetId", asset.id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForIdleSync();
            SystemClock.sleep(1500);
            screenshot("animate-workspace.png");

            if (arguments.getString("export", "true").equals("true")) {
                Activity export = startActivitySync(new Intent(target, ExportActivity.class)
                        .putExtra("projectId", project.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                waitForIdleSync();
                runOnMainSync(() -> {
                    Button button = findButton(export.getWindow().getDecorView(), "Start Export / Retry");
                    if (button == null) throw new AssertionError("Manual export button missing");
                    button.performClick();
                });
                long deadline = SystemClock.elapsedRealtime() + 180000;
                String state = "not_started";
                JSONObject job = new JSONObject();
                while (SystemClock.elapsedRealtime() < deadline) {
                    String binding = target.getSharedPreferences("videostudio_native_v1", 0)
                            .getString("manual_export_job", "{}");
                    JSONObject bound = new JSONObject(binding);
                    if(!project.id.equals(bound.optString("projectId"))){SystemClock.sleep(500);continue;}
                    String jobId = bound.optString("jobId");
                    JSONArray jobs = new JSONArray(target.getSharedPreferences("videostudio_native_v1", 0)
                            .getString(ExecutionTruthPolicy.JOB_RECOVERY_PREF_KEY, "[]"));
                    for (int i = 0; i < jobs.length(); i++) {
                        JSONObject candidate = jobs.getJSONObject(i);
                        if (jobId.equals(candidate.optString("id"))) {
                            job = candidate;
                            state = job.optString("state");
                        }
                    }
                    if (JobManager.isTerminal(state)) break;
                    SystemClock.sleep(500);
                }
                report.put("exportJob", job);
                report.put("exportUri", store.get(project.id).latestExportUri);
                screenshot("export.png");
                if (!"completed".equals(state)) throw new AssertionError("Native export did not complete: " + state);
                if (store.get(project.id).latestExportUri.isEmpty()) throw new AssertionError("No published MP4");
            }
            report.put("ok", true);
            result.putString("result", "Device smoke test passed");
        } catch (Throwable error) {
            try { report.put("ok", false).put("error", error.toString()); } catch (Exception ignored) {}
            result.putString("error", error.toString());
        } finally {
            if (output != null) try (FileOutputStream stream = new FileOutputStream(new File(output, "report.json"))) {
                stream.write(report.toString(2).getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) {}
            finish(report.optBoolean("ok") ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
        }
    }

    private void screenshot(String name) throws Exception {
        Bitmap pixels = getUiAutomation().takeScreenshot();
        if (pixels == null) throw new IllegalStateException("Android did not provide a screenshot");
        try (FileOutputStream stream = new FileOutputStream(new File(output, name))) {
            if (!pixels.compress(Bitmap.CompressFormat.PNG, 100, stream)) throw new IllegalStateException("PNG failed");
        } finally { pixels.recycle(); }
    }

    private static Button findButton(View view, String text) {
        if (view instanceof Button && text.contentEquals(((Button) view).getText())) return (Button) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            Button found = findButton(((ViewGroup) view).getChildAt(i), text);
            if (found != null) return found;
        }
        return null;
    }
}
