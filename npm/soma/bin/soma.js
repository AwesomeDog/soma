#!/usr/bin/env node
"use strict";

const { spawnSync } = require("node:child_process");
const { accessSync, constants } = require("node:fs");
const os = require("node:os");

const { name, homepage, optionalDependencies } = require("../package.json");
const platforms = Object.keys(optionalDependencies).map((dep) => dep.slice(name.length + 1));
const platform = `${process.platform}-${process.arch}`;

const fail = (message) => {
  console.error(`soma: ${message}`);
  process.exit(1);
};

if (!optionalDependencies[`${name}-${platform}`])
  fail(`no build for ${platform}. Supported: ${platforms.join(", ")}. Download one from ${homepage}/releases`);

const hasGlibc = () => {
  try {
    process.report.excludeNetwork = true; // keep getReport cheap where supported (Node >= 22.3)
    return Boolean(process.report.getReport().header.glibcVersionRuntime);
  } catch {
    return true; // no diagnostic report: assume the common case and let exec decide
  }
};
if (process.platform === "linux" && !hasGlibc())
  fail(`the prebuilt binaries need glibc; musl-based distros are not supported. See ${homepage}/releases`);

let binary;
try {
  binary = require.resolve(`${name}-${platform}/bin/${process.platform === "win32" ? "soma.exe" : "soma"}`);
} catch {
  fail(`${name}-${platform} is missing. Reinstall with optional dependencies: npm install -g ${name}`);
}

// execve aborts instead of throwing when it fails on Node < 26.1, so catch a lost exec bit here.
try {
  accessSync(binary, constants.X_OK);
} catch {
  fail(`${binary} is not executable. Reinstall with: npm install -g ${name}`);
}

const env = { ...process.env };
if (process.platform === "linux" && !/(^|[\s:/])libm\.so\b/.test(env.LD_PRELOAD ?? ""))
  env.LD_PRELOAD = env.LD_PRELOAD ? `${env.LD_PRELOAD}:libm.so.6` : "libm.so.6";

const args = process.argv.slice(2);

// Node >= 22.15, POSIX only: replace this process outright. No parent node holding ~40MB, and
// signals, terminal control, and exit codes reach soma untouched. args[0] becomes soma's argv[0];
// nothing may be pending on stdio at this point.
if (typeof process.execve === "function") {
  try {
    process.execve(binary, [binary, ...args], env);
  } catch {}
}

if (process.platform !== "win32") process.on("SIGINT", () => {});

const { status, signal, error } = spawnSync(binary, args, { stdio: "inherit", env });
if (error) fail(error.message);
if (signal) {
  process.removeAllListeners(signal);
  try {
    process.kill(process.pid, signal);
  } catch {}
  process.exit(128 + (os.constants.signals[signal] ?? 0)); // Signals node ignores, such as SIGPIPE.
}
process.exit(status ?? 1);
