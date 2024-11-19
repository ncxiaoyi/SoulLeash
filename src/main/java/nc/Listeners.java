package nc;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

public class Listeners implements Listener {

    private final SoulLeash main = SoulLeash.instance;
    private final Map<UUID, List<UUID>> leashMap = new HashMap<>();
    private final Map<UUID, BukkitRunnable> leashTasks = new HashMap<>();
    private final File leashDataFile = new File(main.getDataFolder(), "leash_data.yml");
    private FileConfiguration leashDataConfig;

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

            if (leashMap.containsKey(mUUID)) return;

            // 绑定关系
            leashMap.putIfAbsent(sUUID, new ArrayList<>());
            leashMap.get(sUUID).add(mUUID);
            leashDataConfig.set(sUUID.toString(), leashMap.get(sUUID).stream().map(UUID::toString).collect(Collectors.toList()));
            saveLeashData();

            startLeashTask(s, m);
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
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent e) {
        UUID playerUUID = e.getPlayer().getUniqueId();

        if (leashMap.containsKey(playerUUID)) {
            // s 玩家退出
            List<UUID> mUUIDs = new ArrayList<>(leashMap.get(playerUUID));
            for (UUID mUUID : mUUIDs) {
                clearLeashTask(mUUID); // 清理任务
                Player m = Bukkit.getPlayer(mUUID);
            }
        } else {
            // m 玩家退出
            for (Map.Entry<UUID, List<UUID>> entry : leashMap.entrySet()) {
                if (entry.getValue().contains(playerUUID)) {
                    clearLeashTask(playerUUID);
                    Player s = Bukkit.getPlayer(entry.getKey());
                    break;
                }
            }
        }

        saveLeashData();
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent e) {
        Player player = e.getPlayer();
        UUID playerUUID = player.getUniqueId();

        Bukkit.getScheduler().runTaskLater(main, () -> {
            for (Map.Entry<UUID, List<UUID>> entry : leashMap.entrySet()) {
                UUID sUUID = entry.getKey();
                List<UUID> mUUIDs = entry.getValue();

                if (sUUID.equals(playerUUID)) {
                    // s 玩家重进，恢复所有绑定关系
                    for (UUID mUUID : new ArrayList<>(mUUIDs)) {
                        Player m = Bukkit.getPlayer(mUUID);
                        if (m != null && m.isOnline()) {
                            m.teleport(player.getLocation());
                            startLeashTask(player, m);
                        }
                    }
                } else if (mUUIDs.contains(playerUUID)) {
                    // m 玩家重进，传送到绑定者位置
                    Player s = Bukkit.getPlayer(sUUID);
                    if (s != null && s.isOnline()) {
                        player.teleport(s.getLocation());
                        startLeashTask(s, player);
                    }
                }
            }
        }, 20L);
    }

    private void startLeashTask(Player s, Player m) {
        BukkitRunnable task = new BukkitRunnable() {
            private boolean isRecentlyTeleported = true;

            @Override
            public void run() {
                if (!isLeashed(s.getUniqueId(), m.getUniqueId())) {
                    cancel();
                    leashTasks.remove(m.getUniqueId());
                    return;
                }

                Location sLoc = s.getLocation();
                Location mLoc = m.getLocation();

                try {
                    double distance = sLoc.distance(mLoc);

                    if (isRecentlyTeleported) {
                        if (distance > 1) isRecentlyTeleported = false;
                        return;
                    }

                    if (distance > 5) {
                        Vector pull = sLoc.toVector().subtract(mLoc.toVector()).normalize().multiply(0.3);
                        m.setVelocity(m.getVelocity().add(pull));
                    }

                    if (distance > 6 && m.isOnGround()) {
                        m.setVelocity(m.getVelocity().setY(0.42));
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
        };

        task.runTaskTimer(main, 0, 1);
        leashTasks.put(m.getUniqueId(), task);
    }

    private void clearLeashTask(UUID playerUUID) {
        if (leashTasks.containsKey(playerUUID)) {
            leashTasks.get(playerUUID).cancel();
            leashTasks.remove(playerUUID);
        }
    }

    private boolean isSword(Material material) {
        return material.name().endsWith("_SWORD");
    }

    private boolean isAlreadyBound(UUID mUUID) {
        return leashMap.values().stream().anyMatch(list -> list.contains(mUUID));
    }

    private boolean isLeashed(UUID sUUID, UUID mUUID) {
        return leashMap.containsKey(sUUID) && leashMap.get(sUUID).contains(mUUID);
    }

    private void loadLeashData() {
        for (String sUUIDStr : leashDataConfig.getKeys(false)) {
            try {
                UUID sUUID = UUID.fromString(sUUIDStr);
                List<UUID> mUUIDs = leashDataConfig.getStringList(sUUIDStr).stream().map(UUID::fromString).collect(Collectors.toList());
                leashMap.put(sUUID, mUUIDs);
            } catch (IllegalArgumentException e) {
                main.getLogger().warning("LP3在数据中发现无效的 UUID: " + sUUIDStr);
            }
        }
    }

    private void saveLeashData() {
        for (UUID sUUID : leashMap.keySet()) {
            List<String> mUUIDs = leashMap.get(sUUID).stream().map(UUID::toString).collect(Collectors.toList());
            leashDataConfig.set(sUUID.toString(), mUUIDs);
        }
        try {
            leashDataConfig.save(leashDataFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
