package dev.waterdog.waterdogpe.network.protocol.codec;

import io.netty.buffer.ByteBuf;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.v860_netease.serializer.ContainerOpenSerializer_v860_NetEase;
import org.cloudburstmc.protocol.bedrock.packet.ContainerOpenPacket;

/**
 * NetEase container open serialization for 1.21.130: two strings and a boolean follow the
 * {@code ignoreBlock} field of the 1.21.124 layout.
 */
public class ContainerOpenSerializer_v898_NetEase extends ContainerOpenSerializer_v860_NetEase {

    /**
     * Shared instance.
     */
    public static final ContainerOpenSerializer_v898_NetEase INSTANCE = new ContainerOpenSerializer_v898_NetEase();

    @Override
    public void serialize(ByteBuf buffer, BedrockCodecHelper helper, ContainerOpenPacket packet) {
        super.serialize(buffer, helper, packet);
        helper.writeString(buffer, "");
        helper.writeString(buffer, "");
        buffer.writeBoolean(false);
    }

    @Override
    public void deserialize(ByteBuf buffer, BedrockCodecHelper helper, ContainerOpenPacket packet) {
        super.deserialize(buffer, helper, packet);
        if (!buffer.isReadable()) {
            return;
        }

        int readerIndex = buffer.readerIndex();
        try {
            helper.readString(buffer);
            helper.readString(buffer);
            buffer.readBoolean();
        } catch (RuntimeException e) {
            buffer.readerIndex(readerIndex);
        }
    }
}
