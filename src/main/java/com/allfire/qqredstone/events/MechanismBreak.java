package com.allfire.qqredstone.events;

import com.allfire.qqredstone.QQRedstone;
import com.allfire.qqredstone.database.DatabaseManager;
import com.allfire.qqredstone.database.Mechanism;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

import java.util.List;

public class MechanismBreak implements Listener {

    private final QQRedstone plugin;
    private final DatabaseManager databaseManager;

    public MechanismBreak(QQRedstone plugin, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
    }

    // 1. Игрок ломает блок
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (processDestroyedBlock(event.getBlock())) {
            plugin.sendMessage(event.getPlayer(), "device-removed");
        }
    }

    // 2. Взрыв от сущности (TNT, Крипер, Кристалл Края)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        processDestroyedBlocks(event.blockList());
    }

    // 3. Взрыв от блока (Кровать в Аду, Якорь возрождения)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        processDestroyedBlocks(event.blockList());
    }

    // 4. Поршень толкает блоки
    // ВНИМАНИЕ: поршень сдвигает блок, а не ломает.
    // Механизм удаляется, так как его основа была сдвинута.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        processDestroyedBlocks(event.getBlocks());
    }

    // 5. Липкий поршень тянет блоки
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        processDestroyedBlocks(event.getBlocks());
    }

    /**
     * Обрабатывает список уничтоженных или сдвинутых блоков
     */
    private void processDestroyedBlocks(List<Block> blocks) {
        for (Block block : blocks) {
            processDestroyedBlock(block);
        }
    }

    /**
     * Основная логика поиска и удаления механизма (Сценарии 1, 2 и 3)
     * Возвращает true, если был удален хотя бы один механизм
     */
    private boolean processDestroyedBlock(Block block) {
        boolean removed = false;

        // СЦЕНАРИЙ 1: Уничтожен сам механизм
        Mechanism mech = databaseManager.getMechanismAt(block);
        if (mech != null) {
            databaseManager.removeMechanism(block.getWorld().getName(), 
                    block.getX(), block.getY(), block.getZ());
            removed = true;
        }

        // СЦЕНАРИЙ 2: Уничтожен блок, к которому прикреплён механизм (стена/пол)
        List<Mechanism> attachedMechanisms = databaseManager.getMechanismsAttachedTo(block);
        for (Mechanism m : attachedMechanisms) {
            databaseManager.removeMechanism(m.getWorld(), m.getX(), m.getY(), m.getZ());
            removed = true;
        }

        // СЦЕНАРИЙ 3: Уничтожен блок ПОД механизмом (для плит)
        List<Mechanism> belowMechanisms = databaseManager.getMechanismsBelow(block);
        for (Mechanism m : belowMechanisms) {
            // Проверяем, не был ли этот механизм уже удалён
            Block mBlock = Bukkit.getWorld(m.getWorld()).getBlockAt(m.getX(), m.getY(), m.getZ());
            if (databaseManager.getMechanismAt(mBlock) != null) {
                databaseManager.removeMechanism(m.getWorld(), m.getX(), m.getY(), m.getZ());
                removed = true;
            }
        }

        return removed;
    }
}