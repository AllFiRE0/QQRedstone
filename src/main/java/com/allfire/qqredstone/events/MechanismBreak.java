package com.allfire.qqredstone.events;

import com.allfire.qqredstone.QQRedstone;
import com.allfire.qqredstone.database.DatabaseManager;
import com.allfire.qqredstone.database.Mechanism;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.StructureGrowEvent;

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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (processDestroyedBlock(event.getBlock())) {
            plugin.sendMessage(event.getPlayer(), "device-removed");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        processDestroyedBlocks(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        processDestroyedBlocks(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        for (Block block : event.getBlocks()) {
            processDestroyedBlock(block);
        }
        Block pistonHead = event.getBlock().getRelative(event.getDirection());
        processDestroyedBlock(pistonHead);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        for (Block block : event.getBlocks()) {
            processDestroyedBlock(block);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockFromTo(BlockFromToEvent event) {
        Block toBlock = event.getToBlock();
        if (toBlock == null) return;
        
        Material liquid = event.getBlock().getType();
        if (liquid != Material.WATER && liquid != Material.LAVA) return;
        
        processDestroyedBlock(toBlock);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStructureGrow(StructureGrowEvent event) {
        for (org.bukkit.block.BlockState state : event.getBlocks()) {
            processDestroyedBlock(state.getBlock());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBurn(BlockBurnEvent event) {
        processDestroyedBlock(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockForm(BlockFormEvent event) {
        processDestroyedBlock(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockFade(BlockFadeEvent event) {
        processDestroyedBlock(event.getBlock());
    }

    private void processDestroyedBlocks(List<Block> blocks) {
        for (Block block : blocks) {
            processDestroyedBlock(block);
        }
    }

    private boolean processDestroyedBlock(Block block) {
        if (block == null) return false;
        
        Location loc = block.getLocation();
        boolean removed = false;

        Mechanism mech = databaseManager.getMechanismAt(block);
        if (mech != null) {
            databaseManager.removeMechanism(
                block.getWorld().getName(), 
                block.getX(), block.getY(), block.getZ()
            );
            
            if (redstoneUpdate != null) {
                redstoneUpdate.removeMechanism(loc);
            }
            
            removed = true;
            plugin.getLogger().info("[QQR] Механизм удалён (разрушен): " + loc);
        }

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
}
