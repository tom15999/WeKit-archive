import { exec, hasKernelSuBridge, shellQuote } from "./bridge.js";

const $ = (selector) => document.querySelector(selector);
const targetList = $("#target-list");
const targetLoading = $("#targets-loading");
const emptyState = $("#empty-state");
const enabledCount = $("#enabled-count");
const refreshTargetsButton = $("#refresh-targets");
const toast = $("#toast");

let toastTimer = 0;

function configScriptPath() {
  try {
    const path = decodeURIComponent(new URL("../config.sh", document.baseURI).pathname);
    if (path.startsWith("/data/adb/modules/") || path.startsWith("/data/adb/modules_update/")) {
      return path;
    }
  } catch (_) {
    // Fall through to the stable module path.
  }
  return "/data/adb/modules/wekit/config.sh";
}

async function configCommand(command, ...args) {
  const commandLine = ["sh", shellQuote(configScriptPath()), shellQuote(command), ...args.map(shellQuote)].join(" ");
  const result = await exec(commandLine);
  if (result.errno !== 0) {
    throw new Error(result.stderr.trim() || "Command failed (" + result.errno + ")");
  }
  return result.stdout;
}

function showToast(message, isError = false) {
  clearTimeout(toastTimer);
  toast.textContent = message;
  toast.classList.toggle("error", isError);
  toast.classList.add("visible");
  toastTimer = setTimeout(() => toast.classList.remove("visible"), 2800);
}

function parseTargets(stdout) {
  return stdout.split("\n").map((line) => line.trim()).filter(Boolean).map((line) => {
    const [userId, packageName, enabled] = line.split("\t");
    return { userId, packageName, enabled };
  }).filter((entry) =>
    /^\d+$/.test(entry.userId) &&
    entry.packageName &&
    (entry.enabled === "0" || entry.enabled === "1")
  ).map((entry) => ({ ...entry, enabled: entry.enabled === "1" }));
}

function createTargetRow(entry) {
  const row = document.createElement("article");
  row.className = "target-row";

  const main = document.createElement("div");
  main.className = "target-main";
  const packageName = document.createElement("div");
  packageName.className = "package-name";
  packageName.textContent = entry.packageName;
  packageName.title = entry.packageName;
  const userName = document.createElement("div");
  userName.className = "user-name";
  userName.textContent = "Android 用户 " + entry.userId;
  main.append(packageName, userName);

  const label = document.createElement("label");
  label.className = "switch";
  label.title = entry.enabled ? "已启用" : "已关闭";
  const toggle = document.createElement("input");
  toggle.type = "checkbox";
  toggle.checked = entry.enabled;
  toggle.setAttribute("role", "switch");
  toggle.setAttribute("aria-label", entry.packageName + "，Android 用户 " + entry.userId);
  const track = document.createElement("span");
  track.className = "switch-track";
  label.append(toggle, track);

  toggle.addEventListener("change", async () => {
    const requested = toggle.checked;
    toggle.disabled = true;
    try {
      await configCommand("set", entry.userId, entry.packageName, requested ? "1" : "0");
      label.title = requested ? "已启用" : "已关闭";
      showToast(requested ? "已启用，下次启动生效" : "已关闭，下次启动生效");
      await loadTargets();
    } catch (error) {
      toggle.checked = !requested;
      showToast(error.message || "更新失败", true);
    } finally {
      toggle.disabled = false;
    }
  });

  row.append(main, label);
  return row;
}

function renderTargets(targets) {
  targetList.replaceChildren(...targets.map(createTargetRow));
  targetList.hidden = targets.length === 0;
  emptyState.hidden = targets.length !== 0;
  enabledCount.textContent = String(targets.filter((entry) => entry.enabled).length);
}

async function loadTargets() {
  targetLoading.hidden = false;
  targetList.hidden = true;
  emptyState.hidden = true;
  try {
    renderTargets(parseTargets(await configCommand("list")));
    return true;
  } catch (error) {
    renderTargets([]);
    showToast(error.message || "无法读取目标", true);
    return false;
  } finally {
    targetLoading.hidden = true;
  }
}

async function refreshTargets() {
  refreshTargetsButton.disabled = true;
  try {
    await configCommand("refresh");
    if (await loadTargets()) {
      showToast("已重新扫描微信应用");
    }
  } catch (error) {
    showToast(error.message || "刷新失败", true);
  } finally {
    refreshTargetsButton.disabled = false;
  }
}

refreshTargetsButton.addEventListener("click", refreshTargets);

if (!hasKernelSuBridge()) {
  targetLoading.textContent = "请在 KernelSU 管理器中打开此页面";
  refreshTargetsButton.disabled = true;
} else {
  loadTargets();
}
