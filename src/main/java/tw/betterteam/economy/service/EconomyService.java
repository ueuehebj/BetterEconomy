package tw.betterteam.economy.service;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import tw.betterteam.economy.config.ConfigManager;
import tw.betterteam.economy.database.DatabaseManager;
import tw.betterteam.economy.database.Storage;
import tw.betterteam.economy.database.SyncQueueEntry;
import tw.betterteam.economy.model.PlayerBalance;
import tw.betterteam.economy.model.TransactionLog;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Core economy service managing player balances, caching, and transactions
 */
public class EconomyService {

    private final JavaPlugin plugin;
    private final DatabaseManager databaseManager;
    private final ConfigManager configManager;
    private final Storage storage;

    // Cache for player balances
    private final Map<UUID, BigDecimal> balanceCache = new ConcurrentHashMap<>();
    
    // Cache for top balances
    private List<PlayerBalance> topBalancesCache = new ArrayList<>();
    private long topBalancesCacheTime = 0;
    
    // Lock objects for per-player synchronization
    private final Map<UUID, Object> playerLocks = new ConcurrentHashMap<>();

    // Database save task
    private BukkitTask cacheSaveTask;

    public EconomyService(JavaPlugin plugin, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.configManager = ((tw.betterteam.economy.BetterEconomy) plugin).getConfigManager();
        this.storage = databaseManager.getStorage();

        // Start cache save task
        startCacheSaveTask();
        
        // Load existing balances into cache on startup
        loadBalancesIntoCache();
    }

    private void startCacheSaveTask() {
        cacheSaveTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
            plugin,
            () -> {
                // Clear old transaction logs
                int retentionDays = configManager.getHistoryRetentionDays();
                if (retentionDays > 0) {
                    storage.clearOldTransactionLogs(retentionDays);
                }
                // Clear old sync entries
                if (configManager.isCrossSyncEnabled()) {
                    storage.clearOldSyncEntries(24);
                }
            },
            20L * 60 * 5, // Run every 5 minutes
            20L * 60 * 5
        );
    }

    private void loadBalancesIntoCache() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<PlayerBalance> allBalances = storage.getAllPlayers();
            for (PlayerBalance balance : allBalances) {
                balanceCache.put(balance.getUuid(), balance.getBalance());
            }
            plugin.getLogger().info("Loaded " + allBalances.size() + " player balances into cache.");
        });
    }

    // ========== Balance Query Operations ==========

    /**
     * Get player balance from cache or database
     */
    public BigDecimal getBalance(UUID uuid) {
        BigDecimal cached = balanceCache.get(uuid);
        if (cached != null) {
            return cached.setScale(configManager.getDecimalPlaces(), RoundingMode.HALF_UP);
        }

        // Not in cache, check database
        Optional<PlayerBalance> playerBalance = storage.getBalance(uuid);
        if (playerBalance.isPresent()) {
            BigDecimal balance = playerBalance.get().getBalance();
            balanceCache.put(uuid, balance);
            return balance.setScale(configManager.getDecimalPlaces(), RoundingMode.HALF_UP);
        }

        // Player doesn't exist, create account if starting balance enabled
        if (configManager.isStartingBalanceEnabled()) {
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(uuid);
            createPlayerAccount(uuid, offlinePlayer.getName() != null ? offlinePlayer.getName() : uuid.toString());
            return BigDecimal.valueOf(configManager.getStartingBalance())
                    .setScale(configManager.getDecimalPlaces(), RoundingMode.HALF_UP);
        }

        return BigDecimal.ZERO;
    }

    /**
     * Check if player has enough balance
     */
    public boolean has(UUID uuid, double amount) {
        return getBalance(uuid).compareTo(BigDecimal.valueOf(amount)) >= 0;
    }

    /**
     * Get player balance as double
     */
    public double getBalanceDouble(UUID uuid) {
        return getBalance(uuid).doubleValue();
    }

    // ========== Balance Modification Operations ==========

    /**
     * Deposit money to player
     */
    public boolean deposit(UUID uuid, String username, double amount) {
        return deposit(uuid, username, BigDecimal.valueOf(amount));
    }

    public boolean deposit(UUID uuid, String username, BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }

        Object lock = getPlayerLock(uuid);
        synchronized (lock) {
            BigDecimal currentBalance = getBalance(uuid);
            BigDecimal newBalance = currentBalance.add(amount);

            // Check balance cap
            if (configManager.isBalanceCapEnabled() && !hasBalanceCap(uuid)) {
                BigDecimal cap = BigDecimal.valueOf(configManager.getBalanceCap());
                if (newBalance.compareTo(cap) > 0) {
                    newBalance = cap;
                }
            }

            // Update cache
            balanceCache.put(uuid, newBalance);

            // Update database asynchronously
            final BigDecimal finalBalance = newBalance;
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                storage.addBalance(uuid, username, amount, getServerId());
                logTransaction(uuid, TransactionLog.TransactionType.GIVE, amount, finalBalance, "plugin-api", "API deposit");
            });

            return true;
        }
    }

    /**
     * Withdraw money from player
     */
    public boolean withdraw(UUID uuid, String username, double amount) {
        return withdraw(uuid, username, BigDecimal.valueOf(amount));
    }

    public boolean withdraw(UUID uuid, String username, BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }

        Object lock = getPlayerLock(uuid);
        synchronized (lock) {
            BigDecimal currentBalance = getBalance(uuid);

            // Check if balance is sufficient
            if (!configManager.isNegativeBalanceAllowed() && 
                currentBalance.compareTo(amount) < 0) {
                return false;
            }

            BigDecimal newBalance = currentBalance.subtract(amount);

            // Update cache
            balanceCache.put(uuid, newBalance);

            // Update database asynchronously
            final BigDecimal finalBalance = newBalance;
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                storage.subtractBalance(uuid, username, amount, getServerId());
                logTransaction(uuid, TransactionLog.TransactionType.TAKE, amount.negate(), finalBalance, "plugin-api", "API withdrawal");
            });

            return true;
        }
    }

    /**
     * Set player balance directly
     */
    public boolean setBalance(UUID uuid, String username, BigDecimal balance) {
        if (balance.compareTo(BigDecimal.ZERO) < 0 && !configManager.isNegativeBalanceAllowed()) {
            return false;
        }

        Object lock = getPlayerLock(uuid);
        synchronized (lock) {
            balanceCache.put(uuid, balance);

            final BigDecimal finalBalance = balance;
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                storage.setBalance(uuid, username, finalBalance, getServerId());
                logTransaction(uuid, TransactionLog.TransactionType.SET, finalBalance, finalBalance, "admin", "Admin set balance");
            });

            return true;
        }
    }

    // ========== Player Account Management ==========

    /**
     * Create a new player account with starting balance
     */
    public void createPlayerAccount(UUID uuid, String username) {
        if (storage.playerExists(uuid)) {
            return; // Account already exists
        }

        final BigDecimal startingBalance;
        if (configManager.isStartingBalanceEnabled()) {
            startingBalance = BigDecimal.valueOf(configManager.getStartingBalance());
        } else {
            startingBalance = BigDecimal.ZERO;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (storage.createPlayerAccount(uuid, username, startingBalance, getServerId())) {
                balanceCache.put(uuid, startingBalance);
                if (!startingBalance.equals(BigDecimal.ZERO)) {
                    logTransaction(uuid, TransactionLog.TransactionType.STARTING_BALANCE, startingBalance, 
                                 startingBalance, "system", "Starting balance");
                }
            }
        });
    }

    /**
     * Check if player account exists
     */
    public boolean playerExists(UUID uuid) {
        return balanceCache.containsKey(uuid) || storage.playerExists(uuid);
    }

    // ========== Top Balances & Rankings ==========

    /**
     * Get top balances with caching
     */
    public List<PlayerBalance> getTopBalances(int limit) {
        long currentTime = System.currentTimeMillis();
        int cacheSeconds = configManager.getBaltopCacheSeconds();
        long cacheExpiry = topBalancesCacheTime + (cacheSeconds * 1000L);

        if (currentTime < cacheExpiry && !topBalancesCache.isEmpty()) {
            return topBalancesCache;
        }

        // Refresh cache
        topBalancesCache = storage.getTopBalances(limit);
        topBalancesCacheTime = currentTime;
        return topBalancesCache;
    }

    /**
     * Get player rank
     */
    public int getPlayerRank(UUID uuid) {
        return storage.getPlayerRank(uuid);
    }

    /**
     * Get total balance across all players
     */
    public BigDecimal getTotalBalance() {
        return storage.getTotalBalance();
    }

    /**
     * Get total player count
     */
    public int getTotalPlayerCount() {
        return storage.getTotalPlayerCount();
    }

    // ========== Transaction History ==========

    /**
     * Get transaction history with pagination
     */
    public List<TransactionLog> getTransactionHistory(UUID uuid, int page, int pageSize) {
        return storage.getTransactionHistory(uuid, page, pageSize);
    }

    /**
     * Get transaction count for a player
     */
    public int getTransactionCount(UUID uuid) {
        return storage.getTransactionCount(uuid);
    }

    /**
     * Log a transaction
     */
    private void logTransaction(UUID uuid, TransactionLog.TransactionType type, BigDecimal amount,
                               BigDecimal newBalance, String operator, String reason) {
        TransactionLog log = new TransactionLog(uuid, type, amount, newBalance, operator, getServerId(), reason);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> storage.logTransaction(log));
    }

    // ========== Wipe Operations ==========

    /**
     * Wipe all balances and reset to starting balance
     */
    public boolean wipeAllBalances() {
        BigDecimal startingBalance = BigDecimal.valueOf(configManager.getStartingBalance());
        
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (storage.wipeAllBalances(startingBalance, getServerId())) {
                // Update cache
                balanceCache.clear();
                balanceCache.putAll(
                    storage.getAllPlayers().stream()
                           .collect(java.util.stream.Collectors.toMap(
                               PlayerBalance::getUuid,
                               PlayerBalance::getBalance
                           ))
                );
            }
        });
        
        return true;
    }

    /**
     * Wipe all transaction logs
     */
    public boolean wipeAllTransactionLogs() {
        return storage.wipeAllTransactionLogs();
    }

    // ========== Cross-Server Sync ==========

    /**
     * Sync with other servers (called periodically)
     */
    public void syncCrossServer() {
        if (!configManager.isCrossSyncEnabled()) {
            return;
        }

        String thisServerId = getServerId();
        List<SyncQueueEntry> unprocessedEntries = storage.getUnprocessedSyncEntries(thisServerId);

        for (SyncQueueEntry entry : unprocessedEntries) {
            UUID uuid = entry.getUuid();
            BigDecimal delta = entry.getDelta();

            Object lock = getPlayerLock(uuid);
            synchronized (lock) {
                BigDecimal currentBalance = getBalance(uuid);
                BigDecimal newBalance = currentBalance.add(delta);
                balanceCache.put(uuid, newBalance);

                // Update database
                storage.setBalance(uuid, "", newBalance, thisServerId);
            }

            // Mark as processed
            storage.markSyncEntryProcessed(entry.getId());
        }
    }

    /**
     * Add entry to sync queue for other servers
     */
    public void addToSyncQueue(UUID uuid, BigDecimal delta) {
        if (!configManager.isCrossSyncEnabled()) {
            return;
        }

        storage.addSyncQueueEntry(uuid, delta, getServerId());
    }

    // ========== Helper Methods ==========

    /**
     * Get per-player lock object
     */
    private Object getPlayerLock(UUID uuid) {
        return playerLocks.computeIfAbsent(uuid, k -> new Object());
    }

    /**
     * Get this server's ID
     */
    private String getServerId() {
        return configManager.getServerId();
    }

    /**
     * Check if player has balance cap exemption
     */
    private boolean hasBalanceCap(UUID uuid) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(uuid);
        return player.isOnline() && player.getPlayer() != null && 
               player.getPlayer().hasPermission("economy.exempt.cap");
    }

    /**
     * Shutdown service
     */
    public void shutdown() {
        if (cacheSaveTask != null) {
            cacheSaveTask.cancel();
        }
    }
}
