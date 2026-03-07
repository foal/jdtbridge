# @kaluchi/jdtbridge

CLI for [Eclipse JDT Bridge](../README.md) — semantic Java analysis, refactoring, testing, and diagnostics via a running Eclipse instance.

## Install

```bash
npm install -g @kaluchi/jdtbridge
```

Provides two aliases: `jdt` (short) and `jdtbridge` (long).

## Prerequisites

- Eclipse running with the `io.github.kaluchi.jdtbridge` plugin installed
- Node.js >= 20

## Usage

```bash
jdt --help                  # command overview
jdt help <command>          # detailed usage for a command
```

### Search & navigation

```bash
jdt projects
jdt project-info m8-server --lines 100
jdt find *Controller* --source-only
jdt references app.m8.dao.StaffDaoImpl getStaff
jdt references app.m8.dao.StaffDaoImpl --field staffCache
jdt subtypes app.m8.web.shared.core.HasId
jdt hierarchy app.m8.web.client.AGMEntryPoint
jdt implementors app.m8.web.shared.core.HasId getId
jdt type-info app.m8.dto.web.core.IdOrgRoot
jdt source app.m8.dao.StaffDaoImpl save --arity 2
```

### Testing

```bash
jdt test app.m8ws.utils.ObjectMapperTest
jdt test app.m8ws.utils.ObjectMapperTest testSerialize
jdt test --project m8-server --package app.m8ws.utils
```

### Diagnostics

```bash
jdt errors --project m8-server
jdt errors --file m8-server/src/main/java/.../Foo.java
jdt errors --project m8-server --clean
jdt errors --project m8-server --all --warnings
```

### Refactoring

```bash
jdt organize-imports m8-server/src/main/java/.../Foo.java
jdt format m8-server/src/main/java/.../Foo.java
jdt rename app.m8.dto.Foo Bar
jdt rename app.m8.dto.Foo getBar --method getFoo
jdt move app.m8.dto.Foo app.m8.dto.shared
```

### Editor

```bash
jdt active-editor
jdt open app.m8.dao.StaffDaoImpl getStaff
```

## Instance discovery

The CLI reads instance files from `~/.jdtbridge/instances/`. Each running Eclipse writes a JSON file there with port, auth token, PID, and workspace path. The CLI checks PID liveness to filter out stale instances.

Override the home directory with `JDTBRIDGE_HOME` environment variable.

When multiple Eclipse instances are running, use `--workspace <hint>` or the CLI picks the first live instance.

## Color output

Colored output is auto-detected based on TTY. Override with:

- `--color` / `--no-color` flags
- `FORCE_COLOR=1` / `NO_COLOR=1` environment variables
- `JDTBRIDGE_COLOR=1` environment variable

## Development

```bash
cd cli
npm install
npm test              # run tests
npm run test:watch    # watch mode
```

## License

Apache License 2.0
