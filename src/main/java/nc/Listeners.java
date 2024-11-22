package nc;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;


public class Listeners implements Listener {

    private final SoulLeash main = SoulLeash.instance;
    private final Map<UUID, List<UUID>> leashMap = new HashMap<>();
    private final Map<UUID, BukkitRunnable> leashTasks = new HashMap<>();
    private final File leashDataFile = new File(main.getDataFolder(), "leash_data.yml");
    private FileConfiguration leashDataConfig;

    private static final ThreadLocal<AtomicBoolean> isTeleporting = ThreadLocal.withInitial(AtomicBoolean::new);

    public Listeners() {
        if (!leashDataFile.exists()) {
            try {
                leashDataFile.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        leashDataConfig = YamlConfiguration.loadConfiguration(leashDataFile);
        loadLeashData();
    }

    @EventHandler
    public void onLeash(PlayerInteractAtEntityEvent e) {
        if (!(e.getRightClicked() instanceof Player)) return;
        if (!e.getHand().equals(EquipmentSlot.HAND)) return;

        Player s = e.getPlayer();
        Player m = (Player) e.getRightClicked();

        if (!s.hasPermission("leashplayers.use")) return;
        if (!m.hasPermission("leashplayers.leashable")) return;

        UUID sUUID = s.getUniqueId();
        UUID mUUID = m.getUniqueId();

        // 使用绳子绑定
        if (s.getInventory().getItemInMainHand().getType() == Material.LEAD) {
            if (isAlreadyBound(mUUID)) return;

            leashMap.putIfAbsent(sUUID, new ArrayList<>());
            leashMap.get(sUUID).add(mUUID);
            leashDataConfig.set(sUUID.toString(), leashMap.get(sUUID).stream().map(UUID::toString).collect(Collectors.toList()));
            saveLeashData();

            startLeashTask(s, m);
            s.sendMessage(ChatColor.GREEN + "你拴住了 " + ChatColor.AQUA + m.getName() + ChatColor.GREEN + "，现在她是你的了！");

        }

        // 使用剑解除绑定
        if (isSword(s.getInventory().getItemInMainHand().getType())) {
            if (leashMap.containsKey(sUUID) && leashMap.get(sUUID).contains(mUUID)) {
                leashMap.get(sUUID).remove(mUUID);
                if (leashMap.get(sUUID).isEmpty()) {
                    leashMap.remove(sUUID);
                    leashDataConfig.set(sUUID.toString(), null);
                } else {
                    leashDataConfig.set(sUUID.toString(), leashMap.get(sUUID).stream().map(UUID::toString).collect(Collectors.toList()));
                }
                saveLeashData();

                clearLeashTask(mUUID);
                s.sendMessage(ChatColor.RED + "你抛弃了 " + ChatColor.AQUA + m.getName() + ChatColor.RED + "，她现在只能流浪了");
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent e) {
        UUID playerUUID = e.getPlayer().getUniqueId();

        // 如果玩家是 S 玩家，保存数据但不移除绑定关系
        if (leashMap.containsKey(playerUUID)) {
            saveLeashData();
            return;
        }

        // 如果玩家是 M 玩家
        for (Map.Entry<UUID, List<UUID>> entry : leashMap.entrySet()) {
            if (entry.getValue().remove(playerUUID)) {
                UUID sUUID = entry.getKey();
                Player s = Bukkit.getPlayer(sUUID);

                // 如果 S 在线，保存待处理传送任务
                if (s != null && s.isOnline()) {
                    leashDataConfig.set("pendingTeleport." + playerUUID, sUUID.toString());
                    saveLeashData();
                }

                // 如果 S 绑定的所有 M 玩家都下线，清除空列表
                if (entry.getValue().isEmpty()) {
                    leashMap.remove(sUUID);
                }
                break;
            }
        }
    }


    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player) {
            Player player = (Player) event.getEntity();
            UUID playerUUID = player.getUniqueId();

            if (leashMap.values().stream().anyMatch(list -> list.contains(playerUUID))) {
                if (event.getCause() == EntityDamageEvent.DamageCause.FALL) {
                    event.setCancelled(true);
                }
            }
        }
    }

    private void startLeashTask(Player s, Player m) {
        if (leashTasks.containsKey(m.getUniqueId())) {
            leashTasks.get(m.getUniqueId()).cancel();
        }

        // 用于记录 M 的位置和时间
        final Location[] lastLocation = {null};
        final long[] stuckStartTime = {0};

        BukkitRunnable task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!isLeashed(s.getUniqueId(), m.getUniqueId())) {
                    cancel();
                    m.setAllowFlight(false); // 解除绑定时禁用飞行
                    leashTasks.remove(m.getUniqueId());
                    return;
                }

                if (!s.isOnline() || !m.isOnline()) {
                    cancel();
                    return;
                }

                Location sLoc = s.getLocation();
                Location mLoc = m.getLocation();

                // 强制 M 跟随 S 的逻辑
                if (!sLoc.getWorld().equals(mLoc.getWorld())) {
                    m.teleport(sLoc);
                    return;
                }

                double distance = sLoc.distance(mLoc);

                // 卡住检测逻辑
                if (distance > 5.5) {
                    if (lastLocation[0] != null) {
                        double movementXZ = Math.sqrt(Math.pow(lastLocation[0].getX() - mLoc.getX(), 2) +
                                Math.pow(lastLocation[0].getZ() - mLoc.getZ(), 2));
                        double deltaY = Math.abs(lastLocation[0].getY() - mLoc.getY());
                        double approach = sLoc.distance(lastLocation[0]) - distance;

                        // 忽略高度跳动（小于等于 3 格）
                        if (movementXZ < 0.1 && approach < 0.1 && deltaY <= 5.0) {
                            if (stuckStartTime[0] == 0) {
                                // 开始记录卡住时间
                                stuckStartTime[0] = System.currentTimeMillis();
                            } else if (System.currentTimeMillis() - stuckStartTime[0] > 3000) {
                                // 超过 3 秒，发送提示信息
                                String petName = m.getName();
                                s.spigot().sendMessage(ChatMessageType.ACTION_BAR,
                                        new TextComponent("§e你的小宠物 §d" + petName + " §e似乎被卡住了喵！"));
                                stuckStartTime[0] = 0; // 重置计时器
                            }
                        } else {
                            // 运动明显或 Y 轴变化大，重置计时器
                            stuckStartTime[0] = 0;
                        }
                    }
                }

                // 强制拉动逻辑
                Vector pull = null;
                if (distance > 48) {
                    m.teleport(sLoc);
                } else if (distance > 7 && m.isOnGround()) {
                    m.setVelocity(m.getVelocity().setY(0.3));
                    pull = sLoc.toVector().subtract(mLoc.toVector()).normalize().multiply(0.3);
                } else if (distance > 6) {
                    pull = sLoc.toVector().subtract(mLoc.toVector()).normalize().multiply(0.2);
                } else if (distance > 5.5) {
                    pull = sLoc.toVector().subtract(mLoc.toVector()).normalize().multiply(0.15);
                } else if (distance > 5) {
                    pull = sLoc.toVector().subtract(mLoc.toVector()).normalize().multiply(0.1);
                }

                if (pull != null) {
                    m.setVelocity(m.getVelocity().add(pull));
                }

                // 更新 M 的最后位置
                lastLocation[0] = mLoc.clone();
            }
        };

        m.setAllowFlight(true); // 启用飞行
        task.runTaskTimer(main, 0, 1);
        leashTasks.put(m.getUniqueId(), task);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent e) {
        Player player = e.getPlayer();
        UUID playerUUID = player.getUniqueId();

        Bukkit.getScheduler().runTaskLater(main, () -> {
            // 如果玩家是 S 玩家
            if (leashMap.containsKey(playerUUID)) {
                List<UUID> mUUIDs = leashMap.get(playerUUID);
                for (UUID mUUID : mUUIDs) {
                    Player m = Bukkit.getPlayer(mUUID);
                    if (m != null && m.isOnline()) {
                        // 恢复绑定任务
                        startLeashTask(player, m);
                        m.teleport(player.getLocation()); // TP 到 S
                        m.setAllowFlight(true); // 允许飞行
                    }
                }
            }

            // 如果是 M 玩家，尝试从 pendingTeleport 中恢复绑定
            if (leashDataConfig.contains("pendingTeleport." + playerUUID)) {
                String sUUIDString = leashDataConfig.getString("pendingTeleport." + playerUUID);
                UUID sUUID = UUID.fromString(sUUIDString);
                Player s = Bukkit.getPlayer(sUUID);

                if (s != null && s.isOnline()) {
                    // 恢复绑定任务
                    startLeashTask(s, player);
                    player.teleport(s.getLocation()); // TP 到 S
                    player.setAllowFlight(true); // 允许飞行

                    // 恢复绑定关系到 leashMap
                    leashMap.putIfAbsent(sUUID, new ArrayList<>());
                    leashMap.get(sUUID).add(playerUUID);

                    // 清除 pendingTeleport 中的记录
                    leashDataConfig.set("pendingTeleport." + playerUUID, null);
                    saveLeashData();
                }
            } else {
                // 如果 pendingTeleport 中没有，检查 leashMap
                for (Map.Entry<UUID, List<UUID>> entry : leashMap.entrySet()) {
                    if (entry.getValue().contains(playerUUID)) {
                        UUID sUUID = entry.getKey();
                        Player s = Bukkit.getPlayer(sUUID);

                        if (s != null && s.isOnline()) {
                            // 恢复绑定任务
                            startLeashTask(s, player);
                            player.teleport(s.getLocation()); // TP 到 S
                            player.setAllowFlight(true); // 允许飞行
                        }
                        break;
                    }
                }
            }

            saveLeashData();
        }, 20L); // 延迟 1 秒以确保玩家位置加载完成
    }



    @EventHandler
    public void onPlayerTeleport(PlayerTeleportEvent e) {
        Player s = e.getPlayer();
        UUID sUUID = s.getUniqueId();

        // 如果玩家是绑定者（S）
        if (leashMap.containsKey(sUUID)) {
            List<UUID> mUUIDs = leashMap.get(sUUID);
            for (UUID mUUID : mUUIDs) {
                Player m = Bukkit.getPlayer(mUUID);
                if (m != null && m.isOnline()) {
                    Location destination = e.getTo();
                    if (destination != null && !destination.getWorld().equals(m.getWorld())) {
                        m.teleport(destination); // 仅跨世界时传送
                        m.setAllowFlight(true);
                    }
                }
            }
        }

        // 如果玩家是被绑定者（M）
        for (Map.Entry<UUID, List<UUID>> entry : leashMap.entrySet()) {
            if (entry.getValue().contains(sUUID)) {
                UUID sUUIDKey = entry.getKey();
                Player sPlayer = Bukkit.getPlayer(sUUIDKey);

                if (sPlayer != null && sPlayer.isOnline()) {
                    Location destination = sPlayer.getLocation();
                    if (!e.getTo().getWorld().equals(destination.getWorld())) {
                        e.getPlayer().teleport(destination); // 仅跨世界时传送
                        sPlayer.setAllowFlight(true);
                    }
                }
                break;
            }
        }
    }


    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent e) {
        Player player = e.getPlayer();
        UUID playerUUID = player.getUniqueId();

        Bukkit.getScheduler().runTaskLater(main, () -> {
            // 如果玩家是被绑定者（M）
            for (Map.Entry<UUID, List<UUID>> entry : leashMap.entrySet()) {
                if (entry.getValue().contains(playerUUID)) {
                    UUID sUUID = entry.getKey();
                    Player s = Bukkit.getPlayer(sUUID);
                    if (s != null && s.isOnline()) {
                        player.teleport(s.getLocation()); // 将 M 传送到 S
                        startLeashTask(s, player); // 恢复绑定任务
                        player.setAllowFlight(true);
                    }
                    break;
                }
            }

            // 如果玩家是绑定者（S）
            if (leashMap.containsKey(playerUUID)) {
                List<UUID> mUUIDs = leashMap.get(playerUUID);
                for (UUID mUUID : mUUIDs) {
                    Player m = Bukkit.getPlayer(mUUID);
                    if (m != null && m.isOnline()) {
                        m.teleport(player.getLocation()); // 将 M 传送到 S
                        startLeashTask(player, m); // 恢复绑定任务
                        m.setAllowFlight(true);

                    }
                }
            }
        }, 20L); // 延迟 1 秒以确保玩家重生位置已加载
    }

    @EventHandler
    public void onPlayerPortal(PlayerPortalEvent e) {
        Player player = e.getPlayer();
        UUID playerUUID = player.getUniqueId();

        // 如果是S玩家，确保M跟随
        if (leashMap.containsKey(playerUUID)) {
            List<UUID> mUUIDs = leashMap.get(playerUUID);
            for (UUID mUUID : mUUIDs) {
                Player m = Bukkit.getPlayer(mUUID);
                if (m != null && m.isOnline()) {
                    Bukkit.getScheduler().runTaskLater(main, () -> m.teleport(player.getLocation()), 10L);
                }
            }
        }

        // 如果是M玩家，确保跟随S
        for (Map.Entry<UUID, List<UUID>> entry : leashMap.entrySet()) {
            if (entry.getValue().contains(playerUUID)) {
                UUID sUUID = entry.getKey();
                Player s = Bukkit.getPlayer(sUUID);
                if (s != null && s.isOnline()) {
                    Bukkit.getScheduler().runTaskLater(main, () -> player.teleport(s.getLocation()), 10L);
                }
                break;
            }
        }
    }


    private void loadLeashData() {
        leashMap.clear();
        leashDataConfig.getKeys(false).forEach(key -> {
            try {
                // 检查键是否为有效的 UUID
                UUID sUUID = UUID.fromString(key);
                List<UUID> boundUUIDs = leashDataConfig.getStringList(key).stream()
                        .map(UUID::fromString).collect(Collectors.toList());
                leashMap.put(sUUID, boundUUIDs);
            } catch (IllegalArgumentException e) {
                // 忽略无效的 UUID 键，例如 "pendingTeleport"
            }
        });
    }


    private void saveLeashData() {
        // 保存绑定关系
        leashMap.forEach((uuid, mUUIDs) -> {
            leashDataConfig.set(uuid.toString(), mUUIDs.stream().map(UUID::toString).collect(Collectors.toList()));
        });

        // 保存待处理传送任务
        try {
            leashDataConfig.save(leashDataFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    private boolean isLeashed(UUID sUUID, UUID mUUID) {
        return leashMap.containsKey(sUUID) && leashMap.get(sUUID).contains(mUUID);
    }

    private boolean isAlreadyBound(UUID mUUID) {
        return leashMap.values().stream().anyMatch(list -> list.contains(mUUID));
    }

    private void clearLeashTask(UUID mUUID) {
        if (leashTasks.containsKey(mUUID)) {
            leashTasks.get(mUUID).cancel();
            leashTasks.remove(mUUID);
        }
    }


    private boolean isSword(Material material) {
        return material.name().endsWith("_SWORD");
    }
}
