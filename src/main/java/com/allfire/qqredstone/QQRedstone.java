package com.allfire.qqredstone;

import com.allfire.qqredstone.commands.ReloadCommand;
import com.allfire.qqredstone.events.BookRename;
import com.allfire.qqredstone.events.MechanismBreak;
import com.allfire.qqredstone.events.PlayerInteract;
import com.allfire.qqredstone.events.RedstoneUpdate;
import com.allfire.qqredstone.integration.WorldGuardManager;
import com.allfire.qqredstone.integration.WorldGuardUtils;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public class QQRedstone extends JavaPlugin {

    private static QQRedstone instance;
    private FileConfiguration langConfig;
    private String senderName;
    private String receiverName;
    private WorldGuardManager worldGuardManager;
    private WorldGuardUtils worldGuardUtils;

    public static QQRedstone getInstance() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;

        if (!getDataFolder().exists())
            getDataFolder().mkdirs();

        saveDefaultConfig();

        File langFolder = new File(getDataFolder(), "lang");
        if (!langFolder.exists()) {
            langFolder.mkdirs();
            saveResource("lang/ru.yml", false);
            saveResource("lang/en.yml", false);
        }

        loadLanguage();

        // Инициализация WorldGuard
        worldGuardManager = new WorldGuardManager(this);
        worldGuardManager.initialize();
        worldGuardUtils = new WorldGuardUtils(this);

        PluginManager pluginManager = Bukkit.getPluginManager();
        pluginManager.registerEvents(new PlayerInteract(), this);
        pluginManager.registerEvents(new RedstoneUpdate(), this);
        pluginManager.registerEvents(new MechanismBreak(), this);
        pluginManager.registerEvents(new BookRename(), this);

        ReloadCommand reloadCommand = new ReloadCommand();
        reloadCommand.setRedstoneUpdate(new RedstoneUpdate());
        getCommand("qqredstone").setExecutor(reloadCommand);

        getLogger().info(ChatColor.stripColor(getMessage("plugin-enabled")));
    }

    @Override
    public void onDisable() {
        getLogger().info("QQRedstone disabled.");
    }

    public void loadLanguage() {
        String lang = getConfig().getString("language", "en");
        File langFile = new File(getDataFolder(), "lang/" + lang + ".yml");
        if (!langFile.exists()) {
            saveResource("lang/" + lang + ".yml", false);
        }
        langConfig = YamlConfiguration.loadConfiguration(langFile);
        senderName = langConfig.getString("device-names.sender", "Sender");
        receiverName = langConfig.getString("device-names.receiver", "Receiver");
    }

    public String getMessage(String path) {
        String msg = langConfig.getString("messages." + path, "");
        if (msg.isEmpty()) return "";
        return msg;
    }

    public String getMessage(String path, String... replacements) {
        String msg = getMessage(path);
        for (int i = 0; i < replacements.length; i += 2) {
            if (i + 1 < replacements.length) {
                msg = msg.replace(replacements[i], replacements[i + 1]);
            }
        }
        return msg;
    }

    public void sendMessage(Player player, String path, String... replacements) {
        String rawMsg = getMessage(path, replacements);
        if (rawMsg.isEmpty()) return;

        String msg = ChatColor.translateAlternateColorCodes('&', rawMsg);

        if (msg.startsWith("actionbar!")) {
            String text = msg.substring(10).trim();
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(text));
        } else if (msg.startsWith("title!")) {
            String text = msg.substring(6).trim();
            String[] parts = text.split("\\|");
            String title = parts[0].trim();
            String subtitle = (parts.length > 1) ? parts[1].trim() : "";
            player.sendTitle(title, subtitle, 10, 70, 20);
        } else if (msg.startsWith("subtitle!")) {
            String text = msg.substring(9).trim();
            player.sendTitle("", text, 10, 70, 20);
        } else if (msg.startsWith("title-sub!")) {
            String text = msg.substring(10).trim();
            String[] parts = text.split("\\|");
            String title = parts[0].trim();
            String subtitle = (parts.length > 1) ? parts[1].trim() : "";
            player.sendTitle(title, subtitle, 10, 70, 20);
        } else {
            player.sendMessage(msg);
        }
    }

    public void sendMessage(Player player, String path) {
        sendMessage(player, path, new String[0]);
    }

    public String getSenderName() { return senderName; }
    public String getReceiverName() { return receiverName; }
    public FileConfiguration getLangConfig() { return langConfig; }
    public WorldGuardManager getWorldGuardManager() { return worldGuardManager; }
    public WorldGuardUtils getWorldGuardUtils() { return worldGuardUtils; }
}
