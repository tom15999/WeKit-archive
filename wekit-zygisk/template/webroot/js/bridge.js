import { exec, getPackagesInfo, listPackages, toast } from "./kernelsu.js";

export function hasKernelSuBridge() {
  return typeof window.ksu?.exec === "function";
}

export { exec, getPackagesInfo, listPackages, toast };

export function shellQuote(value) {
  return `'${String(value).replace(/'/g, "'\\\"'\\\"'")}'`;
}
