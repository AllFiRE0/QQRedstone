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
        processBlock(block);
    }

    private void processBlock(Block block) {
        Mechanism sender = databaseManager.getMechanismAt(block);
        if (sender == null || !sender.getType().equals("sender")) return;

        if (!validateAttachedBlock(sender, block)) {
            databaseManager.removeMechanism(sender.getWorld(), sender.getX(), sender.getY(), sender.getZ());
            plugin.getLogger().info("Автоочистка: удалён отправитель на " + 
                    sender.getWorld() + "," + sender.getX() + "," + sender.getY() + "," + sender.getZ() +
                    " (блок под ним изменился)");
            return;
        }

        String freq = sender.getFrequency();

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
                return ((Switch) block.getBlockData()).isPowered() ? 15 : 0;
            }
        }

        if (type.contains("PRESSURE_PLATE")) {
            return block.getBlockPower(BlockFace.UP) > 0 ? 15 : 0;
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
                plugin.getLogger().info("Автоочистка: удалён получатель на " + 
                        receiver.getWorld() + "," + receiver.getX() + "," + receiver.getY() + "," + receiver.getZ() +
                        " (блок под ним изменился)");
                continue;
            }
            
            // Передаём тип отправителя
            activateReceiver(receiverBlock, isOn, sender.getType());
        }
    }

    private void activateReceiver(Block block, boolean isOn, String senderType) {
        String type = block.getType().name();

        // ===== КНОПКА-ПОЛУЧАТЕЛЬ =====
        if (type.contains("BUTTON") && block.getBlockData() instanceof Switch) {
            Switch switchData = (Switch) block.getBlockData();
            
            if (senderType.equals("LEVER")) {
                // Рычаг → Кнопка: кнопка повторяет состояние (держится)
                switchData.setPowered(isOn);
                block.setBlockData(switchData);
            } else {
                // Кнопка/Плита/Громоотвод → Кнопка: импульс при ЛЮБОМ изменении
                switchData.setPowered(true);
                block.setBlockData(switchData);
                // Ваниль сама отпустит через 15 тиков
            }
            return;
        }

        // ===== РЫЧАГ-ПОЛУЧАТЕЛЬ =====
        if (type.equals("LEVER") && block.getBlockData() instanceof Switch) {
            Switch switchData = (Switch) block.getBlockData();
            
            if (senderType.equals("BUTTON")) {
                // Кнопка → Рычаг: переключение при каждом нажатии
                if (isOn) {
                    switchData.setPowered(!switchData.isPowered());
                    block.setBlockData(switchData);
                }
            } else {
                // Рычаг/Плита/Громоотвод → Рычаг: повторение состояния
                if (switchData.isPowered() != isOn) {
                    switchData.setPowered(isOn);
                    block.setBlockData(switchData);
                }
            }
            return;
        }

        // ===== НАЖИМНАЯ ПЛИТА =====
        if (type.contains("PRESSURE_PLATE") && block.getBlockData() instanceof org.bukkit.block.data.Powerable) {
            org.bukkit.block.data.Powerable powerable = (org.bukkit.block.data.Powerable) block.getBlockData();
            powerable.setPowered(isOn);
            block.setBlockData(powerable);
            return;
        }

        // ===== ГРОМООТВОД =====
        if (type.contains("LIGHTNING_ROD") && block.getBlockData() instanceof LightningRod) {
            LightningRod rodData = (LightningRod) block.getBlockData();
            rodData.setPowered(isOn);
            block.setBlockData(rodData);
            return;
        }

        // ===== РЕДСТОУН-ФАКЕЛ =====
        if ((type.equals("REDSTONE_TORCH") || type.equals("REDSTONE_WALL_TORCH"))
                && block.getBlockData() instanceof org.bukkit.block.data.Lightable) {
            org.bukkit.block.data.Lightable lightable = (org.bukkit.block.data.Lightable) block.getBlockData();
            boolean shouldBeLit = !isOn;
            if (lightable.isLit() != shouldBeLit) {
                lightable.setLit(shouldBeLit);
                block.setBlockData(lightable);
            }
            return;
        }
    }

    // Анти-флуд методы
    private boolean isFreqDisabled(String freq) {
        Long disabledUntil = disabledFreqs.get(freq);
        if (disabledUntil == null) return false;
        if (disabledUntil == 0) return true;
        long now = System.currentTimeMillis();
        if (now < disabledUntil) return true;
        disabledFreqs.remove(freq);
        return false;
    }

    private boolean checkAntiFlood(String freq, Block block) {
        boolean enabled = plugin.getConfig().getBoolean("transmission.anti-flood.enabled", false);
        if (!enabled) return true;

        int maxTriggers = plugin.getConfig().getInt("transmission.anti-flood.max-triggers", 10);
        int periodTicks = plugin.getConfig().getInt("transmission.anti-flood.period-ticks", 2);
        int disableTicks = plugin.getConfig().getInt("transmission.anti-flood.disable-ticks", 100);
        boolean notifyAdmins = plugin.getConfig().getBoolean("transmission.anti-flood.notify-admins", true);

        long now = System.currentTimeMillis();
        long periodMs = periodTicks * 50;

        Deque<Long> history = triggerHistory.computeIfAbsent(freq, k -> new ArrayDeque<>());
        history.addLast(now);

        while (!history.isEmpty() && history.peekFirst() < now - periodMs) {
            history.pollFirst();
        }

        if (history.size() > maxTriggers) {
            long disableUntil;
            String disableTimeStr;
            if (disableTicks > 0) {
                disableUntil = now + (disableTicks * 50);
                disableTimeStr = disableTicks + " тиков (" + (disableTicks / 20.0) + " сек)";
            } else {
                disableUntil = 0;
                disableTimeStr = "навсегда";
            }
            disabledFreqs.put(freq, disableUntil);
            history.clear();

            plugin.getLogger().warning("[QQR] Анти-флуд заблокировал частоту " + freq +
                "! " + block.getWorld().getName() + " " + block.getX() + " " + block.getY() + " " + block.getZ());

            if (notifyAdmins) {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (player.hasPermission("qqredstone.notification") || player.isOp()) {
                        player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                            "&c[QQR] &eАнти-флуд: частота &6" + freq + " &cзаблокирована на " + disableTimeStr));
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
