package dev.waterdog.waterdogpe.utils.config.proxy;

import lombok.Getter;
import lombok.experimental.Accessors;
import net.cubespace.Yamler.Config.Comment;
import net.cubespace.Yamler.Config.Path;
import net.cubespace.Yamler.Config.YamlConfig;

@Getter
public class PackSyncSettings extends YamlConfig {

    @Path("enabled")
    @Accessors(fluent = true)
    @Comment("Enable automatic resource pack sync from Allay backends")
    private boolean enabled = true;

    @Path("startup_timeout_seconds")
    @Comment("How long WaterdogPE should wait for the first full pack sync snapshot during startup")
    private int startupTimeoutSeconds = 30;

    @Path("refresh_interval_seconds")
    @Comment("How often WaterdogPE should refresh the synchronized pack snapshot")
    private int refreshIntervalSeconds = 60;

    @Path("block_login_until_ready")
    @Accessors(fluent = true)
    @Comment("If enabled, player logins will be rejected until the first synchronized pack snapshot is ready")
    private boolean blockLoginUntilReady = true;
}
