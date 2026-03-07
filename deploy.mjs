#!/usr/bin/env node

// Build + deploy eclipse-jdt-search plugin to local Eclipse.
//
// Usage:
//   node deploy.mjs              — build, stop Eclipse, deploy, start Eclipse
//   node deploy.mjs --skip-build — deploy last build (skip mvn package)
//   node deploy.mjs --no-restart — don't start Eclipse after deploy
//
// What it does:
//   1. mvn package (Tycho build)
//   2. Read Bundle-Version from built MANIFEST.MF
//   3. Gracefully stop Eclipse (if running)
//   4. Remove old plugin jar(s) from D:/eclipse/plugins/
//   5. Copy new jar with correct OSGi filename
//   6. Update bundles.info entry
//   7. Start Eclipse

import { execSync, spawn } from "node:child_process";
import { readFileSync, writeFileSync, copyFileSync, readdirSync, unlinkSync } from "node:fs";
import { join } from "node:path";

const PLUGIN_DIR = "D:/git/eclipse-jdt-search";
const ECLIPSE_DIR = "D:/eclipse";
const ECLIPSE_EXE = join(ECLIPSE_DIR, "eclipse.exe");
const WORKSPACE = "D:/eclipse-workspace";
const BUNDLE_ID = "app.m8.eclipse.jdtsearch";
const BUNDLES_INFO = join(ECLIPSE_DIR, "configuration/org.eclipse.equinox.simpleconfigurator/bundles.info");

const skipBuild = process.argv.includes("--skip-build");
const noRestart = process.argv.includes("--no-restart");

// ---- helpers ----

function isEclipseRunning() {
	try {
		const out = execSync('tasklist /FI "IMAGENAME eq eclipse.exe" /NH', { encoding: "utf8" });
		return out.includes("eclipse.exe");
	} catch { return false; }
}

function stopEclipse() {
	if (!isEclipseRunning()) return true;

	console.log("Stopping Eclipse...");
	// Graceful shutdown via WM_CLOSE
	try {
		execSync('taskkill /IM eclipse.exe', { stdio: "ignore" });
	} catch { /* ignore */ }

	// Wait up to 30s for Eclipse to exit
	for (let i = 0; i < 60; i++) {
		Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 500);
		if (!isEclipseRunning()) {
			console.log("Eclipse stopped.");
			return true;
		}
	}

	// Force kill as last resort
	console.log("Forcing Eclipse shutdown...");
	try {
		execSync('taskkill /F /IM eclipse.exe', { stdio: "ignore" });
		Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 1000);
	} catch { /* ignore */ }
	return !isEclipseRunning();
}

function startEclipse() {
	console.log("Starting Eclipse...");
	const child = spawn(ECLIPSE_EXE, ["-data", WORKSPACE], {
		detached: true,
		stdio: "ignore",
		windowsHide: false,
	});
	child.unref();
	console.log(`Eclipse starting (PID ${child.pid})`);
}

// ---- main ----

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
const version = match[1];

const builtJar = join(PLUGIN_DIR, `plugin/target/${BUNDLE_ID}-1.0.0-SNAPSHOT.jar`);
const targetFilename = `${BUNDLE_ID}_${version}.jar`;
const targetPath = join(ECLIPSE_DIR, "plugins", targetFilename);

// 3. Stop Eclipse if running
if (!stopEclipse()) {
	console.error("Failed to stop Eclipse. Deploy aborted.");
	process.exit(1);
}

// 4. Remove old plugin jar(s)
const pluginsDir = join(ECLIPSE_DIR, "plugins");
const oldJars = readdirSync(pluginsDir).filter(f => f.startsWith(BUNDLE_ID + "_") && f.endsWith(".jar"));
for (const old of oldJars) {
	if (old !== targetFilename) {
		const oldPath = join(pluginsDir, old);
		console.log(`Removing old: ${old}`);
		unlinkSync(oldPath);
	}
}

// 5. Copy new jar
copyFileSync(builtJar, targetPath);
console.log(`Deployed: ${targetFilename}`);

// 6. Update bundles.info
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

// 7. Start Eclipse
if (!noRestart) {
	startEclipse();
} else {
	console.log("\nDone. Start Eclipse manually to activate.");
}
