package nc;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.LeashHitch;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import static nc.SoulLeash.*;

public class Fence implements Listener {
    private final Map<UUID, Location> fenceBoundPlayers = new HashMap<>();
    public static File fenceLeashFile;
    public static FileConfiguration fenceLeashDataConfig;
    private final SoulLeash plugin;

    public Fence(SoulLeash plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    // 主人空手点栅栏，绑定所有他的从者到该处
    @EventHandler
    public void onPlayerRightClickFence(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getClickedBlock() == null || !isFence(e.getClickedBlock().getType())) return;
        if (e.getPlayer().getInventory().getItemInMainHand().getType() != Material.AIR) return;

        UUID sUUID = e.getPlayer().getUniqueId();
        List<UUID> mUUIDs = SoulLeash.leashMap.getOrDefault(sUUID, new ArrayList<>());

        Location fenceLocation = e.getClickedBlock().getLocation().add(0.5, 1, 0.5);

        for (UUID mUUID : mUUIDs) {
            Player m = Bukkit.getPlayer(mUUID);
            if (fenceBoundPlayers.containsKey(mUUID)) continue; // 如果已经固定在栅栏上，跳过
            if (m != null && m.isOnline()) {
                fenceBoundPlayers.put(mUUID, fenceLocation);
                startFenceLeashTask(sUUID, mUUID, fenceLocation); // 开始栅栏任务
                e.getPlayer().sendMessage("§d小宠物乖乖呆在这里了=v=");
            }
        }
    }

    // 解除栅栏绑定并恢复跟随
    @EventHandler
    public void onRightClickPlayer(org.bukkit.event.player.PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Player)) return;
        if (event.getHand() != EquipmentSlot.HAND) return;

        Player master = event.getPlayer();
        Player target = (Player) event.getRightClicked();

        if (master.getInventory().getItemInMainHand().getType() != Material.AIR) return;

        UUID mUUID = target.getUniqueId();
        UUID sUUID = master.getUniqueId();

        // 是不是主从关系
        if (!SoulLeash.leashMap.containsKey(sUUID)) return;
        if (!SoulLeash.leashMap.get(sUUID).contains(mUUID)) return;

        // 是否固定在栅栏上
        if (!fenceBoundPlayers.containsKey(mUUID)) return;

        // 解除绑定
        if (SoulLeash.leashTasks.containsKey(mUUID)) {
            SoulLeash.leashTasks.get(mUUID).cancel();
            SoulLeash.leashTasks.remove(mUUID);
        }
        Helper.removeLeash(mUUID);
        Helper.attachLeash(target, master);

        fenceBoundPlayers.remove(mUUID);
        leashDataConfig.set("fence_bounds." + mUUID, null);
        plugin.saveLeashData();


        leash.startLeashTask(master, target); // 恢复跟随
        master.sendMessage("§d继续带着小宠物玩=v=");
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();

        // ✅ 只有主人才允许触发绑定
        UUID sUUID = player.getUniqueId();
        if (!SoulLeash.leashMap.containsKey(sUUID) || SoulLeash.leashMap.get(sUUID).isEmpty()) return;

        // 空手右键栅栏
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (player.getInventory().getItemInMainHand().getType() != Material.AIR) return;

        Block clickedBlock = event.getClickedBlock();
        if (clickedBlock == null || !isFence(clickedBlock.getType())) return;

        // 判断是否有绑定的从者
        if (!SoulLeash.leashMap.containsKey(sUUID)) return;
        List<UUID> mUUIDs = SoulLeash.leashMap.get(sUUID);
        if (mUUIDs.isEmpty()) return;

        UUID mUUID = mUUIDs.get(0);
        Player mPlayer = Bukkit.getPlayer(mUUID);
        if (mPlayer == null || !mPlayer.isOnline()) return;

        // 判断是否已经在栅栏上
        if (fenceBoundPlayers.containsKey(mUUID)) {
            // 解除栅栏固定，恢复跟随

        } else {
            // 绑定到栅栏
            Location fenceLocation = clickedBlock.getLocation().add(0.5, 1, 0.5);
            fenceBoundPlayers.put(mUUID, fenceLocation); // 记录绑定位置
            startFenceLeashTask(sUUID, mUUID, fenceLocation); // 开始栅栏绑定任务
        }
    }

    public void startFenceLeashTask(UUID sUUID, UUID mUUID, Location fenceLocation) {
        // 先取消之前的任务
        if (SoulLeash.leashTasks.containsKey(mUUID)) {
            SoulLeash.leashTasks.get(mUUID).cancel();
        }

        // 记录栅栏绑定
        fenceBoundPlayers.put(mUUID, fenceLocation);

        // 创建任务以拉动玩家到栅栏附近
        BukkitRunnable task = new BukkitRunnable() {
            @Override
            public void run() {
                Player m = Bukkit.getPlayer(mUUID);
                if (m == null || !m.isOnline()) return;

                // 如果玩家的世界和栅栏世界不一致，直接传送到栅栏位置
                if (!m.getWorld().equals(fenceLocation.getWorld())) {
                    m.teleport(fenceLocation); // 维度不同直接传送
                    return;
                }

                Location mLoc = m.getLocation();
                double distance = mLoc.distance(fenceLocation);

                // 如果玩家距离栅栏超过48格，直接传送
                if (distance > 48) {
                    m.teleport(fenceLocation); // 超远距离直接传送
                } else if (distance > 5) {
                    // 如果玩家距离栅栏在5到7格之间，施加推力
                    @NotNull Vector pull;
                    if (distance > 7 && m.isOnGround()) {
                        m.setVelocity(m.getVelocity().setY(0.3)); // 适当的向上推力
                        pull = fenceLocation.toVector().subtract(mLoc.toVector()).normalize().multiply(0.5);
                    } else if (distance > 6) {
                        pull = fenceLocation.toVector().subtract(mLoc.toVector()).normalize().multiply(0.4);
                    } else if (distance > 5.5) {
                        pull = fenceLocation.toVector().subtract(mLoc.toVector()).normalize().multiply(0.3);
                    } else {
                        pull = fenceLocation.toVector().subtract(mLoc.toVector()).normalize().multiply(0.2);
                    }
                    m.setVelocity(pull); // 向栅栏拉近
                }
            }
        };

        // 每 1 tick 执行一次
        task.runTaskTimer(SoulLeash.instance, 0L, 1L);
        SoulLeash.leashTasks.put(mUUID, task);
    }


    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        // 确保 leashData 已初始化
        if (leashDataConfig == null) {
            plugin.initializeLeashData();  // 如果尚未初始化，则初始化它
        }

        // 检查是否包含 "fence_bounds" 中的数据
        if (leashDataConfig != null && leashDataConfig.contains("fence_bounds." + uuid.toString())) {
            Map<String, Object> locMap = leashDataConfig.getConfigurationSection("fence_bounds." + uuid.toString()).getValues(false);
            Location loc = Location.deserialize(locMap);

            // 开始栅栏任务
            fenceBoundPlayers.put(uuid, loc);

            UUID sUUID = getOwner(uuid);  // 你需要写个方法找出是谁绑定了他

            if (sUUID != null) {
                startFenceLeashTask(sUUID, uuid, loc);
            }
        } else {
            // 如果没有找到绑定数据，可以做一些默认处理
            plugin.getLogger().info("No fence bounds data for player: " + uuid);
        }
    }


    private UUID getOwner(UUID member) {
        for (Map.Entry<UUID, List<UUID>> entry : SoulLeash.leashMap.entrySet()) {
            if (entry.getValue().contains(member)) {
                return entry.getKey();
            }
        }
        return null;
    }

    public boolean isPlayerOnFence(Player uuid) {
        return fenceBoundPlayers.containsKey(uuid);
    }

    public static void saveFenceLeashData() {
        if (fenceLeashDataConfig == null || fenceLeashFile == null) return;

        // 清空旧数据
        fenceLeashDataConfig.set("data", null);

        // 保存新的绑定数据
        for (Map.Entry<UUID, List<UUID>> entry : fenceLeashMap.entrySet()) {
            List<String> uuidStrings = entry.getValue().stream()
                    .map(UUID::toString)
                    .collect(Collectors.toList());
            // 将绑定数据以 List<String> 存储
            fenceLeashDataConfig.set("data." + entry.getKey().toString(), uuidStrings);
        }

        // 保存到文件
        try {
            fenceLeashDataConfig.save(fenceLeashFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void loadFenceLeashData() {
        if (fenceLeashDataConfig == null) {
            // 确保初始化了配置
            File fenceLeashFile = new File(SoulLeash.getInstance().getDataFolder(), "fence_leash_data.yml");
            if (!fenceLeashFile.exists()) {
                try {
                    fenceLeashFile.createNewFile();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            fenceLeashDataConfig = YamlConfiguration.loadConfiguration(fenceLeashFile);
        }
        fenceLeashMap.clear();

        // 检查数据是否存在
        if (!fenceLeashDataConfig.contains("data")) return;

        // 加载绑定数据
        for (String key : fenceLeashDataConfig.getConfigurationSection("data").getKeys(false)) {
            UUID master = UUID.fromString(key);

            // 获取绑定者的 UUID 列表
            List<UUID> followers = fenceLeashDataConfig.getStringList("data." + key).stream()
                    .map(UUID::fromString)
                    .collect(Collectors.toList());

            // 将加载的绑定数据存入 map
            fenceLeashMap.put(master, followers);
        }
    }


    // 获取所有栅栏绑定的玩家
    public List<String> getAllLeashedFences() {
        List<String> allLeashedFences = new ArrayList<>();
        // 获取绑定的栅栏信息并返回
        // 假设你有一种方式能获得栅栏的ID和绑定的玩家信息
        return allLeashedFences;
    }

    private boolean isFence(Material material) {
        return material.name().endsWith("_FENCE");
    }


    @EventHandler
    public void onFenceBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Location loc = block.getLocation();

        // 遍历所有绑定的栅栏位置，判断是否匹配当前被破坏的方块
        for (Location fenceLoc : fenceBoundPlayers.values()) {
            // 注意 fenceLoc 是栅栏上方0.5格的位置，比较时要减去0.5或取BlockLocation比较
            Location fenceBlockLoc = fenceLoc.clone().add(0, -1, 0).getBlock().getLocation();

            if (fenceBlockLoc.equals(loc)) {
                event.setCancelled(true);
                return;
            }
        }
    }


    @EventHandler
    public void onLeashKnotInteract(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof LeashHitch hitch)) return;

        Location hitchLoc = hitch.getLocation().getBlock().getLocation();

        for (Location fenceLoc : fenceBoundPlayers.values()) {
            Location fenceBlockLoc = fenceLoc.clone().add(0, -1, 0).getBlock().getLocation();

            if (fenceBlockLoc.equals(hitchLoc)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onLeashHitchDamage(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof LeashHitch hitch) {
            Location hitchLoc = hitch.getLocation().getBlock().getLocation();

            for (Location fenceLoc : fenceBoundPlayers.values()) {
                Location fenceBlockLoc = fenceLoc.clone().add(0, -1, 0).getBlock().getLocation();
                if (fenceBlockLoc.equals(hitchLoc)) {
                    event.setCancelled(true); // 禁止破坏拴绳
                    return;
                }
            }
        }
    }

    @EventHandler
    public void onLeashHitchDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof LeashHitch hitch) {
            Location hitchLoc = hitch.getLocation().getBlock().getLocation();

            for (Location fenceLoc : fenceBoundPlayers.values()) {
                Location fenceBlockLoc = fenceLoc.clone().add(0, -1, 0).getBlock().getLocation();
                if (fenceBlockLoc.equals(hitchLoc)) {
                    event.setCancelled(true);
                    return;
                }
            }
        }
    }

}
