package fr.jeanney.mixin;

import fr.jeanney.MultiFabricServer;
import net.minecraft.server.dedicated.DedicatedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(DedicatedServer.class)
public abstract class DedicatedServerChildConsoleInputMixin {

    @Redirect(method = "initServer", at = @At(value = "INVOKE", target = "Ljava/lang/Thread;start()V", ordinal = 0))
    private void multifabricserver$skipChildConsoleReaderThreadStart(Thread consoleThread) {
        DedicatedServer server = (DedicatedServer) (Object) this;
        if (MultiFabricServer.clusterController().isClusterChildServerForRuntime(server)) {
            return;
        }

        consoleThread.start();
    }
}
