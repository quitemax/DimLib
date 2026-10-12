# DimLib

This is a Fabric mod that can:
* Add and remove dimensions when the server is running or when the server is initializing.
* Synchronize dimension info to client when dimension changes if the client has the mod.
* Allow suppressing the "Worlds using Experimental Settings are not supported" warning, through config or code.

For the dynamic dimension functionality, this mod is required on server but not required on client. If the client does not have the mod, the command completion of dimension id will not update if dimension changes.

If you only want the functionality of disabling the warning, this mod is only required on client.

All APIs are in `DimensionAPI` class. You can refer to the javadoc.

### Supported versions

This branch (`26.3`) targets **Minecraft 26.3** (Fabric Loader >= 0.19.5, Fabric API, Java 25). Minecraft 26.x is unobfuscated, so the project builds with the non-remapping Loom plugin (`net.fabricmc.fabric-loom`) and no mappings. Other Minecraft versions live in their own branches.

### Building and running

```
./gradlew build       # jar is in build/libs
./gradlew runClient   # dev client
./gradlew runServer   # dev server (accept the EULA in run/eula.txt first)
```

### Commands

#### `/dims add_dimension <newDimensionId> <preset>`

Dynamically add a new dimension based on a preset.

Example: `/dims add_dimension "aaa:ccc" void`

Currently, the only built-in preset is `void`. Other mods can add presets via the API.

#### `/dims clone_dimension <templateDimension> <newDimensionID>`

Dynamically add a new dimension. That new dimension's dimension type and chunk generator will be the same as the `templateDimension`.

This command only clones the dimension type and world generator. It will not clone the things in world (blocks, entities, ...).

Example: `/dims clone_dimension minecraft:overworld "aaa:bbb"` will dynamically add dimension `aaa:bbb` whose world generation is the same as the overworld.

#### `/dims remove_dimension <dimension>`

Dynamically remove a dimension.

This command will not delete the world saving of that dimension.

#### `/dims view_dim_config <dimension>`

Show the dimension config of a dimension. It includes the dimension type id and chunk generator config.

### Configure Dependency

Add this into `repositories`
```
maven { url 'https://jitpack.io' }
```

Add this into `dependencies`

For Minecraft 26.x (unobfuscated, no remapping):
```
implementation("com.github.iPortalTeam:DimLib:${dimlib_version}") {
    exclude(group: "net.fabricmc.fabric-api")
}
```

For older obfuscated versions:
```
modApi("com.github.iPortalTeam:DimLib:${dimlib_version}") {
    exclude(group: "net.fabricmc.fabric-api")
}
```
