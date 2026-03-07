#!/usr/bin/env node

// Build + deploy eclipse-jdt-search plugin to local Eclipse.
//
// Usage:
//   node deploy.mjs              — build and deploy
//   node deploy.mjs --skip-build — deploy last build (skip mvn package)
//
// What it does:
//   1. mvn package (Tycho build)
//   2. Read Bundle-Version from built MANIFEST.MF
//   3. Remove old plugin jar(s) from D:/eclipse/plugins/
//   4. Copy new jar with correct OSGi filename
//   5. Update bundles.info entry
//
// Requires: Eclipse must be stopped (can't overwrite in-use jars on Windows).

import { execSync } from "node:child_process";
import { readFileSync, writeFileSync, copyFileSync, readdirSync, unlinkSync } from "node:fs";
import { join } from "node:path";

const PLUGIN_DIR = "D:/git/eclipse-jdt-search";
const ECLIPSE_DIR = "D:/eclipse";
const BUNDLE_ID = "app.m8.eclipse.jdtsearch";
const BUNDLES_INFO = join(ECLIPSE_DIR, "configuration/org.eclipse.equinox.simpleconfigurator/bundles.info");

const skipBuild = process.argv.includes("--skip-build");

// 1. Build
if (!skipBuild) {
	console.log("Building...");
	execSync("mvn package", { cwd: PLUGIN_DIR, stdio: "inherit" });
	console.log();
}

// 2. Read Bundle-Version from built MANIFEST
const manifest = readFileSync(join(PLUGIN_DIR, "plugin/target/MANIFEST.MF"), "utf8");
const match = manifest.match(/Bundle-Version:\s+(\S+)/);
if (!match) {
	console.error("Cannot find Bundle-Version in built MANIFEST.MF");
	process.exit(1);
}
const version = match[1]; // e.g. "1.0.0.202603071234"

const builtJar = join(PLUGIN_DIR, `plugin/target/${BUNDLE_ID}-1.0.0-SNAPSHOT.jar`);
const targetFilename = `${BUNDLE_ID}_${version}.jar`;
const targetPath = join(ECLIPSE_DIR, "plugins", targetFilename);

// 3. Remove old plugin jar(s)
const pluginsDir = join(ECLIPSE_DIR, "plugins");
const oldJars = readdirSync(pluginsDir).filter(f => f.startsWith(BUNDLE_ID + "_") && f.endsWith(".jar"));
for (const old of oldJars) {
	if (old !== targetFilename) {
		const oldPath = join(pluginsDir, old);
		console.log(`Removing old: ${old}`);
		unlinkSync(oldPath);
	}
}

// 4. Copy new jar
copyFileSync(builtJar, targetPath);
console.log(`Deployed: ${targetFilename}`);

// 5. Update bundles.info
let info = readFileSync(BUNDLES_INFO, "utf8");
const entry = `${BUNDLE_ID},${version},plugins/${targetFilename},4,false`;
const regex = new RegExp(`^${BUNDLE_ID.replace(/\./g, "\\.")},.*$`, "m");
if (regex.test(info)) {
	info = info.replace(regex, entry);
} else {
	info = info.trimEnd() + "\n" + entry + "\n";
}
writeFileSync(BUNDLES_INFO, info);

console.log(`bundles.info updated: ${entry}`);
console.log("\nDone. Start Eclipse to activate.");
