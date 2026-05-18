package com.allfire.qqredstone.integration;

import com.allfire.qqredstone.QQRedstone;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/**
 * WorldGuard Utility Class
 * Helper methods for WorldGuard region checks
 * 
 * @author AllF1RE
 */
public class WorldGuardUtils {

    private final QQRedstone plugin;

    public WorldGuardUtils(QQRedstone plugin) {
        this.plugin = plugin;
    }

    /**
     * Check if player can build at location
     */
    public boolean canBuild(Player player, Block block) {
        if (!plugin.getWorldGuardManager().isEnabled()) return true;
        if (player.hasPermission("qqredstone.admin.region.bypass")) return true;

        try {
            LocalPlayer localPlayer = WorldGuardPlugin.inst().wrapPlayer(player);
            RegionQuery query = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery();
            ApplicableRegionSet regions = query.getApplicableRegions(
                BukkitAdapter.adapt(block.getLocation())
            );
            return regions.testState(localPlayer, com.sk89q.worldguard.protection.flags.Flags.BUILD);
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * Check StateFlag for player at location
     */
    public boolean checkStateFlag(Player player, Block block, com.sk89q.worldguard.protection.flags.StateFlag flag, boolean defaultValue) {
        if (!plugin.getWorldGuardManager().isEnabled()) return defaultValue;
        if (flag == null) return defaultValue;
        if (player.hasPermission("qqredstone.admin.region.bypass")) return true;

        try {
            LocalPlayer localPlayer = WorldGuardPlugin.inst().wrapPlayer(player);
            RegionQuery query = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery();
            ApplicableRegionSet regions = query.getApplicableRegions(
                BukkitAdapter.adapt(block.getLocation())
            );

            if (regions.size() == 0) return defaultValue;

            com.sk89q.worldguard.protection.flags.StateFlag.State state = regions.queryState(localPlayer, flag);
            if (state == null) return defaultValue;
            return state == com.sk89q.worldguard.protection.flags.StateFlag.State.ALLOW;

        } catch (Exception e) {
            return defaultValue;
        }
    }

    /**
     * Get max power from region flag
     */
    public int getMaxPower(Player player, Block block) {
        if (!plugin.getWorldGuardManager().isEnabled()) return 15;
        if (player.hasPermission("qqredstone.admin.region.bypass")) return 15;

        try {
            LocalPlayer localPlayer = WorldGuardPlugin.inst().wrapPlayer(player);
            RegionQuery query = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery();
            ApplicableRegionSet regions = query.getApplicableRegions(
                BukkitAdapter.adapt(block.getLocation())
            );

            Integer maxPower = regions.queryValue(localPlayer, plugin.getWorldGuardManager().getMaxPowerFlag());
            return (maxPower != null) ? Math.min(maxPower, 15) : 15;
        } catch (Exception e) {
            return 15;
        }
    }
}
