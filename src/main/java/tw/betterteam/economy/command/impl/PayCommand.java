package tw.betterteam.economy.command.impl;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import tw.betterteam.economy.command.EconomyCommand;
import tw.betterteam.economy.config.ConfigManager;
import tw.betterteam.economy.model.TransactionLog;
import tw.betterteam.economy.service.EconomyService;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * /pay command implementation
 */
public class PayCommand extends EconomyCommand {

    private final EconomyService economyService;
    private final ConfigManager configManager;

    public PayCommand(JavaPlugin plugin, EconomyService economyService, ConfigManager configManager) {
        super(plugin);
        this.economyService = economyService;
        this.configManager = configManager;
    }

    @Override
    public void register() {
        plugin.getCommand("pay").setExecutor(this);
        plugin.getCommand("pay").setTabCompleter(this);
    }

    @Override
    protected String getCommandName() {
        return "pay";
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("economy.pay")) {
            sender.sendMessage(configManager.getMessage("general.no-permission"));
            return true;
        }

        if (!(sender instanceof Player)) {
            sender.sendMessage(configManager.getMessage("general.player-not-found", "{player}", "Console"));
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage("§cUsage: /pay <player> <amount>");
            return true;
        }

        Player senderPlayer = (Player) sender;
        UUID senderUUID = senderPlayer.getUniqueId();
        String targetName = args[0];
        
        try {
            double amount = Double.parseDouble(args[1]);

            // Validation
            if (amount < 0) {
                sender.sendMessage(configManager.getMessage("pay.negative-amount"));
                return true;
            }

            if (amount == 0) {
                sender.sendMessage(configManager.getMessage("general.invalid-amount"));
                return true;
            }

            double minimumAmount = configManager.getMinimumPayAmount();
            if (amount < minimumAmount && !sender.hasPermission("economy.pay.bypass.limit")) {
                sender.sendMessage(configManager.getMessage("pay.below-minimum",
                        "{amount}", String.valueOf(minimumAmount)));
                return true;
            }

            // Check self-pay
            if (targetName.equalsIgnoreCase(senderPlayer.getName())) {
                if (!configManager.isAllowSelfPay()) {
                    sender.sendMessage(configManager.getMessage("pay.cannot-pay-self"));
                    return true;
                }
            }

            // Get target player
            OfflinePlayer targetPlayer = Bukkit.getOfflinePlayer(targetName);
            UUID targetUUID = targetPlayer.getUniqueId();

            // Check if target exists
            if (!economyService.playerExists(targetUUID) && !targetPlayer.hasPlayedBefore()) {
                if (!configManager.isAllowOfflineTarget()) {
                    sender.sendMessage(configManager.getMessage("pay.target-not-found"));
                    return true;
                }
            }

            // Create account for target if needed
            if (!economyService.playerExists(targetUUID)) {
                economyService.createPlayerAccount(targetUUID, targetName);
            }

            // Check sender balance
            if (!economyService.has(senderUUID, amount)) {
                sender.sendMessage(configManager.getMessage("pay.insufficient-balance"));
                return true;
            }

            // Check balance cap for target
            BigDecimal targetBalance = economyService.getBalance(targetUUID);
            BigDecimal newTargetBalance = targetBalance.add(BigDecimal.valueOf(amount));

            if (configManager.isBalanceCapEnabled()) {
                OfflinePlayer target = Bukkit.getOfflinePlayer(targetUUID);
                if ((target.isOnline() && !target.getPlayer().hasPermission("economy.exempt.cap")) ||
                    (!target.isOnline())) {
                    BigDecimal cap = BigDecimal.valueOf(configManager.getBalanceCap());
                    if (newTargetBalance.compareTo(cap) > 0) {
                        sender.sendMessage(configManager.getMessage("pay.cap-exceeded"));
                        return true;
                    }
                }
            }

            // Perform transaction
            if (economyService.withdraw(senderUUID, senderPlayer.getName(), amount)) {
                if (economyService.deposit(targetUUID, targetName, amount)) {
                    // Log transaction for sender
                    logTransaction(senderUUID, senderPlayer.getName(),
                            TransactionLog.TransactionType.PAY_SEND, -amount, targetName);

                    // Log transaction for target
                    logTransaction(targetUUID, targetName,
                            TransactionLog.TransactionType.PAY_RECEIVE, amount, senderPlayer.getName());

                    // Notify sender
                    sender.sendMessage(configManager.getMessage("pay.success-sender",
                            "{player}", targetName,
                            "{amount}", String.format("%.2f", amount)));

                    // Notify target if online
                    Player targetOnline = Bukkit.getPlayer(targetUUID);
                    if (targetOnline != null) {
                        targetOnline.sendMessage(configManager.getMessage("pay.success-receiver",
                                "{player}", senderPlayer.getName(),
                                "{amount}", String.format("%.2f", amount)));
                    }
                } else {
                    // Refund sender if deposit failed
                    economyService.deposit(senderUUID, senderPlayer.getName(), amount);
                    sender.sendMessage(configManager.getMessage("general.error-occurred",
                            "{error}", "Failed to complete transaction"));
                }
            }

        } catch (NumberFormatException e) {
            sender.sendMessage(configManager.getMessage("general.invalid-amount"));
        }

        return true;
    }

    private void logTransaction(UUID uuid, String username, TransactionLog.TransactionType type,
                               double amount, String otherPlayer) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            BigDecimal newBalance = economyService.getBalance(uuid);
            TransactionLog log = new TransactionLog(
                    uuid,
                    type,
                    BigDecimal.valueOf(amount),
                    newBalance,
                    uuid.toString(),
                    configManager.getServerId(),
                    "转账与 " + otherPlayer
            );
            // Log would be recorded by EconomyService
        });
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            for (OfflinePlayer player : Bukkit.getOfflinePlayers()) {
                String name = player.getName();
                if (name != null && !name.equals(sender.getName()) && 
                    name.toLowerCase().startsWith(prefix)) {
                    completions.add(name);
                }
            }
        } else if (args.length == 2) {
            completions.add("100");
            completions.add("500");
            completions.add("1000");
        }

        return completions;
    }
}
