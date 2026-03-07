# eclipse-jdt-search

Eclipse plugin that exposes JDT SearchEngine via HTTP on `localhost:7891`. Designed for use by Claude Code (or any other agent/tool) to perform semantic Java code analysis -- find usages, type hierarchy, locate declarations -- without relying on text-based grep.

Unlike grep, JDT search understands Java semantics: it resolves types across projects, follows inheritance, and distinguishes declarations from references.

## Architecture

```
+------------------+         HTTP/JSON          +-------------------+
|  Node.js client  |  ---- localhost:7891 ---->  |  Eclipse plugin   |
|  (jdt.mjs)       |                            |  (JDT SearchEngine|
+------------------+                            |   via raw socket) |
                                                +-------------------+
```

**Server side** (this project): An Eclipse plugin (`eclipse-plugin` packaging, built with Tycho). On activation, `Activator` starts a minimal HTTP server on a daemon thread using a raw `ServerSocket`. The server parses GET requests, dispatches to JDT SearchEngine APIs, and returns JSON.

**Client side**: A standalone Node.js script (`jdt.mjs`) that sends HTTP requests and formats the output as `file:line` entries. Lives in the consumer project (e.g., `m8/.claude/scripts/jdt.mjs`).

## HTTP API

All endpoints accept GET requests and return JSON. The server binds to `localhost:7891`.

### `GET /ping`

Health check. Returns workspace project list.

```
GET /ping

{"ok":true,"port":7891,"projects":["m8-server","m8-shared","m8-client",...]}
```

### `GET /find?name=<SimpleName>`

Find type declarations by simple (unqualified) name. Returns all matching types across all workspace projects.

```
GET /find?name=GetOrgByOrg

[
  {"fqn":"app.m8.dao.proc.org.GetOrgByOrg","file":"/m8-server/src/main/java/app/m8/dao/proc/org/GetOrgByOrg.java"}
]
```

### `GET /references?class=<FQN>[&method=<name>]`

Find all references to a type or method. If `method` is omitted, finds references to the type itself.

```
GET /references?class=app.m8.dao.proc.org.GetOrgByOrg&method=execute

[
  {"file":"/m8-server/src/main/java/app/m8/web/servlet/gwt/orgstructure/SomeHandler.java","line":42},
  {"file":"/m8-server/src/main/java/app/m8/service/OrgService.java","line":118}
]
```

### `GET /subtypes?class=<FQN>`

Find all subtypes (subclasses and implementors) of a type, recursively.

```
GET /subtypes?class=app.m8.web.shared.core.structure.OrgStructure

[
  {"fqn":"app.m8.web.shared.core.structure.OrgStructureDetailed","file":"/m8-shared/src/main/java/..."}
]
```

### `GET /errors?[file=<path>][&project=<name>][&refresh][&build][&clean][&warnings]`

Compilation diagnostics. By default returns only errors (not warnings).

Parameters:
- `file` — workspace-relative path to filter by specific file
- `project` — project name to filter by
- `refresh` — refresh from disk + wait for auto-build (use after external file edits)
- `build` — trigger explicit incremental build
- `clean` — clean + full rebuild (requires `project`)
- `warnings` — include warnings (default: errors only)

```
GET /errors?project=m8-server&refresh

[
  {"file":"/m8-server/src/main/java/app/m8/dao/StaffDaoImpl.java","line":42,"severity":"ERROR","message":"Cannot resolve symbol 'foo'"}
]
```

## Client usage (jdt.mjs)

```bash
node .claude/scripts/jdt.mjs ping
node .claude/scripts/jdt.mjs find GetOrgByOrg
node .claude/scripts/jdt.mjs references app.m8.dao.proc.org.GetOrgByOrg execute
node .claude/scripts/jdt.mjs subtypes app.m8.web.shared.core.structure.OrgStructure
node .claude/scripts/jdt.mjs errors --refresh
node .claude/scripts/jdt.mjs errors --project m8-server --clean
node .claude/scripts/jdt.mjs errors --project m8-server --refresh --warnings
```

Output for `references` is `file:line` (one per line). Output for `find` and `subtypes` is `fqn  file` (one per line). Output for `errors` is `SEVERITY file:line  message` (one per line).

## Build

Requires Maven and a local Eclipse installation (the target platform points to `D:\eclipse`).

```bash
cd D:/git/eclipse-jdt-search
mvn package
```

The built JAR is at:
```
plugin/target/app.m8.eclipse.jdtsearch-1.0.0-SNAPSHOT.jar
```

### Target platform

The file `eclipse-jdt-search.target` points to a local Eclipse directory (`D:\eclipse`). If your Eclipse is installed elsewhere, update the `<location path="...">` in that file.

## Deploy

**Important: do NOT use Eclipse `dropins/` folder -- it does not work for this plugin** (lazy activation + JDT dependencies are not resolved correctly via dropins).

### Steps

1. Copy the built JAR to your Eclipse `plugins/` directory:

   ```bash
   cp plugin/target/app.m8.eclipse.jdtsearch-1.0.0-SNAPSHOT.jar D:/eclipse/plugins/
   ```

2. Add an entry to `bundles.info` so Eclipse loads it:

   ```bash
   echo 'app.m8.eclipse.jdtsearch,1.0.0.qualifier,plugins/app.m8.eclipse.jdtsearch-1.0.0-SNAPSHOT.jar,4,false' \
     >> D:/eclipse/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info
   ```

   The format is: `symbolicName,version,location,startLevel,autoStart`

   **auto-start must be `false`** (the `4,false` part). Setting it to `true` causes the bundle to activate before the workspace is ready, resulting in workspace-not-ready errors from JDT APIs. With `false`, the bundle uses lazy activation (`Bundle-ActivationPolicy: lazy`) and the `org.eclipse.ui.startup` extension point triggers `earlyStartup()` at the right time.

3. Restart Eclipse.

4. Verify: `curl http://localhost:7891/ping` (or `node jdt.mjs ping`).

## Updating after changes

After modifying the plugin source and rebuilding:

```bash
mvn package
cp plugin/target/app.m8.eclipse.jdtsearch-1.0.0-SNAPSHOT.jar D:/eclipse/plugins/
```

No need to edit `bundles.info` again -- just overwrite the JAR and restart Eclipse.

## Project structure

```
eclipse-jdt-search/
  pom.xml                              # Parent POM (Tycho reactor)
  eclipse-jdt-search.target            # Target platform (local Eclipse dir)
  plugin/
    pom.xml                            # Plugin module (eclipse-plugin packaging)
    META-INF/MANIFEST.MF               # OSGi manifest
    plugin.xml                         # Extension: org.eclipse.ui.startup
    build.properties
    src/app/m8/eclipse/jdtsearch/
      Activator.java                   # Bundle activator - starts HTTP server
      HttpServer.java                  # Raw-socket HTTP server + routing
      SearchHandler.java               # /find, /references, /subtypes (JDT search)
      DiagnosticsHandler.java          # /errors (refresh, build, markers)
      StartupHandler.java              # IStartup impl - forces early activation
```

## Known limitations

- **Method overload disambiguation is not supported.** When searching for method references with `/references?class=FQN&method=name`, the server picks the first method matching the name. If the class has overloaded methods with the same name, there is no way to specify which overload you want (e.g., by parameter types). This is adequate for most cases since overloaded methods usually share the same semantic purpose.

- **Workspace must be fully built.** JDT search relies on the Eclipse index. If the workspace has build errors or has not been fully indexed, results may be incomplete.

- **Single Eclipse instance.** The server binds to a fixed port (7891). Only one Eclipse instance can run the plugin at a time.
