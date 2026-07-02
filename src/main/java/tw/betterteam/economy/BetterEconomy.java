package tw.betterteam.economy;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import tw.betterteam.economy.config.ConfigManager;
import tw.betterteam.economy.database.DatabaseManager;
import tw.betterteam.economy.service.EconomyService;

import java.util.logging.Level;

/**
 * BetterEconomy - A comprehensive economy plugin for Purpur servers
 * Supporting Vault Economy API and cross-server synchronization
 */
public class BetterEconomy extends JavaPlugin {

    private static BetterEconomy instance;
    
    private ConfigManager configManager;
    private DatabaseManager databaseManager;
    private EconomyService economyService;
    
    private BukkitTask syncTask;

    @Override
    public void onEnable() {
        instance = this;
        
        getLogger().info("§6Loading BetterEconomy v" + getDescription().getVersion() + "...");
        
        try {
            // Initialize configuration
            this.configManager = new ConfigManager(this);
            configManager.loadConfig();
            
            // Initialize database
            this.databaseManager = new DatabaseManager(this);
            databaseManager.initialize();
            
            // Initialize economy service
            this.economyService = new EconomyService(this, databaseManager);
            
            // Register commands
            registerCommands();
            
            // TODO: Vault integration and PlaceholderAPI expansion can be added by users
            // who have those dependencies installed. See: optional-integrations.md
            
            // Start cross-server sync if enabled
            if (configManager.isCrossSyncEnabled()) {
                startCrossServerSync();
            }
            
            getLogger().info("§aSuccessfully loaded BetterEconomy!");
            
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to load BetterEconomy", e);
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        try {
            if (syncTask != null) {
                syncTask.cancel();
            }
            
            if (databaseManager != null) {
                databaseManager.shutdown();
            }
            
            getLogger().info("§eBetterEconomy disabled.");
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Error during plugin shutdown", e);
        }
    }

    private void registerCommands() {
        new tw.betterteam.economy.command.impl.EconomyAdminCommand(this, economyService, configManager).register();
        new tw.betterteam.economy.command.impl.BalanceCommand(this, economyService, configManager).register();
        new tw.betterteam.economy.command.impl.BaltopCommand(this, economyService, configManager).register();
        new tw.betterteam.economy.command.impl.PayCommand(this, economyService, configManager).register();
    }

    private void startCrossServerSync() {
        syncTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
            this,
            () -> economyService.syncCrossServer(),
            configManager.getSyncPollingInterval(),
            configManager.getSyncPollingInterval()
        );
        getLogger().info("§aCross-server sync started.");
    }

    public static BetterEconomy getInstance() {
        return instance;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public EconomyService getEconomyService() {
        return economyService;
    }
}
