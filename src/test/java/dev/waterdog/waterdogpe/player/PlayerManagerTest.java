package dev.waterdog.waterdogpe.player;

import dev.waterdog.waterdogpe.ProxyServer;
import dev.waterdog.waterdogpe.utils.config.proxy.ProxyConfig;
import dev.waterdog.waterdogpe.utils.types.Permission;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class PlayerManagerTest {

    @Test
    void subscribePermissionsSetsAdminForWildcardToken() {
        ProxyServer proxy = mock(ProxyServer.class);
        ProxyConfig config = mock(ProxyConfig.class);
        when(proxy.getConfiguration()).thenReturn(config);
        when(config.getDefaultPermissions()).thenReturn(List.of("waterdog.command.help"));

        Object2ObjectOpenHashMap<String, List<String>> playerPermissions = new Object2ObjectOpenHashMap<>();
        playerPermissions.put("mironlavreka", List.of("*", "waterdog.player.transfer"));
        when(config.getPlayerPermissions()).thenReturn(playerPermissions);

        ProxiedPlayer player = mock(ProxiedPlayer.class);
        when(player.getName()).thenReturn("mironlavreka");

        PlayerManager playerManager = new PlayerManager(proxy);
        playerManager.subscribePermissions(player);

        verify(player).addPermission(argThat((Permission permission) ->
                permission.getName().equals("waterdog.command.help") && permission.getValue()));
        verify(player).addPermission(argThat((Permission permission) ->
                permission.getName().equals("waterdog.player.transfer") && permission.getValue()));
        verify(player).setAdmin(true);
    }
}
