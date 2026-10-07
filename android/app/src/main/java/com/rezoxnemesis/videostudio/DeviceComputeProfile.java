package com.rezoxnemesis.videostudio;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.os.PowerManager;
import android.os.StatFs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;

/**
 * Hardware-aware compute planner for future local generative providers.
 *
 * The goal is not to require an entire model/pipeline to fit into RAM. The
 * planner describes a bounded active working set and enables phase swapping,
 * tiling, disk-backed intermediates and small temporal windows.
 */
public final class DeviceComputeProfile {
    private final Context context;

    public DeviceComputeProfile(Context context) {
        this.context = context.getApplicationContext();
    }

    public JSONObject snapshot() {
        ActivityManager.MemoryInfo memory = memoryInfo();
        long availableMb = memory.availMem / (1024L * 1024L);
        long totalMb = memory.totalMem / (1024L * 1024L);
        int thermal = thermalStatus();
        long storageFreeMb = storageFreeMb();

        long workingBudgetMb = Math.max(256, Math.min(
                (long) (availableMb * 0.42),
                (long) (totalMb * 0.26)
        ));
        long residentModelBudgetMb = Math.max(192, (long) (workingBudgetMb * 0.68));
        int tile = tileSize(workingBudgetMb);
        int frameWindow = frameWindow(workingBudgetMb);
        int parallel = availableMb >= 5500 && thermal < PowerManager.THERMAL_STATUS_SEVERE ? 2 : 1;

        JSONObject out = new JSONObject();
        JSONArray abis = new JSONArray();
        if (Build.SUPPORTED_ABIS != null) for (String abi : Build.SUPPORTED_ABIS) abis.put(abi);
        JSONArray precision = new JSONArray();
        precision.put("int8");
        precision.put("fp16");
        precision.put("fp32");

        try {
            out.put("availableRamMb", availableMb);
            out.put("totalRamMb", totalMb);
            out.put("androidRuntimeMaxMb", Runtime.getRuntime().maxMemory() / (1024L * 1024L));
            out.put("cpuCores", Runtime.getRuntime().availableProcessors());
            out.put("abis", abis);
            out.put("thermalStatus", thermal);
            out.put("thermalSafeForHeavyWork", thermal < PowerManager.THERMAL_STATUS_SEVERE);
            out.put("storageFreeMb", storageFreeMb);
            out.put("activeWorkingSetBudgetMb", workingBudgetMb);
            out.put("residentModelBudgetMb", residentModelBudgetMb);
            out.put("recommendedTile", tile);
            out.put("recommendedFrameWindow", frameWindow);
            out.put("recommendedParallelInference", parallel);
            out.put("precisionPreference", precision);
            out.put("phaseModelSwapping", true);
            out.put("diskBackedIntermediates", true);
            out.put("tiledInference", true);
            out.put("boundedTemporalWindows", true);
            out.put("checkpointBetweenPhases", true);
            out.put("profileClass", profileClass(workingBudgetMb));
        } catch (Exception ignored) {}
        return out;
    }

    public JSONObject plan(long estimatedModelMb,
                           int width,
                           int height,
                           String quality) {
        JSONObject base = snapshot();
        long budget = base.optLong("residentModelBudgetMb", 256);
        long model = Math.max(0, estimatedModelMb);
        int pixels = Math.max(1, width) * Math.max(1, height);
        int baseTile = base.optInt("recommendedTile", 384);

        boolean modelFitsResident = model == 0 || model <= budget;
        boolean useModelSwap = !modelFitsResident;
        boolean useTiling = pixels > 1280 * 1280 || !modelFitsResident;
        int tile = useTiling ? Math.min(baseTile, width > 0 ? width : baseTile) : Math.max(width, height);
        if ("draft".equalsIgnoreCase(quality)) tile = Math.min(tile, 384);
        if (base.optInt("thermalStatus") >= PowerManager.THERMAL_STATUS_SEVERE) tile = Math.min(tile, 256);

        int overlap = Math.max(16, Math.min(96, tile / 8));
        int frameWindow = base.optInt("recommendedFrameWindow", 2);
        if (!modelFitsResident) frameWindow = Math.min(frameWindow, 2);

        JSONObject out = new JSONObject();
        try {
            out.put("estimatedModelMb", model);
            out.put("residentBudgetMb", budget);
            out.put("modelFitsResident", modelFitsResident);
            out.put("modelSwapRequired", useModelSwap);
            out.put("tiledInference", useTiling);
            out.put("tileSize", Math.max(128, tile));
            out.put("tileOverlap", overlap);
            out.put("temporalWindow", Math.max(1, frameWindow));
            out.put("parallelInference", modelFitsResident ? base.optInt("recommendedParallelInference", 1) : 1);
            out.put("writeIntermediatesToDisk", true);
            out.put("checkpointEachTileGroup", true);
            out.put("thermalPauseRequired", !base.optBoolean("thermalSafeForHeavyWork", true));
            out.put("quality", quality == null || quality.isEmpty() ? "balanced" : quality);
        } catch (Exception ignored) {}
        return out;
    }

    private ActivityManager.MemoryInfo memoryInfo() {
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager != null) manager.getMemoryInfo(info);
        return info;
    }

    private int thermalStatus() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return PowerManager.THERMAL_STATUS_NONE;
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return power == null ? PowerManager.THERMAL_STATUS_NONE : power.getCurrentThermalStatus();
    }

    private long storageFreeMb() {
        File files = context.getFilesDir();
        StatFs fs = new StatFs(files.getAbsolutePath());
        return fs.getAvailableBytes() / (1024L * 1024L);
    }

    private static int tileSize(long budgetMb) {
        if (budgetMb >= 2800) return 768;
        if (budgetMb >= 1800) return 640;
        if (budgetMb >= 1100) return 512;
        if (budgetMb >= 650) return 384;
        return 256;
    }

    private static int frameWindow(long budgetMb) {
        if (budgetMb >= 3000) return 8;
        if (budgetMb >= 1800) return 6;
        if (budgetMb >= 1000) return 4;
        return 2;
    }

    private static String profileClass(long budgetMb) {
        if (budgetMb >= 3000) return "large-local";
        if (budgetMb >= 1800) return "balanced-local";
        if (budgetMb >= 900) return "compact-local";
        return "streaming-local";
    }
}
