package nc;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.*;

import static nc.SoulLeash.leashMap;

public class SummonAll implements Listener {

    private final Map<UUID, Long> cooldownMap = new HashMap<>();
    private static final long CD = 10 * 60 * 1000L; // 10分钟

    @EventHandler
    public void onUseStar(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;

        Player p = e.getPlayer();
        ItemStack item = p.getInventory().getItemInMainHand();
        if (item == null || item.getType() != Material.NETHER_STAR) return;

        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();

        if (cooldownMap.containsKey(id) && now - cooldownMap.get(id) < CD) {
            long left = (CD - (now - cooldownMap.get(id))) / 1000;
            p.sendMessage(ChatColor.RED + "冷却中哦，不要老想着传送，多在意一下你的小宝贝吧（还需 " + left + " 秒！）");
            return;
        }

        List<UUID> list = leashMap.get(id);
        if (list == null || list.isEmpty()) {
            return;
        }

        Location loc = p.getLocation();

        for (UUID uid : list) {
            Player f = Bukkit.getPlayer(uid);
            if (f != null && f.isOnline()) {
                f.teleport(loc);
//                Helper.attachLeash(f, p);
                f.playSound(loc, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
            }
        }

        cooldownMap.put(id, now);
        p.sendMessage(ChatColor.GREEN + "你召唤了你的小宠物过来了=v=！");
    }
}
