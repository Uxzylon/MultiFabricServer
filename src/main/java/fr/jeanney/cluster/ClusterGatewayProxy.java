package fr.jeanney.cluster;

import fr.jeanney.MultiFabricServer;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.server.MinecraftServer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public final class ClusterGatewayProxy {

    private static final int CONNECT_TIMEOUT_MILLIS = 3000;

    private volatile boolean running;
    private ServerSocket serverSocket;
    private Thread acceptThread;

    public synchronized void onServerStarted(MinecraftServer server, IntegratedClusterController controller) {
        if (!controller.isOwnerServer(server)) {
            return;
        }

        if (server instanceof GameTestServer) {
            return;
        }

        if (!controller.isGatewayEnabled()) {
            stopInternal("gateway-disabled");
            return;
        }

        if (running) {
            return;
        }

        try {
            String bindHost = controller.gatewayBindHost();
            int bindPort = controller.gatewayBindPort();

            ServerSocket socket = new ServerSocket();
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(bindHost, bindPort));

            serverSocket = socket;
            running = true;
            acceptThread = Thread.startVirtualThread(() -> acceptLoop(controller));

            MultiFabricServer.LOGGER.info("Cluster in-mod gateway started on {}:{}", bindHost, bindPort);
        } catch (IOException ioException) {
            MultiFabricServer.LOGGER.error(
                    "Failed to start cluster in-mod gateway on {}:{}",
                    controller.gatewayBindHost(),
                    controller.gatewayBindPort(),
                    ioException);
        }
    }

    public synchronized void onServerStopped(MinecraftServer server, IntegratedClusterController controller) {
        if (!controller.isOwnerServer(server)) {
            return;
        }
        stopInternal("owner-stopped");
    }

    private void acceptLoop(IntegratedClusterController controller) {
        while (running) {
            Socket client;
            try {
                client = serverSocket.accept();
            } catch (IOException ioException) {
                if (running) {
                    MultiFabricServer.LOGGER.error("Gateway accept loop failed", ioException);
                }
                break;
            }

            Thread.startVirtualThread(() -> handleClient(client, controller));
        }
    }

    private void handleClient(Socket clientSocket, IntegratedClusterController controller) {
        try (Socket client = clientSocket) {
            client.setTcpNoDelay(true);

            InputStream clientIn = client.getInputStream();
            OutputStream clientOut = client.getOutputStream();

            Handshake handshake = readHandshake(clientIn);
            if (handshake == null) {
                return;
            }

            FramedPacket loginHelloPacket = null;
            if (handshake.intention() == 2) {
                loginHelloPacket = readFramedPacket(clientIn);
            }

            String playerUuid = loginHelloPacket == null ? null : extractLoginProfileUuid(loginHelloPacket.payload());

            Optional<IntegratedClusterController.GatewayRoute> routeOptional = controller
                    .resolveGatewayRoute(handshake.requestHost(), playerUuid);
            if (routeOptional.isEmpty()) {
                return;
            }

            IntegratedClusterController.GatewayRoute route = routeOptional.get();
            try (Socket backend = new Socket()) {
                SocketAddress backendAddress = new InetSocketAddress(route.backendHost(), route.backendPort());
                backend.connect(backendAddress, CONNECT_TIMEOUT_MILLIS);
                backend.setTcpNoDelay(true);

                InputStream backendIn = backend.getInputStream();
                OutputStream backendOut = backend.getOutputStream();

                backendOut.write(handshake.rawPacket());
                if (loginHelloPacket != null) {
                    backendOut.write(loginHelloPacket.rawPacket());
                }
                backendOut.flush();

                Thread uplink = Thread.startVirtualThread(() -> pump(clientIn, backendOut));
                Thread downlink = Thread.startVirtualThread(() -> pump(backendIn, clientOut));

                uplink.join();
                downlink.join();
            }
        } catch (Exception exception) {
            MultiFabricServer.LOGGER.debug("Gateway connection terminated", exception);
        }
    }

    private static void pump(InputStream in, OutputStream out) {
        byte[] buffer = new byte[8192];
        try {
            while (true) {
                int read = in.read(buffer);
                if (read < 0) {
                    break;
                }
                out.write(buffer, 0, read);
                out.flush();
            }
        } catch (IOException ignored) {
        }
    }

    private Handshake readHandshake(InputStream inputStream) throws IOException {
        FramedPacket framedPacket = readFramedPacket(inputStream);
        if (framedPacket == null) {
            return null;
        }

        ByteArrayInputStream packetStream = new ByteArrayInputStream(framedPacket.payload());
        int packetId = readVarInt(packetStream, null);
        if (packetId != 0) {
            return null;
        }

        readVarInt(packetStream, null); // protocol version
        String requestHost = readString(packetStream);

        // The port and intention are only needed to validate packet structure.
        int high = packetStream.read();
        int low = packetStream.read();
        if (high < 0 || low < 0) {
            return null;
        }
        int intention = readVarInt(packetStream, null);

        return new Handshake(requestHost, intention, framedPacket.rawPacket());
    }

    private FramedPacket readFramedPacket(InputStream inputStream) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        int packetLength = readVarInt(inputStream, raw);
        if (packetLength <= 0 || packetLength > (1 << 21)) {
            return null;
        }

        byte[] payload = inputStream.readNBytes(packetLength);
        if (payload.length != packetLength) {
            return null;
        }
        raw.write(payload);
        return new FramedPacket(raw.toByteArray(), payload);
    }

    private static String extractLoginProfileUuid(byte[] loginPayload) {
        try {
            ByteArrayInputStream packetStream = new ByteArrayInputStream(loginPayload);
            int packetId = readVarInt(packetStream, null);
            if (packetId != 0) {
                return null;
            }

            readString(packetStream); // player name

            byte[] uuidBytes = packetStream.readNBytes(16);
            if (uuidBytes.length < 16) {
                return null;
            }

            ByteBuffer buffer = ByteBuffer.wrap(uuidBytes);
            UUID uuid = new UUID(buffer.getLong(), buffer.getLong());
            if (uuid.getMostSignificantBits() == 0L && uuid.getLeastSignificantBits() == 0L) {
                return null;
            }
            return uuid.toString().toLowerCase(Locale.ROOT);
        } catch (Exception exception) {
            return null;
        }
    }

    private static String readString(InputStream inputStream) throws IOException {
        int length = readVarInt(inputStream, null);
        if (length < 0 || length > 32767) {
            throw new IOException("Invalid handshake host length");
        }

        byte[] bytes = inputStream.readNBytes(length);
        if (bytes.length != length) {
            throw new IOException("Unexpected EOF while reading handshake host");
        }
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static int readVarInt(InputStream inputStream, ByteArrayOutputStream raw) throws IOException {
        int numRead = 0;
        int result = 0;
        byte read;

        do {
            int value = inputStream.read();
            if (value < 0) {
                throw new IOException("Unexpected EOF while reading varint");
            }

            read = (byte) value;
            if (raw != null) {
                raw.write(read);
            }

            int part = read & 0x7F;
            result |= part << (7 * numRead);

            numRead++;
            if (numRead > 5) {
                throw new IOException("VarInt too large");
            }
        } while ((read & 0x80) != 0);

        return result;
    }

    private synchronized void stopInternal(String reason) {
        if (!running) {
            return;
        }

        running = false;

        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
            serverSocket = null;
        }

        if (acceptThread != null) {
            acceptThread.interrupt();
            acceptThread = null;
        }

        MultiFabricServer.LOGGER.info("Cluster in-mod gateway stopped ({})", reason);
    }

    private record Handshake(String requestHost, int intention, byte[] rawPacket) {
    }

    private record FramedPacket(byte[] rawPacket, byte[] payload) {
    }
}
