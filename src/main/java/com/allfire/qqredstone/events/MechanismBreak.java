package com.allfire.qqredstone.events;

import com.allfire.qqredstone.QQRedstone;
import com.allfire.qqredstone.database.DatabaseManager;
import com.allfire.qqredstone.database.Mechanism;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

import java.util.List;

public class MechanismBreak implements Listener {

    private final QQRedstone plugin;
    private final DatabaseManager databaseManager;

    public MechanismBreak(QQRedstone plugin, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        boolean removed = false;

        // СЦЕНАРИЙ 1: Сломали сам механизм
        Mechanism mech = databaseManager.getMechanismAt(block);
        if (mech != null) {
            databaseManager.removeMechanism(block.getWorld().getName(), 
                    block.getX(), block.getY(), block.getZ());
            removed = true;
            plugin.sendMessage(event.getPlayer(), "device-removed");
            return;
        }

        // СЦЕНАРИЙ 2: Сломали блок, к которому прикреплён механизм (стена под кнопкой/рычагом)
        List<Mechanism> attachedMechanisms = databaseManager.getMechanismsAttachedTo(block);
        for (Mechanism m : attachedMechanisms) {
            databaseManager.removeMechanism(m.getWorld(), m.getX(), m.getY(), m.getZ());
            removed = true;
        }

        // СЦЕНАРИЙ 3: Сломали блок под нажимной плитой (если не покрыто сценарием 2)
        List<Mechanism> belowMechanisms = databaseManager.getMechanismsBelow(block);
        for (Mechanism m : belowMechanisms) {
            // Проверяем, не был ли этот механизм уже удалён через attached
            if (databaseManager.getMechanismAt(
                    org.bukkit.Bukkit.getWorld(m.getWorld()).getBlockAt(m.getX(), m.getY(), m.getZ())) != null) {
                databaseManager.removeMechanism(m.getWorld(), m.getX(), m.getY(), m.getZ());
                removed = true;
            }
        }

        if (removed) {
            plugin.sendMessage(event.getPlayer(), "device-removed");
        }
    }
}
