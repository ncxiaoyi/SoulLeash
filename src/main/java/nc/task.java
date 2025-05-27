package nc;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static nc.SoulLeash.*;
import static nc.leash.startLeashTask;

// Listeners 类：实现了 Bukkit 的事件监听器接口，用于处理事件和持续逻辑
public class task implements Listener {

    // 启动持续为被拴者添加效果的定时任务
    static void startLeashEffectTask(SoulLeash plugin) {
        new BukkitRunnable() {
            @Override
            public void run() {
                // 遍历所有主人 UUID
                for (UUID masterUUID : leashMap.keySet()) {
                    // 获取在线的主人玩家对象
                    Player master = Bukkit.getPlayer(masterUUID);
                    if (master != null && master.isOnline()) {
                        // 获取该主人绑定的所有玩家
                        List<UUID> followers = leashMap.get(masterUUID);
                        if (followers != null) {
                            // 遍历每一个被绑定的玩家
                            for (UUID followerUUID : followers) {
                                Player follower = Bukkit.getPlayer(followerUUID);
                                // 只对在线的绑定者生效
                                if (follower != null && follower.isOnline()) {
                                    // 创建生命恢复效果（2秒），无粒子
                                    PotionEffect effect = new PotionEffect(
                                            PotionEffectType.REGENERATION,
                                            50, // 持续 50 tick（2秒）
                                            0,  // 等级 1（0级代表第一档）
                                            true,  // 强制刷新已有效果
                                            false // 不显示粒子效果
                                    );
                                    // 添加该效果
                                    follower.addPotionEffect(effect);
                                }
                            }
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, 40L); // 立即启动，每 40 tick（2 秒）执行一次
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent e) {
        Player player = e.getPlayer(); // 获取玩家对象
        UUID playerUUID = player.getUniqueId(); // 获取玩家的唯一 ID

        // 检查该玩家是否已经绑定到栅栏上
        if (SoulLeash.getFenceLeashManager().isPlayerOnFence(player)) {
            return; // 如果是，直接返回，避免执行其他绑定相关的逻辑
        }

        // 延迟 1 秒后执行，确保玩家位置加载完成
        Bukkit.getScheduler().runTaskLater(instance, () -> {
            // 如果玩家是 S 玩家（主人），恢复绑定任务
            if (leashMap.containsKey(playerUUID)) {
                List<UUID> mUUIDs = leashMap.get(playerUUID); // 获取与 S 绑定的所有 M（仆从） UUID
                for (UUID mUUID : mUUIDs) {
                    Player m = Bukkit.getPlayer(mUUID); // 获取每个 M 的玩家对象
                    if (SoulLeash.getFenceLeashManager().isPlayerOnFence(m)) { // 新增判断：如果仆从被绑定在栅栏，跳过传送和绑定
                        continue;
                    }
                    if (m != null && m.isOnline()) { // 如果 M 在线
                        // 恢复绑定任务，让 M 跟随 S
                        startLeashTask(player, m);
                        m.teleport(player.getLocation()); // 将 M 传送到 S 的位置
                        Helper.attachLeash(m, player);
                    }
                }
            }

            // 如果玩家是 M 玩家（仆从），尝试从 pendingTeleport 中恢复绑定
            if (leashDataConfig.contains("pendingTeleport." + playerUUID)) {
                String sUUIDString = leashDataConfig.getString("pendingTeleport." + playerUUID); // 获取 S 的 UUID 字符串
                UUID sUUID = UUID.fromString(sUUIDString); // 转换为 UUID
                Player s = Bukkit.getPlayer(sUUID); // 获取 S 的玩家对象

                if (s != null && s.isOnline()) { // 如果 S 在线
                    // 恢复绑定任务，让 M 跟随 S
                    startLeashTask(s, player);
                    player.teleport(s.getLocation()); // 将 M 传送到 S 的位置
                }

                // 清除 pendingTeleport 中的记录
                leashDataConfig.set("pendingTeleport." + playerUUID, null);
            }
            instance.saveLeashData(); // 确保数据被保存
        }, 20L); // 延迟 1 秒执行
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent e) {
        UUID playerUUID = e.getPlayer().getUniqueId();

        // 检查玩家是否是主人或仆从，避免重复处理
        if (leashMap.containsKey(playerUUID)) {
            // 主人退出，保存数据
            return; // 退出不进行绑定解除
        }

        // 如果是仆从退出，则在主人的绑定列表中移除
        for (Map.Entry<UUID, List<UUID>> entry : leashMap.entrySet()) {
            Helper.removeLeash(playerUUID);
            if (entry.getValue().remove(playerUUID)) {
                UUID sUUID = entry.getKey(); // 获取主人的 UUID
                Player s = Bukkit.getPlayer(sUUID); // 获取主人对象

                // 如果主人在线，则继续跟随行为
                if (s != null && s.isOnline()) {
                    // 可根据需要处理
                }

                // 如果该主人没有绑定其他仆从，移除主人
                if (entry.getValue().isEmpty()) {
                    leashMap.remove(sUUID);
                }
                break;
            }
        }
    }

    // 监听实体受到伤害的事件
    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        // 检查伤害是否发生在玩家身上
        if (event.getEntity() instanceof Player) {
            Player player = (Player) event.getEntity(); // 获取玩家对象
            UUID playerUUID = player.getUniqueId(); // 获取玩家的 UUID

            // 检查是否有任何主人绑定了这个玩家
            if (leashMap.values().stream().anyMatch(list -> list.contains(playerUUID))) {
                // 如果伤害来源是摔落（Fall Damage），则取消伤害
                if (event.getCause() == EntityDamageEvent.DamageCause.FALL) {
                    event.setCancelled(true); // 取消摔落伤害
                }
            }
        }
    }
}
