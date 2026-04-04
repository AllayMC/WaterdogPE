package dev.waterdog.waterdogpe.network.protocol;

import org.cloudburstmc.protocol.bedrock.codec.v944.Bedrock_v944;
import org.cloudburstmc.protocol.bedrock.packet.PyRpcPacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ProtocolCodecsTest {

    @Test
    void buildCodecRegistersPyRpcPacketForStandardBedrockCodecs() {
        var codec = ProtocolCodecs.buildCodec(Bedrock_v944.CODEC);
        var definition = codec.getPacketDefinition(PyRpcPacket.class);

        assertNotNull(definition);
        assertEquals(200, definition.id());
    }
}
