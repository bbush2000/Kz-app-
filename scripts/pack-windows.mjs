#!/usr/bin/env node
/**
 * Stage the WebView UI, inject CarolNative, copy public/app for the
 * live preview, and cross-compile KZ-Control.exe (Win32 + WebView2 + BLE).
 */
import { cpSync, existsSync, mkdirSync, readFileSync, writeFileSync, rmSync, statSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const www = join(root, "android", "assets", "www");
const win = join(root, "windows");
const nativeDir = join(win, "native");
const appDir = join(nativeDir, "app");
const publicDir = join(root, "public");
const publicApp = join(publicDir, "app");
const exeOut = join(publicDir, "KZ-Control.exe");
const zipOut = join(publicDir, "KZ-Control-Windows.zip");

if (!existsSync(www)) {
  console.error("missing android/assets/www");
  process.exit(1);
}

rmSync(appDir, { recursive: true, force: true });
mkdirSync(appDir, { recursive: true });
cpSync(www, appDir, { recursive: true });

const catalog = readFileSync(join(win, "kz-catalog.json"), "utf8").trim();
let shim = readFileSync(join(win, "carol-native-web.js"), "utf8");
if (!shim.includes("__CATALOG__")) {
  console.error("carol-native-web.js missing __CATALOG__ placeholder");
  process.exit(1);
}
shim = shim.replace("__CATALOG__", catalog);
writeFileSync(join(appDir, "carol-native-web.js"), shim);

let html = readFileSync(join(appDir, "index.html"), "utf8");
if (!html.includes("carol-native-web.js")) {
  html = html.replace(
    "<title>The Ultimate KZ Control App</title>",
    '<title>KZ Control</title>\n<link rel="icon" href="img/carol-icon.png"/>',
  );
  html = html.replace(
    '<script src="kz-manuals.js"></script>',
    '<script src="kz-manuals.js"></script>\n<script src="carol-native-web.js"></script>',
  );
  writeFileSync(join(appDir, "index.html"), html);
}

rmSync(publicApp, { recursive: true, force: true });
mkdirSync(publicDir, { recursive: true });
cpSync(appDir, publicApp, { recursive: true });

const goBin = existsSync(join(root, ".grok", "go", "bin", "go"))
  ? join(root, ".grok", "go", "bin", "go")
  : "go";
const env = {
  ...process.env,
  GOOS: "windows",
  GOARCH: "amd64",
  CGO_ENABLED: "0",
  GOPATH: join(root, ".grok", "gopath"),
  GOCACHE: join(root, ".grok", "gocache"),
  GOPROXY: process.env.GOPROXY || "https://proxy.golang.org,direct",
  PATH: `${join(root, ".grok", "go", "bin")}:${join(root, ".grok", "gopath", "bin")}:${process.env.PATH || ""}`,
};

function run(cmd, args, extra = {}) {
  const r = spawnSync(cmd, args, {
    cwd: extra.cwd || nativeDir,
    env: extra.env ? { ...env, ...extra.env } : env,
    encoding: "utf8",
    timeout: extra.timeout || 180000,
  });
  if (r.status !== 0) {
    console.error((r.stderr || "") + (r.stdout || ""));
    if (!extra.optional) process.exit(r.status || 1);
  }
  return r;
}

run(goBin, ["install", "github.com/tc-hib/go-winres@v0.3.3"], {
  optional: true,
  env: { GOOS: process.platform === "win32" ? "windows" : "linux", GOARCH: "amd64" },
});
const winres = join(root, ".grok", "gopath", "bin", "go-winres");
if (existsSync(winres) && existsSync(join(nativeDir, "icon.ico"))) {
  run(winres, ["make", "--in", "winres.json", "--arch", "amd64", "--product-version", "2.24.0", "--file-version", "2.24.0"], {
    optional: true,
    env: { GOOS: "", GOARCH: "" },
  });
}

run(goBin, ["mod", "tidy"]);
const built = join(nativeDir, "KZ-Control.exe");
run(goBin, [
  "build",
  "-trimpath",
  "-ldflags",
  "-H windowsgui -s -w",
  "-o",
  built,
  ".",
]);
if (!existsSync(built)) {
  console.error("go build did not produce KZ-Control.exe");
  process.exit(1);
}
cpSync(built, exeOut);

const staging = join(root, ".grok", "kz-windows-pack");
rmSync(staging, { recursive: true, force: true });
mkdirSync(staging, { recursive: true });
cpSync(built, join(staging, "KZ-Control.exe"));
cpSync(join(win, "README.txt"), join(staging, "README.txt"));

const py = `
import zipfile, pathlib
root = pathlib.Path(${JSON.stringify(staging)})
out = pathlib.Path(${JSON.stringify(zipOut)})
out.parent.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    for p in root.rglob("*"):
        if p.is_file():
            z.write(p, arcname=str(p.relative_to(root)))
print("zip", out.stat().st_size)
`;
const zr = spawnSync("python3", ["-c", py], { encoding: "utf8" });
if (zr.status !== 0) {
  console.error(zr.stderr || zr.stdout);
  process.exit(zr.status || 1);
}

console.log("packed", exeOut, "bytes", statSync(exeOut).size);
console.log((zr.stdout || "").trim());
