package fr.jeanney.mixin;

import fr.jeanney.MultiFabricServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;

@Pseudo
@Mixin(targets = "com.hypherionmc.sdlink.server.ServerEvents", remap = false)
public abstract class SDLinkTravelJoinLeaveSuppressionMixin {

    @Inject(method = "playerJoinEvent", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void multifabricserver$suppressInternalTravelJoinLifecycle(@Coerce Object event,
            CallbackInfo callbackInfo) {
        suppressWhenInternalTravel(event, callbackInfo);
    }

    @Inject(method = "playerLeaveEvent", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void multifabricserver$suppressInternalTravelLeaveLifecycle(@Coerce Object event,
            CallbackInfo callbackInfo) {
        suppressWhenInternalTravel(event, callbackInfo);
    }

    private static void suppressWhenInternalTravel(Object event, CallbackInfo callbackInfo) {
        String playerUuid = resolvePlayerUuid(event);
        if (playerUuid == null || playerUuid.isBlank()) {
            return;
        }

        if (MultiFabricServer.clusterController().consumeExternalJoinLeaveSuppression(playerUuid)) {
            callbackInfo.cancel();
        }
    }

    private static String resolvePlayerUuid(Object craterEvent) {
        if (craterEvent == null) {
            return null;
        }

        Object player = invokeNoArg(craterEvent, "getPlayer");
        if (player == null) {
            return null;
        }

        Object stringUuid = invokeNoArg(player, "getStringUUID");
        if (stringUuid instanceof String uuidString && !uuidString.isBlank()) {
            return uuidString;
        }

        Object uuidObject = invokeNoArg(player, "getUUID");
        if (uuidObject == null) {
            return null;
        }

        String uuid = uuidObject.toString();
        return uuid == null || uuid.isBlank() ? null : uuid;
    }

    private static Object invokeNoArg(Object instance, String methodName) {
        try {
            Method method = instance.getClass().getMethod(methodName);
            return method.invoke(instance);
        } catch (Exception ignored) {
            return null;
        }
    }
}
