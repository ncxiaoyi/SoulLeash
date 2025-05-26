package nc;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Map;
import java.util.UUID;

import static nc.SoulLeash.leashMap;

public class FoodShare implements Listener {

    private final SoulLeash plugin;
    private final long COOLDOWN_TIME = 1000L; // 1秒冷却时间
    private final Map<UUID, Long> playerCooldowns = new java.util.HashMap<>();

    public FoodShare(SoulLeash plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onFeedPartner(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;

        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        UUID playerUUID = player.getUniqueId();

        if (item == null || item.getType() == Material.AIR) return;

        // 获取绑定对象
        UUID targetUUID = getBoundPartner(playerUUID);
        if (targetUUID == null || !(event.getRightClicked() instanceof Player)) return;

        Player target = (Player) event.getRightClicked();
        if (!target.getUniqueId().equals(targetUUID)) return;

        // ✅ 牛奶处理逻辑：清除药水效果并替换为桶
        if (item.getType() == Material.MILK_BUCKET) {
            event.setCancelled(true); // 防止自己喝

            for (PotionEffect effect : target.getActivePotionEffects()) {
                target.removePotionEffect(effect.getType());
            }

            item.setAmount(item.getAmount() - 1);
            player.getInventory().addItem(new ItemStack(Material.BUCKET));

            target.getWorld().spawnParticle(Particle.HEART, target.getLocation().add(0, 1.5, 0), 3);
            target.playSound(target.getLocation(), Sound.ENTITY_GENERIC_DRINK, 1.0f, 1.2f);
            return;
        }

        // ✅ 剩下才判断是否为食物
        if (!item.getType().isEdible()) return;

        // 🕒 冷却判断
        if (playerCooldowns.containsKey(playerUUID)) {
            long lastActionTime = playerCooldowns.get(playerUUID);
            if (System.currentTimeMillis() - lastActionTime < COOLDOWN_TIME) {
                return;
            }
        }
        playerCooldowns.put(playerUUID, System.currentTimeMillis());

        Material originalType = item.getType();
        applyFoodEffects(originalType, target);

        if (target.getFoodLevel() < 20 && player.getFoodLevel() > 0) {
            int addedFood = getFoodValue(item.getType());
            if (addedFood <= 0) return;

            event.setCancelled(true);
            item.setAmount(item.getAmount() - 1);

            target.setFoodLevel(Math.min(20, target.getFoodLevel() + addedFood));
            target.setSaturation(Math.min(20f, target.getSaturation() + addedFood * 0.6f));

            target.getWorld().spawnParticle(Particle.HEART, target.getLocation().add(0, 1.5, 0), 3);
            target.playSound(target.getLocation(), Sound.ENTITY_GENERIC_EAT, 1.0f, 1.2f);
        }
    }


    private UUID getBoundPartner(UUID uuid) {
        // 查找谁绑定了谁（双向判断）
        for (Map.Entry<UUID, java.util.List<UUID>> entry : leashMap.entrySet()) {
            if (entry.getKey().equals(uuid)) {
                // 我是主人，返回第一个绑定者
                return entry.getValue().isEmpty() ? null : entry.getValue().get(0);
            }
            if (entry.getValue().contains(uuid)) {
                // 我是绑定者，返回主人
                return entry.getKey();
            }
        }
        return null;
    }

    private int getFoodValue(Material mat) {
        switch (mat) {
            case BREAD: return 5;
            case COOKED_BEEF: return 8;
            case COOKED_PORKCHOP: return 8;
            case COOKED_CHICKEN: return 6;
            case COOKED_MUTTON: return 6;
            case COOKED_RABBIT: return 5;
            case APPLE: return 4;
            case GOLDEN_APPLE: return 4;
            case ENCHANTED_GOLDEN_APPLE: return 4; // 金苹果改成20
            case CARROT: return 3;
            case POTATO: return 1;
            case BAKED_POTATO: return 5;
            case POISONOUS_POTATO: return 2;
            case BEETROOT: return 1;
            case DRIED_KELP: return 1;
            case BEEF: return 3;
            case COOKIE: return 2;
            case PORKCHOP: return 3;
            case CHICKEN: return 2;
            case MUTTON: return 2;
            case RABBIT: return 3;
            case COD: return 2;
            case COOKED_COD: return 5;
            case SALMON: return 2;
            case COOKED_SALMON: return 6;
            case TROPICAL_FISH: return 1;
            case PUFFERFISH: return 1;
            case SWEET_BERRIES: return 2;
            case MELON_SLICE: return 2;
            case GOLDEN_CARROT: return 6; // 金胡萝卜的饱食度设为6
            case GLOW_BERRIES: return 2;
            case CHORUS_FRUIT: return 4;
            case CAKE: return 30;
            case PUMPKIN: return 8;
            case ROTTEN_FLESH: return 4;
            case SPIDER_EYE: return 2;
            case MUSHROOM_STEM: return 6;
            case BEETROOT_SOUP: return 6;
            case RABBIT_STEW: return 10;
            case SUSPICIOUS_STEW: return 6;
            case HONEY_BOTTLE: return 6;
        }
        return 0;
    }

    private void applyFoodEffects(Material type, Player target) {
        // 根据不同的食物类型应用相应的药水效果
        switch (type) {
            case GOLDEN_APPLE:
                target.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 100, 1)); // 5秒 恢复 II
                target.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 2400, 0)); // 2分钟 吸收 I
                break;
            case ENCHANTED_GOLDEN_APPLE:
                target.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 400, 1)); // 20秒 恢复 II
                target.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 6000, 0));  // 5分钟 抗性 I
                target.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 6000, 0)); // 5分钟 火抗
                target.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 2400, 3)); // 2分钟 吸收 IV
                break;
        }
    }
}
