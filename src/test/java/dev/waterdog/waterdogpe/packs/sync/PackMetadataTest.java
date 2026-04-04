package dev.waterdog.waterdogpe.packs.sync;

import dev.waterdog.waterdogpe.packs.types.PackedVersion;
import dev.waterdog.waterdogpe.packs.types.ResourcePack;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PackMetadataTest {

    @Test
    void assertCompatibleRejectsConflictingVersionOrHash() {
        UUID packId = UUID.randomUUID();
        PackMetadata first = PackMetadata.fromPack(new TestResourcePack(packId, "1.0.0", ResourcePack.TYPE_RESOURCES, new byte[]{1, 2, 3}));
        PackMetadata second = new PackMetadata(packId, "2.0.0", ResourcePack.TYPE_RESOURCES, 3, 102400, 1, new byte[]{4, 5, 6}, "");

        assertThrows(PackConflictException.class, () -> first.assertCompatible(second));
    }

    @Test
    void assertCompatibleAcceptsSamePack() {
        UUID packId = UUID.randomUUID();
        PackMetadata first = PackMetadata.fromPack(new TestResourcePack(packId, "1.0.0", ResourcePack.TYPE_RESOURCES, new byte[]{1, 2, 3}));
        PackMetadata second = new PackMetadata(packId, "1.0.0", ResourcePack.TYPE_RESOURCES, 3, 102400, 1, new byte[]{1, 2, 3}, "");

        assertDoesNotThrow(() -> first.assertCompatible(second));
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
