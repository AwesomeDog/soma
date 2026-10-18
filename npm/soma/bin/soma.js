#!/usr/bin/env node
"use strict";

const { execFileSync, spawnSync } = require("node:child_process");
const { accessSync, constants, existsSync } = require("node:fs");
const os = require("node:os");

const { name, homepage, optionalDependencies } = require("../package.json");
const platforms = Object.keys(optionalDependencies).map((dep) => dep.slice(name.length + 1));

const fail = (message) => {
  console.error(`soma: ${message}`);
  process.exit(1);
};

// sqlite-vec is built without libm, so loading it dies with "undefined symbol: sqrtf" on Linux
// unless a libm is preloaded. The bare soname is used so ld.so picks each process's own ABI copy.
const hasLibm = () => {
  if (["/usr/lib64", "/lib64", "/usr/lib", "/lib"].some((dir) => existsSync(`${dir}/libm.so.6`))) return true;
  // Debian and Ubuntu keep libm in a multiarch directory, so ask ldconfig instead; /sbin is not
  // on a normal user's PATH there either.
  for (const ldconfig of ["ldconfig", "/sbin/ldconfig", "/usr/sbin/ldconfig"]) {
    try {
      const listed = execFileSync(ldconfig, ["-p"], { encoding: "utf8", stdio: ["ignore", "pipe", "ignore"] });
      if (/^\s*libm\.so\.6\s/m.test(listed)) return true;
    } catch {}
  }
  return false;
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

// execve aborts instead of throwing when it fails on Node < 26.1, so check the binary first.
try {
  accessSync(binary, constants.X_OK);
} catch {
  fail(`${binary} is not executable. Reinstall with: npm install -g ${name}`);
}

const env = { ...process.env };
const preloaded = env.LD_PRELOAD ?? "";
if (process.platform === "linux" && !/(^|[\s:/])libm\.so\b/.test(preloaded) && hasLibm())
  env.LD_PRELOAD = preloaded ? `libm.so.6:${preloaded}` : "libm.so.6";

const args = process.argv.slice(2);

// Node >= 22.15, not Windows: this replaces the process, leaving no parent node holding ~40MB and
// passing signals and exit codes through untouched. args[0] is argv[0]; nothing may write to stdio.
if (typeof process.execve === "function") {
  try {
    process.execve(binary, [binary, ...args], env);
  } catch {}
}

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
