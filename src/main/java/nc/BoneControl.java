package nc;

import org.bukkit.*;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.*;
import java.util.regex.Pattern;

import static nc.SoulLeash.leashMap;

public class BoneControl implements Listener {

    private static final Pattern COLOR_PATTERN = Pattern.compile("(?i)(§[0-9A-FK-ORX]|&#[a-fA-F0-9]{6})");
    private static final Pattern PREFIX_SUFFIX_PATTERN = Pattern.compile("^(喵~|\\[.+?])|喵~$");

    private boolean isMasterOf(UUID master, UUID servant) {
        return leashMap.containsKey(master) && leashMap.get(master).contains(servant);
    }

    private boolean isWearingBone(Player p) {
        ItemStack helmet = p.getInventory().getHelmet();
        return helmet != null && helmet.getType() == Material.BONE &&
                helmet.containsEnchantment(Enchantment.BINDING_CURSE);
    }

    private ItemStack getCursedBone() {
        ItemStack bone = new ItemStack(Material.BONE);
        bone.addUnsafeEnchantment(Enchantment.BINDING_CURSE, 1);
        return bone;
    }

    @EventHandler
    public void onRightClick(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Player)) return;
        if (event.getHand() != EquipmentSlot.HAND) return;

        Player master = event.getPlayer();
        Player servant = (Player) event.getRightClicked();

        UUID masterId = master.getUniqueId();
        UUID servantId = servant.getUniqueId();

        if (!isMasterOf(masterId, servantId)) return;

        ItemStack hand = master.getInventory().getItemInMainHand();

        if (hand.getType() == Material.BONE) {
            if (isWearingBone(servant)) {
                return;
            }
            PlayerInventory inv = servant.getInventory();
            ItemStack currentHelmet = inv.getHelmet();
            if (currentHelmet != null && currentHelmet.getType() != Material.AIR) {
                HashMap<Integer, ItemStack> leftover = inv.addItem(currentHelmet);
                if (!leftover.isEmpty()) {
                    for (ItemStack i : leftover.values()) {
                        servant.getWorld().dropItemNaturally(servant.getLocation(), i);
                    }
                }
            }
            inv.setHelmet(getCursedBone());
            master.sendMessage("§e呜呜呜不能说话了qwq");

        } else if (hand.getType() == Material.SHEARS) {
            if (isWearingBone(servant)) {
                servant.getInventory().setHelmet(null);
                servant.getWorld().playSound(servant.getLocation(), Sound.ENTITY_SHEEP_SHEAR, 1, 1.2f);
                master.sendMessage("§a可以乖乖说话了owo");
            }
        }
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        Player p = event.getPlayer();
        if (!isWearingBone(p)) return;

        String msg = event.getMessage();
        msg = ChatColor.stripColor(msg);
        msg = COLOR_PATTERN.matcher(msg).replaceAll("");
        msg = PREFIX_SUFFIX_PATTERN.matcher(msg).replaceAll("");

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < msg.length(); i++) {
            sb.append("呜");
        }

        event.setMessage(sb.toString());
    }

    @EventHandler
    public void onMsg(PlayerCommandPreprocessEvent event) {
        Player p = event.getPlayer();
        String msg = event.getMessage();
        if (!isWearingBone(p)) return;

        if (msg.startsWith("/msg ") || msg.startsWith("/tell ") || msg.startsWith("/w ")) {
            String[] parts = msg.split(" ", 3);
            if (parts.length >= 3) {
                String original = parts[2];
                original = ChatColor.stripColor(original);
                original = COLOR_PATTERN.matcher(original).replaceAll("");
                original = PREFIX_SUFFIX_PATTERN.matcher(original).replaceAll("");

                String censored = "呜".repeat(original.length());
                event.setMessage(parts[0] + " " + parts[1] + " " + censored);
            }
        }
    }
}
