package qouteall.dimlib;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.dimlib.api.DimensionAPI;
import qouteall.dimlib.mixin.client.IClientPacketListener;

public class DimLibClientNetworking {
    private static final Logger LOGGER = LoggerFactory.getLogger(DimLibClientNetworking.class);
    
    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(
            DimLibNetworking.DimSyncPacket.TYPE,
            (p, c) -> {
                // it's now handled in client thread, not networking thread
                handle(p, c.client().getConnection());
            }
        );
    }
    
    private static void handle(
        DimLibNetworking.DimSyncPacket packet, ClientGamePacketListener listener
    ) {
        Validate.isTrue(
            Minecraft.getInstance().isSameThread(),
            "Not running in client thread"
        );
        
        LOGGER.info(
            "Client received dimension info\n{}",
            String.join("\n", packet.dimIdToTypeIdTag().keySet())
        );
        
        var dimIdToDimType = packet.toMap();
        ClientDimensionInfo.accept(dimIdToDimType);
        ((IClientPacketListener) listener).dimlib_setLevels(dimIdToDimType.keySet());
        
        DimensionAPI.CLIENT_DIMENSION_UPDATE_EVENT.invoker().run(
            ClientDimensionInfo.getDimensionIds()
        );
    }
}
