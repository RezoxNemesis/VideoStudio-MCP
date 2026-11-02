// onnxruntime-web ships types.d.ts but its exports map hides it from "bundler" resolution.
declare module "onnxruntime-web" {
  export * from "onnxruntime-common";
}
