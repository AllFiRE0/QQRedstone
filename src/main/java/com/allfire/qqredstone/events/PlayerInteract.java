package com.allfire.qqredstone.events;

import com.allfire.qqredstone.QQRedstone;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;

import java.util.ArrayList;
import java.util.List;

public class PlayerInteract implements Listener {

    @EventHandler(priority = EventPriority.LOWEST)
    public void onMechanismClick(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        
        // Принимаем и RIGHT_CLICK_BLOCK и PHYSICAL (для плит)
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_BLOCK && action != Action.PHYSICAL)
            return;

        Block clickedBlock = event.getClickedBlock();
        if (clickedBlock == null)
            return;

        ItemStack itemInHand = event.getItem();
        if (itemInHand == null || itemInHand.getType() != Material.WRITABLE_BOOK)
            return;

        QQRedstone plugin = QQRedstone.getInstance();

        BookMeta bookMeta = (BookMeta) itemInHand.getItemMeta();
        if (bookMeta == null)
            return;

        String displayName = bookMeta.hasDisplayName() ? bookMeta.getDisplayName() : "";
        String senderName = plugin.getSenderName();
        String receiverName = plugin.getReceiverName();

        String role = null;
        if (displayName.equalsIgnoreCase(senderName)) {
            role = "sender";
        } else if (displayName.equalsIgnoreCase(receiverName)) {
            role = "receiver";
        }

        // Книга не подписана — выходим молча
        if (role == null) {
            return;
        }

        // Проверяем механизм
        if (!isValidMechanism(clickedBlock, role)) {
            return;
        }

        // СРАЗУ отменяем событие (книга не откроется)
        event.setCancelled(true);
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);

        // Дальше все проверки...
        String worldName = clickedBlock.getWorld().getName();
        if (!player.hasPermission("qqredstone.worlds.use." + worldName) 
                && !player.hasPermission("qqredstone.worlds.use.*")) {
            plugin.sendMessage(player, "world-denied", "%world%", worldName);
            return;
        }

        String frequency = (bookMeta.getPageCount() > 0) ? bookMeta.getPage(1).trim() : "0";

        if (!plugin.getWorldGuardUtils().canBuild(player, clickedBlock)) {
            plugin.sendMessage(player, "no-permission-region");
            return;
        }

        if (!player.hasPermission("qqredstone.use." + role) && !player.hasPermission("qqredstone.use.all")) {
            plugin.sendMessage(player, "no-permission-mechanism");
            return;
        }

        if (!plugin.getWorldGuardUtils().checkStateFlag(player, clickedBlock,
                plugin.getWorldGuardManager().getUseFlag(), true)) {
            plugin.sendMessage(player, "region-denied");
            return;
        }

        if (role.equals("sender") && !plugin.getWorldGuardUtils().checkStateFlag(player, clickedBlock,
                plugin.getWorldGuardManager().getSenderFlag(), true)) {
            plugin.sendMessage(player, "region-denied-sender");
            return;
        }
        if (role.equals("receiver") && !plugin.getWorldGuardUtils().checkStateFlag(player, clickedBlock,
                plugin.getWorldGuardManager().getReceiverFlag(), true)) {
            plugin.sendMessage(player, "region-denied-receiver");
            return;
        }

        if (isOwnedByOther(player, clickedBlock)) {
            plugin.sendMessage(player, "already-owned");
            return;
        }

        int bookPower = readBookPower(bookMeta);
        int maxPowerByPerms = getMaxPowerByPermission(player);
        int maxPowerByRegion = plugin.getWorldGuardUtils().getMaxPower(player, clickedBlock);
        int maxPower = Math.min(maxPowerByPerms, maxPowerByRegion);

        String powerMode = plugin.getConfig().getString("transmission.power-mode", "vanilla");
        boolean bookPowerUsed = false;
        int savedPower = 0;

        if (bookPower > 0) {
            if ((powerMode.equals("book") || powerMode.equals("both"))
                    && plugin.getWorldGuardUtils().checkStateFlag(player, clickedBlock,
                    plugin.getWorldGuardManager().getBookPowerFlag(), true)) {
                if (bookPower > maxPower) {
                    plugin.sendMessage(player, "power-limited",
                            "%max%", String.valueOf(maxPower),
                            "%book%", String.valueOf(bookPower));
                    bookPower = maxPower;
                }
                savedPower = bookPower;
                bookPowerUsed = true;
            }
        }

        saveLocation(player, clickedBlock, frequency, role, bookPowerUsed ? savedPower : 0);

        if (role.equals("sender")) {
            if (bookPowerUsed && savedPower > 0) {
                plugin.sendMessage(player, "registered-sender-power",
                        "%frequency%", frequency,
                        "%power%", String.valueOf(savedPower));
            } else {
                plugin.sendMessage(player, "registered-sender", "%frequency%", frequency);
            }
        } else {
            if (bookPowerUsed && savedPower > 0) {
                plugin.sendMessage(player, "registered-receiver-power",
                        "%frequency%", frequency,
                        "%power%", String.valueOf(savedPower));
            } else {
                plugin.sendMessage(player, "registered-receiver", "%frequency%", frequency);
            }
        }
    }

    private int readBookPower(BookMeta meta) {
        if (meta.getPageCount() < 2) return 0;
        try {
            String text = meta.getPage(2).trim();
            text = text.replaceAll("§[0-9a-fk-or]", "").trim();
            int power = Integer.parseInt(text);
            return Math.min(Math.max(power, 0), 15);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private int getMaxPowerByPermission(Player player) {
        for (int i = 15; i >= 1; i--) {
            if (player.hasPermission("qqredstone.power." + i)) {
                return i;
            }
        }
        return 1;
    }

    private boolean isValidMechanism(Block block, String role) {
        QQRedstone plugin = QQRedstone.getInstance();
        String type = block.getType().name();

        boolean acceptAll = plugin.getConfig().getBoolean("mechanism.accept-all", false);
        if (acceptAll) {
            return type.equals("LEVER") 
                || type.contains("BUTTON") 
                || type.contains("PRESSURE_PLATE")
                || type.contains("LIGHTNING_ROD") 
                || type.equals("REDSTONE_TORCH") 
                || type.equals("REDSTONE_WALL_TORCH");
        }

        List<String> allowedTypes = plugin.getConfig().getStringList("mechanism." + role);
        if (allowedTypes == null || allowedTypes.isEmpty()) {
            String singleType = plugin.getConfig().getString("mechanism." + role, "LIGHTNING_ROD");
            allowedTypes = new ArrayList<>();
            allowedTypes.add(singleType);
        }

        String material = plugin.getConfig().getString("mechanism." + role + "-material", "");

        for (String allowed : allowedTypes) {
            switch (allowed) {
                case "LEVER":
                    if (type.equals("LEVER")) return true;
                    break;
                case "BUTTON":
                    if (type.contains("BUTTON")) {
                        if (material.isEmpty() || type.equals(material)) return true;
                    }
                    break;
                case "PRESSURE_PLATE":
                    if (type.contains("PRESSURE_PLATE")) {
                        if (material.isEmpty() || type.equals(material)) return true;
                    }
                    break;
                case "LIGHTNING_ROD":
                    if (type.contains("LIGHTNING_ROD")) return true;
                    break;
                case "REDSTONE_TORCH":
                    if (type.equals("REDSTONE_TORCH") || type.equals("REDSTONE_WALL_TORCH")) return true;
                    break;
            }
        }

        return false;
    }

    private boolean isOwnedByOther(Player player, Block block) {
        QQRedstone plugin = QQRedstone.getInstance();
        String locStr = getLocString(block);
        String ownerKey = "ownership." + locStr.replace(",", ".");
        String owner = plugin.getConfig().getString(ownerKey);

        ConfigurationSection data = plugin.getConfig().getConfigurationSection("data");
        if (data == null) return false;

        for (String freq : data.getKeys(false)) {
            List<String> senders = plugin.getConfig().getStringList("data." + freq + ".senders");
            List<String> receivers = plugin.getConfig().getStringList("data." + freq + ".receivers");

            if ((senders.contains(locStr) || receivers.contains(locStr))
                    && owner != null && !owner.equals(player.getUniqueId().toString())) {
                return !player.hasPermission("qqredstone.admin.override");
            }
        }
        return false;
    }

    private void saveLocation(Player player, Block block, String freq, String type, int bookPower) {
        QQRedstone plugin = QQRedstone.getInstance();
        String locString = getLocString(block);
        String path = "data." + freq + "." + type + "s";

        List<String> locations = plugin.getConfig().getStringList(path);
        if (locations == null)
            locations = new ArrayList<>();

        boolean changed = false;

        if (!locations.contains(locString)) {
            // Новая запись
            locations.add(locString);
            plugin.getConfig().set(path, locations);
            changed = true;
        }

        // Всегда обновляем владельца и мощность
        String ownerKey = "ownership." + locString.replace(",", ".");
        String oldOwner = plugin.getConfig().getString(ownerKey);
        String newOwner = player.getUniqueId().toString();

        if (!newOwner.equals(oldOwner)) {
            plugin.getConfig().set(ownerKey, newOwner);
            changed = true;
        }

        String powerKey = "book-power." + locString.replace(",", ".");
        int oldPower = plugin.getConfig().getInt(powerKey, -1);

        if (bookPower > 0 && bookPower != oldPower) {
            plugin.getConfig().set(powerKey, bookPower);
            changed = true;
        } else if (bookPower == 0 && oldPower > 0) {
            // Если раньше была мощность, а теперь нет — удаляем ключ
            plugin.getConfig().set(powerKey, null);
            changed = true;
        }

        if (changed) {
            plugin.saveConfig();
        }
    }

    private String getLocString(Block block) {
        return block.getWorld().getName() + "," 
            + block.getX() + "," 
            + block.getY() + "," 
            + block.getZ();
    }
}
