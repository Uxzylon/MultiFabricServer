package fr.jeanney.mixin;

import fr.jeanney.MultiFabricServer;
import fr.jeanney.compat.DynmapCompat;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Pseudo
@Mixin(targets = "org.dynmap.fabric_26_1.DynmapPlugin", remap = false)
public abstract class DynmapCompatMixins {

    @Inject(method = "serverStart", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void multifabricserver$skipDynmapServerStartOnChild(MinecraftServer server, CallbackInfo callbackInfo) {
        if (isClusterChildServer(server)) {
            callbackInfo.cancel();
        }
    }

    @Inject(method = "serverStarted", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void multifabricserver$skipDynmapServerStartedOnChild(MinecraftServer server, CallbackInfo callbackInfo) {
        if (isClusterChildServer(server)) {
            callbackInfo.cancel();
        }
    }

    @Inject(method = "serverStarted", at = @At("TAIL"), remap = false, require = 0)
    private void multifabricserver$pruneOrphanedClusterWorlds(MinecraftServer server, CallbackInfo callbackInfo) {
        if (!isClusterChildServer(server)) {
            DynmapCompat.pruneOrphanedClusterWorlds(this, server);
        }
    }

    @Inject(method = "serverStop", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void multifabricserver$skipDynmapServerStopOnChild(MinecraftServer server, CallbackInfo callbackInfo) {
        if (isClusterChildServer(server)) {
            callbackInfo.cancel();
        }
    }

    private static boolean isClusterChildServer(MinecraftServer server) {
        return server != null && MultiFabricServer.clusterController().isClusterChildServerForRuntime(server);
    }
}

@Pseudo
@Mixin(targets = "org.dynmap.fabric_26_1.FabricServer", remap = false)
abstract class DynmapFabricServerClusterPlayersMixin {

    @Shadow
    private MinecraftServer server;

    @Inject(method = "tickEvent(Lnet/minecraft/server/MinecraftServer;)V", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void multifabricserver$skipDynmapTickOnChild(MinecraftServer tickingServer, CallbackInfo callbackInfo) {
        if (MultiFabricServer.clusterController().isClusterChildServerForRuntime(tickingServer)) {
            callbackInfo.cancel();
        }
    }

    @Redirect(method = "getOnlinePlayers", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/players/PlayerList;getPlayers()Ljava/util/List;"), remap = false, require = 0)
    private List<ServerPlayer> multifabricserver$includeClusterPlayersForDynmap(PlayerList playerList) {
        List<ServerPlayer> aggregatedPlayers = MultiFabricServer.clusterController().dynmapOnlinePlayers(server);
        if (aggregatedPlayers.isEmpty()) {
            return playerList.getPlayers();
        }
        return aggregatedPlayers;
    }
}

@Pseudo
@Mixin(targets = "org.dynmap.fabric_26_1.FabricWorld", remap = false)
abstract class DynmapFabricWorldClusterNamingMixin {

    @Inject(method = "getWorldName", at = @At("RETURN"), cancellable = true, remap = false, require = 0)
    private static void multifabricserver$namespaceChildWorldName(@Coerce Object plugin,
            ServerLevel level,
            CallbackInfoReturnable<String> callbackInfoReturnable) {
        String resolved = resolveWorldName(level, callbackInfoReturnable.getReturnValue());
        callbackInfoReturnable.setReturnValue(resolved);
    }

    @Inject(method = "<init>(Lorg/dynmap/fabric_26_1/DynmapPlugin;Lnet/minecraft/server/level/ServerLevel;)V", at = @At("TAIL"), remap = false, require = 0)
    private void multifabricserver$renameChildWorldTitle(@Coerce Object plugin, ServerLevel level,
            CallbackInfo callbackInfo) {
        String title = resolveWorldTitle(level, null);
        if (title == null || title.isBlank()) {
            return;
        }
        invokeSetTitle(this, title);
    }

    private static void invokeSetTitle(Object worldInstance, String title) {
        try {
            Method setTitle = worldInstance.getClass().getMethod("setTitle", String.class);
            setTitle.invoke(worldInstance, title);
        } catch (Exception ignored) {
        }
    }

    private static String resolveWorldName(ServerLevel level, String fallbackName) {
        String nodeId = resolveClusterNodeId(level);
        if (nodeId == null || nodeId.isBlank()) {
            return fallbackName;
        }

        ResourceKey<Level> dimensionKey = level.dimension();
        if (dimensionKey == Level.OVERWORLD) {
            return nodeId;
        }
        if (dimensionKey == Level.NETHER) {
            return nodeId + "_nether";
        }
        if (dimensionKey == Level.END) {
            return nodeId + "_the_end";
        }

        Identifier identifier = dimensionKey.identifier();
        String suffix = sanitizeSegment(identifier.getNamespace() + "_" + identifier.getPath());
        if (suffix.isBlank()) {
            return nodeId + "_dimension";
        }
        return nodeId + "__" + suffix;
    }

    private static String resolveWorldTitle(ServerLevel level, String fallbackTitle) {
        String nodeId = resolveClusterNodeId(level);
        if (nodeId == null || nodeId.isBlank()) {
            return fallbackTitle;
        }

        ResourceKey<Level> dimensionKey = level.dimension();
        if (dimensionKey == Level.OVERWORLD) {
            return nodeId + " (overworld)";
        }
        if (dimensionKey == Level.NETHER) {
            return nodeId + " (nether)";
        }
        if (dimensionKey == Level.END) {
            return nodeId + " (end)";
        }

        Identifier identifier = dimensionKey.identifier();
        return nodeId + " (" + identifier.getNamespace() + ":" + identifier.getPath() + ")";
    }

    private static String resolveClusterNodeId(ServerLevel level) {
        if (level == null) {
            return null;
        }

        MinecraftServer server = level.getServer();
        if (server == null || !MultiFabricServer.clusterController().isClusterChildServerForRuntime(server)) {
            return null;
        }

        Path rootPath;
        try {
            rootPath = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        } catch (Exception exception) {
            return null;
        }

        List<String> segments = new ArrayList<>();
        for (Path segment : rootPath) {
            segments.add(segment.toString());
        }

        for (int index = 0; index < segments.size() - 1; index++) {
            if ("cluster-runtime".equals(segments.get(index))) {
                String candidate = sanitizeSegment(segments.get(index + 1));
                return candidate.isBlank() ? null : candidate;
            }
        }

        return null;
    }

    private static String sanitizeSegment(String value) {
        if (value == null) {
            return "";
        }

        String lower = value.toLowerCase(Locale.ROOT);
        StringBuilder sanitized = new StringBuilder(lower.length());
        boolean lastWasUnderscore = false;
        for (int index = 0; index < lower.length(); index++) {
            char ch = lower.charAt(index);
            boolean allowed = (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9');
            if (allowed) {
                sanitized.append(ch);
                lastWasUnderscore = false;
                continue;
            }

            if (!lastWasUnderscore) {
                sanitized.append('_');
                lastWasUnderscore = true;
            }
        }

        while (sanitized.length() > 0 && sanitized.charAt(0) == '_') {
            sanitized.deleteCharAt(0);
        }
        while (sanitized.length() > 0 && sanitized.charAt(sanitized.length() - 1) == '_') {
            sanitized.deleteCharAt(sanitized.length() - 1);
        }

        return sanitized.toString();
    }
}

@Pseudo
@Mixin(targets = "org.dynmap.fabric_26_1.DynmapPlugin$PlayerTracker", remap = false)
abstract class DynmapPlayerTrackerTravelLogoutSuppressMixin {

    @Inject(method = "onPlayerLogout", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void multifabricserver$suppressTravelLogout(ServerPlayer player, CallbackInfo callbackInfo) {
        if (MultiFabricServer.clusterController().consumeDynmapLogoutSuppression(player)) {
            callbackInfo.cancel();
        }
    }
}
