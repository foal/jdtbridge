# @kaluchi/jdtbridge

CLI for [Eclipse JDT Bridge](../README.md) — semantic Java analysis, refactoring, testing, and diagnostics via a running Eclipse instance.

## Install

```bash
cd cli
npm install
npm link
```

This registers two global commands: `jdt` (short) and `jdtbridge` (long).

## Prerequisites

- Node.js >= 20
- Java, Maven (for `jdt setup` — building the plugin from source)
- Eclipse IDE

## Plugin setup

Install or update the Eclipse plugin in one command:

```bash
jdt setup                 # build from source (mvn verify) and install into Eclipse
jdt setup --check         # show status of all components
jdt setup --skip-build    # install last build without rebuilding
jdt setup --clean         # clean build (mvn clean verify)
jdt setup --remove        # uninstall the plugin from Eclipse
jdt setup --eclipse D:/eclipse  # specify Eclipse path (saved to config)
```

If Eclipse is running, you will be prompted to stop it. After installation, Eclipse is restarted automatically with the same workspace.

Once installed, Eclipse must be running with the plugin for all other commands to work.

## Usage

```bash
jdt --help                  # command overview
jdt help <command>          # detailed usage for a command
```

### Search & navigation

```bash
jdt projects
jdt project-info my-app --lines 100
jdt find *Controller* --source-only
jdt references com.example.dao.UserDao getUser
jdt references com.example.dao.UserDao --field cache
jdt subtypes com.example.core.HasId
jdt hierarchy com.example.app.MainEntry
jdt implementors com.example.core.HasId getId
jdt type-info com.example.dto.UserDto
jdt source com.example.dao.UserDao save --arity 2
```

### Testing

```bash
jdt test com.example.utils.ObjectMapperTest
jdt test com.example.utils.ObjectMapperTest testSerialize
jdt test --project my-app --package com.example.utils
```

### Diagnostics

```bash
jdt errors --project my-app
jdt errors --file my-app/src/main/java/.../Foo.java
jdt errors --project my-app --clean
jdt errors --project my-app --all --warnings
```

### Refactoring

```bash
jdt organize-imports my-app/src/main/java/.../Foo.java
jdt format my-app/src/main/java/.../Foo.java
jdt rename com.example.dto.Foo Bar
jdt rename com.example.dto.Foo getBar --method getFoo
jdt move com.example.dto.Foo com.example.dto.shared
```

### Editor

```bash
jdt active-editor
jdt open com.example.dao.UserDao getUser
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
