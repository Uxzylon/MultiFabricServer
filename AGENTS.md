# MultiFabricServer Agent Guide

MultiFabricServer is a Fabric server-side mod that runs lazy-loaded cluster servers as separate `MinecraftServer` instances in one JVM. The repository also builds a Velocity companion plugin for seamless proxy transfers.

## Local Instructions

If `AGENTS.local.md` exists at the repository root, read it after this file and apply it as additional environment-specific guidance. Do not assume it exists or require it in other checkouts.

## Environment

- Use the checked-in Gradle wrapper.
- The Fabric mod targets Java 25; the Velocity plugin targets Java 17.
- Minecraft, Fabric, Velocity, and project versions are defined in `gradle.properties`.
- Do not install dependencies globally or replace the wrapper/toolchain without a task requiring it.

## Commands

Fast compile checks:

```bash
./gradlew compileJava
./gradlew :velocity:compileJava
./gradlew compileGametestJava
```

GameTests:

```bash
./gradlew runGameTest
```

Full clean build of both the Fabric mod and Velocity plugin:

```bash
./gradlew clean build
```

For changes to cluster lifecycle, travel, player aggregation, chat, compatibility, or shutdown behavior, update the GameTests and run both `runGameTest` and `clean build` before finishing.

## Project Map

- `src/main/java/fr/jeanney/cluster/`: cluster configuration, lifecycle, travel, state, player aggregation, and compatibility logic.
- `src/main/java/fr/jeanney/cluster/command/`: `/cluster` command tree and suggestions.
- `src/main/java/fr/jeanney/cluster/network/`: Fabric/Velocity payloads.
- `src/main/java/fr/jeanney/cluster/api/`: public lifecycle and chat integration events.
- `src/main/java/fr/jeanney/mixin/`: narrowly scoped Minecraft and optional-mod mixins.
- `src/main/resources/data/multifabricserver/lang/`: bundled player-facing translations.
- `src/gametest/`: Fabric GameTests; prefer extending these for regressions.
- `velocity/`: Java 17 Velocity companion plugin.

## Architecture Invariants

- Host and child servers remain distinct. Never aggregate `MinecraftServer.getAllLevels()` or make the host tick, save, or stop child worlds.
- Run server-affine work on the owning server thread. Lifecycle and broadcast code must tolerate a child stopping concurrently.
- Use `ClusterServerRegistry` immutable snapshots for cross-server discovery; do not expose mutable runtime collections.
- Shared player lists and broadcasts must include each player exactly once. Preserve signed chat objects and propagation guards.
- Keep Dynmap optional and version-gated. No Dynmap classes may load when the mod is absent.
- Keep player-facing text in language JSON files. Console logs are fixed developer/operator messages and are not localized.
- Preserve lazy loading, player affinity, host fallback, and safe player evacuation during stop, disable, and removal.

## Change Guidelines

- Follow existing Java naming, nullness, and helper patterns; keep classes focused and below 1,000 lines.
- Prefer small, typed APIs over reflection, duplicated compatibility paths, or mutable global state.
- Add comments only where concurrency or protocol behavior is not evident from the code.
- Preserve unrelated working-tree changes.
- Do not commit, push, publish releases, rewrite history, or perform destructive Git operations unless explicitly requested.
