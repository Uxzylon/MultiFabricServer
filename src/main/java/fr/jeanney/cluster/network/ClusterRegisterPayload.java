package fr.jeanney.cluster.network;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Objects;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;

public record ClusterRegisterPayload(String proxyServerName, String backendHost, int backendPort)
        implements CustomPacketPayload {

    private static final String MAGIC = "MultiFabricServerRegister";
    public static final String CHANNEL = "multifabricserver:cluster_register";

    public static final CustomPacketPayload.@NonNull Type<ClusterRegisterPayload> TYPE = createType();

    public static final @NonNull StreamCodec<FriendlyByteBuf, ClusterRegisterPayload> STREAM_CODEC = createCodec();

    public ClusterRegisterPayload {
        proxyServerName = Objects.requireNonNull(proxyServerName, "proxyServerName");
        backendHost = Objects.requireNonNull(backendHost, "backendHost");
        if (proxyServerName.isBlank()) {
            throw new IllegalArgumentException("Proxy server name cannot be blank");
        }
        if (backendHost.isBlank()) {
            throw new IllegalArgumentException("Backend host cannot be blank");
        }
        if (backendPort <= 0 || backendPort > 65535) {
            throw new IllegalArgumentException("Backend port is out of range: " + backendPort);
        }
    }

    private ClusterRegisterPayload(FriendlyByteBuf buffer) {
        this(read(buffer));
    }

    private ClusterRegisterPayload(Decoded decoded) {
        this(decoded.proxyServerName(), decoded.backendHost(), decoded.backendPort());
    }

    @SuppressWarnings("null")
    private void write(FriendlyByteBuf buffer) {
        byte[] payloadBytes = encode(proxyServerName, backendHost, backendPort);
        buffer.writeBytes(payloadBytes);
    }

    @Override
    public CustomPacketPayload.@NonNull Type<ClusterRegisterPayload> type() {
        return TYPE;
    }

    private static Decoded read(FriendlyByteBuf buffer) {
        int readable = buffer.readableBytes();
        if (readable <= 0) {
            throw new IllegalArgumentException("Missing cluster registration payload bytes");
        }

        byte[] payloadBytes = new byte[readable];
        buffer.readBytes(payloadBytes);

        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payloadBytes))) {
            String magic = input.readUTF();
            if (!MAGIC.equals(magic)) {
                throw new IllegalArgumentException("Unsupported cluster registration payload: " + magic);
            }

            String proxyServerName = input.readUTF();
            String backendHost = input.readUTF();
            int backendPort = input.readUnsignedShort();
            return new Decoded(proxyServerName, backendHost, backendPort);
        } catch (IOException ioException) {
            throw new IllegalArgumentException("Failed to decode cluster registration payload", ioException);
        }
    }

    private static byte[] encode(String proxyServerName, String backendHost, int backendPort) {
        Objects.requireNonNull(proxyServerName, "proxyServerName");
        Objects.requireNonNull(backendHost, "backendHost");
        ByteArrayOutputStream byteOutput = new ByteArrayOutputStream();
        try (DataOutputStream dataOutput = new DataOutputStream(byteOutput)) {
            dataOutput.writeUTF(MAGIC);
            dataOutput.writeUTF(proxyServerName);
            dataOutput.writeUTF(backendHost);
            dataOutput.writeShort(backendPort);
            dataOutput.flush();
            return byteOutput.toByteArray();
        } catch (IOException ioException) {
            throw new IllegalStateException("Failed to encode cluster registration payload", ioException);
        }
    }

    private record Decoded(String proxyServerName, String backendHost, int backendPort) {
        private Decoded {
            proxyServerName = Objects.requireNonNull(proxyServerName, "proxyServerName");
            backendHost = Objects.requireNonNull(backendHost, "backendHost");
        }
    }

    private static CustomPacketPayload.@NonNull Type<ClusterRegisterPayload> createType() {
        return new CustomPacketPayload.Type<>(Identifier.parse(CHANNEL));
    }

    private static @NonNull StreamCodec<FriendlyByteBuf, ClusterRegisterPayload> createCodec() {
        return CustomPacketPayload.codec(ClusterRegisterPayload::write, ClusterRegisterPayload::new);
    }
}
