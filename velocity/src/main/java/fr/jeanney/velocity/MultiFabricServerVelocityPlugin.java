package fr.jeanney.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.ServerInfo;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Optional;

@Plugin(
        id = "multifabricserver",
        name = "MultiFabricServer",
        version = "1.0.0",
        description = "Dynamic Velocity backend registration for MultiFabricServer clusters",
        authors = {"Uxzylon"})
public final class MultiFabricServerVelocityPlugin {

    private static final MinecraftChannelIdentifier REGISTER_CHANNEL = MinecraftChannelIdentifier.from(
            "multifabricserver:cluster_register");
    private static final String MAGIC = "MultiFabricServerRegister";

    private final ProxyServer proxyServer;
    private final Logger logger;

    @Inject
    public MultiFabricServerVelocityPlugin(ProxyServer proxyServer, Logger logger) {
        this.proxyServer = proxyServer;
        this.logger = logger;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        proxyServer.getChannelRegistrar().register(REGISTER_CHANNEL);
        logger.info("MultiFabricServer dynamic cluster registration channel enabled");
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!REGISTER_CHANNEL.equals(event.getIdentifier())) {
            return;
        }

        event.setResult(PluginMessageEvent.ForwardResult.handled());

        if (!(event.getSource() instanceof ServerConnection serverConnection)) {
            logger.warn("Ignoring cluster registration from non-server source: {}", event.getSource());
            return;
        }

        Optional<ClusterRegistration> registration = decodeRegistration(event.getData());
        if (registration.isEmpty()) {
            logger.warn("Ignoring invalid cluster registration from {}", serverConnection.getServerInfo().getName());
            return;
        }

        ClusterRegistration target = registration.get();
        if (!isSafeServerName(target.serverName())) {
            logger.warn("Ignoring cluster registration with unsafe server name '{}'", target.serverName());
            return;
        }

        ServerInfo serverInfo = new ServerInfo(
                target.serverName(),
                new InetSocketAddress(target.backendHost(), target.backendPort()));

        Optional<ServerInfo> existingInfo = proxyServer.getServer(target.serverName())
                .map(existing -> existing.getServerInfo());
        if (existingInfo.isPresent() && existingInfo.get().getAddress().equals(serverInfo.getAddress())) {
            logger.debug(
                    "Dynamic cluster backend '{}' is already registered at {}:{}",
                    target.serverName(),
                    target.backendHost(),
                    target.backendPort());
            return;
        }

        existingInfo.ifPresent(existing -> proxyServer.unregisterServer(existing));

        proxyServer.registerServer(serverInfo);
        logger.info(
                "Registered dynamic cluster backend '{}' at {}:{} from {}",
                target.serverName(),
                target.backendHost(),
                target.backendPort(),
                serverConnection.getServerInfo().getName());
    }

    private static Optional<ClusterRegistration> decodeRegistration(byte[] data) {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(data))) {
            String magic = input.readUTF();
            if (!MAGIC.equals(magic)) {
                return Optional.empty();
            }

            String serverName = input.readUTF();
            String backendHost = input.readUTF();
            int backendPort = input.readUnsignedShort();
            if (serverName.isBlank() || backendHost.isBlank() || backendPort <= 0) {
                return Optional.empty();
            }
            return Optional.of(new ClusterRegistration(serverName, backendHost, backendPort));
        } catch (IOException ignored) {
            return Optional.empty();
        }
    }

    private static boolean isSafeServerName(String serverName) {
        return serverName.length() <= 64 && serverName.matches("[A-Za-z0-9_.-]+");
    }

    private record ClusterRegistration(String serverName, String backendHost, int backendPort) {
    }
}
