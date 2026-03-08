# JDT Bridge

Eclipse plugin that exposes JDT SearchEngine, refactoring, diagnostics, and test runner over HTTP for use by AI coding assistants and CLI tools.

Unlike grep, JDT search understands Java semantics: it resolves types across projects, follows inheritance, and distinguishes declarations from references.

## Architecture

```
+------------------+       HTTP/JSON (loopback)     +-------------------+
|  CLI (jdt)       |  ---- localhost:<random> ---->  |  Eclipse plugin   |
|  @kaluchi/       |                                |  (JDT SearchEngine|
|   jdtbridge      |                                |   via raw socket) |
+------------------+                                +-------------------+
```

**Server side** (this project): An Eclipse plugin (`eclipse-plugin` packaging, built with Tycho). On activation, `Activator` starts a minimal HTTP server on a random port, bound to loopback only. Connection details are written to `~/.jdtbridge/instances/<hash>.json`.

**Client side**: The `cli/` directory contains a Node.js CLI package (`@kaluchi/jdtbridge`). Install globally with `npm link` — provides `jdt` and `jdtbridge` commands. See [cli/README.md](cli/README.md) for full documentation.

**Security**: The server binds to `127.0.0.1` only (not reachable from the network). Every request requires a `Bearer <token>` header. The token is a random 32-hex-char string generated on each Eclipse startup. Bridge files have POSIX `rwx------` permissions on Unix.

**Multi-instance**: Multiple Eclipse instances can run simultaneously. Each writes its own instance file (`~/.jdtbridge/instances/<sha256-hash>.json`), keyed by workspace path. The CLI auto-discovers live instances via PID liveness checks.

## Getting started

Prerequisites: Node.js >= 20, Java, Maven, Eclipse IDE.

```bash
git clone https://github.com/kaluchi/jdtbridge.git
cd jdtbridge/cli
npm install
npm link          # registers global `jdt` and `jdtbridge` commands
jdt setup         # builds plugin (mvn verify), installs into Eclipse
```

`jdt setup` handles everything: runs the Maven build with integration tests, stops Eclipse if running, installs the plugin via p2 director, and restarts Eclipse with the same workspace.

Alternatively, delegate to an AI coding agent (e.g. Claude Code) — it can clone, install, and run `jdt setup` for you.

### Target platform

`jdt setup` automatically generates `jdtbridge.target` pointing to the discovered Eclipse installation. This file is machine-specific and excluded from git.

### Updating

After pulling new changes, run `jdt setup` again. Use `jdt setup --skip-build` to reinstall the last build without rebuilding.

### Manual install (alternative)

After `mvn clean verify`, use **Help > Install New Software** in Eclipse, point to the `site/target/repository/` directory, and install the "JDT Bridge" feature.

## HTTP API

All endpoints accept GET requests with query parameters. The server returns JSON (`application/json`) unless noted otherwise.

### Search & Navigation

#### `GET /projects`

List all Java projects in the workspace.

```json
["my-app-server","my-app-shared","my-app-client"]
```

#### `GET /project-info?project=<name>[&members-threshold=N]`

Project overview with adaptive detail. Returns source roots, packages, types, and optionally method signatures (included when `totalTypes <= members-threshold`, default 200).

#### `GET /find?name=<Name>[&source]`

Find type declarations by name. Supports wildcards (`*Controller*`, `Find*`). Add `&source` to exclude binary/library types.

```json
[
  {"fqn":"com.example.service.UserService","file":"/my-app-server/src/main/java/..."}
]
```

#### `GET /references?class=<FQN>[&method=<name>][&field=<name>][&arity=<n>]`

Find all references to a type, method, or field. Filters out inaccurate matches and javadoc references. Returns file, line, enclosing member, and source line content.

#### `GET /subtypes?class=<FQN>`

Find all direct and indirect subtypes/implementors of a type.

#### `GET /hierarchy?class=<FQN>`

Full type hierarchy: superclass chain, all super interfaces, and all subtypes.

#### `GET /implementors?class=<FQN>&method=<name>[&arity=<n>]`

Find implementations of an interface method across all implementing classes.

#### `GET /type-info?class=<FQN>`

Class overview: kind, superclass, interfaces, fields (with modifiers and types), methods (with full signatures), and line numbers.

#### `GET /source?class=<FQN>[&method=<name>][&arity=<n>]`

Returns source code as `text/plain` with `X-File`, `X-Start-Line`, `X-End-Line` headers. Without `method`, returns the full class source. With `method`, returns method source (or all overloads if multiple match).

### Testing

#### `GET /test?class=<FQN>[&method=<name>][&timeout=<sec>][&no-refresh]`
#### `GET /test?project=<name>[&package=<pkg>][&timeout=<sec>][&no-refresh]`

Run JUnit tests via Eclipse's built-in runner. By default, refreshes the project from disk and waits for auto-build before launching. Returns summary and failure details.

### Diagnostics

#### `GET /errors?[file=<path>][&project=<name>][&no-refresh][&build][&clean][&warnings][&all]`

Compilation diagnostics. By default refreshes from disk and returns only errors.

| Parameter | Description |
|-----------|-------------|
| `file` | Workspace-relative path to filter by specific file |
| `project` | Project name to filter by |
| `no-refresh` | Skip disk refresh (use if Eclipse is already in sync) |
| `build` | Trigger explicit incremental build |
| `clean` | Clean + full rebuild (requires `project`) |
| `warnings` | Include warnings (default: errors only) |
| `all` | All marker types (jdt + checkstyle + maven + ...) |

### Refactoring

#### `GET /organize-imports?file=<path>`

Organize imports using Eclipse project settings. Returns `{"added":N,"removed":N}`.

#### `GET /format?file=<path>`

Format a Java file using Eclipse project formatter settings. Returns `{"modified":true/false}`.

#### `GET /rename?class=<FQN>&newName=<name>[&method=<old>][&field=<old>][&arity=<n>]`

Rename a type, method, or field. Updates all references across the workspace.

#### `GET /move?class=<FQN>&target=<package>`

Move a type to another package. Creates the target package if it doesn't exist. Updates all references.

### Editor

#### `GET /active-editor`

Returns the file and cursor line of the currently active Eclipse editor.

#### `GET /open?class=<FQN>[&method=<name>][&arity=<n>]`

Open a type or method in the Eclipse editor.

## Project structure

```
jdtbridge/
  pom.xml                              # Parent POM (Tycho reactor)
  LICENSE                              # Apache License 2.0
  jdtbridge.target            # Target platform definition
  plugin/
    pom.xml                            # Plugin module (eclipse-plugin packaging)
    META-INF/MANIFEST.MF               # OSGi manifest
    plugin.xml                         # Extension: org.eclipse.ui.startup
    src/io/github/kaluchi/jdtbridge/
      Activator.java                   # Starts HTTP server, writes instance file
      HttpServer.java                  # Loopback HTTP server, auth, routing
      Log.java                         # Centralized ILog logging
      Json.java                        # Lightweight JSON builder (fluent API)
      JdtUtils.java                    # Shared JDT utilities (findType, findMethod, etc.)
      SearchHandler.java               # /projects, /find, /references, /subtypes, /hierarchy,
                                       #   /implementors, /type-info, /source
      DiagnosticsHandler.java          # /errors
      RefactoringHandler.java          # /organize-imports, /format, /rename, /move
      TestHandler.java                 # /test (JUnit runner)
      EditorHandler.java               # /active-editor, /open
      ProjectHandler.java              # /project-info
      StartupHandler.java              # IStartup impl — forces early activation
  plugin.tests/
    pom.xml                            # Test fragment (eclipse-test-plugin packaging)
    META-INF/MANIFEST.MF               # Fragment-Host: io.github.kaluchi.jdtbridge
    src/io/github/kaluchi/jdtbridge/   # Unit tests (Json, HttpServer, handlers)
  feature/
    pom.xml                            # Feature module (eclipse-feature packaging)
    feature.xml                        # Eclipse feature for p2 update site
  site/
    pom.xml                            # P2 repository (eclipse-repository packaging)
    category.xml                       # Update site category
  cli/                                 # Node.js CLI package — see cli/README.md
```

## Known limitations

- **Workspace must be fully built.** JDT search relies on the Eclipse index. If the workspace has not been fully indexed, results may be incomplete. Use `jdt errors --build` or `--clean` to trigger builds.

## License

Apache License 2.0. See [LICENSE](LICENSE).
