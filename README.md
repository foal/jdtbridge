# JDT Bridge

Eclipse's JDT compiler builds a deep semantic index of your Java code — type hierarchies, cross-project references, method resolution, import management. JDT Bridge makes all of that available from the terminal via the `jdt` CLI.

Built for AI coding assistants (Claude Code, Cursor, etc.) and developers who want IDE-grade Java intelligence outside the IDE.

## What you can do

```bash
# Find all implementations of an interface method across your workspace
jdt implementors com.example.core.HasId getId

# Rename a method — updates every reference in every project
jdt rename com.example.dao.UserDao getUser --method findUser

# Run a single test method and see failures inline
jdt test com.example.service.UserServiceTest testCreate

# Get compilation errors after editing outside Eclipse
jdt errors --project my-app

# Read source code by name — project code and library code alike
jdt source com.example.dao.UserDao save --arity 2
```

## Getting started

Prerequisites: Node.js >= 20, Java, Maven, Eclipse IDE.

```bash
git clone https://github.com/kaluchi/jdtbridge.git
cd jdtbridge/cli
npm install
npm link          # registers global `jdt` and `jdtbridge` commands
jdt setup         # builds plugin, installs into Eclipse
```

`jdt setup` handles everything: Maven build with tests, stops Eclipse if running, installs the plugin via p2, restarts Eclipse with the same workspace.

After pulling updates, run `jdt setup` again.

## Commands

Most commands have short aliases for quick typing.

| Command | Alias | What it does |
|---------|-------|-------------|
| `projects` | | List workspace projects |
| `project-info <name>` | `pi` | Project overview (packages, types, methods) |
| `find <Name>` | | Find type declarations (supports `*wildcards*`) |
| `references <FQN> [method]` | `refs` | All references to a type, method, or field |
| `subtypes <FQN>` | `subt` | All subtypes and implementors |
| `hierarchy <FQN>` | `hier` | Full type hierarchy (supers + subs) |
| `implementors <FQN> <method>` | `impl` | Implementations of an interface method |
| `type-info <FQN>` | `ti` | Class overview (fields, methods, signatures) |
| `source <FQN> [method]` | `src` | Source code — project and library classes |
| `test <FQN> [method]` | | Run JUnit tests |
| `errors [--project <name>]` | `err` | Compilation errors and diagnostics |
| `organize-imports <file>` | `oi` | Organize imports (Eclipse settings) |
| `format <file>` | `fmt` | Format code (Eclipse settings) |
| `rename <FQN> <new>` | | Rename type, method, or field across workspace |
| `move <FQN> <package>` | | Move type to another package |
| `open <FQN> [method]` | | Open in Eclipse editor |
| `active-editor` | `ae` | Current file and cursor position |

Run `jdt help <command>` for detailed flags and options.

## How it works

```
  Terminal / AI agent              Eclipse IDE
+--------------------+         +--------------------+
|                    |  HTTP   |                    |
|   jdt CLI          | ------> |   JDT Bridge       |
|   (Node.js)        | JSON    |   plugin           |
|                    | <------ |   (JDT SearchEngine)|
+--------------------+         +--------------------+
        |                               |
   auto-discovers              writes instance file
        |                               |
        +--- ~/.jdtbridge/instances/ ---+
```

The Eclipse plugin starts a loopback HTTP server on a random port at startup. The CLI auto-discovers running instances via `~/.jdtbridge/instances/` files and routes commands to the right Eclipse.

Multiple Eclipse instances are supported — each writes its own instance file, keyed by workspace path.

**Security**: loopback-only binding (`127.0.0.1`), per-session random token, POSIX `rwx------` permissions on instance files.

## Documentation

- **[CLI reference](cli/README.md)** — all commands, flags, options, instance discovery
- **[HTTP API](plugin/README.md)** — raw endpoint reference for building custom clients

## Known limitations

- **Workspace must be fully built.** JDT search relies on the Eclipse index. If the workspace hasn't been fully indexed, results may be incomplete. Use `jdt errors --build` or `--clean` to trigger builds.

## License

Apache License 2.0. See [LICENSE](LICENSE).
