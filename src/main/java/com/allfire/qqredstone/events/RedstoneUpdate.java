package com.allfire.qqredstone.events;

import com.allfire.qqredstone.QQRedstone;
import com.allfire.qqredstone.database.DatabaseManager;
import com.allfire.qqredstone.database.Mechanism;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.LightningRod;
import org.bukkit.block.data.type.Switch;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockRedstoneEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class RedstoneUpdate implements Listener {

    private final QQRedstone plugin;
    private final DatabaseManager databaseManager;
    
    private final Map<Location, Integer> poweredSenders = new HashMap<>();
    private final Map<String, Long> lastTransmission = new HashMap<>();
    private final Map<String, Deque<Long>> triggerHistory = new HashMap<>();
    private final Map<String, Long> disabledFreqs = new HashMap<>();
    
    private final Set<Location> forcedOffTorches = new HashSet<>();
    
    // ===== ФЛАГ ИГНОРИРОВАНИЯ ФИЗИКИ =====
    private final Set<Location> ignorePhysicsBlocks = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public RedstoneUpdate(QQRedstone plugin, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
    }

    /**
     * Безопасное применение состояния блока с принудительным обновлением редстоун-сети.
     */
    private void applyBlockState(Block block, BlockData data) {
        Location loc = block.getLocation();
        
        ignorePhysicsBlocks.add(loc);
        
        try {
            block.setBlockData(data, true);
            block.getState().update(true, true);
        } finally {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                ignorePhysicsBlocks.remove(loc);
            }, 2L);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRedstoneChange(BlockRedstoneEvent event) {
        Block block = event.getBlock();
        
        if (ignorePhysicsBlocks.contains(block.getLocation())) {
            return;
        }
        
        processBlock(block);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockPhysics(BlockPhysicsEvent event) {
        Block block = event.getBlock();
        Location loc = block.getLocation();
        
        if (ignorePhysicsBlocks.contains(loc)) {
            event.setCancelled(true);
            return;
        }
        
        Material m = block.getType();
        
        // Валидация: если блок стал воздухом, удаляем механизм
        if (m == Material.AIR || m == Material.WATER || m == Material.LAVA) {
            Mechanism mech = databaseManager.getMechanismAt(block);
            if (mech != null) {
                databaseManager.removeMechanism(
                    block.getWorld().getName(),
                    block.getX(), block.getY(), block.getZ()
                );
                forcedOffTorches.remove(loc);
                poweredSenders.remove(loc);
                plugin.getLogger().info("[QQR] Механизм удалён (блок стал " + m.name() + "): " + loc);
            }
            return;
        }
        
        // Обрабатываем только нужные типы блоков
        if (m != Material.LEVER && 
            !m.name().contains("BUTTON") && 
            !m.name().contains("PRESSURE_PLATE") &&
            !m.name().contains("LIGHTNING_ROD") &&
            m != Material.REDSTONE_TORCH && 
            m != Material.REDSTONE_WALL_TORCH) {
            return;
        }
        
        // Обработка принудительно выключенных факелов
        if ((m == Material.REDSTONE_TORCH || m == Material.REDSTONE_WALL_TORCH)
                && forcedOffTorches.contains(loc)) {
            
            if (block.getBlockData() instanceof org.bukkit.block.data.Lightable) {
                org.bukkit.block.data.Lightable lightable = (org.bukkit.block.data.Lightable) block.getBlockData();
                if (lightable.isLit()) {
                    ignorePhysicsBlocks.add(loc);
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        try {
                            if (block.getType() == m && block.getBlockData() instanceof org.bukkit.block.data.Lightable) {
                                org.bukkit.block.data.Lightable l = (org.bukkit.block.data.Lightable) block.getBlockData();
                                if (l.isLit() && forcedOffTorches.contains(loc)) {
                                    l.setLit(false);
                                    block.setBlockData(l, true);
                                    block.getState().update(true, true);
                                }
                            }
                        } finally {
                            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                                ignorePhysicsBlocks.remove(loc);
                            }, 2L);
                        }
                    });
                }
            }
            return;
        }
        
        processBlock(block);
    }

    // ===== СЛУШАТЕЛЬ: ВОДА/ЛАВА =====
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockFromTo(BlockFromToEvent event) {
        Block toBlock = event.getToBlock();
        if (toBlock == null) return;
        
        Material liquid = event.getBlock().getType();
        if (liquid != Material.WATER && liquid != Material.LAVA) return;
        
        Mechanism mech = databaseManager.getMechanismAt(toBlock);
        if (mech != null) {
            databaseManager.removeMechanism(
                toBlock.getWorld().getName(),
                toBlock.getX(), toBlock.getY(), toBlock.getZ()
            );
            forcedOffTorches.remove(toBlock.getLocation());
            poweredSenders.remove(toBlock.getLocation());
            plugin.getLogger().info("[QQR] Механизм удалён (залит " + liquid.name() + "): " + toBlock.getLocation());
        }
    }

    private void processBlock(Block block) {
        Mechanism sender = databaseManager.getMechanismAt(block);
        if (sender == null || !sender.getType().equals("sender")) return;

        if (!isValidMechanismBlock(block)) {
            databaseManager.removeMechanism(
                sender.getWorld(), sender.getX(), sender.getY(), sender.getZ()
            );
            forcedOffTorches.remove(block.getLocation());
            poweredSenders.remove(block.getLocation());
            return;
        }

        if (!validateAttachedBlock(sender, block)) {
            databaseManager.removeMechanism(
                sender.getWorld(), sender.getX(), sender.getY(), sender.getZ()
            );
            forcedOffTorches.remove(block.getLocation());
            poweredSenders.remove(block.getLocation());
            return;
        }

        String freq = sender.getFrequency().trim();

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

        if (type.equals("REDSTONE_TORCH") || type.equals("REDSTONE_WALL_TORCH")) {
            if (block.getBlockData() instanceof org.bukkit.block.data.Lightable) {
                return ((org.bukkit.block.data.Lightable) block.getBlockData()).isLit() ? 15 : 0;
            }
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
            
            Location loc = new Location(Bukkit.getWorld(receiver.getWorld()), receiver.getX(), receiver.getY(), receiver.getZ());
            Block receiverBlock = loc.getBlock();
            
            if (!isValidMechanismBlock(receiverBlock)) {
                databaseManager.removeMechanism(
                    receiver.getWorld(), receiver.getX(), receiver.getY(), receiver.getZ()
                );
                forcedOffTorches.remove(loc);
                continue;
            }
            
            if (!validateAttachedBlock(receiver, receiverBlock)) {
                databaseManager.removeMechanism(
                    receiver.getWorld(), receiver.getX(), receiver.getY(), receiver.getZ()
                );
                forcedOffTorches.remove(loc);
                continue;
            }
            
            activateReceiver(receiverBlock, isOn, block.getType().name());
        }
    }

    // ===== ИСПРАВЛЕННАЯ ЛОГИКА АКТИВАЦИИ ПОЛУЧАТЕЛЯ =====
    private void activateReceiver(Block block, boolean isOn, String senderType) {
        String type = block.getType().name();
        Location loc = block.getLocation();

        // ==========================================
        // ПОЛУЧАТЕЛЬ — КНОПКА (BUTTON)
        // ==========================================
        if (type.contains("BUTTON") && block.getBlockData() instanceof Switch) {
            Switch s = (Switch) block.getBlockData();

            if (senderType.contains("BUTTON") || senderType.contains("PRESSURE_PLATE") || 
                senderType.contains("LIGHTNING_ROD") || senderType.contains("TORCH")) {
                
                if (isOn) {
                    ignorePhysicsBlocks.add(loc);
                    s.setPowered(true);
                    block.setBlockData(s, true);
                    block.getState().update(true, true);
                    
                    int duration = getButtonDuration(block);
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        try {
                            if (block.getType().name().contains("BUTTON") && block.getBlockData() instanceof Switch) {
                                Switch ss = (Switch) block.getBlockData();
                                if (ss.isPowered()) {
                                    ignorePhysicsBlocks.add(loc);
                                    ss.setPowered(false);
                                    block.setBlockData(ss, true);
                                    block.getState().update(true, true);
                                }
                            }
                        } finally {
                            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                                ignorePhysicsBlocks.remove(loc);
                            }, 2L);
                        }
                    }, duration);
                }
                return;
            }

            if (senderType.contains("LEVER")) {
                ignorePhysicsBlocks.add(loc);
                s.setPowered(isOn);
                block.setBlockData(s, true);
                block.getState().update(true, true);
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    ignorePhysicsBlocks.remove(loc);
                }, 2L);
                return;
            }
        }

        // ==========================================
        // ПОЛУЧАТЕЛЬ — РЫЧАГ (LEVER)
        // ==========================================
        if (type.equals("LEVER") && block.getBlockData() instanceof Switch) {
            Switch s = (Switch) block.getBlockData();

            if (senderType.contains("BUTTON") || senderType.contains("PRESSURE_PLATE")) {
                if (isOn) {
                    ignorePhysicsBlocks.add(loc);
                    s.setPowered(!s.isPowered());
                    block.setBlockData(s, true);
                    block.getState().update(true, true);
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        ignorePhysicsBlocks.remove(loc);
                    }, 2L);
                }
                return;
            }

            if (senderType.contains("LEVER") || senderType.contains("LIGHTNING_ROD") || senderType.contains("TORCH")) {
                if (s.isPowered() != isOn) {
                    ignorePhysicsBlocks.add(loc);
                    s.setPowered(isOn);
                    block.setBlockData(s, true);
                    block.getState().update(true, true);
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        ignorePhysicsBlocks.remove(loc);
                    }, 2L);
                }
                return;
            }
        }

        // ==========================================
        // ПОЛУЧАТЕЛЬ — ПЛИТА (PRESSURE_PLATE)
        // ==========================================
        if (type.contains("PRESSURE_PLATE") && block.getBlockData() instanceof org.bukkit.block.data.Powerable) {
            org.bukkit.block.data.Powerable p = (org.bukkit.block.data.Powerable) block.getBlockData();

            if (senderType.contains("BUTTON") || senderType.contains("PRESSURE_PLATE") || 
                senderType.contains("LIGHTNING_ROD") || senderType.contains("TORCH")) {
                
                if (isOn) {
                    ignorePhysicsBlocks.add(loc);
                    p.setPowered(true);
                    block.setBlockData(p, true);
                    block.getState().update(true, true);
                    
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        try {
                            if (block.getType().name().contains("PRESSURE_PLATE") && 
                                block.getBlockData() instanceof org.bukkit.block.data.Powerable) {
                                org.bukkit.block.data.Powerable pp = 
                                    (org.bukkit.block.data.Powerable) block.getBlockData();
                                if (pp.isPowered()) {
                                    ignorePhysicsBlocks.add(loc);
                                    pp.setPowered(false);
                                    block.setBlockData(pp, true);
                                    block.getState().update(true, true);
                                }
                            }
                        } finally {
                            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                                ignorePhysicsBlocks.remove(loc);
                            }, 2L);
                        }
                    }, 15L);
                }
                return;
            }

            if (senderType.contains("LEVER")) {
                ignorePhysicsBlocks.add(loc);
                p.setPowered(isOn);
                block.setBlockData(p, true);
                block.getState().update(true, true);
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    ignorePhysicsBlocks.remove(loc);
                }, 2L);
                return;
            }
        }

        // ==========================================
        // ПОЛУЧАТЕЛЬ — ГРОМООТВОД (LIGHTNING_ROD)
        // ==========================================
        if (type.contains("LIGHTNING_ROD") && block.getBlockData() instanceof LightningRod) {
            LightningRod r = (LightningRod) block.getBlockData();

            if (senderType.contains("BUTTON") || senderType.contains("PRESSURE_PLATE")) {
                if (isOn) {
                    ignorePhysicsBlocks.add(loc);
                    r.setPowered(true);
                    block.setBlockData(r, true);
                    block.getState().update(true, true);
                    
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        try {
                            if (block.getType().name().contains("LIGHTNING_ROD") && 
                                block.getBlockData() instanceof LightningRod) {
                                LightningRod rr = (LightningRod) block.getBlockData();
                                if (rr.isPowered()) {
                                    ignorePhysicsBlocks.add(loc);
                                    rr.setPowered(false);
                                    block.setBlockData(rr, true);
                                    block.getState().update(true, true);
                                }
                            }
                        } finally {
                            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                                ignorePhysicsBlocks.remove(loc);
                            }, 2L);
                        }
                    }, 15L);
                }
                return;
            }

            if (senderType.contains("LEVER") || senderType.contains("LIGHTNING_ROD") || senderType.contains("TORCH")) {
                if (r.isPowered() != isOn) {
                    ignorePhysicsBlocks.add(loc);
                    r.setPowered(isOn);
                    block.setBlockData(r, true);
                    block.getState().update(true, true);
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        ignorePhysicsBlocks.remove(loc);
                    }, 2L);
                }
                return;
            }
        }

        // ==========================================
        // ПОЛУЧАТЕЛЬ — РЕДСТОУН-ФАКЕЛ (TORCH)
        // ==========================================
        if ((type.equals("REDSTONE_TORCH") || type.equals("REDSTONE_WALL_TORCH"))
                && block.getBlockData() instanceof org.bukkit.block.data.Lightable) {
            org.bukkit.block.data.Lightable l = (org.bukkit.block.data.Lightable) block.getBlockData();

            // КНОПКА -> ФАКЕЛ (Импульс)
            // ПЛИТА -> ФАКЕЛ (Импульс)
            if (senderType.contains("BUTTON") || senderType.contains("PRESSURE_PLATE")) {
                if (isOn) {
                    ignorePhysicsBlocks.add(loc);
                    l.setLit(false);
                    block.setBlockData(l, true);
                    block.getState().update(true, true);
                    forcedOffTorches.add(loc);
                    
                    int duration = senderType.contains("BUTTON") ? getButtonDuration(block) : 15;
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        try {
                            if ((block.getType().name().equals("REDSTONE_TORCH") || 
                                 block.getType().name().equals("REDSTONE_WALL_TORCH"))
                                    && block.getBlockData() instanceof org.bukkit.block.data.Lightable) {
                                org.bukkit.block.data.Lightable ll = 
                                    (org.bukkit.block.data.Lightable) block.getBlockData();
                                ignorePhysicsBlocks.add(loc);
                                ll.setLit(true);
                                block.setBlockData(ll, true);
                                block.getState().update(true, true);
                                forcedOffTorches.remove(loc);
                            }
                        } finally {
                            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                                ignorePhysicsBlocks.remove(loc);
                            }, 2L);
                        }
                    }, duration);
                }
                return;
            }

            // РЫЧАГ -> ФАКЕЛ (Инверсия)
            // ГРОМООТВОД -> ФАКЕЛ (Инверсия)
            // ФАКЕЛ -> ФАКЕЛ (Повторяет состояние)
            if (senderType.contains("LEVER") || senderType.contains("LIGHTNING_ROD") || senderType.contains("TORCH")) {
                boolean shouldBeLit;
                if (senderType.contains("TORCH")) {
                    shouldBeLit = isOn;
                } else {
                    shouldBeLit = !isOn;
                }
                
                if (l.isLit() != shouldBeLit) {
                    ignorePhysicsBlocks.add(loc);
                    l.setLit(shouldBeLit);
                    block.setBlockData(l, true);
                    block.getState().update(true, true);
                    
                    if (!shouldBeLit) {
                        forcedOffTorches.add(loc);
                    } else {
                        forcedOffTorches.remove(loc);
                    }
                    
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        ignorePhysicsBlocks.remove(loc);
                    }, 2L);
                }
                return;
            }
        }
    }

    private int getButtonDuration(Block block) {
        String name = block.getType().name();
        if (name.contains("OAK") || name.contains("SPRUCE") || name.contains("BIRCH") || 
            name.contains("JUNGLE") || name.contains("ACACIA") || name.contains("DARK_OAK") ||
            name.contains("MANGROVE") || name.contains("CHERRY") || name.contains("BAMBOO") ||
            name.contains("CRIMSON") || name.contains("WARPED")) {
            return 15;
        }
        return 10;
    }

    // ===== ВСПОМОГАТЕЛЬНЫЙ МЕТОД ДЛЯ УДАЛЕНИЯ МЕХАНИЗМА =====
    public void removeMechanism(Location loc) {
        if (loc == null) return;
        Block block = loc.getBlock();
        Mechanism mech = databaseManager.getMechanismAt(block);
        if (mech != null) {
            databaseManager.removeMechanism(
                block.getWorld().getName(),
                block.getX(), block.getY(), block.getZ()
            );
            forcedOffTorches.remove(loc);
            poweredSenders.remove(loc);
        }
    }

    // ===== ОСТАЛЬНЫЕ МЕТОДЫ =====
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
