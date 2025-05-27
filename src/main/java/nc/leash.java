package nc;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.UUID;
import java.util.stream.Collectors;

import static nc.SoulLeash.*;


public class leash implements Listener {
    // 玩家右键其他实体事件（用于绑定或解绑玩家）
    @EventHandler
    public void onLeash(PlayerInteractAtEntityEvent e) {
        // 如果右键的不是玩家，直接返回
        if (!(e.getRightClicked() instanceof Player)) return;
        // 只处理主手的交互（防止副手触发）
        if (!e.getHand().equals(EquipmentSlot.HAND)) return;

        // s：交互发起者（主人）
        Player s = e.getPlayer();
        // m：被交互者（目标玩家）
        Player m = (Player) e.getRightClicked();

        // 权限检查：s 必须拥有使用权限，m 必须允许被拴住
        if (!s.hasPermission("leashplayers.use")) return;
        if (!m.hasPermission("leashplayers.leashable")) return;

        UUID sUUID = s.getUniqueId(); // 主人的 UUID
        UUID mUUID = m.getUniqueId(); // 被拴者的 UUID

        // ----------- 使用绳子进行绑定逻辑 -----------
        if (s.getInventory().getItemInMainHand().getType() == Material.LEAD) {
            // 如果目标玩家已经被绑定，就不重复绑定
            if (isAlreadyBound(mUUID)) return;
            // 若该主人还没有绑定列表，则创建一个
            leashMap.putIfAbsent(sUUID, new ArrayList<>());
            // 将目标玩家加入主人的绑定列表
            leashMap.get(sUUID).add(mUUID);
            // 保存到配置文件（将 UUID 转为字符串）
            SoulLeash.leashDataConfig.set(
                    sUUID.toString(),
                    leashMap.get(sUUID).stream().map(UUID::toString).collect(Collectors.toList())
            );
            instance.saveLeashData();
            Helper.attachLeash(m, s);

            // 启动绑定状态下的效果任务
            startLeashTask(s, m);
            // 提示消息
            s.sendMessage("§d你拴住了 " + ChatColor.AQUA + m.getName() + " §d现在她是你的了！");
        }

        // ----------- 使用剑解除绑定逻辑 -----------
        if (isSword(s.getInventory().getItemInMainHand().getType())) {
            // 检查是否已经绑定
            if (leashMap.containsKey(sUUID) && leashMap.get(sUUID).contains(mUUID)) {
                // 从绑定列表中移除该玩家
                leashMap.get(sUUID).remove(mUUID);

                // 如果该主人的绑定列表为空，则从 map 中移除，并删除配置项
                if (leashMap.get(sUUID).isEmpty()) {
                    leashMap.remove(sUUID);
                    leashDataConfig.set(sUUID.toString(), null);
                } else {
                    // 否则更新配置文件中的 UUID 列表
                    leashDataConfig.set(
                            sUUID.toString(),
                            leashMap.get(sUUID).stream().map(UUID::toString).collect(Collectors.toList())
                    );
                }
                Helper.removeLeash(m.getUniqueId());
                Fence.removeFence(sUUID,mUUID);  // 从栅栏绑定缓存移除
                leashDataConfig.set("fence_bounds." + mUUID.toString(), null);  // 配置文件中删除
                // 保存到文件
                instance.saveLeashData();

                // 取消目标玩家身上的效果任务
                clearLeashTask(mUUID);

                // 提示消息
                s.sendMessage(ChatColor.RED + "你抛弃了 " + ChatColor.AQUA + m.getName() + ChatColor.RED + "，她现在只能流浪了");
            }
        }
    }
    // 启动一个任务让 M（仆从）始终跟随 S（主人）
    static void startLeashTask(Player s, Player m) {
        if (getFenceLeashManager().isPlayerOnFence(m)) {
            return; // 取消传送或传送逻辑
        }

        // 如果 M 已经有一个任务在运行，取消当前任务
        if (leashTasks.containsKey(m.getUniqueId())) {
            leashTasks.get(m.getUniqueId()).cancel();
        }

        // 用于记录 M 的上次位置和卡住开始时间
        final Location[] lastLocation = {null};
        final long[] stuckStartTime = {0};

        // 创建一个新的任务（每 tick 执行一次）
        BukkitRunnable task = new BukkitRunnable() {
            @Override
            public void run() {
                // 检查是否已解除绑定，如果解除则取消任务并禁用飞行
                if (!isLeashed(s.getUniqueId(), m.getUniqueId())) {
                    cancel();
                    m.setAllowFlight(false); // 解除绑定时禁用飞行
                    leashTasks.remove(m.getUniqueId());
                    return;
                }

                // 检查 S 和 M 是否在线，若不在线则取消任务
                if (!s.isOnline() || !m.isOnline()) {
                    cancel();
                    return;
                }

                // 获取 S 和 M 当前的位置
                Location sLoc = s.getLocation();
                Location mLoc = m.getLocation();

                // 强制 M 跟随 S，如果他们不在同一个世界，传送 M 到 S
                if (!sLoc.getWorld().equals(mLoc.getWorld())) {
                    Helper.removeLeash(m.getUniqueId());
                    m.teleport(sLoc);
                    Helper.attachLeash(m, s);
                    return;
                }

                // 计算 S 和 M 的距离
                double distance = sLoc.distance(mLoc);

                // 卡住检测逻辑：检测 M 是否卡住，若卡住超过 3 秒，发送提示
                if (distance > 7) {
                    if (lastLocation[0] != null) {
                        double movementXZ = Math.sqrt(Math.pow(lastLocation[0].getX() - mLoc.getX(), 1) +
                                Math.pow(lastLocation[0].getZ() - mLoc.getZ(), 1));
                        double deltaY = Math.abs(lastLocation[0].getY() - mLoc.getY());
                        double approach = sLoc.distance(lastLocation[0]) - distance;

                        // 如果 M 卡住且未移动超过 3 秒，发送提示信息
                        if (movementXZ < 0.1 && approach < 0.1 && deltaY <= 3.0) {
                            if (stuckStartTime[0] == 0) {
                                stuckStartTime[0] = System.currentTimeMillis(); // 开始计时
                            } else if (System.currentTimeMillis() - stuckStartTime[0] > 3000) {
                                // 超过 3 秒，提醒主人仆从被卡住了
                                String petName = m.getName();
                                s.spigot().sendMessage(ChatMessageType.ACTION_BAR,
                                        new TextComponent("§e你的小宠物 §d" + petName + " §e似乎被卡住了喵！"));
                                Helper.removeLeash(m.getUniqueId());
                                stuckStartTime[0] = 0; // 重置计时器
                            }
                        } else {
                            stuckStartTime[0] = 0; // 重置计时器（表示 M 正在移动）
                        }
                    }
                }

                // 强制拉动逻辑，根据距离调整 M 的速度
                Vector pull = null;
                if (distance > 48) {
                    m.teleport(sLoc); // 超远距离时直接传送
                    Helper.attachLeash(m, s);
                } else if (distance > 7 && m.isOnGround()) {
                    m.setVelocity(m.getVelocity().setY(0.3)); // 如果在地面，拉起 M
                    pull = sLoc.toVector().subtract(mLoc.toVector()).normalize().multiply(0.3); // 拉力
                } else if (distance > 6) {
                    pull = sLoc.toVector().subtract(mLoc.toVector()).normalize().multiply(0.2); // 拉力减少
                } else if (distance > 5.5) {
                    pull = sLoc.toVector().subtract(mLoc.toVector()).normalize().multiply(0.15);
                } else if (distance > 5) {
                    pull = sLoc.toVector().subtract(mLoc.toVector()).normalize().multiply(0.1);
                }

                // 如果需要拉动 M，则添加拉力
                if (pull != null) {
                    m.setVelocity(m.getVelocity().add(pull));
                }

                // 更新 M 的最后位置
                lastLocation[0] = mLoc.clone();
            }
        };

        // 启动任务，每 tick 执行一次
        task.runTaskTimer(instance, 0, 1);
        leashTasks.put(m.getUniqueId(), task); // 将任务存入 leashTasks
    }

    // 检查 S（绑定者）是否已经绑定了 M（被绑定者）
    private static boolean isLeashed(UUID sUUID, UUID mUUID) {
        // 如果 leashMap 中包含 S 的 UUID，并且 S 的绑定列表包含 M 的 UUID，则说明 S 已经绑定了 M
        return leashMap.containsKey(sUUID) && leashMap.get(sUUID).contains(mUUID);
    }
    // 检查 M（被绑定者）是否已经绑定到某个 S（绑定者）
    private boolean isAlreadyBound(UUID mUUID) {
        // 遍历 leashMap 的所有绑定关系，如果 M 的 UUID 在任何一个 S 的绑定列表中，说明 M 已经绑定
        return leashMap.values().stream().anyMatch(list -> list.contains(mUUID));
    }
    // 清除与 M（被绑定者）相关的绑定任务
    private void clearLeashTask(UUID mUUID) {
        // 检查 leashTasks 中是否存在 M 的绑定任务，如果存在，取消并移除该任务
        if (leashTasks.containsKey(mUUID)) {
            leashTasks.get(mUUID).cancel();  // 取消任务
            leashTasks.remove(mUUID);        // 从任务列表中移除
        }
    }
    // 检查传入的物品是否为剑类物品
    private boolean isSword(Material material) {
        // 判断物品的名称是否以 "_SWORD" 结尾，例如 "WOODEN_SWORD"、"IRON_SWORD" 等
        return material.name().endsWith("_SWORD");
    }
}
