package com.allfire.qqredstone.events;

import com.allfire.qqredstone.QQRedstone;
import com.allfire.qqredstone.database.DatabaseManager;
import com.allfire.qqredstone.database.Mechanism;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
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

    private final QQRedstone plugin;
    private final DatabaseManager databaseManager;

    public PlayerInteract(QQRedstone plugin, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onMechanismClick(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        Action action = event.getAction();
        
        ItemStack itemInHand = event.getItem();
        if (itemInHand == null) return;
        
        Material itemType = itemInHand.getType();
        if (itemType != Material.WRITABLE_BOOK && itemType != Material.WRITTEN_BOOK)
            return;

        BookMeta bookMeta = (BookMeta) itemInHand.getItemMeta();
        if (bookMeta == null)
            return;

        String displayName = bookMeta.hasDisplayName() ? bookMeta.getDisplayName() : "";
        
        boolean isSpecialBook = plugin.isSenderName(displayName) || 
                                plugin.isReceiverName(displayName) || 
                                plugin.isRemoverName(displayName);
        
        if (!isSpecialBook) {
            return;
        }

        // ============================================================
        // 1. КЛИК В ВОЗДУХ (RIGHT_CLICK_AIR)
        // ============================================================
        if (action == Action.RIGHT_CLICK_AIR) {
            if (itemType == Material.WRITABLE_BOOK) {
                return;
            }
            event.setCancelled(true);
            return;
        }

        if (action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Block clickedBlock = event.getClickedBlock();
        if (clickedBlock == null) return;

        // ============================================================
        // 2. Книга-деактиватор
        // ============================================================
        if (plugin.isRemoverName(displayName)) {
            event.setCancelled(true);
            event.setUseInteractedBlock(Event.Result.DENY);
            event.setUseItemInHand(Event.Result.DENY);
            
            Mechanism existing = databaseManager.getMechanismAt(clickedBlock);
            if (existing != null) {
                databaseManager.removeMechanism(clickedBlock.getWorld().getName(),
                        clickedBlock.getX(), clickedBlock.getY(), clickedBlock.getZ());
                plugin.sendMessage(player, "device-removed");
            } else {
                plugin.sendMessage(player, "no-device-at-block");
            }
            return;
        }

        // ============================================================
        // 3. Отправитель или Получатель
        // ============================================================
        String role = null;
        if (plugin.isSenderName(displayName)) {
            role = "sender";
        } else if (plugin.isReceiverName(displayName)) {
            role = "receiver";
        }

        if (role == null) {
            return;
        }

        if (!isValidMechanism(clickedBlock, role)) {
            event.setCancelled(true);
            event.setUseInteractedBlock(Event.Result.DENY);
            event.setUseItemInHand(Event.Result.DENY);
            plugin.sendMessage(player, "wrong-mechanism");
            return;
        }

        event.setCancelled(true);
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);

        String worldName = clickedBlock.getWorld().getName();
        if (!player.hasPermission("qqredstone.worlds.use." + worldName) 
                && !player.hasPermission("qqredstone.worlds.use.*")) {
            plugin.sendMessage(player, "world-denied", "%world%", worldName);
            return;
        }

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

        if (databaseManager.isOwnedByOther(clickedBlock, player.getUniqueId().toString(),
                player.hasPermission("qqredstone.admin.override"))) {
            plugin.sendMessage(player, "already-owned");
            return;
        }

        String frequency = "0";
        if (bookMeta.getPageCount() > 0) {
            String raw = bookMeta.getPage(1);
            int spaceIndex = raw.indexOf(' ');
            if (spaceIndex != -1) {
                frequency = raw.substring(0, spaceIndex).trim();
            } else {
                frequency = raw.trim();
            }
        }
        if (frequency.isEmpty()) frequency = "0";

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

        Mechanism mechanism = createMechanismFromBlock(clickedBlock, role, frequency, 
                player.getUniqueId().toString(), savedPower);
        
        if (mechanism == null) {
            plugin.sendMessage(player, "wrong-mechanism");
            return;
        }

        Mechanism existing = databaseManager.getMechanismAt(clickedBlock);
        if (existing != null) {
            databaseManager.removeMechanism(clickedBlock.getWorld().getName(),
                    clickedBlock.getX(), clickedBlock.getY(), clickedBlock.getZ());
            plugin.sendMessage(player, "device-overwritten");
        }

        databaseManager.addMechanism(mechanism);

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

    private Mechanism createMechanismFromBlock(Block block, String type, String frequency, 
                                                String ownerUuid, int bookPower) {
        Mechanism mechanism = new Mechanism();
        mechanism.setType(type);
        mechanism.setFrequency(frequency);
        mechanism.setOwnerUuid(ownerUuid);
        mechanism.setWorld(block.getWorld().getName());
        mechanism.setX(block.getX());
        mechanism.setY(block.getY());
        mechanism.setZ(block.getZ());
        mechanism.setBookPower(bookPower);
        
        String blockTypeName = block.getType().name();
        Block attachedBlock = null;
        BlockFace attachedFace = null;
        Block belowBlock = null;
        
        if (blockTypeName.equals("LEVER") || blockTypeName.contains("BUTTON") ||
            blockTypeName.equals("REDSTONE_WALL_TORCH")) {
            if (block.getBlockData() instanceof Directional) {
                Directional directional = (Directional) block.getBlockData();
                attachedFace = directional.getFacing().getOppositeFace();
                attachedBlock = block.getRelative(attachedFace);
            }
        }
        else if (blockTypeName.equals("REDSTONE_TORCH") || blockTypeName.contains("PRESSURE_PLATE") ||
                 blockTypeName.contains("LIGHTNING_ROD")) {
            attachedBlock = block.getRelative(BlockFace.DOWN);
            attachedFace = BlockFace.DOWN;
        }
        // ===== НОВОЕ: TRAPPED_CHEST =====
        else if (blockTypeName.equals("TRAPPED_CHEST")) {
            attachedBlock = block.getRelative(BlockFace.DOWN);
            attachedFace = BlockFace.DOWN;
        }
        // ===== НОВОЕ: CALIBRATED_SCULK_SENSOR =====
        else if (blockTypeName.equals("CALIBRATED_SCULK_SENSOR")) {
            attachedBlock = block.getRelative(BlockFace.DOWN);
            attachedFace = BlockFace.DOWN;
        }
        
        if (blockTypeName.contains("PRESSURE_PLATE")) {
            belowBlock = attachedBlock;
        }
        
        if (attachedBlock == null) {
            return null;
        }
        
        mechanism.setAttachedWorld(attachedBlock.getWorld().getName());
        mechanism.setAttachedX(attachedBlock.getX());
        mechanism.setAttachedY(attachedBlock.getY());
        mechanism.setAttachedZ(attachedBlock.getZ());
        mechanism.setAttachedType(attachedBlock.getType().name());
        mechanism.setAttachedFace(attachedFace != null ? attachedFace.name() : "UP");
        
        if (belowBlock != null) {
            mechanism.setBelowWorld(belowBlock.getWorld().getName());
            mechanism.setBelowX(belowBlock.getX());
            mechanism.setBelowY(belowBlock.getY());
            mechanism.setBelowZ(belowBlock.getZ());
            mechanism.setBelowType(belowBlock.getType().name());
        }
        
        return mechanism;
    }

    private int readBookPower(BookMeta meta) {
        if (meta.getPageCount() < 2) return 15;

        try {
            String text = meta.getPage(2).trim();
            text = text.replaceAll("§[0-9a-fk-or]", "").trim();

            if (text.isEmpty()) return 15;

            String[] parts = text.split("\\s+");
            for (String part : parts) {
                try {
                    int power = Integer.parseInt(part);
                    if (power >= 1 && power <= 15) {
                        return power;
                    }
                    if (power == 0) {
                        return 15;
                    }
                } catch (NumberFormatException ignored) {}
            }
            return 15;
        } catch (Exception e) {
            return 15;
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
        String type = block.getType().name();

        boolean acceptAll = plugin.getConfig().getBoolean("mechanism.accept-all", false);
        if (acceptAll) {
            return type.equals("LEVER") 
                || type.contains("BUTTON") 
                || type.contains("PRESSURE_PLATE")
                || type.contains("LIGHTNING_ROD") 
                || type.equals("REDSTONE_TORCH") 
                || type.equals("REDSTONE_WALL_TORCH")
                || type.equals("TRAPPED_CHEST")
                || type.equals("CALIBRATED_SCULK_SENSOR");
        }

        List<String> allowedTypes = plugin.getConfig().getStringList("mechanism." + role);
        if (allowedTypes == null || allowedTypes.isEmpty()) {
            String singleType = plugin.getConfig().getString("mechanism." + role, "LIGHTNING_ROD");
            allowedTypes = new ArrayList<>();
            allowedTypes.add(singleType);
        }

        for (String allowed : allowedTypes) {
            switch (allowed) {
                case "LEVER":
                    if (type.equals("LEVER")) return true;
                    break;
                case "BUTTON":
                    if (type.contains("BUTTON")) return true;
                    break;
                case "PRESSURE_PLATE":
                    if (type.contains("PRESSURE_PLATE")) return true;
                    break;
                case "LIGHTNING_ROD":
                    if (type.contains("LIGHTNING_ROD")) return true;
                    break;
                case "REDSTONE_TORCH":
                    if (type.equals("REDSTONE_TORCH") || type.equals("REDSTONE_WALL_TORCH")) return true;
                    break;
                case "TRAPPED_CHEST":
                    if (type.equals("TRAPPED_CHEST")) return true;
                    break;
                case "CALIBRATED_SCULK_SENSOR":
                    if (type.equals("CALIBRATED_SCULK_SENSOR")) return true;
                    break;
            }
        }
        return false;
    }
}
