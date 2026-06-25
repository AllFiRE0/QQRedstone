package com.allfire.qqredstone.events;

import com.allfire.qqredstone.QQRedstone;
import com.allfire.qqredstone.database.DatabaseManager;
import com.allfire.qqredstone.database.Mechanism;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.StructureGrowEvent;

import java.util.ArrayList;
import java.util.List;

public class MechanismBreak implements Listener {

    private final QQRedstone plugin;
    private final DatabaseManager databaseManager;
    private RedstoneUpdate redstoneUpdate;

    public MechanismBreak(QQRedstone plugin, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
    }

    public void setRedstoneUpdate(RedstoneUpdate redstoneUpdate) {
        this.redstoneUpdate = redstoneUpdate;
    }

    // ===== 1. Игрок ломает блок =====
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (processDestroyedBlock(event.getBlock())) {
            plugin.sendMessage(event.getPlayer(), "device-removed");
        }
    }

    // ===== 2. Взрыв от сущности =====
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        processDestroyedBlocks(event.blockList());
    }

    // ===== 3. Взрыв от блока =====
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        processDestroyedBlocks(event.blockList());
    }

    // ===== 4. Поршень толкает блоки =====
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        // Проверяем блоки, которые будут сдвинуты
        for (Block block : event.getBlocks()) {
            processDestroyedBlock(block);
        }
        
        // Проверяем блок, который будет двигать поршень (головка поршня)
        Block pistonHead = event.getBlock().getRelative(event.getDirection());
        processDestroyedBlock(pistonHead);
    }

    // ===== 5. Липкий поршень тянет блоки =====
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        for (Block block : event.getBlocks()) {
            processDestroyedBlock(block);
        }
    }

    // ===== 6. ВОДА/ЛАВА (уже есть в RedstoneUpdate, но дублируем на всякий случай) =====
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockFromTo(BlockFromToEvent event) {
        Block toBlock = event.getToBlock();
        if (toBlock == null) return;
        
        Material liquid = event.getBlock().getType();
        if (liquid != Material.WATER && liquid != Material.LAVA) return;
        
        processDestroyedBlock(toBlock);
    }

    // ===== 7. Рост деревьев/грибов (могут заменить блоки) =====
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStructureGrow(StructureGrowEvent event) {
        for (org.bukkit.block.BlockState state : event.getBlocks()) {
            processDestroyedBlock(state.getBlock());
        }
    }

    // ===== 8. Сгорание блока от огня =====
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBurn(BlockBurnEvent event) {
        processDestroyedBlock(event.getBlock());
    }

    // ===== 9. Замена блока (например, превращение земли в траву) =====
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockForm(BlockFormEvent event) {
        processDestroyedBlock(event.getBlock());
    }

    // ===== 10. Опадение блока (песок/гравий) =====
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockFade(BlockFadeEvent event) {
        processDestroyedBlock(event.getBlock());
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
     * Основная логика поиска и удаления механизма
     * Возвращает true, если был удалён хотя бы один механизм
     */
    private boolean processDestroyedBlock(Block block) {
        if (block == null) return false;
        
        Location loc = block.getLocation();
        boolean removed = false;

        // ===== СЦЕНАРИЙ 1: Уничтожен сам механизм =====
        Mechanism mech = databaseManager.getMechanismAt(block);
        if (mech != null) {
            databaseManager.removeMechanism(
                block.getWorld().getName(), 
                block.getX(), block.getY(), block.getZ()
            );
            
            // Очищаем кэши в RedstoneUpdate
            if (redstoneUpdate != null) {
                redstoneUpdate.removeMechanism(loc);
            }
            
            removed = true;
            plugin.getLogger().info("[QQR] Механизм удалён (разрушен): " + loc);
        }

        // ===== СЦЕНАРИЙ 2: Уничтожен блок, к которому прикреплён механизм =====
        List<Mechanism> attachedMechanisms = databaseManager.getMechanismsAttachedTo(block);
        for (Mechanism m : attachedMechanisms) {
            databaseManager.removeMechanism(m.getWorld(), m.getX(), m.getY(), m.getZ());
            
            Location mechLoc = new Location(
                Bukkit.getWorld(m.getWorld()),
                m.getX(), m.getY(), m.getZ()
            );
            if (redstoneUpdate != null) {
                redstoneUpdate.removeMechanism(mechLoc);
            }
            
            removed = true;
            plugin.getLogger().info("[QQR] Механизм удалён (разрушена основа): " + mechLoc);
        }

        // ===== СЦЕНАРИЙ 3: Уничтожен блок ПОД механизмом (для плит) =====
        List<Mechanism> belowMechanisms = databaseManager.getMechanismsBelow(block);
        for (Mechanism m : belowMechanisms) {
            Block mBlock = Bukkit.getWorld(m.getWorld()).getBlockAt(m.getX(), m.getY(), m.getZ());
            if (databaseManager.getMechanismAt(mBlock) != null) {
                databaseManager.removeMechanism(m.getWorld(), m.getX(), m.getY(), m.getZ());
                
                Location mechLoc = new Location(
                    Bukkit.getWorld(m.getWorld()),
                    m.getX(), m.getY(), m.getZ()
                );
                if (redstoneUpdate != null) {
                    redstoneUpdate.removeMechanism(mechLoc);
                }
                
                removed = true;
                plugin.getLogger().info("[QQR] Механизм удалён (разрушен блок под плитой): " + mechLoc);
            }
        }

        return removed;
    }

    /**
     * Проверяет все механизмы в мире на валидность (вызывается при загрузке/релоаде)
     */
    public void validateAllMechanisms() {
        // Получаем все механизмы из БД (через кэш)
        // В идеале нужно добавить метод в DatabaseManager для получения всех механизмов
        plugin.getLogger().info("[QQR] Запущена валидация всех механизмов...");
        // TODO: реализовать полную валидацию при релоаде
    }
}
