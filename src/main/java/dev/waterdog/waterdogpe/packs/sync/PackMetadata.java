package dev.waterdog.waterdogpe.packs.sync;

import dev.waterdog.waterdogpe.packs.PackManager;
import dev.waterdog.waterdogpe.packs.types.ResourcePack;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

@Getter
@EqualsAndHashCode
public final class PackMetadata {

    private final UUID packId;
    private final String version;
    private final String type;
    private final long size;
    private final int chunkSize;
    private final int chunkCount;
    private final byte[] hash;
    private final String contentKey;

    public PackMetadata(UUID packId, String version, String type, long size, int chunkSize, int chunkCount, byte[] hash, String contentKey) {
        this.packId = packId;
        this.version = version;
        this.type = type;
        this.size = size;
        this.chunkSize = chunkSize;
        this.chunkCount = chunkCount;
        this.hash = hash.clone();
        this.contentKey = contentKey == null ? "" : contentKey;
    }

    public String idVersion() {
        return this.packId + "_" + this.version;
    }

    public void assertCompatible(PackMetadata other) {
        if (!this.packId.equals(other.packId)) {
            return;
        }

        boolean sameVersion = Objects.equals(this.version, other.version);
        boolean sameType = Objects.equals(this.type, other.type);
        boolean sameSize = this.size == other.size;
        boolean sameHash = Arrays.equals(this.hash, other.hash);
        boolean sameContentKey = Objects.equals(this.contentKey, other.contentKey);
        if (sameVersion && sameType && sameSize && sameHash && sameContentKey) {
            return;
        }

        throw new PackConflictException("Conflicting resource pack " + this.packId + ": "
                + this.describe() + " vs " + other.describe());
    }

    private String describe() {
        return "version=" + this.version + ", type=" + this.type + ", size=" + this.size + ", hash=" + Arrays.toString(this.hash);
    }

    public static PackMetadata fromPack(ResourcePack pack) {
        int chunkSize = PackManager.getDefaultChunkSize();
        long packSize = pack.getPackSize();
        int chunkCount = packSize <= 0 ? 0 : (int) ((packSize - 1) / chunkSize + 1);
        return new PackMetadata(
                pack.getPackId(),
                pack.getVersion().toString(),
                pack.getType(),
            packSize,
            chunkSize,
            chunkCount,
            pack.getHash(),
            pack.getContentKey()
        );
    }
}
