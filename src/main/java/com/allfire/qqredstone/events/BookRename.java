package com.allfire.qqredstone.events;

import com.allfire.qqredstone.QQRedstone;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public class BookRename implements Listener {

    @EventHandler
    public void onAnvil(PrepareAnvilEvent event) {
        ItemStack result = event.getResult();
        if (result == null || result.getType() != Material.WRITABLE_BOOK)
            return;
        ItemMeta meta = result.getItemMeta();
        if (meta == null)
            return;

        QQRedstone plugin = QQRedstone.getInstance();
        
        if (meta.hasDisplayName()) {
            String name = meta.getDisplayName();
            // Проверяем по ВСЕМ спискам
            if (plugin.isSenderName(name) || plugin.isReceiverName(name) || plugin.isRemoverName(name)) {
                meta.setEnchantmentGlintOverride(true);
            } else {
                meta.setEnchantmentGlintOverride(null);
            }
        } else {
            meta.setEnchantmentGlintOverride(null);
        }
        result.setItemMeta(meta);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        ItemStack item = event.getCurrentItem();
        if (item == null || item.getType() != Material.WRITABLE_BOOK)
            return;
        ItemMeta meta = item.getItemMeta();
        if (meta == null)
            return;

        QQRedstone plugin = QQRedstone.getInstance();

        if (meta.hasDisplayName()) {
            String name = meta.getDisplayName();
            if (plugin.isSenderName(name) || plugin.isReceiverName(name) || plugin.isRemoverName(name)) {
                boolean hasGlint = meta.hasEnchantmentGlintOverride();
                Boolean currentGlint = hasGlint ? meta.getEnchantmentGlintOverride() : null;
                
                if (currentGlint == null || !currentGlint) {
                    meta.setEnchantmentGlintOverride(true);
                    item.setItemMeta(meta);
                }
            } else {
                boolean hasGlint = meta.hasEnchantmentGlintOverride();
                Boolean currentGlint = hasGlint ? meta.getEnchantmentGlintOverride() : null;
                
                if (currentGlint != null && currentGlint) {
                    meta.setEnchantmentGlintOverride(null);
                    item.setItemMeta(meta);
                }
            }
        } else {
            boolean hasGlint = meta.hasEnchantmentGlintOverride();
            Boolean currentGlint = hasGlint ? meta.getEnchantmentGlintOverride() : null;
            
            if (currentGlint != null && currentGlint) {
                meta.setEnchantmentGlintOverride(null);
                item.setItemMeta(meta);
            }
        }
    }
}