import { basename } from "node:path";
import { get } from "../client.mjs";
import { toSandboxPath } from "../paths.mjs";
import { formatTable } from "../format/table.mjs";

export async function projects() {
  const results = await get("/projects");
  if (results.error) {
    console.error(results.error);
    return;
  }
  if (results.length === 0) {
    console.log("(no projects)");
    return;
  }

  const repoSet = new Set();
  const rows = results.map((p) => {
    const name = p.name || p;
    const loc = toSandboxPath(p.location || "");
    const repo = (p.repo || "").replace(/\\/g, "/");
    const repoName = repo ? basename(repo) : "";
    if (repoName) repoSet.add(repoName);
    return [`\`${name}\``, loc, repoName];
  });

  console.log(formatTable(["PROJECT", "LOCATION", "REPO"], rows));
  console.log(`\n${results.length} projects, ${repoSet.size} repos`);
}

export const help = `List workspace projects with repo mapping.

Usage:  jdt projects

Output: PROJECT, REPO — one project per line.`;
