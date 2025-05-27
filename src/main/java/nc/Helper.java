package nc;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Bat;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPortalEnterEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.EntityUnleashEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class Helper implements Listener {

    private static SoulLeash plugin = null;

    // key: 玩家UUID，value: 绑定的僵尸实体UUID
    private static final Map<UUID, UUID> playerToLeashEntity = new HashMap<>();

    public Helper(SoulLeash plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    /**
     * 给目标玩家附上绳子效果（隐形无敌僵尸作为绳子挂点）
     *
     * @param target 被拴的玩家
     * @param holder 拴绳的玩家
     */
    public static void attachLeash(Player target, Player holder) {

        Location loc = target.getLocation().clone();
        float yaw = loc.getYaw();
        double yawRad = Math.toRadians(yaw);
        double forwardX = -Math.sin(yawRad);
        double forwardZ = Math.cos(yawRad);
        double offsetX = -forwardX * 0.3;
        double offsetZ = -forwardZ * 0.3;
        double offsetY = 1;
        loc.add(offsetX, offsetY, offsetZ);

        Bat leashEntity = target.getWorld().spawn(loc, Bat.class, Bat -> {
            Bat.setAI(false);
            Bat.setInvisible(true);
            Bat.setSilent(true);
            Bat.setInvulnerable(true);
            Bat.setCollidable(false);
            Bat.setCanPickupItems(false);
            Bat.setRemoveWhenFarAway(false);
            Bat.getEquipment().clear();
            Bat.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, Integer.MAX_VALUE, 255, false, false));
            Bat.setLeashHolder(holder);
        });
        // 设置客户端也不和僵尸发生碰撞
        makeNoCollision(leashEntity);
        playerToLeashEntity.put(target.getUniqueId(), leashEntity.getUniqueId());

        new BukkitRunnable() {
            @Override
            public void run() {
                if (!target.isOnline() || !leashEntity.isValid() || !leashEntity.isLeashed()) {
                    playerToLeashEntity.remove(target.getUniqueId());
                    leashEntity.remove();
                    cancel();
                    return;
                }

                leashEntity.setInvulnerable(true);
                leashEntity.setFireTicks(0);

                Location loc = target.getLocation().clone();
                float yaw = loc.getYaw();
                double yawRad = Math.toRadians(yaw);
                double forwardX = -Math.sin(yawRad);
                double forwardZ = Math.cos(yawRad);
                double offsetX = -forwardX * 0.3;
                double offsetZ = -forwardZ * 0.3;
                double offsetY = 1;
                loc.add(offsetX, offsetY, offsetZ);

                leashEntity.teleport(loc);
            }
        }.runTaskTimer(plugin, 0L, 2L);
    }

    // 将实体加入“无碰撞”记分板队伍
    private static void makeNoCollision(Bat zombie) {
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        Team team = scoreboard.getTeam("no_collision");

        if (team == null) {
            team = scoreboard.registerNewTeam("no_collision");
            team.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
        }

        team.addEntry(zombie.getUniqueId().toString());
    }

    // 左键点击穿透：阻止攻击蝙蝠
    @EventHandler
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player)) return;
        if (playerToLeashEntity.containsValue(event.getEntity().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /**
     * 监听玩家右键实体事件，阻止玩家与绑定的僵尸交互，避免点到僵尸
     */
    @EventHandler
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        Entity entity = event.getRightClicked();
        if (entity instanceof Bat && playerToLeashEntity.containsValue(entity.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /**
     * 根据玩家UUID删除对应的僵尸实体
     *
     * @param playerUUID 玩家UUID
     */
    public static void removeLeash(UUID playerUUID) {
        UUID leashUUID = playerToLeashEntity.remove(playerUUID);
        if (leashUUID != null) {
            Entity entity = plugin.getServer().getEntity(leashUUID);
            if (entity != null && entity.isValid()) {
                entity.remove();
            }
        }
    }

    // 防止被目标生物锁定（例如铁傀儡、狼）
    @EventHandler
    public void onEntityTarget(EntityTargetEvent event) {
        if (event.getTarget() instanceof Player && playerToLeashEntity.containsValue(event.getEntity().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    // 事件监听
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityPortalEnter(EntityPortalEnterEvent event) {
        Entity entity = event.getEntity();
        // 判断是否是被绑定的僵尸
        if (entity instanceof Bat && playerToLeashEntity.containsValue(entity.getUniqueId())) {
            // 取消传送，阻止进入传送门
            event.setCancelled(true);
        }
    }
    @EventHandler
    public void onEntityUnleash(EntityUnleashEvent event) {
        Entity entity = event.getEntity();
        if (entity instanceof Bat && playerToLeashEntity.containsValue(entity.getUniqueId())) {
            event.setCancelled(true);   // 取消默认的断绳行为，包括掉落拴绳物品
            UUID toRemoveKey = null;
            for (Map.Entry<UUID, UUID> entry : playerToLeashEntity.entrySet()) {
                if (entry.getValue().equals(entity.getUniqueId())) {
                    toRemoveKey = entry.getKey();
                    break;
                }
            }
            if (toRemoveKey != null) {
                playerToLeashEntity.remove(toRemoveKey);
            }
            entity.remove();  // 直接删除蝙蝠实体，保证不掉落物品
        }
    }



}
