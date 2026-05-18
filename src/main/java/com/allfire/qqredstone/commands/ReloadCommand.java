package com.allfire.qqredstone.commands;

import com.allfire.qqredstone.QQRedstone;
import com.allfire.qqredstone.database.DatabaseManager;
import com.allfire.qqredstone.events.RedstoneUpdate;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

public class ReloadCommand implements CommandExecutor {

    private final QQRedstone plugin;
    private final DatabaseManager databaseManager;
    private RedstoneUpdate redstoneUpdate;

    public ReloadCommand(QQRedstone plugin, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
    }

    public void setRedstoneUpdate(RedstoneUpdate redstoneUpdate) {
        this.redstoneUpdate = redstoneUpdate;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("qqredstone.admin.reload")) {
            sender.sendMessage(ChatColor.RED + "[QQR] У вас нет прав!");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(ChatColor.YELLOW + "[QQR] Использование:");
            sender.sendMessage(ChatColor.YELLOW + "  /qqredstone reload" + ChatColor.GRAY + " - перезагрузить конфиг и БД");
            sender.sendMessage(ChatColor.YELLOW + "  /qqredstone resetflood [частота]" + ChatColor.GRAY + " - сбросить блокировку частоты");
            sender.sendMessage(ChatColor.YELLOW + "  /qqredstone resetallfloods" + ChatColor.GRAY + " - сбросить все блокировки");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload":
                plugin.reloadConfig();
                plugin.loadLanguage();
                databaseManager.reload();  // Перезагружаем кеш из БД
                sender.sendMessage(ChatColor.GREEN + "[QQR] Конфиг и БД перезагружены!");
                break;

            case "resetflood":
                if (redstoneUpdate == null) {
                    sender.sendMessage(ChatColor.RED + "[QQR] Ошибка: RedstoneUpdate не инициализирован.");
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.RED + "[QQR] Укажите частоту: /qqredstone resetflood <частота>");
                    return true;
                }
                redstoneUpdate.resetFreq(args[1]);
                sender.sendMessage(ChatColor.GREEN + "[QQR] Блокировка частоты " + args[1] + " сброшена.");
                break;

            case "resetallfloods":
                if (redstoneUpdate == null) {
                    sender.sendMessage(ChatColor.RED + "[QQR] Ошибка: RedstoneUpdate не инициализирован.");
                    return true;
                }
                redstoneUpdate.resetAllFreqs();
                sender.sendMessage(ChatColor.GREEN + "[QQR] Все блокировки частот сброшены.");
                break;

            default:
                sender.sendMessage(ChatColor.RED + "[QQR] Неизвестная команда. Используйте /qqredstone");
                break;
        }

        return true;
    }
}
