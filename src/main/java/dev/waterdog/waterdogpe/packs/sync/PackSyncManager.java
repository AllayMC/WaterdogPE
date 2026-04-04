package dev.waterdog.waterdogpe.packs.sync;

import dev.waterdog.waterdogpe.ProxyServer;
import dev.waterdog.waterdogpe.network.serverinfo.BedrockServerInfo;
import dev.waterdog.waterdogpe.network.serverinfo.ServerInfo;
import dev.waterdog.waterdogpe.packs.types.ResourcePack;
import dev.waterdog.waterdogpe.scheduler.TaskHandler;
import dev.waterdog.waterdogpe.utils.config.proxy.PackSyncSettings;
import lombok.extern.log4j.Log4j2;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Log4j2
public final class PackSyncManager {

    private final ProxyServer proxy;
    private final PackSyncSettings settings;
    private final Path cacheDirectory;
    private final AtomicBoolean refreshInProgress = new AtomicBoolean(false);

    private volatile boolean ready;
    private volatile TaskHandler<?> refreshTask;

    public PackSyncManager(ProxyServer proxy) {
        this.proxy = proxy;
        this.settings = proxy.getConfiguration().getPackSyncSettings();
        this.cacheDirectory = proxy.getDataPath().resolve("pack-sync-cache");
    }

    public boolean isEnabled() {
        return this.proxy.getConfiguration().enableResourcePacks() && this.settings.enabled();
    }

    public boolean isReadyForLogins() {
        return !this.isEnabled() || !this.settings.blockLoginUntilReady() || this.ready;
    }

    public void initialize() {
        if (!this.proxy.getConfiguration().enableResourcePacks()) {
            this.ready = true;
            return;
        }

        if (!this.settings.enabled()) {
            this.ready = true;
            return;
        }

        try {
            Files.createDirectories(this.cacheDirectory);
        } catch (IOException exception) {
            this.proxy.getLogger().error("Unable to create pack sync cache directory {}", this.cacheDirectory, exception);
        }

        this.proxy.getLogger().info("Initializing Allay resource pack sync");
        this.refreshSnapshot(true);

        int refreshIntervalSeconds = this.settings.getRefreshIntervalSeconds();
        if (refreshIntervalSeconds > 0) {
            int refreshTicks = refreshIntervalSeconds * 20;
            this.refreshTask = this.proxy.getScheduler().scheduleDelayedRepeating(() -> this.refreshSnapshot(false), refreshTicks, refreshTicks, true);
        }
    }

    public void refreshAsync() {
        if (!this.isEnabled()) {
            return;
        }
        this.proxy.getScheduler().scheduleAsync(() -> this.refreshSnapshot(false));
    }

    public void shutdown() {
        TaskHandler<?> currentTask = this.refreshTask;
        if (currentTask != null) {
            currentTask.cancel();
            this.refreshTask = null;
        }
    }

    private boolean refreshSnapshot(boolean initialRefresh) {
        if (!this.isEnabled() || !this.refreshInProgress.compareAndSet(false, true)) {
            return this.ready;
        }

        try {
            Map<UUID, ResourcePack> mergedPacks = new LinkedHashMap<>(this.proxy.getPackManager().getLocalPacks());
            Map<UUID, PackMetadata> mergedMetadata = new LinkedHashMap<>(this.proxy.getPackManager().getLocalPackMetadata());
            Map<String, Set<String>> serverPacks = new LinkedHashMap<>();

            long timeout = Math.max(5, this.settings.getStartupTimeoutSeconds());
            for (BedrockServerInfo serverInfo : this.getSyncTargets()) {
                ServerSyncResult syncResult = this.syncServer(serverInfo, timeout);
                serverPacks.put(serverInfo.getServerName().toLowerCase(Locale.ROOT), syncResult.packKeys());

                for (PackMetadata metadata : syncResult.metadata()) {
                    PackMetadata currentMetadata = mergedMetadata.get(metadata.getPackId());
                    if (currentMetadata != null) {
                        currentMetadata.assertCompatible(metadata);
                        continue;
                    }

                    mergedMetadata.put(metadata.getPackId(), metadata);
                    mergedPacks.put(metadata.getPackId(), syncResult.packs().get(metadata.getPackId()));
                }
            }

            PackSnapshot snapshot = PackSnapshot.create(
                    mergedPacks.values(),
                    mergedMetadata.values(),
                    serverPacks,
                    this.proxy.getConfiguration().isForceServerPacks(),
                    this.proxy.getConfiguration().isOverwriteClientPacks(),
                    this.proxy.getConfiguration().enableEducationFeatures()
            );
            this.proxy.getPackManager().publishSnapshot(snapshot);
            this.proxy.getLogger().info("Allay resource pack snapshot ready: {} packs across {} backend servers",
                    snapshot.getMetadataById().size(), serverPacks.size());
            this.ready = true;
            return true;
        } catch (Exception exception) {
            this.proxy.getLogger().error("Unable to refresh Allay resource pack snapshot", exception);
            if (initialRefresh) {
                this.ready = false;
            }
            return false;
        } finally {
            this.refreshInProgress.set(false);
        }
    }

    private List<BedrockServerInfo> getSyncTargets() {
        List<BedrockServerInfo> servers = new ArrayList<>();
        for (ServerInfo serverInfo : this.proxy.getServers()) {
            if (serverInfo instanceof BedrockServerInfo bedrockServerInfo) {
                servers.add(bedrockServerInfo);
            }
        }
        servers.sort(Comparator.comparing(ServerInfo::getServerName));
        return servers;
    }

    private ServerSyncResult syncServer(BedrockServerInfo serverInfo, long timeoutSeconds) throws IOException {
        IOException lastFailure = null;
        for (InetSocketAddress address : this.resolveSyncAddresses(serverInfo)) {
            try (AllayPackSyncClient client = new AllayPackSyncClient(this.proxy, serverInfo, address)) {
                this.proxy.getLogger().info("Syncing packs from {} via {}", serverInfo.getServerName(), this.describeAddress(address));
                client.awaitLogin(timeoutSeconds, TimeUnit.SECONDS);
                return this.syncConnectedServer(client, serverInfo, timeoutSeconds);
            } catch (java.util.concurrent.TimeoutException exception) {
                lastFailure = new IOException("Timed out while syncing packs from " + serverInfo.getServerName() + " via " + address, exception);
            } catch (IOException exception) {
                lastFailure = exception;
            }

            if (lastFailure != null) {
                this.proxy.getLogger().warning("Pack sync attempt for {} via {} failed: {}", serverInfo.getServerName(), this.describeAddress(address), lastFailure.getMessage());
            }
        }

        throw lastFailure == null
                ? new IOException("Unable to sync packs from " + serverInfo.getServerName())
                : lastFailure;
    }

    private ServerSyncResult syncConnectedServer(AllayPackSyncClient client, BedrockServerInfo serverInfo, long timeoutSeconds) throws IOException, java.util.concurrent.TimeoutException {
        List<PackMetadata> remoteMetadata = client.fetchPackList(timeoutSeconds, TimeUnit.SECONDS);
        Map<UUID, ResourcePack> resolvedPacks = new LinkedHashMap<>();
        Map<UUID, PackMetadata> seenRemoteMetadata = new HashMap<>();
        Set<String> packKeys = new LinkedHashSet<>();

        Map<UUID, PackMetadata> localMetadata = this.proxy.getPackManager().getLocalPackMetadata();
        Map<UUID, ResourcePack> localPacks = this.proxy.getPackManager().getLocalPacks();

        for (PackMetadata metadata : remoteMetadata) {
            packKeys.add(metadata.idVersion());

            PackMetadata previousMetadata = seenRemoteMetadata.putIfAbsent(metadata.getPackId(), metadata);
            if (previousMetadata != null) {
                previousMetadata.assertCompatible(metadata);
                continue;
            }

            PackMetadata localPackMetadata = localMetadata.get(metadata.getPackId());
            if (localPackMetadata != null) {
                localPackMetadata.assertCompatible(metadata);
                resolvedPacks.put(metadata.getPackId(), localPacks.get(metadata.getPackId()));
                continue;
            }

            resolvedPacks.put(metadata.getPackId(), this.loadOrDownloadPack(client, metadata, timeoutSeconds));
        }

        this.proxy.getLogger().info("Synchronized {} packs from {}", remoteMetadata.size(), serverInfo.getServerName());
        return new ServerSyncResult(resolvedPacks, List.copyOf(remoteMetadata), Collections.unmodifiableSet(packKeys));
    }

    private List<InetSocketAddress> resolveSyncAddresses(BedrockServerInfo serverInfo) {
        LinkedHashMap<String, InetSocketAddress> addresses = new LinkedHashMap<>();
        InetSocketAddress primary = this.resolveAddress(serverInfo.getAddress());
        InetSocketAddress fallback = this.resolveAddress(serverInfo.getPublicAddress());
        addresses.put(primary.getHostString() + ":" + primary.getPort(), primary);
        addresses.put(fallback.getHostString() + ":" + fallback.getPort(), fallback);
        return new ArrayList<>(addresses.values());
    }

    private InetSocketAddress resolveAddress(InetSocketAddress address) {
        if (!address.isUnresolved()) {
            return address;
        }

        try {
            InetAddress resolved = InetAddress.getByName(address.getHostString());
            return new InetSocketAddress(resolved, address.getPort());
        } catch (UnknownHostException exception) {
            this.proxy.getLogger().warning("Unable to resolve pack sync address {}: {}", address.getHostString(), exception.getMessage());
            return address;
        }
    }

    private String describeAddress(InetSocketAddress address) {
        if (address.getAddress() != null) {
            return address.getAddress().getHostAddress() + ":" + address.getPort();
        }
        return address.getHostString() + ":" + address.getPort();
    }

    private ResourcePack loadOrDownloadPack(AllayPackSyncClient client, PackMetadata metadata, long timeoutSeconds) throws IOException, java.util.concurrent.TimeoutException {
        Path packPath = this.cacheDirectory.resolve(metadata.idVersion() + ".zip");
        ResourcePack cachedPack = this.tryLoadCachedPack(packPath, metadata);
        if (cachedPack != null) {
            return cachedPack;
        }

        Path tempFile = this.cacheDirectory.resolve(metadata.idVersion() + ".tmp");
        try (OutputStream output = Files.newOutputStream(tempFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            for (int chunkIndex = 0; chunkIndex < metadata.getChunkCount(); chunkIndex++) {
                output.write(client.fetchChunk(metadata, chunkIndex, timeoutSeconds, TimeUnit.SECONDS));
            }
        }

        try {
            Files.move(tempFile, packPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(tempFile, packPath, StandardCopyOption.REPLACE_EXISTING);
        }
        this.writeContentKey(packPath, metadata.getContentKey());

        ResourcePack loadedPack = this.proxy.getPackManager().loadPack(packPath);
        if (loadedPack == null) {
            throw new IOException("Downloaded pack " + metadata.idVersion() + " is invalid");
        }

        PackMetadata.fromPack(loadedPack).assertCompatible(metadata);
        return loadedPack;
    }

    private ResourcePack tryLoadCachedPack(Path packPath, PackMetadata metadata) throws IOException {
        if (!Files.isRegularFile(packPath)) {
            return null;
        }

        ResourcePack pack = this.proxy.getPackManager().loadPack(packPath);
        if (pack == null) {
            return null;
        }

        try {
            PackMetadata.fromPack(pack).assertCompatible(metadata);
            return pack;
        } catch (PackConflictException ignored) {
            Files.deleteIfExists(packPath);
            Files.deleteIfExists(Path.of(packPath + ".key"));
            return null;
        }
    }

    private void writeContentKey(Path packPath, String contentKey) throws IOException {
        Path keyPath = Path.of(packPath + ".key");
        Files.writeString(keyPath, contentKey == null ? "" : contentKey, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private record ServerSyncResult(Map<UUID, ResourcePack> packs, List<PackMetadata> metadata, Set<String> packKeys) {
    }
}
