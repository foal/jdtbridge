/**
 * Composite dashboard — shows Eclipse IDE state in one call.
 * Each section maps to a standalone jdt command for incremental refresh.
 *
 * Usage:
 *   jdt status              all sections + help
 *   jdt status -q           all sections, no help
 *   jdt status git editors  specific sections only
 */

import { execSync, spawnSync } from "node:child_process";
import { existsSync } from "node:fs";
import { dirname, basename, resolve } from "node:path";
import { get } from "../client.mjs";
import { toSandboxPath } from "../paths.mjs";
import { formatTable } from "../format/table.mjs";
import { green, red, yellow, dim } from "../color.mjs";

const SECTIONS = ["git", "editors", "errors", "launches", "tests", "projects"];

export async function status(args) {
  const quiet = args.includes("-q") || args.includes("--quiet");
  const requested = args.filter((a) => !a.startsWith("-"));
  const sections = requested.length > 0
    ? requested.filter((s) => SECTIONS.includes(s))
    : SECTIONS;

  const parts = [];
  for (const s of sections) {
    const fn = RENDERERS[s];
    if (fn) parts.push(await fn());
  }
  if (!quiet) parts.push(renderHelp());

  console.log(parts.join("\n\n"));
}

// ---- Section renderers ----

const RENDERERS = {
  git: renderGit,
  editors: renderEditors,
  errors: renderErrors,
  launches: renderLaunches,
  tests: renderTests,
  projects: renderProjects,
};

async function renderGit() {
  // Get project locations to discover repos
  const projects = await get("/projects");
  if (projects.error) return "## Git\n\n(unavailable)";

  const repos = discoverRepos(projects);
  const lines = ["## Git", "", "$ jdt git"];

  const headers = ["REPO", "BRANCH", "STATUS"];
  const repoRows = [];

  for (const repo of repos) {
    const branch = gitCmd(repo.path, "git branch --show-current") || "unknown";
    const statusOut = gitCmd(repo.path, "git status --short") || "";
    const dirty = statusOut.split("\n").filter((l) => l.trim()).length;
    const status = dirty > 0 ? `${dirty} modified` : "clean";
    repoRows.push([toSandboxPath(repo.path), branch, status]);

    // Show modified files for small diffs, truncate for large
    if (dirty > 0 && dirty <= 10) {
      repoRows.push(...statusOut.split("\n").filter((l) => l.trim())
        .map((l) => ["  " + l.trim(), "", ""]));
    } else if (dirty > 10) {
      const top = statusOut.split("\n").filter((l) => l.trim()).slice(0, 5);
      for (const l of top) repoRows.push(["  " + l.trim(), "", ""]);
      repoRows.push([`  ...+${dirty - 5} more`, "", ""]);
    }
  }
  lines.push(formatTable(headers, repoRows));
  return lines.join("\n");
}

async function renderEditors() {
  const results = await get("/editors");
  const lines = ["## Editors", "", "$ jdt editors"];
  if (results.error || results.length === 0) {
    lines.push("(no open editors)");
    return lines.join("\n");
  }

  for (const r of results) {
    const marker = r.active ? " > " : "   ";
    const name = r.fqn ? `\`${r.fqn}\`` : basename(r.file);
    const proj = r.project || "";
    lines.push(`${marker}${name}  ${dim(proj)}`);
  }
  return lines.join("\n");
}

async function renderErrors() {
  const results = await get("/errors", 180_000);
  const lines = ["## Errors", "", "$ jdt errors"];
  if (results.error) { lines.push(results.error); return lines.join("\n"); }
  if (results.length === 0) { lines.push("(no errors)"); return lines.join("\n"); }

  for (const r of results) {
    const sev = r.severity === "ERROR" ? red("ERROR") : yellow("WARN ");
    lines.push(`${sev} ${toSandboxPath(r.file)}:${r.line}  ${r.message}`);
  }
  return lines.join("\n");
}

async function renderLaunches() {
  const results = await get("/launch/list");
  const lines = ["## Launches", "", "$ jdt launch list"];
  if (results.error) { lines.push(results.error); return lines.join("\n"); }

  const running = Array.isArray(results)
    ? results.filter((r) => !r.terminated) : [];
  if (running.length === 0) {
    const total = Array.isArray(results) ? results.length : 0;
    lines.push(total > 0
      ? `(no running launches, ${total} terminated)`
      : "(no launches)");
    return lines.join("\n");
  }

  const headers = ["NAME", "TYPE", "MODE", "PID"];
  const rows = running.map((r) => [r.name, r.type, r.mode, r.pid ? `${r.pid}` : ""]);
  lines.push(formatTable(headers, rows));
  return lines.join("\n");
}

async function renderTests() {
  const results = await get("/test/sessions");
  const lines = ["## Tests", "", "$ jdt test sessions"];
  if (results.error) { lines.push(results.error); return lines.join("\n"); }
  if (results.length === 0) { lines.push("(no test sessions)"); return lines.join("\n"); }

  // Latest per label only
  const seen = new Set();
  const latest = results.filter((s) => {
    const key = s.label || s.session;
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });

  const now = Date.now();
  const headers = ["SESSION", "TESTS", "RESULT", "STATUS"];
  const rows = latest.map((s) => {
    const startMs = s.startedAt || 0;
    const counts = [];
    if (s.passed > 0) counts.push(green(`${s.passed} passed`));
    if (s.failed > 0) counts.push(red(`${s.failed} failed`));
    let st;
    if (s.state === "running") {
      st = startMs ? `running, started ${ago(now - startMs)}` : "running";
    } else {
      const endMs = startMs && s.time > 0 ? startMs + s.time * 1000 : 0;
      st = endMs ? `finished ${ago(now - endMs)}` : "finished";
    }
    return [s.label || s.session, `${s.total}`, counts.join(", "), st];
  });
  lines.push(formatTable(headers, rows));
  return lines.join("\n");
}

async function renderProjects() {
  const projects = await get("/projects");
  const lines = ["## Projects", "", "$ jdt projects"];
  if (projects.error) { lines.push(projects.error); return lines.join("\n"); }
  if (projects.length === 0) { lines.push("(no projects)"); return lines.join("\n"); }

  const repos = discoverRepos(projects);

  // Get dirty file counts per project dir
  const dirtyMap = {};
  for (const repo of repos) {
    const statusOut = gitCmd(repo.path, "git status --short") || "";
    for (const line of statusOut.split("\n")) {
      if (!line.trim()) continue;
      const file = line.slice(3);
      const dir = file.split("/")[0];
      dirtyMap[repo.path + "/" + dir] = (dirtyMap[repo.path + "/" + dir] || 0) + 1;
    }
  }

  const headers = ["PROJECT", "REPO", "MODIFIED"];
  const rows = [];

  // Dirty first, then clean
  const withDirty = [];
  const clean = [];
  for (const p of projects) {
    const name = typeof p === "string" ? p : p.name;
    const loc = typeof p === "string" ? "" : (p.location || "");
    const normLoc = loc.replace(/\\/g, "/");
    const repoName = repos.find((r) => normLoc.startsWith(r.path))?.name || "";
    const dirKey = normLoc;
    const parentKey = dirname(normLoc) + "/" + basename(normLoc);
    const count = dirtyMap[normLoc] || dirtyMap[parentKey] || 0;
    if (count > 0) {
      withDirty.push([`\`${name}\``, repoName, `~${count}`]);
    } else {
      clean.push([`\`${name}\``, repoName, "—"]);
    }
  }

  // Sort dirty by count descending
  withDirty.sort((a, b) => parseInt(b[2].slice(1)) - parseInt(a[2].slice(1)));
  rows.push(...withDirty, ...clean);

  lines.push(formatTable(headers, rows));
  lines.push("");
  lines.push(`${projects.length} projects, ${repos.length} repos`);
  return lines.join("\n");
}

function renderHelp() {
  return `## Refresh

  jdt status              full dashboard
  jdt status -q           without this guide
  jdt status git          only git state
  jdt status editors      only open editors
  jdt status errors       only compilation errors
  jdt status launches     only running launches
  jdt status tests        only test sessions
  jdt status projects     only project list

  Add -q to suppress this guide.`;
}

// ---- Helpers ----

function discoverRepos(projects) {
  const seen = new Map();
  for (const p of projects) {
    const loc = (typeof p === "string" ? "" : (p.location || "")).replace(/\\/g, "/");
    if (!loc) continue;
    // Walk up to find .git
    let dir = dirname(loc);
    while (dir && dir !== dirname(dir)) {
      const gitDir = dir.replace(/\//g, "\\") + "\\.git";
      if (existsSync(gitDir) || existsSync(dir + "/.git")) {
        if (!seen.has(dir)) {
          seen.set(dir, { path: dir, name: basename(dir), projects: [] });
        }
        seen.get(dir).projects.push(typeof p === "string" ? p : p.name);
        break;
      }
      dir = dirname(dir);
    }
  }
  return [...seen.values()];
}

function gitCmd(repoPath, cmd) {
  try {
    return execSync(cmd, {
      cwd: repoPath, encoding: "utf8", timeout: 5000,
    }).trim();
  } catch { return ""; }
}

function ago(ms) {
  const s = Math.floor(ms / 1000);
  if (s < 60) return `${s}s ago`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m}m ago`;
  const h = Math.floor(m / 60);
  return `${h}h ago`;
}

export const help = `Eclipse workspace dashboard — composite view of IDE state.

Usage:  jdt status [sections...] [-q]

Sections (default: all):
  git          git repos, branches, modified files
  editors      open editor tabs (active first)
  errors       compilation errors
  launches     running launches
  tests        recent test sessions
  projects     workspace projects with repo mapping

Examples:
  jdt status                    full dashboard
  jdt status -q                 full dashboard, no guide
  jdt status editors errors     only editors + errors
  jdt status git                only git state`;
