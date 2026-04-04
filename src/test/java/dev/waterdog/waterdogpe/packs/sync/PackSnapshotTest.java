package dev.waterdog.waterdogpe.packs.sync;

import dev.waterdog.waterdogpe.packs.types.PackedVersion;
import dev.waterdog.waterdogpe.packs.types.ResourcePack;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackStackPacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePacksInfoPacket;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class PackSnapshotTest {

    @Test
    void snapshotValidatesSubsetPacketsAndCoveredServers() {
        TestResourcePack resourcePack = new TestResourcePack(UUID.randomUUID(), "1.0.0", ResourcePack.TYPE_RESOURCES, new byte[]{1, 2, 3});
        TestResourcePack dataPack = new TestResourcePack(UUID.randomUUID(), "1.0.0", ResourcePack.TYPE_DATA, new byte[]{4, 5, 6});

        PackMetadata resourceMetadata = PackMetadata.fromPack(resourcePack);
        PackMetadata dataMetadata = PackMetadata.fromPack(dataPack);

        PackSnapshot snapshot = PackSnapshot.create(
                List.of(resourcePack, dataPack),
                List.of(resourceMetadata, dataMetadata),
                Map.of(
                        "alpha", Set.of(resourceMetadata.idVersion(), dataMetadata.idVersion()),
                        "beta", Set.of()
                ),
                false,
                false,
                false
        );

        ResourcePacksInfoPacket infoPacket = new ResourcePacksInfoPacket();
        infoPacket.getResourcePackInfos().add(new ResourcePacksInfoPacket.Entry(
                resourcePack.getPackId(),
                resourcePack.getVersion().toString(),
                resourcePack.getPackSize(),
                "",
                "",
                resourcePack.getPackId().toString(),
                false,
                false,
                null,
                false
        ));
        assertTrue(snapshot.coversInfoPacket(infoPacket));

        ResourcePackStackPacket stackPacket = new ResourcePackStackPacket();
        stackPacket.getBehaviorPacks().add(new ResourcePackStackPacket.Entry(
                dataPack.getPackId().toString(),
                dataPack.getVersion().toString(),
                ""
        ));
        assertTrue(snapshot.coversStackPacket(stackPacket));

        ResourcePacksInfoPacket invalidInfoPacket = new ResourcePacksInfoPacket();
        invalidInfoPacket.getResourcePackInfos().add(new ResourcePacksInfoPacket.Entry(
                UUID.randomUUID(),
                "9.9.9",
                1,
                "",
                "",
                "invalid",
                false,
                false,
                null,
                false
        ));
        assertFalse(snapshot.coversInfoPacket(invalidInfoPacket));
        assertTrue(snapshot.coversServer("alpha"));
        assertTrue(snapshot.coversServer("beta"));
        assertFalse(snapshot.coversServer("gamma"));
    }

    private static final class TestResourcePack extends ResourcePack {
        private final UUID packId;
        private final PackedVersion version;
        private final String type;
        private final byte[] hash;

        private TestResourcePack(UUID packId, String version, String type, byte[] hash) {
            super(Path.of("test"));
            this.packId = packId;
            String[] parts = version.split("\\.");
            this.version = new PackedVersion(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
            this.type = type;
            this.hash = hash;
            this.contentKey = "";
        }

        @Override
        public long getPackSize() {
            return this.hash.length;
        }

        @Override
        public byte[] getHash() {
            return this.hash.clone();
        }

        @Override
        public byte[] getChunk(int off, int len) {
            return this.hash.clone();
        }

        @Override
        public void saveToCache() {
        }

        @Override
        public ByteBuffer getCachedPack() {
            return null;
        }

        @Override
        public InputStream getStream(Path path) {
            return null;
        }

        @Override
        public UUID getPackId() {
            return this.packId;
        }

        @Override
        public PackedVersion getVersion() {
            return this.version;
        }

        @Override
        public String getType() {
            return this.type;
        }
    }
}
