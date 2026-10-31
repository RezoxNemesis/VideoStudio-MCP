import type { CapacitorConfig } from "@capacitor/cli";

const config: CapacitorConfig = {
  appId: "com.rezoxnemesis.studio",
  appName: "VideoStudio",
  webDir: "dist",
  backgroundColor: "#0B0D12",
  android: {
    allowMixedContent: false,
    webContentsDebuggingEnabled: false,
  },
  plugins: {
    // The page reads env(safe-area-inset-*), so let it draw edge to edge.
    SystemBars: { insetsHandling: "css", initialViewportFitValueHint: "cover", style: "DEFAULT" },
  },
};

export default config;
