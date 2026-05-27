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

public record ProxyConnectPayload(String targetServerName) implements CustomPacketPayload {

    private static final String SUBCHANNEL_CONNECT = "Connect";

    public static final CustomPacketPayload.@NonNull Type<ProxyConnectPayload> TYPE = createType();

    public static final @NonNull StreamCodec<FriendlyByteBuf, ProxyConnectPayload> STREAM_CODEC = createCodec();

    public ProxyConnectPayload {
        targetServerName = Objects.requireNonNull(targetServerName, "targetServerName");
        if (targetServerName.isBlank()) {
            throw new IllegalArgumentException("Target proxy server name cannot be blank");
        }
    }

    private ProxyConnectPayload(FriendlyByteBuf buffer) {
        this(readTargetServerName(buffer));
    }

    @SuppressWarnings("null")
    private void write(FriendlyByteBuf buffer) {
        byte[] payloadBytes = encodeConnectPayload(targetServerName);
        buffer.writeBytes(payloadBytes);
    }

    @Override
    public CustomPacketPayload.@NonNull Type<ProxyConnectPayload> type() {
        return TYPE;
    }

    private static String readTargetServerName(FriendlyByteBuf buffer) {
        int readable = buffer.readableBytes();
        if (readable <= 0) {
            throw new IllegalArgumentException("Missing proxy connect payload bytes");
        }

        byte[] payloadBytes = new byte[readable];
        buffer.readBytes(payloadBytes);

        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payloadBytes))) {
            String subchannel = input.readUTF();
            if (!SUBCHANNEL_CONNECT.equals(subchannel)) {
                throw new IllegalArgumentException("Unsupported proxy subchannel: " + subchannel);
            }

            String target = input.readUTF();
            if (target.isBlank()) {
                throw new IllegalArgumentException("Target proxy server name cannot be blank");
            }
            return target;
        } catch (IOException ioException) {
            throw new IllegalArgumentException("Failed to decode proxy connect payload", ioException);
        }
    }

    private static byte[] encodeConnectPayload(String targetServerName) {
        Objects.requireNonNull(targetServerName, "targetServerName");
        ByteArrayOutputStream byteOutput = new ByteArrayOutputStream();
        try (DataOutputStream dataOutput = new DataOutputStream(byteOutput)) {
            dataOutput.writeUTF(SUBCHANNEL_CONNECT);
            dataOutput.writeUTF(targetServerName);
            dataOutput.flush();
            return byteOutput.toByteArray();
        } catch (IOException ioException) {
            throw new IllegalStateException("Failed to encode proxy connect payload", ioException);
        }
    }

    private static CustomPacketPayload.@NonNull Type<ProxyConnectPayload> createType() {
        return new CustomPacketPayload.Type<>(Identifier.parse("bungeecord:main"));
    }

    private static @NonNull StreamCodec<FriendlyByteBuf, ProxyConnectPayload> createCodec() {
        return CustomPacketPayload.codec(ProxyConnectPayload::write, ProxyConnectPayload::new);
    }
}
