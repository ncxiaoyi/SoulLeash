package nc;

import io.papermc.paper.entity.LookAnchor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.event.Listener;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class Lookat implements Listener{
    public final Map<UUID, BukkitRunnable> leashTasks = new HashMap<>();
    // 玩家右键使用特定物品（这里是不死图腾）时触发的事件
    @EventHandler
    public void onCherryButtonRightClick(PlayerInteractEvent event) {
        // 只处理主手的点击事件（防止副手触发两次）
        if (event.getHand() != EquipmentSlot.HAND) return;

        // 获取当前交互的玩家（作为“主人”）
        Player master = event.getPlayer();

        // 若玩家手中没有物品或不是不死图腾则退出
        if (event.getItem() == null || event.getItem().getType() != Material.TOTEM_OF_UNDYING) return;

        // 调用绑定仆从注视主人的逻辑（通用方法）
        performSoulLeashLook(master);
    }

    // 让所有被绑定的玩家看向主人
    public static void performSoulLeashLook(Player master) {
        // 获取主人的 UUID
        UUID masterUUID = master.getUniqueId();

        // 如果该玩家没有绑定任何人，则直接返回
        if (!SoulLeash.leashMap.containsKey(masterUUID)) return;

        // 获取该玩家绑定的所有“跟随者”UUID 列表
        List<UUID> followers = SoulLeash.leashMap.get(masterUUID);
        if (followers == null || followers.isEmpty()) return;

        // 获取主人的当前位置
        Location masterLocation = master.getLocation();

        // 遍历所有跟随者
        for (UUID followerUUID : followers) {
            // 根据 UUID 获取在线玩家对象
            Player follower = Bukkit.getPlayer(followerUUID);
            // 如果跟随者在线，则强制其朝向主人位置看（从眼睛方向）
            if (follower != null && follower.isOnline()) {
                follower.lookAt(masterLocation, LookAnchor.EYES);
            }
        }
    }
}
