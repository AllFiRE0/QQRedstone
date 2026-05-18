package com.allfire.qqredstone.integration;

import com.allfire.qqredstone.QQRedstone;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.flags.IntegerFlag;
import com.sk89q.worldguard.protection.flags.registry.FlagConflictException;
import com.sk89q.worldguard.protection.flags.registry.FlagRegistry;
import org.bukkit.Bukkit;

import java.util.logging.Level;

/**
 * WorldGuard Integration Manager
 * Handles integration with WorldGuard plugin
 * Manages custom flags and region checks
 * 
 * @author AllF1RE
 */
public class WorldGuardManager {

    private final QQRedstone plugin;
    private boolean enabled;

    // Custom flags
    private StateFlag qqrsUseFlag;
    private StateFlag qqrsSenderFlag;
    private StateFlag qqrsReceiverFlag;
    private StateFlag qqrsVanillaPowerFlag;
    private StateFlag qqrsBookPowerFlag;
    private IntegerFlag qqrsMaxPowerFlag;

    public WorldGuardManager(QQRedstone plugin) {
        this.plugin = plugin;
        this.enabled = false;
    }

    /**
     * Initialize WorldGuard integration
     */
    public void initialize() {
        try {
            if (Bukkit.getPluginManager().getPlugin("WorldGuard") == null) {
                plugin.getLogger().warning("WorldGuard не найден! Интеграция отключена.");
                return;
            }

            registerFlags();
            enabled = true;
            plugin.getLogger().info("Интеграция с WorldGuard успешно инициализирована!");

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Ошибка при инициализации WorldGuard", e);
            enabled = false;
        }
    }

    /**
     * Register custom flags in WorldGuard
     */
    private void registerFlags() {
        try {
            FlagRegistry registry = WorldGuard.getInstance().getFlagRegistry();

            // qqredstone-use
            try {
                qqrsUseFlag = new StateFlag("qqredstone-use", true);
                registry.register(qqrsUseFlag);
                plugin.getLogger().info("Флаг 'qqredstone-use' зарегистрирован!");
            } catch (FlagConflictException e) {
                qqrsUseFlag = (StateFlag) registry.get("qqredstone-use");
                plugin.getLogger().info("Флаг 'qqredstone-use' уже существует!");
            }

            // qqredstone-sender
            try {
                qqrsSenderFlag = new StateFlag("qqredstone-sender", true);
                registry.register(qqrsSenderFlag);
                plugin.getLogger().info("Флаг 'qqredstone-sender' зарегистрирован!");
            } catch (FlagConflictException e) {
                qqrsSenderFlag = (StateFlag) registry.get("qqredstone-sender");
                plugin.getLogger().info("Флаг 'qqredstone-sender' уже существует!");
            }

            // qqredstone-receiver
            try {
                qqrsReceiverFlag = new StateFlag("qqredstone-receiver", true);
                registry.register(qqrsReceiverFlag);
                plugin.getLogger().info("Флаг 'qqredstone-receiver' зарегистрирован!");
            } catch (FlagConflictException e) {
                qqrsReceiverFlag = (StateFlag) registry.get("qqredstone-receiver");
                plugin.getLogger().info("Флаг 'qqredstone-receiver' уже существует!");
            }

            // qqredstone-vanilla-power
            try {
                qqrsVanillaPowerFlag = new StateFlag("qqredstone-vanilla-power", true);
                registry.register(qqrsVanillaPowerFlag);
                plugin.getLogger().info("Флаг 'qqredstone-vanilla-power' зарегистрирован!");
            } catch (FlagConflictException e) {
                qqrsVanillaPowerFlag = (StateFlag) registry.get("qqredstone-vanilla-power");
                plugin.getLogger().info("Флаг 'qqredstone-vanilla-power' уже существует!");
            }

            // qqredstone-book-power
            try {
                qqrsBookPowerFlag = new StateFlag("qqredstone-book-power", true);
                registry.register(qqrsBookPowerFlag);
                plugin.getLogger().info("Флаг 'qqredstone-book-power' зарегистрирован!");
            } catch (FlagConflictException e) {
                qqrsBookPowerFlag = (StateFlag) registry.get("qqredstone-book-power");
                plugin.getLogger().info("Флаг 'qqredstone-book-power' уже существует!");
            }

            // qqredstone-max-power
            try {
                qqrsMaxPowerFlag = new IntegerFlag("qqredstone-max-power");
                registry.register(qqrsMaxPowerFlag);
                plugin.getLogger().info("Флаг 'qqredstone-max-power' зарегистрирован!");
            } catch (FlagConflictException e) {
                qqrsMaxPowerFlag = (IntegerFlag) registry.get("qqredstone-max-power");
                plugin.getLogger().info("Флаг 'qqredstone-max-power' уже существует!");
            }

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Ошибка регистрации флагов WorldGuard", e);
        }
    }

    /**
     * Check if integration is enabled
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Check if WorldGuard is available
     */
    public boolean isWorldGuardAvailable() {
        return Bukkit.getPluginManager().getPlugin("WorldGuard") != null;
    }

    /**
     * Get WorldGuard version
     */
    public String getWorldGuardVersion() {
        if (isWorldGuardAvailable()) {
            return Bukkit.getPluginManager().getPlugin("WorldGuard").getDescription().getVersion();
        }
        return null;
    }

    // Flag getters
    public StateFlag getUseFlag() { return qqrsUseFlag; }
    public StateFlag getSenderFlag() { return qqrsSenderFlag; }
    public StateFlag getReceiverFlag() { return qqrsReceiverFlag; }
    public StateFlag getVanillaPowerFlag() { return qqrsVanillaPowerFlag; }
    public StateFlag getBookPowerFlag() { return qqrsBookPowerFlag; }
    public IntegerFlag getMaxPowerFlag() { return qqrsMaxPowerFlag; }

    /**
     * Reload integration
     */
    public void reload() {
        initialize();
    }
}
