package com.allfire.qqredstone.events;

import com.allfire.qqredstone.QQRedstone;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

import java.util.*;

public class MechanismBreak implements Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        QQRedstone plugin = QQRedstone.getInstance();

        boolean removed = false;

        // 1. Проверяем сам сломанный блок — любой механизм?
        if (isAnyMechanism(block)) {
            removed = removeFromConfig(block);
        }

        // 2. Проверяем ВСЕ сохранённые механизмы в конфиге — 
        //    может сломали блок, на котором висел механизм?
        removed = removeAttachedToBlock(block) || removed;

        // 3. Проверяем соседей (механизм мог ещё не исчезнуть)
        for (BlockFace face : BlockFace.values()) {
            Block neighbor = block.getRelative(face);
            if (!neighbor.getType().isAir() && isAnyMechanism(neighbor)) {
                removed = removeFromConfig(neighbor) || removed;
            }
        }

        if (removed) {
            plugin.sendMessage(event.getPlayer(), "device-removed");
        }
    }

    /**
     * Является ли блок любым механизмом (рычаг, кнопка, плита, громоотвод, факел)
     */
    private boolean isAnyMechanism(Block block) {
        String type = block.getType().name();
        return type.equals("LEVER")
            || type.contains("BUTTON")
            || type.contains("PRESSURE_PLATE")
            || type.contains("LIGHTNING_ROD")
            || type.equals("REDSTONE_TORCH")
            || type.equals("REDSTONE_WALL_TORCH");
    }

    /**
     * Проверяет все сохранённые механизмы — не привязаны ли они к сломанному блоку
     */
    private boolean removeAttachedToBlock(Block brokenBlock) {
        QQRedstone plugin = QQRedstone.getInstance();
        ConfigurationSection data = plugin.getConfig().getConfigurationSection("data");
        if (data == null) return false;

        boolean changed = false;
        Set<String> toRemove = new HashSet<>();

        for (String freq : data.getKeys(false)) {
            List<String> senders = plugin.getConfig().getStringList("data." + freq + ".senders");
            List<String> receivers = plugin.getConfig().getStringList("data." + freq + ".receivers");

            for (String locStr : senders) {
                if (isNearBrokenBlock(locStr, brokenBlock)) {
                    toRemove.add(locStr);
                }
            }
            for (String locStr : receivers) {
                if (isNearBrokenBlock(locStr, brokenBlock)) {
                    toRemove.add(locStr);
                }
            }
        }

        for (String locStr : toRemove) {
            if (removeFromConfig(locStr)) {
                changed = true;
            }
        }

        return changed;
    }

    /**
     * Проверяет, находится ли сохранённый механизм рядом со сломанным блоком
     */
    private boolean isNearBrokenBlock(String locStr, Block brokenBlock) {
        String[] p = locStr.split(",");
        if (p.length < 4) return false;

        int mechX = Integer.parseInt(p[1]);
        int mechY = Integer.parseInt(p[2]);
        int mechZ = Integer.parseInt(p[3]);

        int brokenX = brokenBlock.getX();
        int brokenY = brokenBlock.getY();
        int brokenZ = brokenBlock.getZ();

        int dx = Math.abs(mechX - brokenX);
        int dy = Math.abs(mechY - brokenY);
        int dz = Math.abs(mechZ - brokenZ);

        // Если та же самая локация — обработано в п.1
        if (dx == 0 && dy == 0 && dz == 0) return false;

        // Механизм должен быть на расстоянии 1 блока (прикреплён)
        if (dx > 1 || dy > 1 || dz > 1) return false;
        if (dx + dy + dz > 2) return false;

        // Проверяем по типу механизма:
        String mechType = ""; 
        QQRedstone plugin = QQRedstone.getInstance();
        String fullLocStr = locStr;
        
        // LEVER/BUTTON/PRESSURE_PLATE/WALL_TORCH — крепятся к соседнему блоку
        // LIGHTNING_ROD — стоит на блоке снизу (dy == 1)
        // REDSTONE_TORCH — стоит на блоке снизу

        return (dx + dy + dz == 1); // ровно 1 блок разницы
    }

    private boolean removeFromConfig(String locStr) {
        QQRedstone plugin = QQRedstone.getInstance();
        ConfigurationSection data = plugin.getConfig().getConfigurationSection("data");
        if (data == null) return false;

        boolean changed = false;

        for (String freq : data.getKeys(false)) {
            List<String> senders = plugin.getConfig().getStringList("data." + freq + ".senders");
            List<String> receivers = plugin.getConfig().getStringList("data." + freq + ".receivers");

            if (senders.remove(locStr)) {
                plugin.getConfig().set("data." + freq + ".senders", senders);
                changed = true;
            }
            if (receivers.remove(locStr)) {
                plugin.getConfig().set("data." + freq + ".receivers", receivers);
                changed = true;
            }
        }

        String ownerKey = "ownership." + locStr.replace(",", ".");
        if (plugin.getConfig().contains(ownerKey)) {
            plugin.getConfig().set(ownerKey, null);
            changed = true;
        }

        String powerKey = "book-power." + locStr.replace(",", ".");
        if (plugin.getConfig().contains(powerKey)) {
            plugin.getConfig().set(powerKey, null);
            changed = true;
        }

        for (String freq : data.getKeys(false)) {
            List<String> senders = plugin.getConfig().getStringList("data." + freq + ".senders");
            List<String> receivers = plugin.getConfig().getStringList("data." + freq + ".receivers");
            if (senders.isEmpty() && receivers.isEmpty()) {
                plugin.getConfig().set("data." + freq, null);
                changed = true;
            }
        }

        if (changed) {
            plugin.saveConfig();
        }

        return changed;
    }

    private boolean removeFromConfig(Block block) {
        return removeFromConfig(getLocString(block));
    }

    private String getLocString(Block block) {
        return block.getWorld().getName() + ","
            + block.getX() + ","
            + block.getY() + ","
            + block.getZ();
    }
}
