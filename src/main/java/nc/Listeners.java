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
                s.sendMessage(ChatColor.RED + "你抛弃了 " + ChatColor.AQUA + m.getName() + ChatColor.RED + "，现在无家她可归了！");
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent e) {
        UUID playerUUID = e.getPlayer().getUniqueId();

        if (leashMap.containsKey(playerUUID)) {
            List<UUID> mUUIDs = new ArrayList<>(leashMap.get(playerUUID));
            for (UUID mUUID : mUUIDs) {
                clearLeashTask(mUUID);
            }
            leashMap.remove(playerUUID);
        } else {
            for (Map.Entry<UUID, List<UUID>> entry : leashMap.entrySet()) {
                if (entry.getValue().remove(playerUUID)) {
                    clearLeashTask(playerUUID);
                    if (entry.getValue().isEmpty()) leashMap.remove(entry.getKey());
                    break;
                }
            }
        }
        saveLeashData();
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
        // 如果已存在任务，先取消
        if (leashTasks.containsKey(m.getUniqueId())) {
            leashTasks.get(m.getUniqueId()).cancel();
        }

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

                if (!sLoc.getWorld().equals(mLoc.getWorld())) {
                    m.teleport(sLoc);
                    m.setAllowFlight(true);
                    return;
                }

                double distance = sLoc.distance(mLoc);

                // 距离大于 48 时传送
                if (distance > 48) {
                    m.teleport(sLoc);
                    return;

                } else if (distance > 5.5) {
                    // 拉近逻辑
                    Vector pull = sLoc.toVector().subtract(mLoc.toVector()).normalize().multiply(0.15);
                    m.setVelocity(m.getVelocity().add(pull));
                    if (distance > 7 && m.isOnGround()) {
                        m.setVelocity(m.getVelocity().setY(0.3)); // 弹跳拉近
                    }
                }
            }
        };

        m.setAllowFlight(true); // 启用飞行
        task.runTaskTimer(main, 0, 1); // 每秒更新一次
        leashTasks.put(m.getUniqueId(), task);
    }


    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent e) {
        Player player = e.getPlayer();
        UUID playerUUID = player.getUniqueId();

        Bukkit.getScheduler().runTaskLater(main, () -> {
            // 恢复S玩家绑定
            if (leashMap.containsKey(playerUUID)) {
                List<UUID> mUUIDs = leashMap.get(playerUUID);
                for (UUID mUUID : mUUIDs) {
                    Player m = Bukkit.getPlayer(mUUID);
                    if (m != null && m.isOnline()) {
                        // 如果S玩家和M玩家不在同一个世界，传送M到S的位置
                        if (!player.getWorld().equals(m.getWorld())) {
                            m.teleport(player.getLocation());
                        }
                        startLeashTask(player, m); // 启动任务
                    }
                }
            }

            // 恢复M玩家绑定
            for (Map.Entry<UUID, List<UUID>> entry : leashMap.entrySet()) {
                if (entry.getValue().contains(playerUUID)) {
                    UUID sUUID = entry.getKey();
                    Player s = Bukkit.getPlayer(sUUID);
                    if (s != null && s.isOnline()) {
                        // 如果S玩家和M玩家不在同一个世界，传送M到S的位置
                        if (!s.getWorld().equals(player.getWorld())) {
                            player.teleport(s.getLocation());
                        }
                        startLeashTask(s, player); // 启动任务
                    }
                    break;
                }
            }
        }, 20L);
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
            List<UUID> boundUUIDs = leashDataConfig.getStringList(key).stream()
                    .map(UUID::fromString).collect(Collectors.toList());
            leashMap.put(UUID.fromString(key), boundUUIDs);
        });
    }

    private void saveLeashData() {
        leashMap.forEach((uuid, mUUIDs) -> {
            leashDataConfig.set(uuid.toString(), mUUIDs.stream().map(UUID::toString).collect(Collectors.toList()));
        });
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
