#!/usr/bin/env node

// Release helper — bumps versions, builds, and optionally tags.
//
// Usage:
//   node scripts/release.mjs <version>          # bump + build + tag
//   node scripts/release.mjs <version> --dry     # bump + build only (no tag/push)
//   node scripts/release.mjs <version> --bump    # bump only (no build)
//
// Examples:
//   node scripts/release.mjs 1.1.0
//   node scripts/release.mjs 1.2.0 --dry

import { execSync } from "node:child_process";
import { existsSync } from "node:fs";
import { resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(__dirname, "..");

function run(cmd, opts = {}) {
  console.log(`  $ ${cmd}`);
  return execSync(cmd, { cwd: ROOT, stdio: "inherit", ...opts });
}

function runCapture(cmd) {
  return execSync(cmd, { cwd: ROOT, encoding: "utf8" }).trim();
}

// ---- Parse args ----

const args = process.argv.slice(2);
const version = args.find((a) => !a.startsWith("-"));
const dry = args.includes("--dry");
const bumpOnly = args.includes("--bump");

if (!version || !/^\d+\.\d+\.\d+$/.test(version)) {
  console.error("Usage: node scripts/release.mjs <version> [--dry] [--bump]");
  console.error("  version must be semver (e.g. 1.1.0)");
  process.exit(1);
}

const snapshotVersion = `${version}-SNAPSHOT`;
const tag = `v${version}`;

// ---- Preflight ----

console.log(`\nRelease ${version}\n`);

const branch = runCapture("git branch --show-current");
if (branch !== "master") {
  console.error(`Error: must be on master (currently on ${branch})`);
  process.exit(1);
}

const status = runCapture("git status --porcelain");
if (status) {
  console.error("Error: working tree is not clean\n" + status);
  process.exit(1);
}

const tags = runCapture("git tag -l");
if (tags.split("\n").includes(tag)) {
  console.error(`Error: tag ${tag} already exists`);
  process.exit(1);
}

// ---- Version bump ----

console.log("Bumping Tycho versions...");
run(
  `mvn org.eclipse.tycho:tycho-versions-plugin:set-version -DnewVersion=${snapshotVersion} -B -q`,
);

console.log("\nBumping CLI version...");
run(`npm version ${version} --no-git-tag-version`, { cwd: resolve(ROOT, "cli") });

if (bumpOnly) {
  console.log("\n--bump: stopping after version bump.");
  process.exit(0);
}

// ---- Build ----

console.log("\nRunning full build (mvn clean verify)...");
run("mvn clean verify -B");

console.log("\nRunning CLI tests...");
run("npm test", { cwd: resolve(ROOT, "cli") });

if (dry) {
  console.log("\n--dry: skipping commit, tag, and push.");
  console.log("To finish the release manually:");
  console.log(`  git add -A && git commit -m "Release ${version}"`);
  console.log(`  git tag ${tag}`);
  console.log(`  git push origin master ${tag}`);
  process.exit(0);
}

// ---- Commit + Tag + Push ----

console.log("\nCommitting version bump...");
run("git add -A");
run(`git commit -m "Release ${version}"`);

console.log(`\nTagging ${tag}...`);
run(`git tag ${tag}`);

console.log("\nPushing...");
run(`git push origin master ${tag}`);

console.log(`\n✓ Released ${version}`);
console.log(`  CI will deploy the p2 site to GitHub Pages.`);
console.log(`  Create GitHub release: gh release create ${tag} --generate-notes`);
