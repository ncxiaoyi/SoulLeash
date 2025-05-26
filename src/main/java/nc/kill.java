package nc;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static nc.SoulLeash.instance;
import static nc.SoulLeash.leashMap;
import static nc.leash.startLeashTask;

public class kill implements Listener {
    // 监听玩家重生事件，恢复绑定关系并传送
    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent e) {
        Player player = e.getPlayer();                  // 获取重生的玩家对象
        UUID playerUUID = player.getUniqueId();         // 获取玩家的唯一 ID

        // 延迟 1 秒执行，确保重生位置完全加载
        Bukkit.getScheduler().runTaskLater(instance, () -> {
            // -------- 如果玩家是被绑定者（M），先处理 M 的逻辑 -----------
            for (Map.Entry<UUID, List<UUID>> entry : leashMap.entrySet()) {
                if (SoulLeash.getFenceLeashManager().isPlayerOnFence(player)) {
                    return; // 取消传送或传送逻辑
                }
                if (entry.getValue().contains(playerUUID)) {  // 如果 M 被某个 S 绑定
                    UUID sUUID = entry.getKey();             // 获取 S 的 UUID
                    Player s = Bukkit.getPlayer(sUUID);      // 获取 S 的玩家对象
                    if (s != null && s.isOnline()) {         // S 在线时才执行
                        player.teleport(s.getLocation());    // 将 M 传送到 S 的位置
                        startLeashTask(s, player);           // 恢复跟随任务
//                    player.setAllowFlight(true);       // 如果需要，可以允许 M 飞行
                    }
                    break;  // 找到后跳出循环
                }
            }

            // -------- 如果玩家是绑定者（S），再处理 S 的逻辑 -----------
            if (leashMap.containsKey(playerUUID)) {
                List<UUID> mUUIDs = leashMap.get(playerUUID);  // 获取 S 绑定的所有 M UUID 列表
                for (UUID mUUID : mUUIDs) {
                    Player m = Bukkit.getPlayer(mUUID);
                    if (SoulLeash.getFenceLeashManager().isPlayerOnFence(m)) {
                        return; // 取消传送或传送逻辑
                    }// 获取每个 M 的玩家对象
                    if (m != null && m.isOnline()) {            // M 在线时才执行
                        m.teleport(player.getLocation());       // 将 M 传送到 S 的位置
                        startLeashTask(player, m);              // 恢复跟随任务
//                    m.setAllowFlight(true);               // 如果需要，可以允许 M 飞行
                    }
                }
            }
        }, 20L); // 延迟 20 tick（1秒）执行
    }
}
