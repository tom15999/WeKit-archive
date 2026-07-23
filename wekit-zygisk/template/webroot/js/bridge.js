let callbackId = 0;

export function hasKernelSuBridge() {
  return typeof window.ksu?.exec === "function";
}

// This is the Promise wrapper used by the official `kernelsu` npm package,
// kept local so the installed module stays self-contained and offline-safe.
export function exec(command, options = {}) {
  return new Promise((resolve, reject) => {
    if (!hasKernelSuBridge()) {
      reject(new Error("KernelSU WebUI bridge is unavailable"));
      return;
    }
    const callbackName = `wekit_exec_${Date.now()}_${callbackId++}`;
    window[callbackName] = (errno, stdout, stderr) => {
      delete window[callbackName];
      resolve({ errno, stdout: stdout || "", stderr: stderr || "" });
    };
    try {
      window.ksu.exec(command, JSON.stringify(options), callbackName);
    } catch (error) {
      delete window[callbackName];
      reject(error);
    }
  });
}

export function shellQuote(value) {
  return `'${String(value).replace(/'/g, "'\\\"'\\\"'")}'`;
}
