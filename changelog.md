Update to Minecraft 26.3.

- Requires Java 25, Fabric Loader 0.19.5+.
- Build migrated to the non-remapping Fabric Loom (unobfuscated Minecraft, no mappings).
- Adapted to 26.3 API changes: `ServerLevel` constructor, world seed access, `MinecraftServer.pollTask` access, Fabric networking API renames.
- Restore the permission requirement of `/dims` commands (game master level); it was lost in the previous port.
- Fix `ConcurrentModificationException` when a queued dimension task schedules another task.
- Commands return success value; unify logging to slf4j; remove stale code.
