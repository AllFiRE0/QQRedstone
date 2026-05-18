package com.allfire.qqredstone.events;

import com.allfire.qqredstone.QQRedstone;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.LightningRod;
import org.bukkit.block.data.type.Switch;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockRedstoneEvent;

import java.util.*;

public class RedstoneUpdate implements Listener {

    private final Map<Location, Integer> poweredSenders = new HashMap<>();
    private final Map<String, Long> lastTransmission = new HashMap<>();
    private final Map<String, Deque<Long>> triggerHistory = new HashMap<>();
    private final Map<String, Long> disabledFreqs = new HashMap<>();

    @EventHandler
    public void onRedstoneChange(BlockRedstoneEvent event) {
        Block block = event.getBlock();
        QQRedstone plugin = QQRedstone.getInstance();

        String locStr = getLocString(block);

        ConfigurationSection data = plugin.getConfig().getConfigurationSection("data");
        if (data == null) return;

        boolean isSender = false;
        String senderFreq = null;
        for (String freq : data.getKeys(false)) {
            if (plugin.getConfig().getStringList("data." + freq + ".senders").contains(locStr)) {
                isSender = true;
                senderFreq = freq;
                break;
            }
        }

        if (!isSender) return;

        // Проверяем, существует ли ещё механизм
        if (!validateMechanism(block, "sender")) {
            removeFromConfigSilent(locStr);
            return;
        }

        if (isFreqDisabled(senderFreq)) return;
        if (isOnCooldown(senderFreq)) return;
        if (!checkAntiFlood(senderFreq, block)) return;

        // Определяем мощность в зависимости от типа механизма
        int power = getMechanismPower(block);

        Location loc = block.getLocation();
        boolean isPoweredNow = (power > 0);

        if (isPoweredNow && !poweredSenders.containsKey(loc)) {
            poweredSenders.put(loc, power);
            processSignal(block, power, true, senderFreq);
        } else if (isPoweredNow && poweredSenders.containsKey(loc)) {
            int oldPower = poweredSenders.get(loc);
            if (oldPower != power) {
                poweredSenders.put(loc, power);
                processSignal(block, power, true, senderFreq);
            }
        } else if (!isPoweredNow && poweredSenders.containsKey(loc)) {
            poweredSenders.remove(loc);
            processSignal(block, 0, false, senderFreq);
        }
    }

    @EventHandler
    public void onBlockPhysics(BlockPhysicsEvent event) {
        Block block = event.getBlock();
        QQRedstone plugin = QQRedstone.getInstance();

        String locStr = getLocString(block);

        ConfigurationSection data = plugin.getConfig().getConfigurationSection("data");
        if (data == null) return;

        String senderFreq = null;
        for (String freq : data.getKeys(false)) {
            if (plugin.getConfig().getStringList("data." + freq + ".senders").contains(locStr)) {
                senderFreq = freq;
                break;
            }
        }

        if (senderFreq == null) return;

        // Проверяем, существует ли ещё механизм
        if (!validateMechanism(block, "sender")) {
            removeFromConfigSilent(locStr);
            return;
        }

        if (isFreqDisabled(senderFreq)) return;
        if (isOnCooldown(senderFreq)) return;
        if (!checkAntiFlood(senderFreq, block)) return;

        int power = getMechanismPower(block);
        Location loc = block.getLocation();
        boolean isPoweredNow = (power > 0);

        if (isPoweredNow && !poweredSenders.containsKey(loc)) {
            poweredSenders.put(loc, power);
            processSignal(block, power, true, senderFreq);
        } else if (isPoweredNow && poweredSenders.containsKey(loc)) {
            int oldPower = poweredSenders.get(loc);
            if (oldPower != power) {
                poweredSenders.put(loc, power);
                processSignal(block, power, true, senderFreq);
            }
        } else if (!isPoweredNow && poweredSenders.containsKey(loc)) {
            poweredSenders.remove(loc);
            processSignal(block, 0, false, senderFreq);
        }
    }

    /**
     * Определяет мощность механизма в зависимости от его типа
     */
    private int getMechanismPower(Block block) {
        String type = block.getType().name();

        // Рычаг — проверяем состояние Switch
        if (type.equals("LEVER")) {
            if (block.getBlockData() instanceof Switch) {
                return ((Switch) block.getBlockData()).isPowered() ? 15 : 0;
            }
        }

        // Кнопка — проверяем состояние Switch
        if (type.contains("BUTTON")) {
            if (block.getBlockData() instanceof Switch) {
                return ((Switch) block.getBlockData()).isPowered() ? 15 : 0;
            }
        }

        // Нажимная плита — проверяем через getBlockPower
        if (type.contains("PRESSURE_PLATE")) {
            return block.getBlockPower(BlockFace.UP) > 0 ? 15 : 0;
        }

        // Громоотвод / редстоун-факел — проверяем редстоун-сигнал
        return getMaxRedstonePower(block);
    }

    /**
     * Проверяет, существует ли механизм на месте
     */
    /**
     * Проверяет, существует ли механизм на месте
     * Принимает любой механизм, не только конкретный тип из конфига
     */
    private boolean validateMechanism(Block block, String role) {
        String type = block.getType().name();
        
        // AIR — точно сломан
        if (block.getType().isAir()) return false;

        // Любой механизм считается валидным (рычаг, кнопка, плита, громоотвод, факел)
        if (type.equals("LEVER")) return true;
        if (type.contains("BUTTON")) return true;
        if (type.contains("PRESSURE_PLATE")) return true;
        if (type.contains("LIGHTNING_ROD")) return true;
        if (type.equals("REDSTONE_TORCH") || type.equals("REDSTONE_WALL_TORCH")) return true;

        // Блок есть, но это не механизм (поставили землю вместо рычага)
        return false;
    }

    /**
     * Получает список разрешённых типов механизмов для роли
     */
    private List<String> getMechanismTypes(String role) {
        QQRedstone plugin = QQRedstone.getInstance();

        if (plugin.getConfig().getBoolean("mechanism.accept-all", false)) {
            return Arrays.asList("LEVER", "BUTTON", "PRESSURE_PLATE", "LIGHTNING_ROD", "REDSTONE_TORCH");
        }

        List<String> types = plugin.getConfig().getStringList("mechanism." + role);
        if (types == null || types.isEmpty()) {
            String single = plugin.getConfig().getString("mechanism." + role, "LIGHTNING_ROD");
            types = new ArrayList<>();
            types.add(single);
        }

        return types;
    }

    /**
     * Тихое удаление из конфига
     */
    private void removeFromConfigSilent(String locStr) {
        QQRedstone plugin = QQRedstone.getInstance();
        ConfigurationSection data = plugin.getConfig().getConfigurationSection("data");
        if (data == null) return;

        boolean changed = false;

        for (String freq : data.getKeys(false)) {
            List<String> senders = plugin.getConfig().getStringList("data." + freq + ".senders");
            List<String> receivers = plugin.getConfig().getStringList("data." + freq + ".receivers");

            if (senders.remove(locStr)) {
                plugin.getConfig().set("data." + freq + ".senders", senders);
                changed = true;
            }
            if (receivers.remove(locStr)) {
                plugin.getConfig().set("data." + freq + ".receivers", receivers);
                changed = true;
            }
        }

        String ownerKey = "ownership." + locStr.replace(",", ".");
        if (plugin.getConfig().contains(ownerKey)) {
            plugin.getConfig().set(ownerKey, null);
            changed = true;
        }

        String powerKey = "book-power." + locStr.replace(",", ".");
        if (plugin.getConfig().contains(powerKey)) {
            plugin.getConfig().set(powerKey, null);
            changed = true;
        }

        for (String freq : data.getKeys(false)) {
            List<String> senders = plugin.getConfig().getStringList("data." + freq + ".senders");
            List<String> receivers = plugin.getConfig().getStringList("data." + freq + ".receivers");
            if (senders.isEmpty() && receivers.isEmpty()) {
                plugin.getConfig().set("data." + freq, null);
                changed = true;
            }
        }

        if (changed) {
            plugin.saveConfig();
            plugin.getLogger().info("[QQR] Автоочистка: удалён несуществующий механизм " + locStr);
        }
    }

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
        QQRedstone plugin = QQRedstone.getInstance();
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

            int x = block.getX();
            int y = block.getY();
            int z = block.getZ();
            String world = block.getWorld().getName();

            plugin.getLogger().warning("[QQR] Анти-флуд заблокировал частоту " + freq +
                "! " + world + " " + x + " " + y + " " + z + " | На " + disableTimeStr);

            if (notifyAdmins) {
                String adminMsg = ChatColor.translateAlternateColorCodes('&',
                    "&c[QQR] &eОбнаружен быстрый механизм! &7(частота &6" + freq +
                    "&7, &b" + world + "&7, &a" + x + " " + y + " " + z +
                    "&7) &eЗаблокировано на &c" + disableTimeStr);

                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (player.hasPermission("qqredstone.notification") || player.isOp()) {
                        player.sendMessage(adminMsg);
                        player.spigot().sendMessage(ChatMessageType.ACTION_BAR,
                            TextComponent.fromLegacyText(ChatColor.translateAlternateColorCodes('&',
                                "&c⚡ Анти-флуд! Частота &6" + freq + " &cзаблокирована")));
                    }
                }
            }
            return false;
        }
        return true;
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

    private boolean isOnCooldown(String freq) {
        QQRedstone plugin = QQRedstone.getInstance();
        long cooldownTicks = plugin.getConfig().getLong("transmission.cooldown-ticks", 0);
        if (cooldownTicks <= 0) return false;

        long cooldownMs = cooldownTicks * 50;
        long now = System.currentTimeMillis();
        Long last = lastTransmission.get(freq);

        if (last != null && (now - last) < cooldownMs) return true;

        lastTransmission.put(freq, now);

        if (lastTransmission.size() > 100) {
            lastTransmission.entrySet().removeIf(entry -> (now - entry.getValue()) > 300000);
        }

        return false;
    }

    private int getMaxRedstonePower(Block block) {
        int maxPower = 0;
        for (BlockFace face : BlockFace.values()) {
            int power = block.getBlockPower(face);
            if (power > maxPower) maxPower = power;
        }
        return Math.min(maxPower, 15);
    }

    private void processSignal(Block block, int vanillaPower, boolean isOn, String freq) {
        QQRedstone plugin = QQRedstone.getInstance();
        String powerMode = plugin.getConfig().getString("transmission.power-mode", "vanilla");
        String locStr = getLocString(block);

        int finalPower = 0;
        int bookPower = plugin.getConfig().getInt("book-power." + locStr.replace(",", "."), 0);

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

        updateReceivers(freq, finalPower, isOn, block.getWorld().getName());
    }

    private void updateReceivers(String freq, int power, boolean isOn, String senderWorld) {
        QQRedstone plugin = QQRedstone.getInstance();
        List<String> receivers = plugin.getConfig().getStringList("data." + freq + ".receivers");
        if (receivers == null) return;

        boolean crossWorldEnabled = plugin.getConfig().getBoolean("transmission.allow-cross-world", true);

        for (String locStr : receivers) {
            String[] p = locStr.split(",");
            if (p.length < 4) continue;

            String receiverWorld = p[0];

            if (!senderWorld.equals(receiverWorld)) {
                if (!crossWorldEnabled) continue;

                String ownerKey = "ownership." + locStr.replace(",", ".");
                String ownerUuid = plugin.getConfig().getString(ownerKey);
                if (ownerUuid != null) {
                    org.bukkit.OfflinePlayer owner = Bukkit.getOfflinePlayer(java.util.UUID.fromString(ownerUuid));
                    if (owner.isOnline() && owner.getPlayer() != null) {
                        if (!owner.getPlayer().hasPermission("qqredstone.crossworld")
                                && !owner.getPlayer().hasPermission("qqredstone.admin.region.bypass")) {
                            continue;
                        }
                    }
                }
            }

            Location loc = new Location(Bukkit.getWorld(p[0]),
                Double.parseDouble(p[1]),
                Double.parseDouble(p[2]),
                Double.parseDouble(p[3]));
            Block block = loc.getBlock();

            // Проверяем получатель — если сломан, удаляем
            if (!validateMechanism(block, "receiver")) {
                removeFromConfigSilent(locStr);
                continue;
            }

            // Активируем получатель в зависимости от его типа
            activateReceiver(block, isOn);
        }
    }

    /**
     * Активирует/деактивирует получатель в зависимости от его типа
     * 
     * Логика:
     * - Рычаг: переключается (toggle) только при isOn=true (передний фронт)
     * - Кнопка: повторяет состояние отправителя (isOn)
     * - Плита: повторяет состояние отправителя (isOn)
     * - Громоотвод: повторяет состояние отправителя (isOn)
     * - Факел: переключается (toggle) только при isOn=true (передний фронт)
     */
    private void activateReceiver(Block block, boolean isOn) {
        String type = block.getType().name();

        // Рычаг — переключаем только при включении (передний фронт)
        if (type.equals("LEVER") && block.getBlockData() instanceof Switch) {
            if (isOn) { // Только при нажатии, не при отпускании
                Switch switchData = (Switch) block.getBlockData();
                boolean currentState = switchData.isPowered();
                switchData.setPowered(!currentState);
                block.setBlockData(switchData);
            }
            return;
        }

        // Кнопка — повторяет состояние
        if (type.contains("BUTTON") && block.getBlockData() instanceof Switch) {
            Switch switchData = (Switch) block.getBlockData();
            switchData.setPowered(isOn);
            block.setBlockData(switchData);
            return;
        }

        // Громоотвод — повторяет состояние
        if (type.contains("LIGHTNING_ROD") && block.getBlockData() instanceof LightningRod) {
            LightningRod rodData = (LightningRod) block.getBlockData();
            rodData.setPowered(isOn);
            block.setBlockData(rodData);
            return;
        }

        // Нажимная плита — повторяет состояние
        if (type.contains("PRESSURE_PLATE") && block.getBlockData() instanceof org.bukkit.block.data.Powerable) {
            org.bukkit.block.data.Powerable powerable = (org.bukkit.block.data.Powerable) block.getBlockData();
            powerable.setPowered(isOn);
            block.setBlockData(powerable);
            return;
        }

        // Редстоун-факел — переключаем только при включении (передний фронт)
        if ((type.equals("REDSTONE_TORCH") || type.equals("REDSTONE_WALL_TORCH"))
                && block.getBlockData() instanceof org.bukkit.block.data.Lightable) {
            if (isOn) { // Только при нажатии
                org.bukkit.block.data.Lightable lightable = (org.bukkit.block.data.Lightable) block.getBlockData();
                boolean currentLit = lightable.isLit();
                lightable.setLit(!currentLit);
                block.setBlockData(lightable);
            }
            return;
        }
    }

    private String getLocString(Block block) {
        return block.getWorld().getName() + ","
            + block.getX() + ","
            + block.getY() + ","
            + block.getZ();
    }
}
