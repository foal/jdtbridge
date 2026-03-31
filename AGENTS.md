# JDT Bridge — Agent Instructions

## Prerequisites

Required tools (verified by `jdt setup --check`):
- **Node.js** >= 20, **npm** — CLI runtime
- **Java** >= 21, **Maven** >= 3.9 — plugin build (Tycho)
- **Eclipse IDE** — running, with JDT Bridge plugin installed
- **git**, **gh** (GitHub CLI) — version control, PRs, releases

### Eclipse source bundles (recommended)

`jdt source` needs source bundles to read Eclipse Platform/JDT API source
and javadoc. Without them, `jdt source org.eclipse.core.runtime.CoreException`
returns "Source not available". Install all Eclipse source bundles once
(Eclipse must NOT be running):

```bash
# List available source bundles, filter out tests, install all
BUNDLES=$(D:/eclipse/eclipsec.exe -nosplash \
  -application org.eclipse.equinox.p2.director \
  -repository "https://download.eclipse.org/eclipse/updates/4.39/" \
  -list 2>&1 | grep "^org\.eclipse\..*\.source=" | grep -v "tests\.\|examples\." \
  | sed 's/=.*//' | tr '\n' ',' | sed 's/,$//')

D:/eclipse/eclipsec.exe -nosplash \
  -application org.eclipse.equinox.p2.director \
  -repository "https://download.eclipse.org/eclipse/updates/4.39/" \
  -installIU "$BUNDLES" \
  -destination "D:/eclipse" \
  -profile "epp.package.jee"
```

Adjust the update site URL to match your Eclipse version (e.g. `4.39`)
and paths to your Eclipse installation.

## Environment check (REQUIRED)

**Run at the start of every conversation, before any other work.
Subagents (Explore, Plan, etc.) skip this section — jdt is already
verified by the main agent.**

1. `jdt setup --check` — verifies CLI, Node, Java, Maven, Eclipse, bridge.
   - `jdt: command not found` → `cd cli && npm install && npm link`
   - Plugin not installed → `jdt setup --eclipse <path>`
   - Eclipse not running → start it, re-run check

2. `jdt status` — **workspace dashboard**. Shows git repos, open editors,
   compilation errors, running launches, test results, and project list
   in one call. This is your orientation command — run it first to
   understand what the developer is working on.

3. `jdt help` — read available commands. Use `jdt help <command>` for details.

4. `jdt projects` — verify `io.github.kaluchi.jdtbridge` and
   `io.github.kaluchi.jdtbridge.tests` are listed (imported into Eclipse
   workspace). If missing: Eclipse → File → Import → Existing Projects
   into Workspace → select repo root.

5. Verify `jdtbridge.target` exists in repo root (gitignored, per-developer).
   Without it, Tycho builds fail. If missing, create it pointing to the
   local Eclipse directory:
   ```xml
   <?xml version="1.0" encoding="UTF-8" standalone="no"?>
   <?pde version="3.8"?>
   <target name="eclipse-local" sequenceNumber="1">
       <locations>
           <location path="/path/to/eclipse" type="Directory"/>
       </locations>
   </target>
   ```

## Use `jdt` for Java analysis

**Prefer `jdt` over grep/glob for Java-specific queries.** Grep returns
string matches; `jdt` returns semantic results from Eclipse's compiler index.

Run `jdt help` for the full command list. Run `jdt help <command>` for
detailed usage of any command. Key examples:

```bash
jdt refs <FQMN>                  # find call sites (not string matches)
jdt source <FQMN>               # source + resolved references (hypertext navigation)
jdt type-info <FQN>              # class overview without reading 600 lines
jdt test run <FQN>#<method> -f   # run one test, stream progress
jdt test run <FQN> --project <name> -f  # run with specific project classpath
jdt errors --project <name>      # instant compilation check
jdt build --project <name>       # clean build (default, reliable)
jdt build --project <name> --incremental  # fast incremental (1-3s)
jdt refresh <file>               # explicitly notify Eclipse of file changes
jdt maven update --project <name> -f  # update Maven project (Alt+F5)
```

### `jdt source` — hypertext navigation

`jdt source` returns markdown with source code and resolved references.
Each reference is a ready FQMN for the next `jdt source` call.

References are grouped by:
- **Same-class members** — with javadoc (saves a hop)
- **Project source** — with absolute paths and javadoc
- **Dependencies** — bare FQMNs

All paths are absolute filesystem paths, usable with Read/Edit tools.

### FQMN (Fully Qualified Method Name)

Commands that accept methods support FQMN — class and method in one argument:

```
pkg.Class#method              any overload
pkg.Class#method()            zero-arg overload
pkg.Class#method(String)      specific signature
pkg.Class.method(String)      Eclipse Copy Qualified Name style
```

Types can be simple (`String`) or FQN (`java.lang.String`).
Generics are stripped: `List<String>` matches `List`.

### Pipe composability

```bash
jdt ti io.github.kaluchi.jdtbridge.SearchHandler | grep handle   # 26 methods → 8 handlers
jdt refs io.github.kaluchi.jdtbridge.JdtUtils#findMethod | wc -l # count, not 51 lines
jdt errors --project my-server | head -5                         # one error at a time
jdt src org.springframework.jdbc.core.JdbcTemplate#query | grep -n throw  # throws in library code
```

### Subagents (Explore, Plan)

Custom jdt-aware agents are installed in `.claude/agents/` by
`jdt setup --claude`. They have `jdt` commands in their system prompt
and auto-allow hooks — no setup needed.

**When to use subagents:**
- `Explore` — finding types, tracing references, understanding
  hierarchies, reading source. Fast (haiku model). Use for research
  that doesn't require writing code.
- `Plan` — designing implementation strategy with code understanding.
  Inherits parent model. Read-only.

**When NOT to use subagents:**
- Writing code, editing files — do it yourself, not subagents.
- Simple `jdt` queries (one command) — call `jdt` directly, don't
  spawn a subagent for `jdt refs Foo#bar`.
- Non-Java exploration — subagents add jdt overhead, use plain
  Grep/Glob for config files, scripts, docs.

Subagents skip environment check (CLAUDE.md switch). They assume
jdt is already verified by the main agent.

## Project structure

See [README.md](README.md) for full overview. Key directories:
- `cli/src/` — Node.js CLI (ESM, `.mjs`)
- `plugin/src/` — Eclipse plugin (Java, OSGi)
- `plugin.tests/src/` — Plugin tests (JUnit 5, Fragment-Host of plugin)
- `scripts/release.mjs` — release automation

## Development workflow

### `jdt build` vs full verify

| | `jdt build` | `jdt launch run jdtbridge-package` | `jdt launch run jdtbridge-verify` |
|---|---|---|---|
| What | Eclipse clean+full build (or `--incremental`) | Tycho build, no tests | Tycho full build + tests |
| Speed | 3-10 seconds (incremental: 1-3s) | 30-40 seconds | 40-60 seconds |
| Tests | No | No | Unit + integration |
| Output | Compiled classes in Eclipse | p2 site in `site/target/` | p2 site in `site/target/` |
| When | Quick iteration while coding | Before `jdt setup --skip-build` | Before commit |

Console output is captured by Eclipse and available via `jdt launch logs`.
Use `-f` to stream live, or inspect afterwards with `--tail`. Example:
```bash
jdt launch run jdtbridge-verify           # launch (prints guide first time)
jdt launch logs jdtbridge-verify -f | tail -20  # wait + last 20 lines
jdt launch logs jdtbridge-verify --tail 30      # inspect after completion
```
For CI without local Eclipse: `mvn clean verify -Pci`.

### Making changes

1. **Create a branch** — never commit directly to master
2. **Write code + tests** — every change needs tests
3. **Build** — plugin first, tests second (default is clean build):
   ```bash
   jdt build --project io.github.kaluchi.jdtbridge
   jdt build --project io.github.kaluchi.jdtbridge.tests
   jdt test run --project io.github.kaluchi.jdtbridge.tests -f
   ```
4. **Full Tycho build** — `jdt launch run jdtbridge-verify` then
   `jdt launch logs jdtbridge-verify -f | tail -20` to wait
5. **Install and verify live** — `jdt setup --skip-build`, then test
6. **Check ALL docs** if CLI syntax or API changed:
   - `cli/src/cli.mjs` (`jdt help` output)
   - `cli/src/commands/*.mjs` (per-command help)
   - `README.md`, `cli/README.md`, `plugin/README.md`, this file
7. **Commit → push → PR → squash merge → delete branch**

### Pull requests

Use `gh pr create` / `gh pr edit` / `gh pr merge`. **Always pass `--body`
via HEREDOC** to avoid shell escaping issues. Do NOT use markdown formatting
characters (`*`, `#`, `` ` ``) in `--body` — they break `gh` argument parsing.
Use plain text or `- [x]` checklists only.

```bash
# Create
gh pr create --title "Short title" --body "$(cat <<'EOF'
Summary line.

- [x] Tests pass
- [x] Docs updated
EOF
)"

# Edit (ignore GraphQL Projects Classic deprecation warning)
gh pr edit 55 --body "new body text" 2>&1 | grep -v GraphQL

# Merge + cleanup
gh pr merge 55 --squash --delete-branch
```

### Important details

- **New Java files invisible until build.** `jdt test run` says "Type not found"
  → run `jdt build` for the project first.
- **Build order matters.** `plugin.tests` is a Fragment-Host of `plugin` —
  sees package-private members, but must build after plugin.
- **`jdt setup --skip-build` restarts Eclipse.** Port changes. `jdt` commands
  auto-discover the new port, but raw `curl` URLs break.
- **`jdt setup` without `--skip-build` runs Maven internally.**
  After a full build, always add `--skip-build` — never build twice.

### Test infrastructure

- **CLI tests:** `cd cli && npm test` (vitest)
- **Plugin unit tests:** `jdt test run --project io.github.kaluchi.jdtbridge.tests -f`
- **Integration tests:** full Tycho build only (`jdt launch run jdtbridge-verify`) — use `@EnabledIfSystemProperty(named = "jdtbridge.integration-tests", matches = "true")`
- **Test fixture:** `TestFixture.java` creates a project with known classes —
  `test.model.Animal`, `Dog`, `Cat`, `test.edge.Calculator` (overloads),
  `Repository` (generics), `test.edge.Color` (enum), `test.edge.Marker`
  (annotation). Add new test types there.

### Running plugin tests

**Prefer running individual test classes or methods**, not the full suite.
Full suite (615+ tests) takes ~4 minutes and runs in a separate Eclipse
runtime. Individual tests take 5-15 seconds.

```bash
# Single test method — fastest feedback
jdt test run com.example.FooTest#myMethod -f -q

# Single test class
jdt test run com.example.FooTest -f -q

# Full suite — only before commit or when unsure what broke
jdt test run --project io.github.kaluchi.jdtbridge.tests -f -q
```

Important:
- **Plugin tests run in a PDE test runtime** with workspace bundles
  (not the installed plugin). `jdt build` is enough — no `jdt setup`
  needed before running tests.
- **But `jdt setup` IS needed** to test live behavior (e.g. `jdt find`,
  `jdt hier`) because those go through the installed plugin's HTTP server.
- **Build before testing:** `jdt build --project io.github.kaluchi.jdtbridge.tests`
  — otherwise new test classes won't be found.
- **TestFixture lifecycle:** each test class needs `@BeforeAll static void
  setUp() throws Exception { TestFixture.create(); }`. The fixture is
  idempotent but must be created before `JdtUtils.findType()` calls.
  If a `@Nested` class has `@AfterAll` with `TestFixture.destroy()`,
  subsequent nested classes in the same file won't find fixture types.
- **Check results:** `jdt test sessions` shows all runs with relative time.
  `jdt test status <session> -f` for details on failures.

### Running CLI tests

```bash
cd cli && npm test                    # full suite (~13s, 470+ tests)
cd cli && npx vitest run test/paths.test.mjs   # single file
```

CLI tests mock the HTTP server — no Eclipse needed. They run on both
Windows and Linux (CI). Path conversion tests mock `process.platform`.

## Releasing

```bash
node scripts/release.mjs 1.4.0          # bump + build + tag + push
node scripts/release.mjs 1.4.0 --dry    # bump + build only
node scripts/release.mjs 1.4.0 --bump   # bump versions only
```

Requires: clean working tree, master branch, tag must not exist.
CI deploys automatically: npm publish, p2 site, GitHub Release.

## Conventions

- Java: Eclipse formatter (`jdt format <file>`)
- JavaScript: ESM (`.mjs`), no TypeScript
- Commits: imperative mood ("Add X", "Fix Y"), Co-Authored-By in message
- PRs: no "Generated with Claude Code" in body — Co-Authored-By is enough
