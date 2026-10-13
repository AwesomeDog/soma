// Assembles the four npm packages from a GitHub Release's binaries and, with
// --publish, pushes them. Binaries never live in git.
//
//   node npm/build.mjs --version 1.2.3 --publish
//
// --version is the tag without its "v" and pins all four packages. In CI no
// token is needed — npm uses GitHub's OIDC.

import { execFileSync } from "node:child_process";
import { chmodSync, existsSync, mkdirSync, readFileSync, rmSync, statSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const LICENSE = readFileSync(join(ROOT, "LICENSE"));

// Only the tag-derived fields are filled in; the rest comes from the repo file.
const manifest = JSON.parse(readFileSync(join(ROOT, "npm/soma/package.json"), "utf8"));
const { name, license, repository, homepage, keywords } = manifest;
const NAME = name.split("/")[1];

// os and cpu make npm install only the matching package. Assets mirror the
// native.image.name of each pom.xml profile.
const PLATFORMS = [
  { id: "darwin-arm64", os: "darwin", cpu: "arm64", asset: "soma-mac-arm64", bin: "soma" },
  { id: "linux-x64", os: "linux", cpu: "x64", asset: "soma-linux-x64", bin: "soma" },
  { id: "win32-x64", os: "win32", cpu: "x64", asset: "soma-windows-x64.exe", bin: "soma.exe" },
];

const argv = process.argv.slice(2);
const version = argv.includes("--version") ? argv[argv.indexOf("--version") + 1] : null;

if (!version) {
  console.error("usage: node npm/build.mjs --version <x.y.z> [--publish]");
  process.exit(1);
}

const emit = (file, data) => {
  mkdirSync(dirname(file), { recursive: true });
  writeFileSync(file, data);
};
const pkg = (dir, fields) => emit(join(dir, "package.json"), `${JSON.stringify(fields, null, 2)}\n`);

for (const { asset } of PLATFORMS) {
  const url = `${homepage}/releases/download/v${version}/${asset}`;
  process.stdout.write(`downloading ${url}\n`);
  const response = await fetch(url);
  if (!response.ok) throw new Error(`${url} -> HTTP ${response.status}`);
  emit(join(ROOT, "npm/assets", asset), Buffer.from(await response.arrayBuffer()));
}

rmSync(join(ROOT, "npm/dist"), { recursive: true, force: true });

// No bin field: installing one directly must not claim the global `soma` command.
for (const { id, os, cpu, asset, bin } of PLATFORMS) {
  const dir = join(ROOT, "npm/dist", `${NAME}-${id}`);
  const binary = join(dir, "bin", bin);
  emit(binary, readFileSync(join(ROOT, "npm/assets", asset)));
  if (os !== "win32") chmodSync(binary, 0o755);
  emit(join(dir, "LICENSE"), LICENSE);
  emit(join(dir, "README.md"), `# ${name}-${id}\n\nThe Soma binary for ${os} ${cpu}. Install ${name} instead — it pulls this in.\n`);
  pkg(dir, {
    name: `${name}-${id}`,
    description: `Soma binary for ${id}. Install ${name} instead.`,
    version, license, repository, homepage, keywords,
    os, cpu, files: [`bin/${bin}`, "README.md", "LICENSE"],
  });
}

// The README is the repository's, so ship whatever it points at under the same
// paths and npm renders the same page as GitHub.
const main = join(ROOT, "npm/dist", NAME);
const readme = readFileSync(join(ROOT, "README.md"), "utf8");
const shim = join(main, "bin/soma.js");
emit(shim, readFileSync(join(ROOT, "npm/soma/bin/soma.js")));
chmodSync(shim, 0o755);
emit(join(main, "LICENSE"), LICENSE);
emit(join(main, "README.md"), readme);

// Skip anchors, absolute links and directories — only real files are shippable.
const shipped = ["bin"];
for (const path of new Set([...readme.matchAll(/(?:\]\(|src=")([^)"]+)/g)].map((m) => m[1].split("#")[0]))) {
  const source = join(ROOT, path);
  if (!path || /^https?:/.test(path) || !existsSync(source) || !statSync(source).isFile()) continue;
  emit(join(main, path), readFileSync(source));
  shipped.push(path.split("/")[0]);
}

pkg(main, {
  ...manifest,
  version,
  files: [...new Set(shipped)],
  optionalDependencies: Object.fromEntries(PLATFORMS.map(({ id }) => [`${name}-${id}`, version])),
});

if (!argv.includes("--publish")) {
  process.stdout.write(`assembled ${version} in ${join(ROOT, "npm/dist")}\n`);
  process.exit(0);
}

// Platform packages first: the main package pins versions that must already exist.
for (const dir of [...PLATFORMS.map(({ id }) => join(ROOT, "npm/dist", `${NAME}-${id}`)), main])
  execFileSync("npm", ["publish", "--access", "public"], { cwd: dir, stdio: "inherit" });
