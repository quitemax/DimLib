package qouteall.dimlib;

import com.google.common.collect.ImmutableList;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientCommonPacketListener;
import net.minecraft.network.protocol.game.*;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.border.BorderChangeListener;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.level.storage.WorldData;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.jetbrains.annotations.NotNull;
import qouteall.dimlib.api.DimensionAPI;
import qouteall.dimlib.ducks.IMappedRegistry;
import qouteall.dimlib.ducks.IMinecraftServer;
import qouteall.dimlib.mixin.common.IEWorldBorder;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;

public class DynamicDimensionsImpl {
    private static final Logger LOGGER = LoggerFactory.getLogger(DynamicDimensionsImpl.class);
    
    public static boolean isRemovingDimension = false;
    
    public static void init() {

    }

    public static class DelegateBorderChangeListener implements BorderChangeListener {
        private final WorldBorder worldBorder;

        public DelegateBorderChangeListener(WorldBorder worldBorder) {
            this.worldBorder = worldBorder;
        }

        @Override
        public void onSetSize(WorldBorder worldBorder, double d) {
            this.worldBorder.setSize(d);
        }

        @Override
        public void onLerpSize(WorldBorder worldBorder, double d, double e, long l, long m) {
            this.worldBorder.lerpSizeBetween(d, e, l, m);
        }

        @Override
        public void onSetCenter(WorldBorder worldBorder, double d, double e) {
            this.worldBorder.setCenter(d, e);
        }

        @Override
        public void onSetWarningTime(WorldBorder worldBorder, int i) {
            this.worldBorder.setWarningTime(i);
        }

        @Override
        public void onSetWarningBlocks(WorldBorder worldBorder, int i) {
            this.worldBorder.setWarningBlocks(i);
        }

        @Override
        public void onSetDamagePerBlock(WorldBorder worldBorder, double d) {
            this.worldBorder.setDamagePerBlock(d);
        }

        @Override
        public void onSetSafeZone(WorldBorder worldBorder, double d) {
            this.worldBorder.setSafeZone(d);
        }
    }
    
    public static void addDimensionDynamically(
        MinecraftServer server,
        Identifier dimensionId,
        LevelStem levelStem
    ) {
        ResourceKey<Level> dimensionResourceKey = ResourceKey.create(
            Registries.DIMENSION, dimensionId
        );
        
        Validate.isTrue(server.isSameThread(), "this should be called in server main thread");
        Validate.isTrue(server.isRunning(), "Server is not running");
        
        if (server.getLevel(dimensionResourceKey) != null) {
            throw new RuntimeException("Dimension " + dimensionId + " already exists.");
        }
        
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        Validate.notNull(overworld, "Overworld is null");
        WorldBorder worldBorder = overworld.getWorldBorder();
        Validate.notNull(worldBorder, "Overworld world border is null");
        
        WorldData worldData = server.getWorldData();
        ServerLevelData serverLevelData = worldData.overworldData();
        
        long seed = server.getWorldGenSettings().options().seed();
        long obfuscatedSeed = BiomeManager.obfuscateSeed(seed);
        
        DerivedLevelData derivedLevelData = new DerivedLevelData(
            worldData, serverLevelData
        );
        
        ServerLevel newWorld = new ServerLevel(
            server,
            ((IMinecraftServer) server).dimlib_getExecutor(),
            ((IMinecraftServer) server).dimlib_getStorageSource(),
            derivedLevelData,
            dimensionResourceKey,
            levelStem,
            false, // isDebug
            obfuscatedSeed,
            ImmutableList.of(),
            false // only true for overworld
        );
        
        worldBorder.addListener(
            new DelegateBorderChangeListener(newWorld.getWorldBorder())
        );

        ((IMinecraftServer) server).dimlib_addDimensionToWorldMap(dimensionResourceKey, newWorld);
        
        // register it into registry, so it will be saved into level.dat
        Registry<LevelStem> levelStemRegistry = server.registryAccess().lookupOrThrow(Registries.LEVEL_STEM);
        ((IMappedRegistry) levelStemRegistry).dimlib_setIsFrozen(false);
        ((MappedRegistry<LevelStem>) levelStemRegistry).register(
            ResourceKey.create(Registries.LEVEL_STEM, dimensionId),
            levelStem,
            RegistrationInfo.BUILT_IN // use built-in registration info for now
        );
        ((IMappedRegistry) levelStemRegistry).dimlib_setIsFrozen(true);

        LOGGER.info("Added Dimension {}", dimensionId);
        
        var dimSyncPacket = ServerPlayNetworking.createClientboundPacket(
            DimLibNetworking.DimSyncPacket.createPacket(server)
        );
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.connection.send(dimSyncPacket);
        }
        
        DimensionAPI.SERVER_DIMENSION_DYNAMIC_UPDATE_EVENT.invoker().run(server, server.levelKeys());
    }
    
    public static void removeDimensionDynamically(ServerLevel world) {
        MinecraftServer server = world.getServer();

        assert server != null;
        Validate.isTrue(server.isSameThread());
        
        ResourceKey<Level> dimension = world.dimension();
        
        if (dimension == Level.OVERWORLD || dimension == Level.NETHER || dimension == Level.END) {
            throw new RuntimeException("Cannot remove vanilla dimension");
        }
        
        Validate.isTrue(server.isRunning(), "Server is not running");
        
        LOGGER.info("Started Removing Dimension {}", dimension.identifier());
        
        ((IMinecraftServer) server).dimlib_addTask(() -> {
            DimensionAPI.SERVER_PRE_REMOVE_DIMENSION_EVENT.invoker().accept(world);
            
            evacuatePlayersFromDimension(world);
            
            /**{@link MinecraftServer#stopServer()}*/
            
            long startTime = System.nanoTime();
            long lastLogTime = System.nanoTime();
            
            isRemovingDimension = true;
            
            ((IMinecraftServer) server).dimlib_removeDimensionFromWorldMap(dimension);
            
            try {
                while (world.getChunkSource().chunkMap.hasWork()) {
                    world.getChunkSource().deactivateTicketsOnClosing();
                    world.getChunkSource().tick(() -> true, false);
                    world.getChunkSource().pollTask();
                    ((IMinecraftServer) server).dimlib_pollTask();
                    
                    if (System.nanoTime() - lastLogTime > DimLibUtil.secondToNano(1)) {
                        lastLogTime = System.nanoTime();
                        LOGGER.info("waiting for chunk tasks to finish");
                    }
                    
                    if (System.nanoTime() - startTime > DimLibUtil.secondToNano(15)) {
                        LOGGER.error("Waited too long for chunk tasks");
                        break;
                    }
                    
                    ((IMinecraftServer) server).dimlib_waitUntilNextTick();
                }
            }
            catch (Throwable e) {
                LOGGER.error("Error when waiting for chunk tasks", e);
            }
            
            isRemovingDimension = false;
            
            LOGGER.info(
                "Finished chunk tasks in {} seconds",
                DimLibUtil.nanoToSecond(System.nanoTime() - startTime)
            );
            
            LOGGER.info(
                "Chunk num: {}     Has entities: {}",
                world.getChunkSource().chunkMap.size(),
                world.getAllEntities().iterator().hasNext()
            );
            
            server.saveAllChunks(false, true, false);
            
            try {
                world.close();
            }
            catch (IOException e) {
                LOGGER.error("Error when closing world", e);
            }
            
            resetWorldBorderListener(server);
            
            // force remove it from registry, so it will not be saved into level.dat
            Registry<LevelStem> levelStemRegistry = server.registryAccess()
                .lookupOrThrow(Registries.LEVEL_STEM);
            ((IMappedRegistry) levelStemRegistry).dimlib_forceRemove(dimension.identifier());
            
            LOGGER.info("Removed Dimension {}", dimension.identifier());
            
            Packet<@NotNull ClientCommonPacketListener> dimSyncPacket = ServerPlayNetworking.createClientboundPacket(
                DimLibNetworking.DimSyncPacket.createPacket(server)
            );
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                player.connection.send(dimSyncPacket);
            }
            
            DimensionAPI.SERVER_DIMENSION_DYNAMIC_UPDATE_EVENT.invoker().run(server, server.levelKeys());
        });
    }
    
    private static void resetWorldBorderListener(MinecraftServer server) {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        Validate.notNull(overworld, "Overworld is null");
        
        WorldBorder worldBorder = overworld.getWorldBorder();
        List<BorderChangeListener> borderChangeListeners =
            ((IEWorldBorder) worldBorder).dimlib_getListeners();
        borderChangeListeners.clear();
        for (ServerLevel serverWorld : server.getAllLevels()) {
            if (serverWorld != overworld) {
                worldBorder.addListener(
                    new DelegateBorderChangeListener(serverWorld.getWorldBorder())
                );
            }
        }
        server.getPlayerList().addWorldborderListener(overworld);
    }
    
    private static void evacuatePlayersFromDimension(ServerLevel world) {
        MinecraftServer server = world.getServer();
        assert server != null;
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        Validate.notNull(overworld, "Overworld is null");
        
        List<ServerPlayer> players = world.getPlayers(p -> true);
        
        BlockPos sharedSpawnPos = overworld.getRespawnData().pos();
        
        for (ServerPlayer player : players) {
            player.teleportTo(
                overworld,
                sharedSpawnPos.getX(), sharedSpawnPos.getY(), sharedSpawnPos.getZ(),
                new HashSet<>(),
                0, 0, true
            );
            player.sendSystemMessage(
                Component.literal(
                    "Teleported to spawn pos because dimension %s had been removed"
                        .formatted(world.dimension().identifier())
                )
            );
        }
    }
}
