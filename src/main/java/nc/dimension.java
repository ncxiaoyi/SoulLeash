package nc;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static nc.SoulLeash.instance;
import static nc.SoulLeash.leashMap;

public class dimension implements Listener {
    @EventHandler
    public void onPlayerTeleport(PlayerTeleportEvent e) {

        Player s = e.getPlayer(); // 获取传送的玩家（S）
        UUID sUUID = s.getUniqueId(); // 获取玩家的唯一 ID

        // 如果玩家是绑定者（S），即他有绑定的仆从（M）
        if (leashMap.containsKey(sUUID)) {
            List<UUID> mUUIDs = leashMap.get(sUUID); // 获取与 S 绑定的所有 M（仆从） UUID
            for (UUID mUUID : mUUIDs) {
                Player m = Bukkit.getPlayer(mUUID); // 获取每个 M 的玩家对象
                if (SoulLeash.getFenceLeashManager().isPlayerOnFence(m)) {
                    return; // 取消传送或传送逻辑
                }
                if (m != null && m.isOnline()) { // 如果 M 在线
                    Location destination = e.getTo(); // 获取传送的目的地
                    if (destination != null && !destination.getWorld().equals(m.getWorld())) {
                        // 如果目的地的世界与 M 所在世界不同，进行传送
                        m.teleport(destination); // 将 M 传送到 S 的目的地
//                    m.setAllowFlight(true); // 如果需要，可以允许 M 飞行
                    }
                }
            }
        }

        // 如果玩家是被绑定者（M）
        for (Map.Entry<UUID, List<UUID>> entry : leashMap.entrySet()) {
            if (entry.getValue().contains(sUUID)) { // 检查当前 S 是否绑定了玩家 M
                UUID sUUIDKey = entry.getKey(); // 获取 S 的 UUID
                Player sPlayer = Bukkit.getPlayer(sUUIDKey); // 获取绑定者 S 的玩家对象

                if (sPlayer != null && sPlayer.isOnline()) { // 如果 S 在线
                    Location destination = sPlayer.getLocation(); // 获取 S 的位置
                    if (!e.getTo().getWorld().equals(destination.getWorld())) {
                        // 如果传送的目的地和 S 的世界不同，进行传送
                        e.getPlayer().teleport(destination); // 将 M 传送到 S 的位置
//                    sPlayer.setAllowFlight(true); // 如果需要，可以允许 S 飞行
                    }
                }
                break; // 如果已经找到对应的绑定关系，可以结束循环
            }
        }
    }
    // 监听玩家进入传送门事件，确保绑定关系不受影响
    @EventHandler
    public void onPlayerPortal(PlayerPortalEvent e) {
        Player player = e.getPlayer();                // 获取触发传送门事件的玩家对象
        UUID playerUUID = player.getUniqueId();       // 获取玩家的唯一 ID

        // -------- 如果玩家是绑定者（S），确保所有 M 跟随 -----------
        if (leashMap.containsKey(playerUUID)) {       // 如果玩家是 S（有绑定的仆从）
            List<UUID> mUUIDs = leashMap.get(playerUUID);  // 获取与 S 绑定的所有 M（仆从）UUID
            for (UUID mUUID : mUUIDs) {
                Player m = Bukkit.getPlayer(mUUID);    // 获取每个 M 的玩家对象
                if (SoulLeash.getFenceLeashManager().isPlayerOnFence(m)) {
                    return; // 取消传送或传送逻辑
                }
                if (m != null && m.isOnline()) {       // 如果 M 在线
                    // 延迟 10 tick 后将 M 传送到 S 传送门的目的地
                    Bukkit.getScheduler().runTaskLater(instance, () -> m.teleport(player.getLocation()), 10L);
                }
            }
        }

        // -------- 如果玩家是被绑定者（M），确保 M 跟随 S -----------
        for (Map.Entry<UUID, List<UUID>> entry : leashMap.entrySet()) {
            if (entry.getValue().contains(playerUUID)) { // 如果玩家是 M（被某个 S 绑定）
                UUID sUUID = entry.getKey();            // 获取 S 的 UUID
                Player s = Bukkit.getPlayer(sUUID);     // 获取 S 的玩家对象
                if (s != null && s.isOnline()) {        // 如果 S 在线
                    // 延迟 10 tick 后将 M 传送到 S 的位置
                    Bukkit.getScheduler().runTaskLater(instance, () -> player.teleport(s.getLocation()), 10L);
                }
                break;  // 找到后跳出循环
            }
        }
    }
}
