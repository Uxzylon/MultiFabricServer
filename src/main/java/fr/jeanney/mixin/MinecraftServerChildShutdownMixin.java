package fr.jeanney.mixin;

import fr.jeanney.MultiFabricServer;
import fr.jeanney.cluster.ClusterServerIdentity;
import java.io.IOException;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;

import org.jspecify.annotations.NonNull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public abstract class MinecraftServerChildShutdownMixin {

    @Redirect(method = "stopServer", at = @At(value = "INVOKE", target = "Ljava/util/stream/Stream;anyMatch(Ljava/util/function/Predicate;)Z"))
    private boolean multifabricserver$skipChildStopServerChunkWorkLoop(Stream<?> stream, Predicate<Object> predicate) {
        MinecraftServer server = (MinecraftServer) (Object) this;
        if (MultiFabricServer.clusterController().shouldSkipChunkWorkLoopForServerStop(server)) {
            MultiFabricServer.LOGGER.debug(
                    "Suppressing child chunk-work wait loop server={}",
                    ClusterServerIdentity.describe(server));
            return false;
        }
        return stream.anyMatch(predicate);
    }

    @Inject(method = "stopServer", at = @At("HEAD"), cancellable = true)
    private void multifabricserver$shortCircuitChildStopServer(CallbackInfo ci) {
        MinecraftServer server = (MinecraftServer) (Object) this;
        if (MultiFabricServer.clusterController().shouldShortCircuitChildStopServer(server)) {
            // During host shutdown, child stopServer can still schedule chunk/light tasks
            // after executors start shutting down, causing repeated rejected-task loops.
            // For cluster children in this shutdown mode, skip stopServer internals
            // entirely.
            ci.cancel();
        }
    }

    @Redirect(method = "stopServer", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;saveAllChunks(ZZZ)Z"))
    private boolean multifabricserver$skipChildSaveAllChunks(
            MinecraftServer server,
            boolean silent,
            boolean flush,
            boolean force) {
        if (shouldSkipPersistence(server)) {
            return true;
        }
        return server.saveAllChunks(silent, flush, force);
    }

    @Redirect(method = "stopServer", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerChunkCache;deactivateTicketsOnClosing()V"))
    private void multifabricserver$skipChildDeactivateTickets(ServerChunkCache chunkCache) {
        MinecraftServer server = (MinecraftServer) (Object) this;
        if (shouldSkipPersistence(server)) {
            return;
        }
        chunkCache.deactivateTicketsOnClosing();
    }

    @Redirect(method = "stopServer", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerChunkCache;tick(Ljava/util/function/BooleanSupplier;Z)V"))
    private void multifabricserver$skipChildChunkTick(ServerChunkCache chunkCache, @NonNull BooleanSupplier haveTime,
            boolean tickChunks) {
        MinecraftServer server = (MinecraftServer) (Object) this;
        if (shouldSkipPersistence(server)) {
            return;
        }
        chunkCache.tick(haveTime, tickChunks);
    }

    @Redirect(method = "stopServer", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;close()V"))
    private void multifabricserver$skipChildLevelClose(ServerLevel level) throws IOException {
        MinecraftServer server = (MinecraftServer) (Object) this;
        if (shouldSkipPersistence(server)) {
            return;
        }
        level.close();
    }

    @Unique
    private boolean shouldSkipPersistence(MinecraftServer server) {
        boolean shouldSkip = MultiFabricServer.clusterController().shouldSkipPersistenceForServerStop(server);
        if (shouldSkip) {
            MultiFabricServer.LOGGER.debug(
                    "Suppressing child persistence during host shutdown server={}",
                    ClusterServerIdentity.describe(server));
        }
        return shouldSkip;
    }
}
