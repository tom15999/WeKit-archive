import { exec, hasKernelSuBridge, shellQuote } from "./bridge.js";

const $ = (selector) => document.querySelector(selector);
const targetList = $("#target-list");
const targetLoading = $("#targets-loading");
const emptyState = $("#empty-state");
const enabledCount = $("#enabled-count");
const dialog = $("#app-dialog");
const appList = $("#app-list");
const appsLoading = $("#apps-loading");
const appsEmpty = $("#apps-empty");
const appSearch = $("#app-search");
const toast = $("#toast");

let allApps = [];
let appsLoaded = false;
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
    throw new Error(result.stderr.trim() || `Command failed (${result.errno})`);
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
    return { userId, packageName, enabled: enabled === "1" };
  }).filter((entry) => /^\d+$/.test(entry.userId) && entry.packageName && typeof entry.enabled === "boolean");
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
  userName.textContent = `Android 用户 ${entry.userId}`;
  main.append(packageName, userName);

  const label = document.createElement("label");
  label.className = "switch";
  label.title = entry.enabled ? "已启用" : "已关闭";
  const toggle = document.createElement("input");
  toggle.type = "checkbox";
  toggle.checked = entry.enabled;
  toggle.setAttribute("role", "switch");
  toggle.setAttribute("aria-label", `${entry.packageName}，Android 用户 ${entry.userId}`);
  const track = document.createElement("span");
  track.className = "switch-track";
  label.append(toggle, track);

  const remove = document.createElement("button");
  remove.className = "delete-button";
  remove.type = "button";
  remove.textContent = "删除";
  remove.setAttribute("aria-label", `删除 ${entry.packageName}`);

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

  remove.addEventListener("click", async () => {
    remove.disabled = true;
    try {
      await configCommand("delete", entry.userId, entry.packageName);
      showToast("已从列表删除");
      await loadTargets();
    } catch (error) {
      showToast(error.message || "删除失败", true);
    } finally {
      remove.disabled = false;
    }
  });

  row.append(main, label, remove);
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
  } catch (error) {
    renderTargets([]);
    showToast(error.message || "无法读取目标", true);
  } finally {
    targetLoading.hidden = true;
  }
}

function parseApps(stdout) {
  const unique = new Set();
  return stdout.split("\n").map((line) => line.trim()).filter(Boolean).map((line) => {
    const [userId, packageName] = line.split("\t");
    return { userId, packageName };
  }).filter((entry) => {
    const key = `${entry.userId}\t${entry.packageName}`;
    if (!/^\d+$/.test(entry.userId) || !entry.packageName || unique.has(key)) return false;
    unique.add(key);
    return true;
  }).sort((left, right) => left.packageName.localeCompare(right.packageName) || Number(left.userId) - Number(right.userId));
}

function renderApps() {
  const needle = appSearch.value.trim().toLowerCase();
  const matches = allApps.filter((entry) => !needle || `${entry.packageName} ${entry.userId}`.toLowerCase().includes(needle));
  appList.replaceChildren(...matches.map((entry) => {
    const row = document.createElement("button");
    row.className = "app-row";
    row.type = "button";
    const name = document.createElement("span");
    name.className = "app-package";
    name.textContent = entry.packageName;
    name.title = entry.packageName;
    const user = document.createElement("span");
    user.className = "app-user";
    user.textContent = `用户 ${entry.userId}`;
    row.append(name, user);
    row.addEventListener("click", () => addTarget(entry));
    return row;
  }));
  appList.hidden = matches.length === 0;
  appsEmpty.hidden = matches.length !== 0;
}

async function loadApps() {
  appsLoading.hidden = false;
  appList.hidden = true;
  appsEmpty.hidden = true;
  try {
    allApps = parseApps(await configCommand("apps"));
    appsLoaded = true;
    renderApps();
  } catch (error) {
    allApps = [];
    renderApps();
    showToast(error.message || "扫描应用失败", true);
  } finally {
    appsLoading.hidden = true;
  }
}

async function addTarget(entry) {
  try {
    await configCommand("add", entry.userId, entry.packageName);
    dialog.close();
    showToast("已加入列表，默认关闭");
    await loadTargets();
  } catch (error) {
    showToast(error.message || "添加失败", true);
  }
}

async function openAddDialog() {
  if (!dialog.open) dialog.showModal();
  appSearch.value = "";
  if (appsLoaded) renderApps();
  else await loadApps();
  appSearch.focus();
}

$("#add-target").addEventListener("click", openAddDialog);
$("#empty-add-target").addEventListener("click", openAddDialog);
$("#close-dialog").addEventListener("click", () => dialog.close());
appSearch.addEventListener("input", renderApps);
$("#refresh-targets").addEventListener("click", loadTargets);
$("#reset-targets").addEventListener("click", async () => {
  if (!window.confirm("重置会删除手动添加的项目，并重新扫描所有 Android 用户的微信。")) return;
  const reset = $("#reset-targets");
  reset.disabled = true;
  try {
    await configCommand("reset");
    appsLoaded = false;
    showToast("已重新扫描微信");
    await loadTargets();
  } catch (error) {
    showToast(error.message || "重置失败", true);
  } finally {
    reset.disabled = false;
  }
});

if (!hasKernelSuBridge()) {
  targetLoading.textContent = "请在 KernelSU 管理器中打开此页面";
} else {
  loadTargets();
}
