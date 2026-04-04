package dev.waterdog.waterdogpe.packs.sync;

import dev.waterdog.waterdogpe.packs.types.ResourcePack;
import lombok.Getter;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackStackPacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePacksInfoPacket;

import java.util.*;

@Getter
public final class PackSnapshot {

    public static final String EDU_PACK_ID = "0fba4063-dba1-4281-9b89-ff9390653530";
    public static final String EDU_PACK_VERSION = "1.0.0";

    private final Map<UUID, ResourcePack> packs;
    private final Map<String, ResourcePack> packsByIdVersion;
    private final Map<UUID, PackMetadata> metadataById;
    private final Map<String, Set<String>> serverPacks;
    private final ResourcePacksInfoPacket packsInfoPacket;
    private final ResourcePackStackPacket stackPacket;

    private PackSnapshot(Map<UUID, ResourcePack> packs,
                         Map<String, ResourcePack> packsByIdVersion,
                         Map<UUID, PackMetadata> metadataById,
                         Map<String, Set<String>> serverPacks,
                         ResourcePacksInfoPacket packsInfoPacket,
                         ResourcePackStackPacket stackPacket) {
        this.packs = packs;
        this.packsByIdVersion = packsByIdVersion;
        this.metadataById = metadataById;
        this.serverPacks = serverPacks;
        this.packsInfoPacket = packsInfoPacket;
        this.stackPacket = stackPacket;
    }

    public static PackSnapshot create(Collection<ResourcePack> packs,
                                      Collection<PackMetadata> metadata,
                                      Map<String, Set<String>> serverPacks,
                                      boolean forceServerPacks,
                                      boolean overwriteClientPacks,
                                      boolean educationEnabled) {
        Map<UUID, ResourcePack> packsById = new LinkedHashMap<>();
        for (ResourcePack pack : packs) {
            packsById.put(pack.getPackId(), pack);
        }

        Map<String, ResourcePack> packsByIdVersion = new LinkedHashMap<>();
        for (ResourcePack pack : packsById.values()) {
            packsByIdVersion.put(pack.getPackId() + "_" + pack.getVersion(), pack);
        }

        Map<UUID, PackMetadata> metadataById = new LinkedHashMap<>();
        for (PackMetadata packMetadata : metadata) {
            metadataById.put(packMetadata.getPackId(), packMetadata);
        }

        ResourcePacksInfoPacket infoPacket = new ResourcePacksInfoPacket();
        infoPacket.setForcedToAccept(forceServerPacks);
        infoPacket.setWorldTemplateId(UUID.randomUUID());
        infoPacket.setWorldTemplateVersion("");

        ResourcePackStackPacket stackPacket = new ResourcePackStackPacket();
        stackPacket.setForcedToAccept(overwriteClientPacks);
        stackPacket.setGameVersion("");

        metadataById.values().stream()
                .sorted(Comparator.comparing(pack -> pack.getPackId().toString()))
                .forEach(pack -> {
                    ResourcePacksInfoPacket.Entry infoEntry = new ResourcePacksInfoPacket.Entry(
                            pack.getPackId(),
                            pack.getVersion(),
                            pack.getSize(),
                            pack.getContentKey(),
                            "",
                            pack.getPackId().toString(),
                            false,
                            false,
                            null,
                            ResourcePack.TYPE_DATA.equals(pack.getType())
                    );
                    ResourcePackStackPacket.Entry stackEntry = new ResourcePackStackPacket.Entry(
                            pack.getPackId().toString(),
                            pack.getVersion(),
                            ""
                    );

                    if (ResourcePack.TYPE_RESOURCES.equals(pack.getType())) {
                        infoPacket.getResourcePackInfos().add(infoEntry);
                        stackPacket.getResourcePacks().add(stackEntry);
                    } else if (ResourcePack.TYPE_DATA.equals(pack.getType())) {
                        infoPacket.getBehaviorPackInfos().add(infoEntry);
                        stackPacket.getBehaviorPacks().add(stackEntry);
                    }
                });

        if (educationEnabled) {
            stackPacket.getBehaviorPacks().add(new ResourcePackStackPacket.Entry(EDU_PACK_ID, EDU_PACK_VERSION, ""));
        }

        Map<String, Set<String>> immutableServerPacks = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : serverPacks.entrySet()) {
            immutableServerPacks.put(entry.getKey().toLowerCase(Locale.ROOT), Collections.unmodifiableSet(new LinkedHashSet<>(entry.getValue())));
        }

        return new PackSnapshot(
                Collections.unmodifiableMap(packsById),
                Collections.unmodifiableMap(packsByIdVersion),
                Collections.unmodifiableMap(metadataById),
                Collections.unmodifiableMap(immutableServerPacks),
                infoPacket,
                stackPacket
        );
    }

    public ResourcePack packFromIdVersion(String idVersion) {
        return this.packsByIdVersion.get(idVersion);
    }

    public boolean coversServer(String serverName) {
        return this.serverPacks.containsKey(serverName.toLowerCase(Locale.ROOT));
    }

    public boolean containsPack(UUID packId, String version) {
        PackMetadata metadata = this.metadataById.get(packId);
        return metadata != null && Objects.equals(metadata.getVersion(), version);
    }

    public boolean coversInfoPacket(ResourcePacksInfoPacket packet) {
        return this.coversInfoEntries(packet.getBehaviorPackInfos()) && this.coversInfoEntries(packet.getResourcePackInfos());
    }

    public boolean coversStackPacket(ResourcePackStackPacket packet) {
        return this.coversStackEntries(packet.getBehaviorPacks()) && this.coversStackEntries(packet.getResourcePacks());
    }

    private boolean coversInfoEntries(List<ResourcePacksInfoPacket.Entry> entries) {
        for (ResourcePacksInfoPacket.Entry entry : entries) {
            if (!this.containsPack(entry.getPackId(), entry.getPackVersion())) {
                return false;
            }
        }
        return true;
    }

    private boolean coversStackEntries(List<ResourcePackStackPacket.Entry> entries) {
        for (ResourcePackStackPacket.Entry entry : entries) {
            if (EDU_PACK_ID.equals(entry.packId()) && EDU_PACK_VERSION.equals(entry.packVersion())) {
                continue;
            }

            UUID packId;
            try {
                packId = UUID.fromString(entry.packId());
            } catch (IllegalArgumentException ignored) {
                return false;
            }

            if (!this.containsPack(packId, entry.packVersion())) {
                return false;
            }
        }
        return true;
    }
}
