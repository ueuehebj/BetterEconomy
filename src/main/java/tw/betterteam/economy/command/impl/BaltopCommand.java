package tw.betterteam.economy.command.impl;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import tw.betterteam.economy.command.EconomyCommand;
import tw.betterteam.economy.config.ConfigManager;
import tw.betterteam.economy.model.PlayerBalance;
import tw.betterteam.economy.service.EconomyService;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * /baltop command implementation
 */
public class BaltopCommand extends EconomyCommand {

    private final EconomyService economyService;
    private final ConfigManager configManager;

    public BaltopCommand(JavaPlugin plugin, EconomyService economyService, ConfigManager configManager) {
        super(plugin);
        this.economyService = economyService;
        this.configManager = configManager;
    }

    @Override
    public void register() {
        plugin.getCommand("baltop").setExecutor(this);
        plugin.getCommand("baltop").setTabCompleter(this);
    }

    @Override
    protected String getCommandName() {
        return "baltop";
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("economy.baltop")) {
            sender.sendMessage(configManager.getMessage("general.no-permission"));
            return true;
        }

        int page = 1;
        int entriesPerPage = configManager.getBaltopEntriesPerPage();

        if (args.length > 0) {
            try {
                page = Integer.parseInt(args[0]);
                if (page < 1) {
                    page = 1;
                }
            } catch (NumberFormatException e) {
                sender.sendMessage(configManager.getMessage("baltop.invalid-page"));
                return true;
            }
        }

        // Get all top balances (fetch more than needed to handle pagination)
        List<PlayerBalance> topBalances = economyService.getTopBalances(page * entriesPerPage);

        if (topBalances.isEmpty()) {
            sender.sendMessage(configManager.getMessage("baltop.empty"));
            return true;
        }

        // Calculate total pages
        int totalPlayers = economyService.getTotalPlayerCount();
        int maxPage = (totalPlayers + entriesPerPage - 1) / entriesPerPage;

        if (page > maxPage) {
            page = maxPage;
        }

        // Get entries for this page
        int startIndex = (page - 1) * entriesPerPage;
        int endIndex = Math.min(startIndex + entriesPerPage, topBalances.size());

        if (startIndex >= topBalances.size()) {
            sender.sendMessage(configManager.getMessage("baltop.invalid-page"));
            return true;
        }

        // Display header
        sender.sendMessage(configManager.getMessage("baltop.header",
                "{page}", String.valueOf(page),
                "{max_page}", String.valueOf(maxPage)));

        // Display entries
        for (int i = startIndex; i < endIndex; i++) {
            PlayerBalance balance = topBalances.get(i);
            int rank = i + 1;
            
            sender.sendMessage(configManager.getMessage("baltop.entry",
                    "{rank}", String.valueOf(rank),
                    "{player}", balance.getUsername(),
                    "{balance}", formatBalance(balance.getBalance())));
        }

        // Display footer
        sender.sendMessage(configManager.getMessage("baltop.footer"));

        return true;
    }

    private String formatBalance(BigDecimal balance) {
        if (configManager.isAbbreviateEnabled()) {
            return abbreviateNumber(balance.doubleValue());
        }

        String formatted = balance.toPlainString();
        if (configManager.getSymbolPosition().equalsIgnoreCase("PREFIX")) {
            return getCurrencySymbol() + formatted;
        } else {
            return formatted + getCurrencySymbol();
        }
    }

    private String abbreviateNumber(double number) {
        if (number >= 1_000_000) {
            return String.format("%.1fM", number / 1_000_000);
        } else if (number >= 1_000) {
            return String.format("%.1fK", number / 1_000);
        }
        return String.format("%.0f", number);
    }

    private String getCurrencySymbol() {
        String symbolType = configManager.getCurrencySymbol();

        return switch (symbolType.toUpperCase()) {
            case "DOLLAR" -> "$";
            case "EURO" -> "€";
            case "POUND" -> "£";
            case "YEN" -> "¥";
            case "WON" -> "₩";
            case "RUBLE" -> "₽";
            case "CENT" -> "¢";
            case "CUSTOM" -> configManager.getCustomSymbol();
            default -> "$";
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            // Suggest page numbers
            int totalPlayers = economyService.getTotalPlayerCount();
            int entriesPerPage = configManager.getBaltopEntriesPerPage();
            int maxPage = (totalPlayers + entriesPerPage - 1) / entriesPerPage;

            for (int i = 1; i <= maxPage && i <= 10; i++) {
                completions.add(String.valueOf(i));
            }
        }

        return completions;
    }
}
