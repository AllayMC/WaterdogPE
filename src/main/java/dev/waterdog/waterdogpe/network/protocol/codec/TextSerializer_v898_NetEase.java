package dev.waterdog.waterdogpe.network.protocol.codec;

import io.netty.buffer.ByteBuf;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.v898.serializer.TextSerializer_v898;
import org.cloudburstmc.protocol.bedrock.packet.TextPacket;

/** NetEase text serialization for 1.21.130: an extra string follows chat and popup messages. */
public class TextSerializer_v898_NetEase extends TextSerializer_v898 {

    /**
     * Shared instance.
     */
    public static final TextSerializer_v898_NetEase INSTANCE = new TextSerializer_v898_NetEase();

    @Override
    public void serialize(ByteBuf buffer, BedrockCodecHelper helper, TextPacket packet) {
        super.serialize(buffer, helper, packet);
        if (packet.getType() == TextPacket.Type.CHAT || packet.getType() == TextPacket.Type.POPUP) {
            helper.writeString(buffer, packet.getUnknown());
        }
    }

    @Override
    public void deserialize(ByteBuf buffer, BedrockCodecHelper helper, TextPacket packet) {
        super.deserialize(buffer, helper, packet);
        if (packet.getType() == TextPacket.Type.CHAT || packet.getType() == TextPacket.Type.POPUP) {
            packet.setUnknown(helper.readString(buffer));
        }
    }
}
