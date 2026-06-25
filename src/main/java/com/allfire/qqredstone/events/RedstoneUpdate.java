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
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockRedstoneEvent;

import java.util.*;

public class RedstoneUpdate implements Listener {

    private final QQRedstone plugin;
    private final DatabaseManager databaseManager;
    
    private final Map<Location, Integer> poweredSenders = new HashMap<>();
    private final Map<String, Long> lastTransmission = new HashMap<>();
    private final Map<String, Deque<Long>> triggerHistory = new HashMap<>();
    private final Map<String, Long> disabledFreqs = new HashMap<>();
    
    // Менеджер принудительного удержания состояния факелов для борьбы с ванильной физикой
    private final Set<Location> forcedOffTorches = new HashSet<>();

    public RedstoneUpdate(QQRedstone plugin, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
    }

    /**
     * Единственно верный способ применения BlockData в современном Bukkit.
     * Меняет состояние, обновляет блок в мире и заставляет ядро сервера
     * честно выполнить ванильный пересчет всей редстоун-сети вокруг.
     */
    private void applyBlockState(Block block, BlockData data) {
        block.setBlockData(data, true);
        block.getState().update(true, true);
        block.getWorld().notifyAndSyncOnBlockStateChange(
            block.getLocation(),
            data,
            data
        );
    }

    @EventHandler
    public void onRedstoneChange(BlockRedstoneEvent event) {
        processBlock(event.getBlock());
    }

    @EventHandler
    public void onBlockPhysics(BlockPhysicsEvent event) {
        Block block = event.getBlock();
        Material m = block.getType();
        
        // Перехват ванильной физики для заблокированных факелов-получателей
        if ((m == Material.REDSTONE_TORCH || m == Material.REDSTONE_WALL_TORCH)
                && forcedOffTorches.contains(block.getLocation())) {
            
            if (block.getBlockData() instanceof org.bukkit.block.data.Lightable) {
                org.bukkit.block.data.Lightable lightable = (org.bukkit.block.data.Lightable) block.getBlockData();
                if (lightable.isLit()) {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (block.getType() == m && block.getBlockData() instanceof org.bukkit.block.data.Lightable) {
                            org.bukkit.block.data.Lightable l = (org.bukkit.block.data.Lightable) block.getBlockData();
                            if (l.isLit() && forcedOffTorches.contains(block.getLocation())) {
                                l.setLit(false);
                                applyBlockState(block, l);
                            }
                        }
                    });
                }
            }
            return;
        }
        
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

        if (block.getType() == Material.AIR || block.getType() == Material.WATER || block.getType() == Material.LAVA ||
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
        
        if (!savedType.equals(currentType)) return false;
        
        if (mechanism.getBelowWorld() != null) {
            Block belowBlock = mechanismBlock.getRelative(BlockFace.DOWN);
            String savedBelowType = mechanism.getBelowType();
            String currentBelowType = belowBlock.getType().name();
            if (!savedBelowType.equals(currentBelowType)) return false;
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
        int finalPower = "book".equals(powerMode) ? (sender.getBookPower() > 0 ? sender.getBookPower() : vanillaPower) :
                         "both".equals(powerMode) ? Math.max(vanillaPower, sender.getBookPower()) : vanillaPower;

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
            
            if (!validateAttachedBlock(receiver, receiverBlock)) {
                databaseManager.removeMechanism(receiver.getWorld(), receiver.getX(), receiver.getY(), receiver.getZ());
                continue;
            }
            
            // Запуск распределителя логики по твоей матрице 25 комбинаций
            executeMatrixLogic(receiverBlock, isOn, block.getType().name());
        }
    }

    /**
     * РЕАЛИЗАЦИЯ МАТРИЦЫ ИЗ 25 КОМБИНАЦИЙ
     */
    private void executeMatrixLogic(Block block, boolean isOn, String senderType) {
        String recType = block.getType().name();
        boolean isButtonSender = senderType.contains("BUTTON");
        boolean isPlateSender = senderType.contains("PRESSURE_PLATE");
        boolean isLeverSender = senderType.contains("LEVER");
        boolean isRodSender = senderType.contains("LIGHTNING_ROD");
        boolean isTorchSender = senderType.contains("TORCH");

        // ==========================================
        // 1. ПОЛУЧАТЕЛЬ — КНОПКА (BUTTON)
        // ==========================================
        if (recType.contains("BUTTON") && block.getBlockData() instanceof Switch) {
            Switch s = (Switch) block.getBlockData();
            
            // Комбинации: Кнопка->Кнопка (1.1), Плита->Кнопка (3.1), Громоотвод->Кнопка (4.1), Факел->Кнопка (5.1)
            // Все они работают как Импульс: ВКЛ -> нажимается, ВЫКЛ -> отжимается через 15 тиков
            if (isButtonSender || isPlateSender || isRodSender || isTorchSender) {
                if (isOn) {
                    s.setPowered(true);
                    applyBlockState(block, s);
                    
                    int ticks = getButtonDuration(block); // 15 для дерева, 10 для камня
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (block.getType().name().contains("BUTTON") && block.getBlockData() instanceof Switch) {
                            Switch current = (Switch) block.getBlockData();
                            if (current.isPowered()) {
                                current.setPowered(false);
                                applyBlockState(block, current);
                            }
                        }
                    }, ticks);
                }
            }
            // Комбинация: Рычаг->Кнопка (2.1) -> Держит состояние вкл/выкл напрямую
            else if (isLeverSender) {
                s.setPowered(isOn);
                applyBlockState(block, s);
            }
            return;
        }

        // ==========================================
        // 2. ПОЛУЧАТЕЛЬ — РЫЧАГ (LEVER)
        // ==========================================
        if (recType.equals("LEVER") && block.getBlockData() instanceof Switch) {
            Switch s = (Switch) block.getBlockData();
            
            // Комбинации: Кнопка->Рычаг (1.2), Плита->Рычаг (3.2) -> Инвертируют (переключают) рычаг при ВКЛ, игнорируют ВЫКЛ
            if (isButtonSender || isPlateSender) {
                if (isOn) {
                    s.setPowered(!s.isPowered());
                    applyBlockState(block, s);
                }
            }
            // Комбинации: Рычаг->Рычаг (2.2), Громоотвод->Рычаг (4.2), Факел->Рычаг (5.2) -> Держат состояние напрямую
            else if (isLeverSender || isRodSender || isTorchSender) {
                if (s.isPowered() != isOn) {
                    s.setPowered(isOn);
                    applyBlockState(block, s);
                }
            }
            return;
        }

        // ==========================================
        // 3. ПОЛУЧАТЕЛЬ — ПЛИТА (PRESSURE_PLATE)
        // ==========================================
        if (recType.contains("PRESSURE_PLATE") && block.getBlockData() instanceof org.bukkit.block.data.Powerable) {
            org.bukkit.block.data.Powerable p = (org.bukkit.block.data.Powerable) block.getBlockData();
            
            // Комбинации: Кнопка->Плита (1.3), Плита->Плита (3.3), Громоотвод->Плита (4.3), Факел->Плита (5.3) -> Импульс
            if (isButtonSender || isPlateSender || isRodSender || isTorchSender) {
                if (isOn) {
                    p.setPowered(true);
                    applyBlockState(block, p);
                    
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (block.getType().name().contains("PRESSURE_PLATE") && block.getBlockData() instanceof org.bukkit.block.data.Powerable) {
                            org.bukkit.block.data.Powerable current = (org.bukkit.block.data.Powerable) block.getBlockData();
                            if (current.isPowered()) {
                                current.setPowered(false);
                                applyBlockState(block, current);
                            }
                        }
                    }, 15L); // Ровно 15 тиков по ТЗ
                }
            }
            // Комбинация: Рычаг->Плита (2.3) -> Держит состояние напрямую
            else if (isLeverSender) {
                p.setPowered(isOn);
                applyBlockState(block, p);
            }
            return;
        }

        // ==========================================
        // 4. ПОЛУЧАТЕЛЬ — ГРОМООТВОД (LIGHTNING_ROD)
        // ==========================================
        if (recType.contains("LIGHTNING_ROD") && block.getBlockData() instanceof LightningRod) {
            LightningRod r = (LightningRod) block.getBlockData();
            
            // Комбинации: Кнопка->Громоотвод (1.4), Плита->Громоотвод (3.4) -> Импульс на 15 тиков
            if (isButtonSender || isPlateSender) {
                if (isOn) {
                    r.setPowered(true);
                    applyBlockState(block, r);
                    
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (block.getType().name().contains("LIGHTNING_ROD") && block.getBlockData() instanceof LightningRod) {
                            LightningRod current = (LightningRod) block.getBlockData();
                            if (current.isPowered()) {
                                current.setPowered(false);
                                applyBlockState(block, current);
                            }
                        }
                    }, 15L);
                }
            }
            // Комбинации: Рычаг->Громоотвод (2.4), Громоотвод->Громоотвод (4.4), Факел->Громоотвод (5.4) -> Держит состояние
            else if (isLeverSender || isRodSender || isTorchSender) {
                if (r.isPowered() != isOn) {
                    r.setPowered(isOn);
                    applyBlockState(block, r);
                }
            }
            return;
        }

        // ==========================================
        // 5. ПОЛУЧАТЕЛЬ — РЕДСТОУН-ФАКЕЛ (TORCH)
        // ==========================================
        if ((recType.equals("REDSTONE_TORCH") || recType.equals("REDSTONE_WALL_TORCH")) 
                && block.getBlockData() instanceof org.bukkit.block.data.Lightable) {
            org.bukkit.block.data.Lightable l = (org.bukkit.block.data.Lightable) block.getBlockData();
            Location loc = block.getLocation();
            
            // Комбинации: Кнопка->Факел (1.5), Плита->Факел (3.5) -> Импульсное мигание (гасится при ВКЛ, возвращается через 15 тиков)
            if (isButtonSender || isPlateSender) {
                if (isOn) {
                    l.setLit(false);
                    applyBlockState(block, l);
                    forcedOffTorches.add(loc);
                    
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        forcedOffTorches.remove(loc);
                        if ((block.getType().name().equals("REDSTONE_TORCH") || block.getType().name().equals("REDSTONE_WALL_TORCH"))
                                && block.getBlockData() instanceof org.bukkit.block.data.Lightable) {
                            org.bukkit.block.data.Lightable current = (org.bukkit.block.data.Lightable) block.getBlockData();
                            current.setLit(true);
                            applyBlockState(block, current);
                        }
                    }, 15L);
                }
            }
            // Комбинации: Рычаг->Факел (2.5), Громоотвод->Факел (4.5), Факел->Факел (5.5) -> Полная инверсия (ВКЛ = погас, ВЫКЛ = горит)
            else if (isLeverSender || isRodSender || isTorchSender) {
                boolean shouldBeLit = !isOn;
                if (l.isLit() != shouldBeLit) {
                    l.setLit(shouldBeLit);
                    applyBlockState(block, l);
                    
                    if (!shouldBeLit) {
                        forcedOffTorches.add(loc);
                    } else {
                        forcedOffTorches.remove(loc);
                    }
                }
            }
            return;
        }
    }

    private int getButtonDuration(Block block) {
        String name = block.getType().name();
        if (name.contains("OAK") || name.contains("SPRUCE") || name.contains("BIRCH") || 
            name.contains("JUNGLE") || name.contains("ACACIA") || name.contains("DARK_OAK") ||
            name.contains("MANGROVE") || name.contains("CHERRY") || name.contains("BAMBOO") ||
            name.contains("CRIMSON") || name.contains("WARPED")) {
            return 15; // По ТЗ деревянные кнопки держим 15 тиков
        }
        return 10; // Каменные — 10 тиков
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
    }

    public void resetAllFreqs() {
        disabledFreqs.clear();
        triggerHistory.clear();
    }
}
