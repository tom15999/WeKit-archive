// KernelSU WebUI API 3.x, vendored from the official `kernelsu` package.
// Keeping this small local module makes the installed module self-contained.
let callbackCounter = 0;

function callbackName(prefix) {
  return `${prefix}_callback_${Date.now()}_${callbackCounter++}`;
}

export function exec(command, options = {}) {
  return new Promise((resolve, reject) => {
    const name = callbackName("exec");
    window[name] = (errno, stdout, stderr) => {
      resolve({ errno, stdout: stdout || "", stderr: stderr || "" });
      delete window[name];
    };
    try {
      if (typeof globalThis.ksu?.exec !== "function") {
        throw new Error("ksu.exec is not available");
      }
      globalThis.ksu.exec(command, JSON.stringify(options), name);
    } catch (error) {
      delete window[name];
      reject(error);
    }
  });
}

export async function listPackages(type = "all") {
  if (typeof globalThis.ksu?.listPackages !== "function") {
    throw new Error("ksu.listPackages is not available");
  }
  const raw = await Promise.resolve(globalThis.ksu.listPackages(type));
  const packages = typeof raw === "string" ? JSON.parse(raw) : raw;
  if (!Array.isArray(packages)) {
    throw new Error("ksu.listPackages returned a non-array value");
  }
  return packages
    .map((value) => typeof value === "string" ? value.replace(/^package:/, "") : "")
    .filter(Boolean);
}

export async function getPackagesInfo(packages) {
  if (typeof globalThis.ksu?.getPackagesInfo !== "function") {
    throw new Error("ksu.getPackagesInfo is not available");
  }
  const requested = Array.isArray(packages) ? packages : [packages];
  const raw = await Promise.resolve(globalThis.ksu.getPackagesInfo(JSON.stringify(requested)));
  const result = typeof raw === "string" ? JSON.parse(raw) : raw;
  if (!Array.isArray(result)) {
    throw new Error("ksu.getPackagesInfo returned a non-array value");
  }
  return result;
}

export function toast(message) {
  if (typeof globalThis.ksu?.toast === "function") globalThis.ksu.toast(message);
}
