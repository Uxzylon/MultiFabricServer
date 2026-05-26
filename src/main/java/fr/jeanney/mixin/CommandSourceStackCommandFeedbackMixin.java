package fr.jeanney.mixin;

import fr.jeanney.MultiFabricServer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.util.function.Supplier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CommandSourceStack.class)
public abstract class CommandSourceStackCommandFeedbackMixin {

    @Inject(method = "sendSuccess", at = @At("TAIL"))
    private void multifabricserver$relayCrossClusterCommandFeedback(Supplier<Component> messageSupplier,
            boolean broadcast,
            CallbackInfo ci) {
        if (!broadcast || messageSupplier == null) {
            return;
        }

        CommandSourceStack source = (CommandSourceStack) (Object) this;
        ServerPlayer sourcePlayer = source.getPlayer();
        if (sourcePlayer == null) {
            return;
        }

        MinecraftServer sourceServer = source.getServer();
        if (sourceServer == null || sourceServer.isStopped()) {
            return;
        }

        Component feedback;
        try {
            feedback = messageSupplier.get();
        } catch (RuntimeException exception) {
            return;
        }

        if (feedback == null) {
            return;
        }

        MultiFabricServer.clusterController().relayCommandFeedback(sourceServer, sourcePlayer, feedback);
    }
}
