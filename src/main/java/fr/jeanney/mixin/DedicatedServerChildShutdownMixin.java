package fr.jeanney.mixin;

import fr.jeanney.MultiFabricServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(DedicatedServer.class)
public abstract class DedicatedServerChildShutdownMixin {

    @Redirect(method = "stopServer", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Util;shutdownExecutors()V"))
    private void multifabricserver$skipGlobalExecutorShutdownForClusterChild() {
        DedicatedServer server = (DedicatedServer) (Object) this;
        if (MultiFabricServer.clusterController().shouldSkipGlobalExecutorShutdownForServerStop(server)) {
            MultiFabricServer.LOGGER.info(
                    "[cluster-stop-debug] suppressing global executor shutdown for child server={}",
                    server.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(server)));
            return;
        }
        Util.shutdownExecutors();
    }
}
