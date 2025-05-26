package nc;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityPortalEnterEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

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
        loc.setY(loc.getY() - 0.3);  // 往下调 0.3 格

        Zombie leashEntity = target.getWorld().spawn(target.getLocation(), Zombie.class, zombie -> {
            zombie.setAdult();
            zombie.setAI(false);
            zombie.setInvisible(true);
            zombie.setSilent(true);
            zombie.setInvulnerable(true);
            zombie.setCollidable(false);
            zombie.setCanPickupItems(false);
            zombie.setRemoveWhenFarAway(false);
            zombie.setShouldBurnInDay(false);
            zombie.getEquipment().clear();
            zombie.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, Integer.MAX_VALUE, 255, false, false));
            zombie.setLeashHolder(holder);
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

                Location followLoc = target.getLocation().clone();
                followLoc.setY(followLoc.getY() - 0.3); // 同样往下调 0.3 格
                leashEntity.teleport(target.getLocation());
            }
        }.runTaskTimer(plugin, 0L, 2L);
    }

    // 将实体加入“无碰撞”记分板队伍
    private static void makeNoCollision(Zombie zombie) {
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        Team team = scoreboard.getTeam("no_collision");

        if (team == null) {
            team = scoreboard.registerNewTeam("no_collision");
            team.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
        }

        team.addEntry(zombie.getUniqueId().toString());
    }

    /**
     * 监听玩家右键实体事件，阻止玩家与绑定的僵尸交互，避免点到僵尸
     */
    @EventHandler
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        Entity entity = event.getRightClicked();
        if (entity instanceof Zombie && playerToLeashEntity.containsValue(entity.getUniqueId())) {
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
    // 事件监听
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityPortalEnter(EntityPortalEnterEvent event) {
        Entity entity = event.getEntity();
        // 判断是否是被绑定的僵尸
        if (entity instanceof Zombie && playerToLeashEntity.containsValue(entity.getUniqueId())) {
            // 取消传送，阻止进入传送门
            event.setCancelled(true);
        }
    }
}
