# JDT Bridge

Eclipse plugin that exposes JDT SearchEngine, refactoring, diagnostics, and test runner over HTTP for use by AI coding assistants and CLI tools.

Unlike grep, JDT search understands Java semantics: it resolves types across projects, follows inheritance, and distinguishes declarations from references.

## Architecture

```
+------------------+       HTTP/JSON (loopback)     +-------------------+
|  Node.js client  |  ---- localhost:<random> ---->  |  Eclipse plugin   |
|  (jdt.mjs)       |                                |  (JDT SearchEngine|
+------------------+                                |   via raw socket) |
                                                    +-------------------+
```

**Server side** (this project): An Eclipse plugin (`eclipse-plugin` packaging, built with Tycho). On activation, `Activator` starts a minimal HTTP server on a random port, bound to loopback only. Connection details (port, auth token, PID, workspace) are written to `~/.jdtbridge`.

**Client side**: A standalone Node.js script (`jdt.mjs`) that reads `~/.jdtbridge`, sends authenticated HTTP requests, and formats the output. Lives in the consumer project (e.g., `m8/.claude/scripts/jdt.mjs`).

**Security**: The server binds to `127.0.0.1` only (not reachable from the network). Every request requires a `Bearer <token>` header. The token is a random 32-hex-char string generated on each Eclipse startup.

## HTTP API

All endpoints accept GET requests with query parameters. The server returns JSON (`application/json`) unless noted otherwise.

### Search & Navigation

#### `GET /projects`

List all Java projects in the workspace.

```json
["m8-server","m8-shared","m8-client"]
```

#### `GET /project-info?project=<name>[&members-threshold=N]`

Project overview with adaptive detail. Returns source roots, packages, types, and optionally method signatures (included when `totalTypes <= members-threshold`, default 200).

#### `GET /find?name=<Name>[&source]`

Find type declarations by name. Supports wildcards (`*Controller*`, `Find*`). Add `&source` to exclude binary/library types.

```json
[
  {"fqn":"app.m8.dao.proc.org.GetOrgByOrg","file":"/m8-server/src/main/java/..."}
]
```

#### `GET /references?class=<FQN>[&method=<name>][&field=<name>][&arity=<n>]`

Find all references to a type, method, or field. Filters out inaccurate matches and javadoc references. Returns file, line, enclosing member, and source line content.

```json
[
  {"file":"/m8-server/src/.../OrgService.java","line":118,"project":"m8-server",
   "in":"app.m8.service.OrgService.loadOrg(Long)","content":"GetOrgByOrg proc = new GetOrgByOrg();"}
]
```

#### `GET /subtypes?class=<FQN>`

Find all direct and indirect subtypes/implementors of a type.

#### `GET /hierarchy?class=<FQN>`

Full type hierarchy: superclass chain, all super interfaces, and all subtypes.

```json
{"supers":[...],"interfaces":[...],"subtypes":[...]}
```

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

```json
{"total":5,"passed":4,"failed":1,"errors":0,"ignored":0,"time":2.3,
 "failures":[{"class":"com.example.FooTest","method":"testBar","status":"FAIL","trace":"..."}]}
```

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

## Client usage (jdt.mjs)

The client script reads `~/.jdtbridge` for connection details. Run `jdt.mjs --help` for the full command list, or `jdt.mjs help <command>` for detailed usage of any command.

```bash
# Search & navigation
node jdt.mjs projects
node jdt.mjs project-info m8-server --lines 100
node jdt.mjs find *Controller* --source-only
node jdt.mjs references app.m8.dao.StaffDaoImpl getStaff
node jdt.mjs subtypes app.m8.web.shared.core.HasId
node jdt.mjs hierarchy app.m8.web.client.AGMEntryPoint
node jdt.mjs implementors app.m8.web.shared.core.HasId getId
node jdt.mjs type-info app.m8.dto.web.core.IdOrgRoot
node jdt.mjs source app.m8.dao.StaffDaoImpl save --arity 2

# Testing
node jdt.mjs test app.m8ws.utils.ObjectMapperTest
node jdt.mjs test app.m8ws.utils.ObjectMapperTest testSerialize
node jdt.mjs test --project m8-server --package app.m8ws.utils

# Diagnostics
node jdt.mjs errors --project m8-server
node jdt.mjs errors --file m8-server/src/main/java/.../Foo.java
node jdt.mjs errors --project m8-server --clean
node jdt.mjs errors --project m8-server --all --warnings

# Refactoring
node jdt.mjs organize-imports m8-server/src/main/java/.../Foo.java
node jdt.mjs format m8-server/src/main/java/.../Foo.java
node jdt.mjs rename app.m8.dto.Foo Bar
node jdt.mjs rename app.m8.dto.Foo getBar --method getFoo
node jdt.mjs move app.m8.dto.Foo app.m8.dto.shared

# Editor
node jdt.mjs active-editor
node jdt.mjs open app.m8.dao.StaffDaoImpl getStaff
```

## Build

Requires Maven and a Tycho target platform pointing to a local Eclipse installation.

```bash
cd eclipse-jdt-search
mvn clean verify
```

The p2 update site is built at `site/target/repository/`.

### Target platform

The file `eclipse-jdt-search.target` points to a local Eclipse directory (default `D:\eclipse`). If your Eclipse is installed elsewhere, update the `<location path="...">` in that file.

## Deploy

### Option 1: Update site (recommended)

After `mvn clean verify`, use **Help > Install New Software** in Eclipse, point to the `site/target/repository/` directory, and install the "JDT Bridge" feature.

### Option 2: Manual JAR install

1. Copy the built JAR to your Eclipse `plugins/` directory:

   ```bash
   cp plugin/target/io.github.kaluchi.jdtbridge-1.0.0-SNAPSHOT.jar /path/to/eclipse/plugins/
   ```

2. Add an entry to `bundles.info`:

   ```bash
   echo 'io.github.kaluchi.jdtbridge,1.0.0.qualifier,plugins/io.github.kaluchi.jdtbridge-1.0.0-SNAPSHOT.jar,4,false' \
     >> /path/to/eclipse/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info
   ```

   **auto-start must be `false`** (the `4,false` part). The bundle uses lazy activation (`Bundle-ActivationPolicy: lazy`) and the `org.eclipse.ui.startup` extension triggers activation at the right time.

3. Restart Eclipse.

4. Verify: check `~/.jdtbridge` exists, then `node jdt.mjs projects`.

## Updating after changes

```bash
mvn clean verify
cp plugin/target/io.github.kaluchi.jdtbridge-1.0.0-SNAPSHOT.jar /path/to/eclipse/plugins/
# Restart Eclipse
```

## Project structure

```
eclipse-jdt-search/
  pom.xml                              # Parent POM (Tycho reactor)
  LICENSE                              # Apache License 2.0
  eclipse-jdt-search.target            # Target platform definition
  plugin/
    pom.xml                            # Plugin module (eclipse-plugin packaging)
    META-INF/MANIFEST.MF               # OSGi manifest
    plugin.xml                         # Extension: org.eclipse.ui.startup
    build.properties
    src/io/github/kaluchi/jdtbridge/
      Activator.java                   # Bundle activator — starts HTTP server, writes ~/.jdtbridge
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
    feature.xml                        # Eclipse feature for p2 update site
  site/
    pom.xml                            # P2 repository (eclipse-repository packaging)
    category.xml                       # Update site category
```

## Known limitations

- **Workspace must be fully built.** JDT search relies on the Eclipse index. If the workspace has not been fully indexed, results may be incomplete. Use `jdt errors --build` or `--clean` to trigger builds.

- **Single Eclipse instance.** Only one Eclipse instance should run the plugin at a time (they would overwrite each other's `~/.jdtbridge`).

## License

Apache License 2.0. See [LICENSE](LICENSE).
