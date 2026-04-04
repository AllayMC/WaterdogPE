package dev.waterdog.waterdogpe.packs.sync;

import com.google.gson.JsonObject;
import com.nimbusds.jwt.SignedJWT;
import dev.waterdog.waterdogpe.ProxyServer;
import dev.waterdog.waterdogpe.network.EventLoops;
import dev.waterdog.waterdogpe.network.connection.codec.batch.BedrockBatchDecoder;
import dev.waterdog.waterdogpe.network.connection.codec.batch.BedrockBatchEncoder;
import dev.waterdog.waterdogpe.network.connection.codec.batch.FrameIdCodec;
import dev.waterdog.waterdogpe.network.connection.codec.compression.CompressionType;
import dev.waterdog.waterdogpe.network.connection.codec.compression.ProxiedCompressionCodec;
import dev.waterdog.waterdogpe.network.connection.codec.initializer.ProxiedSessionInitializer;
import dev.waterdog.waterdogpe.network.connection.codec.packet.BedrockPacketCodec;
import dev.waterdog.waterdogpe.network.protocol.ProtocolVersion;
import dev.waterdog.waterdogpe.network.protocol.user.HandshakeUtils;
import dev.waterdog.waterdogpe.network.serverinfo.BedrockServerInfo;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import lombok.extern.log4j.Log4j2;
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.cloudburstmc.protocol.bedrock.PacketDirection;
import org.cloudburstmc.protocol.bedrock.data.auth.AuthType;
import org.cloudburstmc.protocol.bedrock.data.auth.CertificateChainPayload;
import org.cloudburstmc.protocol.bedrock.netty.BedrockBatchWrapper;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.cloudburstmc.protocol.bedrock.util.EncryptionUtils;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.CompressionCodec;
import org.cloudburstmc.protocol.bedrock.netty.codec.encryption.BedrockEncryptionDecoder;
import org.cloudburstmc.protocol.bedrock.netty.codec.encryption.BedrockEncryptionEncoder;

import javax.crypto.SecretKey;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.channels.ClosedChannelException;
import java.security.KeyPair;
import java.security.interfaces.ECPublicKey;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Log4j2
public final class AllayPackSyncClient implements AutoCloseable {

    private static final String SYNC_CLIENT_NAME = "WaterdogSync";
    private static final byte[] EMPTY_SKIN = new byte[64 * 64 * 4];
    private static final String EMPTY_SKIN_BASE64 = Base64.getEncoder().encodeToString(EMPTY_SKIN);
    private static final String SKIN_PATCH_BASE64 = Base64.getEncoder().encodeToString("""
            {"geometry":{"default":"geometry.humanoid.custom"}}
            """.getBytes(StandardCharsets.UTF_8));

    private final ProxyServer proxy;
    private final BedrockServerInfo serverInfo;
    private final InetSocketAddress targetAddress;
    private final ProtocolVersion protocol;
    private final AtomicLong nextMessageId = new AtomicLong(1);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final CompletableFuture<Void> loginFuture = new CompletableFuture<>();
    private final ConcurrentMap<Long, CompletableFuture<byte[]>> pendingResponses = new ConcurrentHashMap<>();

    private volatile Channel channel;

    public AllayPackSyncClient(ProxyServer proxy, BedrockServerInfo serverInfo, InetSocketAddress targetAddress) throws IOException {
        this.proxy = proxy;
        this.serverInfo = serverInfo;
        this.targetAddress = targetAddress;
        this.protocol = ProtocolVersion.latest();

        EventLoop eventLoop = this.proxy.getWorkerEventLoopGroup().next();
        ChannelFuture future = new Bootstrap()
                .channelFactory(RakChannelFactory.client(EventLoops.getChannelType().getDatagramChannel()))
                .group(eventLoop)
                .option(RakChannelOption.RAK_PROTOCOL_VERSION, this.protocol.getRaknetVersion())
                .option(RakChannelOption.RAK_ORDERING_CHANNELS, 1)
                .option(RakChannelOption.RAK_CONNECT_TIMEOUT, this.proxy.getNetworkSettings().getConnectTimeout() * 1000L)
                .option(RakChannelOption.RAK_SESSION_TIMEOUT, 10000L)
                .option(RakChannelOption.RAK_MTU, this.proxy.getNetworkSettings().getMaximumDownstreamMtu())
                .handler(new Initializer())
                .connect(this.targetAddress)
                .syncUninterruptibly();

        if (!future.isSuccess()) {
            throw new IOException("Failed to connect to " + this.serverInfo.getServerName(), future.cause());
        }
        this.channel = future.channel();
    }

    public void awaitLogin(long timeout, TimeUnit unit) throws IOException, TimeoutException {
        this.await(this.loginFuture, timeout, unit);
    }

    public List<PackMetadata> fetchPackList(long timeout, TimeUnit unit) throws IOException, TimeoutException {
        byte[] payload = this.await(this.sendRequest(AllayPackSyncProtocol.PACK_LIST_REQUEST, output -> { }), timeout, unit);
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            byte packetType = AllayPackSyncProtocol.readAndValidateVersion(input);
            if (packetType == AllayPackSyncProtocol.ERROR_RESPONSE) {
                throw new IOException(AllayPackSyncProtocol.readString(input));
            }
            if (packetType != AllayPackSyncProtocol.PACK_LIST_RESPONSE) {
                throw new IOException("Unexpected proxy pack sync response type: " + packetType);
            }

            int packCount = input.readInt();
            List<PackMetadata> packs = new ArrayList<>(packCount);
            for (int i = 0; i < packCount; i++) {
                UUID packId = AllayPackSyncProtocol.readUuid(input);
                String version = AllayPackSyncProtocol.readString(input);
                String type = AllayPackSyncProtocol.readString(input);
                long size = input.readLong();
                int chunkSize = input.readInt();
                int chunkCount = input.readInt();
                byte[] hash = AllayPackSyncProtocol.readBytes(input);
                String contentKey = AllayPackSyncProtocol.readString(input);
                packs.add(new PackMetadata(packId, version, type, size, chunkSize, chunkCount, hash, contentKey));
            }
            return packs;
        }
    }

    public byte[] fetchChunk(PackMetadata metadata, int chunkIndex, long timeout, TimeUnit unit) throws IOException, TimeoutException {
        byte[] payload = this.await(this.sendRequest(AllayPackSyncProtocol.PACK_CHUNK_REQUEST, output -> {
            AllayPackSyncProtocol.writeUuid(output, metadata.getPackId());
            AllayPackSyncProtocol.writeString(output, metadata.getVersion());
            output.writeInt(chunkIndex);
        }), timeout, unit);

        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            byte packetType = AllayPackSyncProtocol.readAndValidateVersion(input);
            if (packetType == AllayPackSyncProtocol.ERROR_RESPONSE) {
                throw new IOException(AllayPackSyncProtocol.readString(input));
            }
            if (packetType != AllayPackSyncProtocol.PACK_CHUNK_RESPONSE) {
                throw new IOException("Unexpected proxy pack sync response type: " + packetType);
            }

            UUID packId = AllayPackSyncProtocol.readUuid(input);
            String version = AllayPackSyncProtocol.readString(input);
            int responseChunkIndex = input.readInt();
            if (!metadata.getPackId().equals(packId) || !Objects.equals(metadata.getVersion(), version) || chunkIndex != responseChunkIndex) {
                throw new IOException("Mismatched proxy pack sync chunk response");
            }
            return AllayPackSyncProtocol.readBytes(input);
        }
    }

    private CompletableFuture<byte[]> sendRequest(byte packetType, RequestWriter writer) throws IOException {
        if (this.channel == null || !this.channel.isActive()) {
            throw new ClosedChannelException();
        }

        long messageId = this.nextMessageId.getAndIncrement();
        ByteArrayOutputStream outputBuffer = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(outputBuffer)) {
            AllayPackSyncProtocol.writeHeader(output, packetType);
            writer.write(output);
        }

        PyRpcPacket packet = new PyRpcPacket();
        packet.setMsgId(messageId);
        packet.setData(outputBuffer.toByteArray());

        CompletableFuture<byte[]> responseFuture = new CompletableFuture<>();
        this.pendingResponses.put(messageId, responseFuture);
        this.sendPacket(packet);
        return responseFuture;
    }

    private void sendPacket(BedrockPacket packet) {
        Channel currentChannel = this.channel;
        if (currentChannel == null) {
            throw new IllegalStateException("Proxy pack sync channel is not initialized");
        }
        this.ensurePacketCodec(currentChannel, packet);
        currentChannel.writeAndFlush(BedrockBatchWrapper.create(0, packet));
    }

    private static void sendPacket(Channel channel, BedrockPacket packet) {
        channel.writeAndFlush(BedrockBatchWrapper.create(0, packet));
    }

    private void ensurePacketCodec(Channel channel, BedrockPacket packet) {
        if (!(packet instanceof PyRpcPacket)) {
            return;
        }

        BedrockPacketCodec packetCodec = channel.pipeline().get(BedrockPacketCodec.class);
        if (packetCodec == null || packetCodec.getCodec().getPacketDefinition(PyRpcPacket.class) != null) {
            return;
        }

        BedrockCodec codec = this.protocol.getCodec();
        packetCodec.setCodecHelper(codec, codec.createHelper());
    }

    private ProxiedCompressionCodec createCompressionCodec(org.cloudburstmc.protocol.bedrock.data.CompressionAlgorithm algorithm, boolean initial) {
        boolean prefixed = !initial && this.protocol.isAfterOrEqual(ProtocolVersion.MINECRAFT_PE_1_20_60);
        return new ProxiedCompressionCodec(
                ProxiedSessionInitializer.getCompressionStrategy(algorithm, this.protocol.getRaknetVersion(), initial),
                prefixed
        );
    }

    private LoginPacket createLoginPacket() {
        KeyPair keyPair = HandshakeUtils.getPrivateKeyPair();

        JsonObject extraData = HandshakeUtils.createChainExtraData(SYNC_CLIENT_NAME, "", UUID.randomUUID());
        extraData.addProperty("Waterdog_PackSync", true);
        SignedJWT signedPayload = HandshakeUtils.createClientDataChain(keyPair, extraData);

        JsonObject clientData = new JsonObject();
        clientData.addProperty("ServerAddress", this.targetAddress.getHostString() + ":" + this.targetAddress.getPort());
        clientData.addProperty("DeviceModel", "WaterdogPE");
        clientData.addProperty("DeviceId", UUID.randomUUID().toString());
        clientData.addProperty("ClientRandomId", ThreadLocalRandom.current().nextLong());
        clientData.addProperty("DeviceOS", 1);
        clientData.addProperty("GuiScale", 0);
        clientData.addProperty("UIProfile", 0);
        clientData.addProperty("LanguageCode", "en_US");
        clientData.addProperty("GameVersion", this.protocol.getMinecraftVersion());
        clientData.addProperty("SkinId", UUID.randomUUID().toString());
        clientData.addProperty("SkinResourcePatch", SKIN_PATCH_BASE64);
        clientData.addProperty("SkinImageWidth", 64);
        clientData.addProperty("SkinImageHeight", 64);
        clientData.addProperty("SkinData", EMPTY_SKIN_BASE64);
        clientData.addProperty("CapeImageWidth", 0);
        clientData.addProperty("CapeImageHeight", 0);
        clientData.addProperty("CapeData", "");
        clientData.addProperty("PremiumSkin", false);
        clientData.addProperty("PersonaSkin", false);
        clientData.addProperty("CapeOnClassicSkin", false);
        clientData.addProperty("CurrentInputMode", 1);
        clientData.addProperty("DefaultInputMode", 1);

        LoginPacket loginPacket = new LoginPacket();
        loginPacket.setProtocolVersion(this.protocol.getProtocol());
        loginPacket.setClientJwt(HandshakeUtils.encodeJWT(keyPair, clientData).serialize());
        loginPacket.setAuthPayload(new CertificateChainPayload(List.of(signedPayload.serialize()), AuthType.SELF_SIGNED));
        return loginPacket;
    }

    public InetSocketAddress getTargetAddress() {
        return this.targetAddress;
    }

    private void handleDisconnect(Throwable cause) {
        if (this.closed.get()) {
            return;
        }

        if (!this.loginFuture.isDone()) {
            this.loginFuture.completeExceptionally(cause);
        }

        this.pendingResponses.forEach((messageId, future) -> future.completeExceptionally(cause));
        this.pendingResponses.clear();
    }

    private void enableEncryption(ServerToClientHandshakePacket packet) throws Exception {
        SignedJWT saltJwt = SignedJWT.parse(packet.getJwt());
        ECPublicKey serverKey = EncryptionUtils.parseKey(saltJwt.getHeader().getX509CertURL().toASCIIString());
        SecretKey key = EncryptionUtils.getSecretKey(
                HandshakeUtils.getPrivateKeyPair().getPrivate(),
                serverKey,
                Base64.getDecoder().decode(saltJwt.getJWTClaimsSet().getStringClaim("salt"))
        );

        int protocolVersion = this.protocol.getCodec().getProtocolVersion();
        boolean useCtr = protocolVersion >= org.cloudburstmc.protocol.bedrock.codec.v428.Bedrock_v428.CODEC.getProtocolVersion();
        this.channel.pipeline().addAfter(FrameIdCodec.NAME, BedrockEncryptionEncoder.NAME,
                new BedrockEncryptionEncoder(key, EncryptionUtils.createCipher(useCtr, true, key)));
        this.channel.pipeline().addAfter(FrameIdCodec.NAME, BedrockEncryptionDecoder.NAME,
                new BedrockEncryptionDecoder(key, EncryptionUtils.createCipher(useCtr, false, key)));
    }

    private <T> T await(CompletableFuture<T> future, long timeout, TimeUnit unit) throws IOException, TimeoutException {
        try {
            return future.get(timeout, unit);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for proxy pack sync", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("Proxy pack sync failed", cause);
        }
    }

    @Override
    public void close() {
        if (!this.closed.compareAndSet(false, true)) {
            return;
        }

        ClosedChannelException closedChannel = new ClosedChannelException();
        this.pendingResponses.forEach((messageId, future) -> future.completeExceptionally(closedChannel));
        this.pendingResponses.clear();

        if (this.channel != null && this.channel.isOpen()) {
            this.channel.close().syncUninterruptibly();
        }
    }

    private final class Initializer extends ChannelInitializer<Channel> {

        @Override
        protected void initChannel(Channel channel) {
            int rakVersion = protocol.getRaknetVersion();
            AllayPackSyncClient.this.channel = channel;
            BedrockPacketCodec packetCodec = ProxiedSessionInitializer.getPacketCodec(rakVersion)
                    .setAlwaysDecode(true);

            channel.attr(PacketDirection.ATTRIBUTE).set(PacketDirection.SERVER_BOUND);
            channel.pipeline()
                    .addLast(FrameIdCodec.NAME, ProxiedSessionInitializer.RAKNET_FRAME_CODEC)
                    .addLast(CompressionCodec.NAME, createCompressionCodec(CompressionType.ZLIB, true))
                    .addLast(BedrockBatchDecoder.NAME, new BedrockBatchDecoder())
                    .addLast(BedrockBatchEncoder.NAME, new BedrockBatchEncoder())
                    .addLast(BedrockPacketCodec.NAME, packetCodec)
                    .addLast("allay-pack-sync-client", new Handler());
        }
    }

    private final class Handler extends SimpleChannelInboundHandler<BedrockBatchWrapper> {

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            RequestNetworkSettingsPacket packet = new RequestNetworkSettingsPacket();
            packet.setProtocolVersion(protocol.getProtocol());
            sendPacket(ctx.channel(), packet);
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, BedrockBatchWrapper batch) {
            for (var packetWrapper : batch.getPackets()) {
                BedrockPacket packet = packetWrapper.getPacket();
                if (packet == null) {
                    continue;
                }

                try {
                    if (packet instanceof NetworkSettingsPacket settingsPacket) {
                        ctx.channel().pipeline().replace(CompressionCodec.NAME, CompressionCodec.NAME,
                                createCompressionCodec(settingsPacket.getCompressionAlgorithm(), false));
                        sendPacket(ctx.channel(), createLoginPacket());
                    } else if (packet instanceof ServerToClientHandshakePacket handshakePacket) {
                        enableEncryption(handshakePacket);
                        sendPacket(ctx.channel(), new ClientToServerHandshakePacket());
                    } else if (packet instanceof PlayStatusPacket statusPacket) {
                        if (statusPacket.getStatus() == PlayStatusPacket.Status.LOGIN_SUCCESS) {
                            loginFuture.complete(null);
                        } else {
                            handleDisconnect(new IOException("Proxy pack sync login failed with status " + statusPacket.getStatus()));
                        }
                    } else if (packet instanceof PyRpcPacket pyRpcPacket) {
                        CompletableFuture<byte[]> future = pendingResponses.remove(pyRpcPacket.getMsgId());
                        if (future != null) {
                            future.complete(pyRpcPacket.getData());
                        }
                    } else if (packet instanceof DisconnectPacket disconnectPacket) {
                        handleDisconnect(new IOException("Proxy pack sync disconnected: " + disconnectPacket.getKickMessage()));
                    }
                } catch (Exception exception) {
                    handleDisconnect(exception);
                    ctx.close();
                    return;
                }
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            handleDisconnect(new IOException("Disconnected from " + serverInfo.getServerName()));
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.debug("Proxy pack sync connection to {} failed", serverInfo.getServerName(), cause);
            handleDisconnect(cause);
            ctx.close();
        }
    }

    @FunctionalInterface
    private interface RequestWriter {
        void write(DataOutputStream output) throws IOException;
    }
}
