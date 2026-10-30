/*
 * Copyright 2023 WaterdogTEAM
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

package dev.waterdog.waterdogpe.network.protocol.handler;

import dev.waterdog.waterdogpe.network.connection.ProxiedConnection;
import dev.waterdog.waterdogpe.network.protocol.Signals;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.ReferenceCountUtil;
import lombok.Data;
import lombok.extern.log4j.Log4j2;
import org.cloudburstmc.protocol.bedrock.PacketDirection;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.data.PacketRecipient;
import org.cloudburstmc.protocol.bedrock.netty.BedrockBatchWrapper;
import org.cloudburstmc.protocol.bedrock.netty.BedrockPacketWrapper;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacketHandler;
import org.cloudburstmc.protocol.bedrock.packet.PacketSignal;
import org.cloudburstmc.protocol.bedrock.packet.UnknownPacket;
import org.cloudburstmc.protocol.bedrock.util.Preconditions;

import java.util.ListIterator;

@Data
@Log4j2
public class ProxyBatchBridge implements BedrockPacketHandler {
    private static final java.util.concurrent.atomic.AtomicInteger flowLogs = new java.util.concurrent.atomic.AtomicInteger();
    private static volatile BedrockCodecHelper VANILLA_HELPER;
private BedrockCodec codec;
    private BedrockCodecHelper helper;

    private ProxyPacketHandler handler;
    private boolean forceEncode;

    public ProxyBatchBridge(BedrockCodec codec, BedrockCodecHelper helper, ProxyPacketHandler handler) {
        this.codec = codec;
        this.helper = helper;
        this.setHandler(handler);
    }

    public void onBedrockBatch(ProxiedConnection source, BedrockBatchWrapper batch) {
        ListIterator<BedrockPacketWrapper> iterator = batch.getPackets().listIterator();
        while (iterator.hasNext()) {
            BedrockPacketWrapper wrapper = iterator.next();
            if (wrapper.getPacket() == null) {
                this.decodePacket(wrapper, source.getPacketDirection());
            }

            PacketSignal signal = this.handlePacket(wrapper.getPacket());
            if (wrapper.getPacket() instanceof org.cloudburstmc.protocol.bedrock.packet.PlayStatusPacket playStatus) {
                log.debug("PlayStatus {} {}", playStatus.getStatus(),
                        signal == PacketSignal.UNHANDLED ? "forwarded" : "consumed");
            }
            if (wrapper.getPacket() instanceof UnknownPacket unknown && flowLogs.getAndIncrement() < 30) {
                log.info("[flow] unknown id={} dir={} signal={}", unknown.getPacketId(), source.getPacketDirection(), signal);
            }
            if (this.isForceEncode() || signal == PacketSignal.HANDLED) {
                ReferenceCountUtil.release(wrapper.getPacketBuffer());
                wrapper.setPacketBuffer(null); // clear cached buffer
                batch.modify();
            } else if (signal == Signals.CANCEL) {
                iterator.remove(); // remove from batch
                wrapper.release(); // release
                batch.modify();
            }
        }

        if (!batch.getPackets().isEmpty()) {
            this.sendProxiedBatch(batch);
        }
    }

    @Override
    public PacketSignal handlePacket(BedrockPacket packet) {
        try {
            PacketSignal signal = this.handler.handlePacket(packet);
            PacketSignal rewriteSignal = this.handler.doPacketRewrite(packet);
            this.handler.getRewriteMaps().getEntityTracker().trackEntity(packet);
            return Signals.mergeSignals(signal, rewriteSignal);
        } catch (Exception e) {
            throw new IllegalStateException("Error while handling " + packet.getPacketType(), e);
        }
    }

    /** Returns the vanilla (international) helper used as a second-chance decoder for 1.21.124. */
    private static BedrockCodecHelper vanillaHelper() {
        if (VANILLA_HELPER == null) {
            VANILLA_HELPER = org.cloudburstmc.protocol.bedrock.codec.v860.Bedrock_v860.CODEC.createHelper();
        }
        return VANILLA_HELPER;
    }

    /** Returns whether a decoded packet carries no usable payload (unknown id or failed decode). */
    private static boolean isUndecoded(BedrockPacket packet) {
        return packet == null || packet instanceof UnknownPacket;
    }

    private void decodePacket(BedrockPacketWrapper wrapper, PacketDirection direction) {
        ByteBuf msg = wrapper.getPacketBuffer().retainedSlice();
        try {
            msg.skipBytes(wrapper.getHeaderLength()); // skip header
            int payloadIndex = msg.readerIndex();
            PacketRecipient recipient = direction.getInbound();
            PacketRecipient other = recipient == PacketRecipient.CLIENT
                    ? PacketRecipient.SERVER
                    : PacketRecipient.CLIENT;
            BedrockPacket decoded = null;
            try {
                decoded = this.codec.tryDecode(helper, msg, wrapper.getPacketId(), recipient);
            } catch (Throwable firstFailure) {
                if (flowLogs.getAndIncrement() < 30) {
                    log.info("[dec] id={} recipient={} threw {}: {}", wrapper.getPacketId(), recipient,
                            firstFailure.getClass().getSimpleName(), firstFailure.getMessage());
                }
            }
            if (isUndecoded(decoded)) {
                // A relay hop can carry packets registered for the opposite recipient only
                // (for example JigsawStructureDataPacket); retry before dropping them.
                msg.readerIndex(payloadIndex);
                try {
                    decoded = this.codec.tryDecode(helper, msg, wrapper.getPacketId(), other);
                } catch (Throwable secondFailure) {
                    log.debug("[dec] id={} recipient={} failed: {}", wrapper.getPacketId(), other, secondFailure.toString());
                }
            }
            if (isUndecoded(decoded)) {
                // Allay writes the item-heavy packets with the international item layout even for
                // NetEase clients, so the NetEase helper cannot parse them; retry with the vanilla one.
                msg.readerIndex(payloadIndex);
                try {
                    decoded = this.codec.tryDecode(vanillaHelper(), msg, wrapper.getPacketId(), recipient);
                } catch (Throwable vanillaFailure) {
                    log.debug("[dec] id={} vanilla helper failed: {}", wrapper.getPacketId(), vanillaFailure.toString());
                }
            }
            if (isUndecoded(decoded)) {
                log.info("[dec] undecoded id={} dir={} codec={} mc={} protocol={} defSelf={} def11={} def144={}",
                        wrapper.getPacketId(), direction, this.codec.getClass().getSimpleName(),
                        this.codec.getMinecraftVersion(), this.codec.getProtocolVersion(),
                        this.codec.getPacketDefinition(wrapper.getPacketId()) == null ? "MISSING" : "PRESENT",
                        this.codec.getPacketDefinition(11) == null ? "MISSING" : "PRESENT",
                        this.codec.getPacketDefinition(144) == null ? "MISSING" : "PRESENT");
            }
            if (decoded == null) {
                // Handlers must never receive null. Keep the packet body only: the header (packet id
                // VarInt) is rewritten by the encoder, so copying it would duplicate it.
                ByteBuf full = wrapper.getPacketBuffer();
                int bodyStart = full.readerIndex() + wrapper.getHeaderLength();
                int bodyLength = full.writerIndex() - bodyStart;
                UnknownPacket unknown = new UnknownPacket();
                unknown.setPacketId(wrapper.getPacketId());
                unknown.setPayload(bodyLength > 0 ? full.retainedSlice(bodyStart, bodyLength) : Unpooled.EMPTY_BUFFER);
                decoded = unknown;
            }
            wrapper.setPacket(decoded);
        } catch (Throwable t) {
            log.warn("Failed to decode packet", t);
            throw t;
        } finally {
            msg.release();
        }
    }

    public void sendProxiedBatch(BedrockBatchWrapper batch) {
        this.handler.sendProxiedBatch(batch);
    }

    public void setHandler(ProxyPacketHandler handler) {
        Preconditions.checkNotNull(handler, "Handler can not be null");
        this.handler = handler;
    }
}
