package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.os.Build;
import org.json.JSONObject;

/**
 * Truthful per-device model admission, not a claim that an NPU compiler exists.
 * This build executes the temporal ABI through ONNX Runtime CPU only.
 * Qualcomm Hexagon/QNN packages cannot be run by this backend or on MediaTek.
 */
public final class NativeOfflineVideoHardware {
    private NativeOfflineVideoHardware(){}

    static String processorFamily(String manufacturer,String hardware,String board){
        String check=((manufacturer==null?"":manufacturer)+" "+(hardware==null?"":hardware)
                +" "+(board==null?"":board)).toLowerCase(java.util.Locale.ROOT);
        if(check.contains("mediatek")||check.contains("dimensity")
                ||check.matches(".*\\bmt[0-9]{4,}.*"))return "mediatek";
        if(check.contains("qualcomm")||check.contains("qcom")||check.contains("snapdragon")
                ||check.contains("kalama")||check.contains("taro")||check.contains("lahaina"))
            return "qualcomm";
        if(check.contains("exynos")||check.contains("samsung"))return "samsung";
        if(check.contains("tensor")||check.contains("google"))return "google";
        return "unknown";
    }

    public static JSONObject inspect(Context context)throws Exception {
        if(context==null)throw new IllegalArgumentException("A local Android device is required");
        String vendor="";
        if(Build.VERSION.SDK_INT>=31)vendor=Build.SOC_MANUFACTURER;
        String family=processorFamily(vendor,Build.HARDWARE,Build.BOARD);
        JSONObject profile=new DeviceComputeProfile(context).snapshot();
        long activeMb=profile.optLong("activeWorkingSetBudgetMb",0);
        long residentMb=profile.optLong("residentModelBudgetMb",0);
        return new JSONObject()
                .put("processorFamily",family)
                .put("cpuExecutionSupported",android.os.Process.is64Bit())
                .put("runningTemporalBackend","onnxruntime-cpu")
                .put("qnnHexagonAvailableThroughThisApp",false)
                .put("nnapiTemporalModelVerified",false)
                .put("trainedTemporalWeightsBundled",false)
                .put("activeWorkingSetBudgetMb",activeMb)
                .put("residentModelBudgetMb",residentMb)
                .put("availableRamMb",profile.optLong("availableRamMb"))
                .put("thermalSafe",profile.optBoolean("thermalSafeForHeavyWork",false))
                .put("storageFreeMb",profile.optLong("storageFreeMb"))
                .put("recommendedFrameWindow",profile.optInt("recommendedFrameWindow",1))
                .put("unsupportedAcceleration","MediaTek and Qualcomm NPUs require distinct tested providers")
                .put("realDeviceNeuralVideoVerified",false);
    }

    /** This is a conservative CPU-only contract until another backend really exists. */
    public static void requireCompatible(JSONObject manifest,JSONObject profile)throws Exception {
        if(manifest==null||profile==null)throw new IllegalArgumentException("Model and compute profile required");
        String backend=manifest.optString("backend","onnxruntime-cpu");
        if(!"onnxruntime-cpu".equals(backend))
            throw new IllegalArgumentException("MODEL_BACKEND_UNSUPPORTED: this build cannot use QNN/Hexagon, NNAPI or hosted GPU video runtimes");
        long resident=manifest.optLong("requiredResidentMb",0);
        long working=manifest.optLong("requiredWorkingSetMb",0);
        long residentBudget=profile.optLong("residentModelBudgetMb",0);
        long workingBudget=profile.optLong("activeWorkingSetBudgetMb",0);
        if(resident<=0||working<=0)
            throw new IllegalArgumentException("Temporal model must declare positive native resident and peak working-set memory");
        if(resident>residentBudget||working>workingBudget)
            throw new IllegalStateException("LOCAL_VIDEO_HARDWARE_BUDGET: model requires "+
                    resident+" MiB resident / "+working+" MiB working; this phone safely allows "+
                    residentBudget+" / "+workingBudget+" MiB");
        if(!profile.optBoolean("thermalSafe",false))
            throw new IllegalStateException("LOCAL_VIDEO_THERMAL_HOLD: device is too warm for neural video");
        if(profile.optLong("storageFreeMb",0)<512)
            throw new IllegalStateException("LOCAL_VIDEO_STORAGE_HOLD: less than 512 MiB temporary space remains");
        if(!profile.optBoolean("cpuExecutionSupported",false))
            throw new IllegalStateException("LOCAL_VIDEO_PROCESS_UNSUPPORTED: 64-bit Android process needed");
    }
}
