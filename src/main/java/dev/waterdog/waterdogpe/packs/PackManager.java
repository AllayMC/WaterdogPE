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

package dev.waterdog.waterdogpe.packs;

import dev.waterdog.waterdogpe.ProxyServer;
import dev.waterdog.waterdogpe.event.defaults.ResourcePacksRebuildEvent;
import dev.waterdog.waterdogpe.packs.sync.PackMetadata;
import dev.waterdog.waterdogpe.packs.sync.PackSnapshot;
import dev.waterdog.waterdogpe.packs.types.ResourcePack;
import dev.waterdog.waterdogpe.packs.types.ZipResourcePack;
import dev.waterdog.waterdogpe.utils.FileUtils;
import io.netty.buffer.Unpooled;
import lombok.Getter;
import org.cloudburstmc.protocol.bedrock.data.ResourcePackType;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackChunkDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackChunkRequestPacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackDataInfoPacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackStackPacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePacksInfoPacket;
import org.cloudburstmc.protocol.bedrock.util.Preconditions;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class PackManager {

    private static final int CHUNK_SIZE = 102400;
    private static final PathMatcher ZIP_PACK_MATCHER = FileSystems.getDefault().getPathMatcher("glob:**.{zip,mcpack}");

    private final ProxyServer proxy;
    private final Map<UUID, ResourcePack> localPacks = new LinkedHashMap<>();
    private final Map<UUID, PackMetadata> localPackMetadata = new LinkedHashMap<>();

    @Getter
    private volatile PackSnapshot currentSnapshot;

    public PackManager(ProxyServer proxy) {
        this.proxy = proxy;
        this.currentSnapshot = PackSnapshot.create(
                List.of(),
                List.of(),
                Map.of(),
                this.proxy.getConfiguration().isForceServerPacks(),
                this.proxy.getConfiguration().isOverwriteClientPacks(),
                this.proxy.getConfiguration().enableEducationFeatures()
        );
    }

    public static int getDefaultChunkSize() {
        return CHUNK_SIZE;
    }

    public synchronized void loadPacks(Path packsDirectory) {
        Preconditions.checkNotNull(packsDirectory, "Packs directory can not be null!");
        Preconditions.checkArgument(Files.isDirectory(packsDirectory), packsDirectory + " must be directory!");
        this.proxy.getLogger().info("Loading resource packs!");

        this.localPacks.clear();
        this.localPackMetadata.clear();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(packsDirectory)) {
            for (Path path : stream) {
                ResourcePack resourcePack = this.constructPack(path);
                if (resourcePack == null) {
                    continue;
                }

                this.localPacks.put(resourcePack.getPackId(), resourcePack);
                this.localPackMetadata.put(resourcePack.getPackId(), PackMetadata.fromPack(resourcePack));
            }
        } catch (IOException e) {
            this.proxy.getLogger().error("Can not load packs!", e);
        }

        this.publishLocalSnapshot();
        this.proxy.getLogger().info("Loaded " + this.localPacks.size() + " packs!");
    }

    public synchronized ResourcePack loadPack(Path packPath) {
        Class<? extends ResourcePack> loader = this.getPackLoader(packPath);
        if (loader == null) {
            return null;
        }

        try {
            ResourcePack pack = loader.getDeclaredConstructor(Path.class).newInstance(packPath);
            if (!pack.loadManifest() || !pack.getPackManifest().validate()) {
                return null;
            }

            File contentKeyFile = new File(packPath.getParent().toFile(), packPath.toFile().getName() + ".key");
            pack.setContentKey(contentKeyFile.exists()
                    ? Files.readString(contentKeyFile.toPath(), StandardCharsets.UTF_8).replace("\n", "")
                    : "");

            if (this.proxy.getConfiguration().getPackCacheSize() >= (pack.getPackSize() / FileUtils.INT_MEGABYTE)) {
                pack.saveToCache();
            }
            return pack;
        } catch (Exception exception) {
            this.proxy.getLogger().error("Can not load resource pack from: " + packPath.getFileName(), exception);
            return null;
        }
    }

    private ResourcePack constructPack(Path packPath) {
        ResourcePack pack = this.loadPack(packPath);
        if (pack != null) {
            return pack;
        }
        if (this.getPackLoader(packPath) != null) {
            this.proxy.getLogger().error("Resource pack manifest.json is invalid or was not found in " + packPath.getFileName() + ", please make sure that you zip the content of the pack and not the folder! Read more on troubleshooting here: https://docs.waterdog.dev/books/waterdogpe-setup/page/troubleshooting");
        }
        return null;
    }

    public Class<? extends ResourcePack> getPackLoader(Path path) {
        if (ZIP_PACK_MATCHER.matches(path)) {
            return ZipResourcePack.class;
        }
        return null;
    }

    public synchronized boolean registerPack(ResourcePack resourcePack) {
        Preconditions.checkNotNull(resourcePack, "Resource pack can not be null!");
        Preconditions.checkArgument(resourcePack.getPackManifest().validate(), "Resource pack has invalid manifest!");

        if (this.localPacks.get(resourcePack.getPackId()) != null) {
            return false;
        }

        this.localPacks.put(resourcePack.getPackId(), resourcePack);
        this.localPackMetadata.put(resourcePack.getPackId(), PackMetadata.fromPack(resourcePack));
        this.publishAfterLocalUpdate();
        return true;
    }

    public synchronized boolean unregisterPack(UUID packId) {
        ResourcePack resourcePack = this.localPacks.remove(packId);
        if (resourcePack == null) {
            return false;
        }

        this.localPackMetadata.remove(packId);
        this.publishAfterLocalUpdate();
        return true;
    }

    public synchronized void publishSnapshot(PackSnapshot snapshot) {
        this.currentSnapshot = snapshot;
        ResourcePacksRebuildEvent event = new ResourcePacksRebuildEvent(snapshot.getPacksInfoPacket(), snapshot.getStackPacket());
        this.proxy.getEventManager().callEvent(event);
    }

    public Map<UUID, ResourcePack> getLocalPacks() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(this.localPacks));
    }

    public Map<UUID, PackMetadata> getLocalPackMetadata() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(this.localPackMetadata));
    }

    public ResourcePacksInfoPacket getPacksInfoPacket() {
        return this.currentSnapshot.getPacksInfoPacket();
    }

    public ResourcePackStackPacket getStackPacket() {
        return this.currentSnapshot.getStackPacket();
    }

    public ResourcePackDataInfoPacket packInfoFromIdVer(String idVersion) {
        ResourcePack resourcePack = this.currentSnapshot.packFromIdVersion(idVersion);
        if (resourcePack == null) {
            return null;
        }

        ResourcePackDataInfoPacket packet = new ResourcePackDataInfoPacket();
        packet.setPackId(resourcePack.getPackId());
        packet.setPackVersion(resourcePack.getVersion().toString());
        packet.setMaxChunkSize(CHUNK_SIZE);
        packet.setChunkCount((resourcePack.getPackSize() - 1) / packet.getMaxChunkSize() + 1);
        packet.setCompressedPackSize(resourcePack.getPackSize());
        packet.setHash(resourcePack.getHash());
        if (resourcePack.getType().equals(ResourcePack.TYPE_RESOURCES)) {
            packet.setType(ResourcePackType.RESOURCES);
        } else if (resourcePack.getType().equals(ResourcePack.TYPE_DATA)) {
            packet.setType(ResourcePackType.ADDON);
        }
        return packet;
    }

    public ResourcePackChunkDataPacket packChunkDataPacket(String idVersion, ResourcePackChunkRequestPacket from) {
        ResourcePack resourcePack = this.currentSnapshot.packFromIdVersion(idVersion);
        if (resourcePack == null) {
            return null;
        }

        ResourcePackChunkDataPacket packet = new ResourcePackChunkDataPacket();
        packet.setPackId(from.getPackId());
        packet.setPackVersion(from.getPackVersion());
        packet.setChunkIndex(from.getChunkIndex());
        packet.setData(Unpooled.wrappedBuffer(resourcePack.getChunk(CHUNK_SIZE * from.getChunkIndex(), CHUNK_SIZE)));
        packet.setProgress((long) CHUNK_SIZE * from.getChunkIndex());
        return packet;
    }

    private void publishLocalSnapshot() {
        this.publishSnapshot(PackSnapshot.create(
                this.localPacks.values(),
                this.localPackMetadata.values(),
                Map.of(),
                this.proxy.getConfiguration().isForceServerPacks(),
                this.proxy.getConfiguration().isOverwriteClientPacks(),
                this.proxy.getConfiguration().enableEducationFeatures()
        ));
    }

    private void publishAfterLocalUpdate() {
        if (this.proxy.getPackSyncManager() != null && this.proxy.getPackSyncManager().isEnabled()) {
            this.proxy.getPackSyncManager().refreshAsync();
            return;
        }
        this.publishLocalSnapshot();
    }
}
