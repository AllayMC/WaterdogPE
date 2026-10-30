/*
 * Copyright 2022 WaterdogTEAM
 * Licensed under the GNU General Public License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.waterdog.waterdogpe.network.connection.codec.packet;

import dev.waterdog.waterdogpe.network.NetworkMetrics;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageCodec;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import org.cloudburstmc.protocol.bedrock.PacketDirection;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.compat.BedrockCompat;
import org.cloudburstmc.protocol.bedrock.data.EncodingSettings;
import org.cloudburstmc.protocol.bedrock.data.PacketRecipient;
import org.cloudburstmc.protocol.bedrock.netty.BedrockBatchWrapper;
import org.cloudburstmc.protocol.bedrock.netty.BedrockPacketWrapper;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.UnknownPacket;

import java.util.List;

import static java.util.Objects.requireNonNull;

@Log4j2
public abstract class BedrockPacketCodec extends MessageToMessageCodec<BedrockBatchWrapper, BedrockBatchWrapper> {
    public static final String NAME = "bedrock-packet-codec";
    private static final java.util.concurrent.atomic.AtomicInteger decodeFailureLogs = new java.util.concurrent.atomic.AtomicInteger();

    @Getter
    private BedrockCodec codec = BedrockCompat.CODEC;
    @Getter
    private BedrockCodecHelper helper = codec.createHelper();

    private boolean alwaysDecode;
    private PacketRecipient inboundRecipient;

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        PacketDirection direction = ctx.channel().attr(PacketDirection.ATTRIBUTE).get();
        if (direction == PacketDirection.CLIENT_BOUND) {
            this.alwaysDecode = true; // packets from client can be always decoded
        }
        this.inboundRecipient = direction.getInbound();
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, BedrockBatchWrapper msg, List<Object> out) {
        if (msg.isModified() || msg.getUncompressed() == null) {
            int passedThrough = 0;
            int encodedPackets = 0;
            for (BedrockPacketWrapper packet : msg.getPackets()) {
                if (this.encode(ctx, packet)) {
                    passedThrough++;
                } else {
                    encodedPackets++;
                }
            }
            this.recordMetrics(ctx, passedThrough, encodedPackets);
        }
        out.add(msg.retain());
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, BedrockBatchWrapper msg, List<Object> out) {
        for (BedrockPacketWrapper packet : msg.getPackets()) {
            this.decode(ctx, packet);
        }
        out.add(msg.retain());
    }

    protected final boolean encode(ChannelHandlerContext ctx, BedrockPacketWrapper wrapper) {
        if (wrapper.getPacketBuffer() != null) {
            // We have a pre-encoded packet buffer, just use that.
            return true;
        }

        ByteBuf buf = ctx.alloc().buffer(128);
        try {
            BedrockPacket packet = wrapper.getPacket();
            wrapper.setPacketId(getPacketId(packet));
            this.encodeHeader(buf, wrapper);
            this.codec.tryEncode(helper, buf, packet);
            wrapper.setPacketBuffer(buf.retain());
        } catch (Throwable t) {
            log.error("Error encoding packet {}", wrapper.getPacket(), t);
        } finally {
            buf.release();
        }
        return false;
    }

    protected final void decode(ChannelHandlerContext ctx, BedrockPacketWrapper wrapper) {
        if (wrapper.getPacketBuffer() == null) {
            throw new IllegalStateException("Packet has no encoded buffer");
        }

        ByteBuf msg = wrapper.getPacketBuffer().retainedSlice();
        try {
            int index = msg.readerIndex();
            this.decodeHeader(msg, wrapper);
            wrapper.setHeaderLength(msg.readerIndex() - index);
            if (this.alwaysDecode) { // Otherwise, we are decoding at other place
                int payloadIndex = msg.readerIndex();
                BedrockPacket decoded = null;
                try {
                    decoded = this.codec.tryDecode(helper, msg, wrapper.getPacketId(), this.inboundRecipient);
                } catch (Throwable firstFailure) {
                    logDecodeFailure(wrapper.getPacketId(), this.inboundRecipient, firstFailure, "first");
                }
                if ((decoded == null || decoded instanceof UnknownPacket) && this.inboundRecipient != PacketRecipient.BOTH) {
                    // The relay hop can carry packets registered for the other recipient only
                    // (e.g. JigsawStructureDataPacket); retry before giving up.
                    msg.readerIndex(payloadIndex);
                    PacketRecipient other = this.inboundRecipient == PacketRecipient.CLIENT
                            ? PacketRecipient.SERVER
                            : PacketRecipient.CLIENT;
                    try {
                        decoded = this.codec.tryDecode(helper, msg, wrapper.getPacketId(), other);
                        if (decoded != null && !(decoded instanceof UnknownPacket)) {
                            log.info("[dec] id={} recovered with recipient={}", wrapper.getPacketId(), other);
                        } else {
                            logDecodeFailure(wrapper.getPacketId(), other, null, "both-unknown");
                        }
                    } catch (Throwable secondFailure) {
                        logDecodeFailure(wrapper.getPacketId(), other, secondFailure, "retry");
                    }
                }
                wrapper.setPacket(decoded);
            }
        } catch (Throwable t) {
            log.error("Failed to decode packet", t);
            throw t;
        } finally {
            msg.release();
        }
    }

    private static final java.util.Map<String, Integer> DECODE_FAILURES = new java.util.concurrent.ConcurrentHashMap<>();

    private static void logDecodeFailure(int id, PacketRecipient recipient, Throwable failure, String phase) {
        String key = id + "/" + phase;
        int count = DECODE_FAILURES.merge(key, 1, Integer::sum);
        if (count > 3) {
            return;
        }
        log.warn("[dec] id={} recipient={} phase={} failure={}", id, recipient, phase,
                failure == null ? "unknown-packet" : failure.getClass().getSimpleName() + ": " + failure.getMessage());
    }

    public abstract void encodeHeader(ByteBuf buf, BedrockPacketWrapper msg);

    public abstract void decodeHeader(ByteBuf buf, BedrockPacketWrapper msg);

    private void recordMetrics(ChannelHandlerContext ctx, int passedThought, int encodedPackets) {
        NetworkMetrics metrics = ctx.channel().attr(NetworkMetrics.ATTRIBUTE).get();
        if (metrics == null) {
            return;
        }

        PacketDirection direction = ctx.channel().attr(PacketDirection.ATTRIBUTE).get();
        if (passedThought > 0) {
            metrics.passedThroughPackets(passedThought, direction);
        }

        if (encodedPackets > 0) {
            metrics.encodedPackets(passedThought, direction);
        }
    }

    public final int getPacketId(BedrockPacket packet) {
        if (packet instanceof UnknownPacket) {
            return ((UnknownPacket) packet).getPacketId();
        }
        return this.codec.getPacketDefinition(packet.getClass()).id();
    }

    public final BedrockPacketCodec setCodecHelper(BedrockCodec codec, BedrockCodecHelper helper) {
        this.codec = requireNonNull(codec, "Codec cannot be null");
        this.helper = requireNonNull(helper, "Helper can not be null");

        switch (this.inboundRecipient) {
            case CLIENT -> this.helper.setEncodingSettings(EncodingSettings.UNLIMITED); // we trust downstream
            case SERVER -> this.helper.setEncodingSettings(EncodingSettings.SERVER);
        }
        return this;
    }

    /**
     * Overrides the recipient used when decoding inbound packets.
     * <p>
     * The proxy derives it from {@link PacketDirection}, which describes the outbound direction of
     * the channel. On a client (downstream) connection the outbound direction is SERVER_BOUND while
     * the packets we receive are CLIENT bound, so the derived value would reject clientbound-only
     * packets such as {@code JigsawStructureDataPacket} and surface them as {@code UnknownPacket}.
     *
     * @param recipient the recipient to decode inbound packets as
     * @return this codec
     */
    public BedrockPacketCodec setInboundRecipient(PacketRecipient recipient) {
        this.inboundRecipient = requireNonNull(recipient, "Inbound recipient cannot be null");
        return this;
    }

    public BedrockPacketCodec setAlwaysDecode(boolean alwaysDecode) {
        this.alwaysDecode = alwaysDecode;        return this;
    }
}
