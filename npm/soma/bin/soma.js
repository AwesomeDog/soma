#!/usr/bin/env node
// Hands the terminal over to the native binary for this machine. The three
// @awesomedog/soma-<platform> packages are optionalDependencies, so npm installs
// only the one matching this machine's os and cpu.

"use strict";

const { execFileSync, spawnSync } = require("node:child_process");
const { existsSync } = require("node:fs");

// Nothing about the package is repeated here: npm/build.mjs writes package.json,
// and the supported platforms are exactly its optionalDependencies.
const { name, homepage, optionalDependencies } = require("../package.json");
const platforms = Object.keys(optionalDependencies).map((dep) => dep.slice(name.length + 1));

// The bundled sqlite-vec extension is built without a libm dependency and Soma
// itself does not link libm, so on Linux loading it dies with "undefined symbol:
// sqrtf" unless the system libm is preloaded. Find it here so nobody has to.
const LIBM = ["/usr/lib/x86_64-linux-gnu", "/lib/x86_64-linux-gnu", "/usr/lib64", "/lib64", "/usr/lib", "/lib"]
  .map((dir) => `${dir}/libm.so.6`)
  .find(existsSync);

const fail = (message) => {
  console.error(`soma: ${message}`);
  process.exit(1);
};

const preload = () => {
  if (process.platform !== "linux" || /libm\.so/.test(process.env.LD_PRELOAD ?? "")) return null;
  if (LIBM) return LIBM;
  try {
    const listed = execFileSync("ldconfig", ["-p"], { encoding: "utf8", stdio: ["ignore", "pipe", "ignore"] });
    return listed.match(/\/\S*libm\.so\.6/)?.[0] ?? null;
  } catch {
    return null;
  }
};

const platform = `${process.platform}-${process.arch}`;
if (!optionalDependencies[`${name}-${platform}`])
  fail(`no build for ${platform}. Supported: ${platforms.join(", ")}. Download one from ${homepage}/releases`);

let binary;
try {
  binary = require.resolve(`${name}-${platform}/bin/${process.platform === "win32" ? "soma.exe" : "soma"}`);
} catch {
  fail(`${name}-${platform} is missing. Reinstall with optional dependencies: npm install -g ${name}`);
}

const libm = preload();
const env = libm ? { ...process.env, LD_PRELOAD: `${process.env.LD_PRELOAD ?? ""} ${libm}`.trim() } : process.env;
const { status, signal, error } = spawnSync(binary, process.argv.slice(2), { stdio: "inherit", env });
if (error) fail(error.message);
if (signal) process.kill(process.pid, signal);
process.exit(status ?? 1);
