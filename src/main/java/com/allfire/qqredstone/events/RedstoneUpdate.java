package com.allfire.qqredstone.events;

import com.allfire.qqredstone.QQRedstone;
import com.allfire.qqredstone.database.DatabaseManager;
import com.allfire.qqredstone.database.Mechanism;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.LightningRod;
import org.bukkit.block.data.type.Switch;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.metadata.FixedMetadataValue;

import java.util.*;

public class RedstoneUpdate implements Listener {

    private final QQRedstone plugin;
    private final DatabaseManager databaseManager;
    
    private final Map<Location, Integer> poweredSenders = new HashMap<>();
    private final Map<String, Long> lastTransmission = new HashMap<>();
    private final Map<String, Deque<Long>> triggerHistory = new HashMap<>();
    private final Map<String, Long> disabledFreqs = new HashMap<>();

    public RedstoneUpdate(QQRedstone plugin, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
    }

    @EventHandler
    public void onRedstoneChange(BlockRedstoneEvent event) {
        Block block = event.getBlock();
        processBlock(block);
    }

    @EventHandler
    public void onBlockPhysics(BlockPhysicsEvent event) {
        Block block = event.getBlock();
        Material m = block.getType();
        
        if (m != Material.LEVER && 
            !m.name().contains("BUTTON") && 
            !m.name().contains("PRESSURE_PLATE") &&
            !m.name().contains("LIGHTNING_ROD") &&
            m != Material.REDSTONE_TORCH && 
            m != Material.REDSTONE_WALL_TORCH) {
            return;
        }
        processBlock(block);
    }

    private void processBlock(Block block) {
        Mechanism sender = databaseManager.getMechanismAt(block);
        if (sender == null || !sender.getType().equals("sender")) return;

        String freq = sender.getFrequency().trim();

        Material m = block.getType();
        if (m == Material.AIR || m == Material.WATER || m == Material.LAVA ||
            !isValidMechanismBlock(block)) {
            databaseManager.removeMechanism(sender.getWorld(), sender.getX(), sender.getY(), sender.getZ());
            return;
        }

        if (!validateAttachedBlock(sender, block)) {
            databaseManager.removeMechanism(sender.getWorld(), sender.getX(), sender.getY(), sender.getZ());
            return;
        }

        if (isFreqDisabled(freq)) return;
        if (isOnCooldown(freq)) return;
        if (!checkAntiFlood(freq, block)) return;

        int power = getMechanismPower(block);
        Location loc = block.getLocation();
        boolean isPoweredNow = (power > 0);

        if (isPoweredNow && !poweredSenders.containsKey(loc)) {
            poweredSenders.put(loc, power);
            processSignal(block, power, true, freq, sender);
        } else if (isPoweredNow && poweredSenders.containsKey(loc)) {
            int oldPower = poweredSenders.get(loc);
            if (oldPower != power) {
                poweredSenders.put(loc, power);
                processSignal(block, power, true, freq, sender);
            }
        } else if (!isPoweredNow && poweredSenders.containsKey(loc)) {
            poweredSenders.remove(loc);
            processSignal(block, 0, false, freq, sender);
        }
    }

    private boolean isValidMechanismBlock(Block block) {
        Material m = block.getType();
        return m == Material.LEVER ||
               m.name().contains("BUTTON") ||
               m.name().contains("PRESSURE_PLATE") ||
               m.name().contains("LIGHTNING_ROD") ||
               m == Material.REDSTONE_TORCH ||
               m == Material.REDSTONE_WALL_TORCH;
    }

    private boolean validateAttachedBlock(Mechanism mechanism, Block mechanismBlock) {
        if (mechanism.getAttachedFace() == null) return true;
        
        BlockFace face = mechanism.getAttachedBlockFace();
        Block attachedBlock = mechanismBlock.getRelative(face);
        
        String savedType = mechanism.getAttachedType();
        String currentType = attachedBlock.getType().name();
        
        if (!savedType.equals(currentType)) {
            return false;
        }
        
        if (mechanism.getBelowWorld() != null) {
            Block belowBlock = mechanismBlock.getRelative(BlockFace.DOWN);
            String savedBelowType = mechanism.getBelowType();
            String currentBelowType = belowBlock.getType().name();
            if (!savedBelowType.equals(currentBelowType)) {
                return false;
            }
        }
        
        return true;
    }

    private int getMechanismPower(Block block) {
        String type = block.getType().name();

        if (type.equals("LEVER") || type.contains("BUTTON")) {
            if (block.getBlockData() instanceof Switch) {
                boolean isPhysicallyPressed = ((Switch) block.getBlockData()).isPowered();
                boolean isPoweredByRedstone = getMaxRedstonePower(block) > 0;
                return (isPhysicallyPressed || isPoweredByRedstone) ? 15 : 0;
            }
        }

        if (type.contains("PRESSURE_PLATE")) {
            boolean isPhysicallyPressed = block.getBlockPower(BlockFace.UP) > 0;
            boolean isPoweredByRedstone = getMaxRedstonePower(block) > 0;
            return (isPhysicallyPressed || isPoweredByRedstone) ? 15 : 0;
        }

        if (type.contains("LIGHTNING_ROD")) {
            return getMaxRedstonePower(block) > 0 ? 15 : 0;
        }

        return getMaxRedstonePower(block);
    }

    private int getMaxRedstonePower(Block block) {
        int maxPower = 0;
        for (BlockFace face : BlockFace.values()) {
            int power = block.getBlockPower(face);
            if (power > maxPower) maxPower = power;
        }
        return Math.min(maxPower, 15);
    }

    private void processSignal(Block block, int vanillaPower, boolean isOn, String freq, Mechanism sender) {
        String powerMode = plugin.getConfig().getString("transmission.power-mode", "vanilla");
        
        int finalPower = 0;
        int bookPower = sender.getBookPower();

        switch (powerMode) {
            case "vanilla":
                finalPower = vanillaPower;
                break;
            case "book":
                finalPower = (bookPower > 0) ? bookPower : vanillaPower;
                break;
            case "both":
                finalPower = Math.max(vanillaPower, bookPower);
                break;
            default:
                finalPower = vanillaPower;
        }

        int configMaxPower = plugin.getConfig().getInt("transmission.max-power", 15);
        finalPower = Math.min(finalPower, configMaxPower);

        List<Mechanism> receivers = databaseManager.getReceiversForFrequency(freq);
        
        boolean crossWorldEnabled = plugin.getConfig().getBoolean("transmission.allow-cross-world", true);

        for (Mechanism receiver : receivers) {
            if (!receiver.getWorld().equals(block.getWorld().getName())) {
                if (!crossWorldEnabled) continue;
                
                org.bukkit.OfflinePlayer owner = Bukkit.getOfflinePlayer(UUID.fromString(receiver.getOwnerUuid()));
                if (owner.isOnline() && owner.getPlayer() != null) {
                    if (!owner.getPlayer().hasPermission("qqredstone.crossworld")
                            && !owner.getPlayer().hasPermission("qqredstone.admin.region.bypass")) {
                        continue;
                    }
                }
            }
            
            Location loc = new Location(Bukkit.getWorld(receiver.getWorld()),
                receiver.getX(), receiver.getY(), receiver.getZ());
            Block receiverBlock = loc.getBlock();
            
            if (!validateAttachedBlock(receiver, receiverBlock)) {
                databaseManager.removeMechanism(receiver.getWorld(), receiver.getX(), receiver.getY(), receiver.getZ());
                continue;
            }
            
            activateReceiver(receiverBlock, isOn, block.getType().name());
        }
    }

    private void activateReceiver(Block block, boolean isOn, String senderType) {
        String type = block.getType().name();

        // ===== КНОПКА-ПОЛУЧАТЕЛЬ =====
        if (type.contains("BUTTON") && block.getBlockData() instanceof Switch && senderType.contains("LEVER")) {
            Switch s = (Switch) block.getBlockData();
            s.setPowered(isOn);
            block.setBlockData(s);
            return;
        }

        if (type.contains("BUTTON") && block.getBlockData() instanceof Switch && senderType.contains("BUTTON")) {
            Switch s = (Switch) block.getBlockData();
            if (!isOn) {
                s.setPowered(true);
                block.setBlockData(s);
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (block.getType().name().contains("BUTTON") && block.getBlockData() instanceof Switch) {
                        Switch ss = (Switch) block.getBlockData();
                        if (ss.isPowered()) { ss.setPowered(false); block.setBlockData(ss); }
                    }
                }, 15L);
            }
            return;
        }

        if (type.contains("BUTTON") && block.getBlockData() instanceof Switch && senderType.contains("PRESSURE_PLATE")) {
            Switch s = (Switch) block.getBlockData();
            if (!isOn) {
                s.setPowered(true);
                block.setBlockData(s);
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (block.getType().name().contains("BUTTON") && block.getBlockData() instanceof Switch) {
                        Switch ss = (Switch) block.getBlockData();
                        if (ss.isPowered()) { ss.setPowered(false); block.setBlockData(ss); }
                    }
                }, 15L);
            }
            return;
        }

        if (type.contains("BUTTON") && block.getBlockData() instanceof Switch && senderType.contains("LIGHTNING_ROD")) {
            Switch s = (Switch) block.getBlockData();
            if (!isOn) {
                s.setPowered(true);
                block.setBlockData(s);
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (block.getType().name().contains("BUTTON") && block.getBlockData() instanceof Switch) {
                        Switch ss = (Switch) block.getBlockData();
                        if (ss.isPowered()) { ss.setPowered(false); block.setBlockData(ss); }
                    }
                }, 15L);
            }
            return;
        }

        if (type.contains("BUTTON") && block.getBlockData() instanceof Switch && 
            (senderType.contains("REDSTONE_TORCH") || senderType.contains("REDSTONE_WALL_TORCH"))) {
            Switch s = (Switch) block.getBlockData();
            if (!isOn) {
                s.setPowered(true);
                block.setBlockData(s);
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (block.getType().name().contains("BUTTON") && block.getBlockData() instanceof Switch) {
                        Switch ss = (Switch) block.getBlockData();
                        if (ss.isPowered()) { ss.setPowered(false); block.setBlockData(ss); }
                    }
                }, 15L);
            }
            return;
        }

        // ===== РЫЧАГ-ПОЛУЧАТЕЛЬ =====
        if (type.equals("LEVER") && block.getBlockData() instanceof Switch && senderType.contains("LEVER")) {
            Switch s = (Switch) block.getBlockData();
            if (s.isPowered() != isOn) {
                s.setPowered(isOn);
                block.setBlockData(s);
            }
            return;
        }

        if (type.equals("LEVER") && block.getBlockData() instanceof Switch && senderType.contains("BUTTON")) {
            Switch s = (Switch) block.getBlockData();
            if (isOn) {
                s.setPowered(!s.isPowered());
                block.setBlockData(s);
            }
            return;
        }

        if (type.equals("LEVER") && block.getBlockData() instanceof Switch && senderType.contains("PRESSURE_PLATE")) {
            Switch s = (Switch) block.getBlockData();
            if (isOn) {
                s.setPowered(!s.isPowered());
                block.setBlockData(s);
            }
            return;
        }

        if (type.equals("LEVER") && block.getBlockData() instanceof Switch && senderType.contains("LIGHTNING_ROD")) {
            Switch s = (Switch) block.getBlockData();
            if (s.isPowered() != isOn) {
                s.setPowered(isOn);
                block.setBlockData(s);
            }
            return;
        }

        if (type.equals("LEVER") && block.getBlockData() instanceof Switch && 
            (senderType.contains("REDSTONE_TORCH") || senderType.contains("REDSTONE_WALL_TORCH"))) {
            Switch s = (Switch) block.getBlockData();
            if (s.isPowered() != isOn) {
                s.setPowered(isOn);
                block.setBlockData(s);
            }
            return;
        }

        // ===== ПЛИТА-ПОЛУЧАТЕЛЬ =====
        if (type.contains("PRESSURE_PLATE") && block.getBlockData() instanceof org.bukkit.block.data.Powerable && senderType.contains("LEVER")) {
            org.bukkit.block.data.Powerable p = (org.bukkit.block.data.Powerable) block.getBlockData();
            p.setPowered(isOn);
            block.setBlockData(p);
            return;
        }

        if (type.contains("PRESSURE_PLATE") && block.getBlockData() instanceof org.bukkit.block.data.Powerable && senderType.contains("BUTTON")) {
            org.bukkit.block.data.Powerable p = (org.bukkit.block.data.Powerable) block.getBlockData();
            if (!isOn) {
                p.setPowered(true);
                block.setBlockData(p);
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (block.getType().name().contains("PRESSURE_PLATE") && block.getBlockData() instanceof org.bukkit.block.data.Powerable) {
                        org.bukkit.block.data.Powerable pp = (org.bukkit.block.data.Powerable) block.getBlockData();
                        if (pp.isPowered()) { pp.setPowered(false); block.setBlockData(pp); }
                    }
                }, 15L);
            }
            return;
        }

        if (type.contains("PRESSURE_PLATE") && block.getBlockData() instanceof org.bukkit.block.data.Powerable && senderType.contains("PRESSURE_PLATE")) {
            org.bukkit.block.data.Powerable p = (org.bukkit.block.data.Powerable) block.getBlockData();
            if (!isOn) {
                p.setPowered(true);
                block.setBlockData(p);
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (block.getType().name().contains("PRESSURE_PLATE") && block.getBlockData() instanceof org.bukkit.block.data.Powerable) {
                        org.bukkit.block.data.Powerable pp = (org.bukkit.block.data.Powerable) block.getBlockData();
                        if (pp.isPowered()) { pp.setPowered(false); block.setBlockData(pp); }
                    }
                }, 15L);
            }
            return;
        }

        if (type.contains("PRESSURE_PLATE") && block.getBlockData() instanceof org.bukkit.block.data.Powerable && senderType.contains("LIGHTNING_ROD")) {
            org.bukkit.block.data.Powerable p = (org.bukkit.block.data.Powerable) block.getBlockData();
            if (!isOn) {
                p.setPowered(true);
                block.setBlockData(p);
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (block.getType().name().contains("PRESSURE_PLATE") && block.getBlockData() instanceof org.bukkit.block.data.Powerable) {
                        org.bukkit.block.data.Powerable pp = (org.bukkit.block.data.Powerable) block.getBlockData();
                        if (pp.isPowered()) { pp.setPowered(false); block.setBlockData(pp); }
                    }
                }, 15L);
            }
            return;
        }

        if (type.contains("PRESSURE_PLATE") && block.getBlockData() instanceof org.bukkit.block.data.Powerable && 
            (senderType.contains("REDSTONE_TORCH") || senderType.contains("REDSTONE_WALL_TORCH"))) {
            org.bukkit.block.data.Powerable p = (org.bukkit.block.data.Powerable) block.getBlockData();
            if (!isOn) {
                p.setPowered(true);
                block.setBlockData(p);
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (block.getType().name().contains("PRESSURE_PLATE") && block.getBlockData() instanceof org.bukkit.block.data.Powerable) {
                        org.bukkit.block.data.Powerable pp = (org.bukkit.block.data.Powerable) block.getBlockData();
                        if (pp.isPowered()) { pp.setPowered(false); block.setBlockData(pp); }
                    }
                }, 15L);
            }
            return;
        }

        // ===== ГРОМООТВОД-ПОЛУЧАТЕЛЬ =====
        if (type.contains("LIGHTNING_ROD") && block.getBlockData() instanceof LightningRod && senderType.contains("LEVER")) {
            LightningRod r = (LightningRod) block.getBlockData();
            r.setPowered(isOn);
            block.setBlockData(r);
            return;
        }

        if (type.contains("LIGHTNING_ROD") && block.getBlockData() instanceof LightningRod && senderType.contains("BUTTON")) {
            LightningRod r = (LightningRod) block.getBlockData();
            if (!isOn) {
                r.setPowered(true);
                block.setBlockData(r);
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (block.getType().name().contains("LIGHTNING_ROD") && block.getBlockData() instanceof LightningRod) {
                        LightningRod rr = (LightningRod) block.getBlockData();
                        if (rr.isPowered()) { rr.setPowered(false); block.setBlockData(rr); }
                    }
                }, 15L);
            }
            return;
        }

        if (type.contains("LIGHTNING_ROD") && block.getBlockData() instanceof LightningRod && senderType.contains("PRESSURE_PLATE")) {
            LightningRod r = (LightningRod) block.getBlockData();
            if (!isOn) {
                r.setPowered(true);
                block.setBlockData(r);
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (block.getType().name().contains("LIGHTNING_ROD") && block.getBlockData() instanceof LightningRod) {
                        LightningRod rr = (LightningRod) block.getBlockData();
                        if (rr.isPowered()) { rr.setPowered(false); block.setBlockData(rr); }
                    }
                }, 15L);
            }
            return;
        }

        if (type.contains("LIGHTNING_ROD") && block.getBlockData() instanceof LightningRod && senderType.contains("LIGHTNING_ROD")) {
            LightningRod r = (LightningRod) block.getBlockData();
            r.setPowered(isOn);
            block.setBlockData(r);
            return;
        }

        if (type.contains("LIGHTNING_ROD") && block.getBlockData() instanceof LightningRod && 
            (senderType.contains("REDSTONE_TORCH") || senderType.contains("REDSTONE_WALL_TORCH"))) {
            LightningRod r = (LightningRod) block.getBlockData();
            r.setPowered(isOn);
            block.setBlockData(r);
            return;
        }

        // ===== ФАКЕЛ-ПОЛУЧАТЕЛЬ =====
        if ((type.equals("REDSTONE_TORCH") || type.equals("REDSTONE_WALL_TORCH"))
                && block.getBlockData() instanceof org.bukkit.block.data.Lightable) {
            org.bukkit.block.data.Lightable l = (org.bukkit.block.data.Lightable) block.getBlockData();
            boolean current = l.isLit();

            if (senderType.contains("LEVER")) {
                // Рычаг → Факел: ИНВЕРСИЯ (синхронно)
                boolean shouldBeLit = !isOn;
                if (current != shouldBeLit) {
                    l.setLit(shouldBeLit);
                    block.setBlockData(l);
                }
            } else if (senderType.contains("BUTTON") || senderType.contains("PRESSURE_PLATE")) {
                // Кнопка/Плита → Факел: МИГАНИЕ ПРИ ОТЖАТИИ (30 тиков = 1.5 секунды)
                if (!isOn && !block.hasMetadata("qqr_flicker")) {
                    l.setLit(!current);
                    block.setBlockData(l);
                    
                    block.setMetadata("qqr_flicker", new FixedMetadataValue(plugin, true));
                    
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if ((block.getType().name().equals("REDSTONE_TORCH") || block.getType().name().equals("REDSTONE_WALL_TORCH"))
                                && block.getBlockData() instanceof org.bukkit.block.data.Lightable) {
                            org.bukkit.block.data.Lightable ll = (org.bukkit.block.data.Lightable) block.getBlockData();
                            ll.setLit(current);
                            block.setBlockData(ll);
                        }
                        block.removeMetadata("qqr_flicker", plugin);
                    }, 30L);
                }
            } else {
                // Громоотвод/Факел → Факел: ИНВЕРСИЯ (синхронно)
                boolean shouldBeLit = !isOn;
                if (current != shouldBeLit) {
                    l.setLit(shouldBeLit);
                    block.setBlockData(l);
                }
            }
            return;
        }
    }

    private boolean isFreqDisabled(String freq) {
        if (!disabledFreqs.containsKey(freq)) return false;
        long until = disabledFreqs.get(freq);
        if (until == 0) return true;
        if (System.currentTimeMillis() > until) {
            disabledFreqs.remove(freq);
            triggerHistory.remove(freq);
            return false;
        }
        return true;
    }

    private boolean checkAntiFlood(String freq, Block block) {
        if (!plugin.getConfig().getBoolean("transmission.anti-flood.enabled", false)) return true;
        int maxTriggers = plugin.getConfig().getInt("transmission.anti-flood.max-triggers", 10);
        long periodMs = plugin.getConfig().getLong("transmission.anti-flood.period-ticks", 2) * 50;
        long disableTicks = plugin.getConfig().getLong("transmission.anti-flood.disable-ticks", 100);

        long now = System.currentTimeMillis();
        Deque<Long> history = triggerHistory.computeIfAbsent(freq, k -> new ArrayDeque<>());

        while (!history.isEmpty() && (now - history.peekFirst()) > periodMs) {
            history.pollFirst();
        }

        history.addLast(now);

        if (history.size() > maxTriggers) {
            long until = (disableTicks <= 0) ? 0 : (now + (disableTicks * 50));
            disabledFreqs.put(freq, until);

            String disableTimeStr = (disableTicks <= 0) ? "навсегда" : (disableTicks / 20) + " сек.";
            plugin.getLogger().warning("[АНТИ-ФЛУД] Частота " + freq + " заблокирована на " + disableTimeStr);

            if (plugin.getConfig().getBoolean("transmission.anti-flood.notify-admins", true)) {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (player.hasPermission("qqredstone.notification") || player.isOp()) {
                        player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                            "&4[QQR] &cЧастота &6" + freq + " &cзаблокирована на " + disableTimeStr));
                    }
                }
            }
            return false;
        }
        return true;
    }

    private boolean isOnCooldown(String freq) {
        long cooldownTicks = plugin.getConfig().getLong("transmission.cooldown-ticks", 0);
        if (cooldownTicks <= 0) return false;

        long cooldownMs = cooldownTicks * 50;
        long now = System.currentTimeMillis();
        Long last = lastTransmission.get(freq);

        if (last != null && (now - last) < cooldownMs) return true;

        lastTransmission.put(freq, now);
        return false;
    }

    public void resetFreq(String freq) {
        disabledFreqs.remove(freq);
        triggerHistory.remove(freq);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("qqredstone.notification") || player.isOp()) {
                player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    "&a[QQR] &eБлокировка частоты &6" + freq + " &eсброшена."));
            }
        }
    }

    public void resetAllFreqs() {
        disabledFreqs.clear();
        triggerHistory.clear();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("qqredstone.notification") || player.isOp()) {
                player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    "&a[QQR] &eВсе блокировки частот сброшены."));
            }
        }
    }
}
