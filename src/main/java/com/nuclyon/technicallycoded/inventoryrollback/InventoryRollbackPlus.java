package com.nuclyon.technicallycoded.inventoryrollback;

import com.nuclyon.technicallycoded.inventoryrollback.UpdateChecker.UpdateResult;
import com.nuclyon.technicallycoded.inventoryrollback.commands.Commands;
import com.nuclyon.technicallycoded.inventoryrollback.util.TimeZoneUtil;
import com.nuclyon.technicallycoded.inventoryrollback.util.test.SelfTestSerialization;
import com.tcoded.lightlibs.bukkitversion.BukkitVersion;
import io.papermc.lib.PaperLib;
import me.danjono.inventoryrollback.InventoryRollback;
import me.danjono.inventoryrollback.config.ConfigData;
import me.danjono.inventoryrollback.config.MessageData;
import me.danjono.inventoryrollback.data.LogType;
import me.danjono.inventoryrollback.inventory.SaveInventory;
import me.danjono.inventoryrollback.listeners.ClickGUI;
import me.danjono.inventoryrollback.listeners.EventLogs;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

public class InventoryRollbackPlus extends InventoryRollback {

    private static InventoryRollbackPlus instancePlus;

    private TimeZoneUtil timeZoneUtil = null;

    private ConfigData configData;
    private BukkitVersion version = BukkitVersion.v1_13_R1;
    private boolean configLoaded;
    private boolean startupComplete;
    private boolean serializationSelfTestsPassed;

    private AtomicBoolean shuttingDown = new AtomicBoolean(false);

    public static InventoryRollbackPlus getInstance() {
        return instancePlus;
    }

    @Override
    public void onEnable() {
        instancePlus = this;
        InventoryRollback.setInstance(instancePlus);

        // Resolve the runtime version before opening storage or accepting backups. Unknown
        // Minecraft versions must not silently use an older serializer/NMS branch.
        String serverVersion = this.getServer().getVersion();
        getLogger().info("Attempting support for version: " + serverVersion);
        BukkitVersion nmsVersion = ServerVersionSupport.resolve(serverVersion);
        if (nmsVersion == null) {
            getLogger().severe("Unsupported Minecraft version " + serverVersion
                    + "; disabling InventoryRollbackPlus before storage starts.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        setVersion(nmsVersion);
        InventoryRollback.setPackageVersion(nmsVersion.name());
        getLogger().info("Using CraftBukkit version: " + getPackageVersion());

        // Load Utils and Config after validating the runtime version.
        this.timeZoneUtil = new TimeZoneUtil();
        configData = new ConfigData();
        configData.setVariables(); // requires TimeZoneUtil
        configLoaded = true;

        // Storage Init & Update checker
        super.startupTasks();

        // bStats
        if (ConfigData.isbStatsEnabled()) initBStats();

        // Commands
        PluginCommand plCmd = getCommand("inventoryrollbackplus");
        Commands cmds = new Commands(this);
        if (plCmd == null) {
            getLogger().severe("InventoryRollbackPlus command is missing; disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        plCmd.setExecutor(cmds);
        plCmd.setTabCompleter(cmds);

        // Events
        getServer().getPluginManager().registerEvents(new ClickGUI(), this);
        getServer().getPluginManager().registerEvents(new EventLogs(), this);
        // Run after all plugin enable
        getServer().getScheduler().runTask(this, EventLogs::patchLowestHandlers);

        // PaperLib
        if (!PaperLib.isPaper()) {
            this.getLogger().info("----------------------------------------");
            this.getLogger().info("We recommend updating your server to use Paper :)");
            this.getLogger().info("Paper significantly reduces lag spikes among other benefits.");
            this.getLogger().info("Learn more at: https://papermc.io/");
            this.getLogger().info("----------------------------------------");
        }

        // A failed round trip must not leave a backup service enabled or write a shutdown
        // snapshot using an unverified codec.
        startupComplete = true;
        serializationSelfTestsPassed = SelfTestSerialization.runTests();
        if (!serializationSelfTestsPassed) {
            getLogger().severe("Inventory serialization self-tests failed; disabling InventoryRollbackPlus.");
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        // Signal to the plugin that new tasks cannot be scheduled
        getLogger().info("Setting shutdown state");
        shuttingDown.set(true);

        // Failed startup/serialization must not replace an older usable snapshot.
        if (startupComplete && serializationSelfTestsPassed) {
            getLogger().info("Saving player inventories...");
            for (Player player : this.getServer().getOnlinePlayers()) {
                if (player.hasPermission("inventoryrollbackplus.leavesave")) {
                    new SaveInventory(player, LogType.QUIT, null, null)
                            .snapshotAndSave(player.getInventory(), player.getEnderChest(), false);
                }
            }
            getLogger().info("Done saving player inventories!");
        }

        // Unregister event listeners
        HandlerList.unregisterAll(this);

        // Cancel tasks
        this.getServer().getScheduler().cancelTasks(this);

        // Clear instance references
        instancePlus = null;
        if (configLoaded) super.onDisable();
        else InventoryRollback.setInstance(null);

        getLogger().info("Plugin is disabled!");
    }

    public void setVersion(BukkitVersion versionName) {
        version = versionName;
    }

    public boolean isCompatibleCb(String cbVersion) {
        for (BukkitVersion v : BukkitVersion.values()) {
            if (v.name().equalsIgnoreCase(cbVersion)) {
                this.setVersion(v);
                return true;
            }
        }

        return false;
    }

    public void checkUpdate() {
        Bukkit.getScheduler().runTaskAsynchronously(InventoryRollback.getInstance(), () -> {
            InventoryRollbackPlus.getInstance().getConsoleSender().sendMessage(MessageData.getPluginPrefix() + "Checking for updates...");

            final UpdateResult result = new UpdateChecker(getInstance(), 85811).getResult();

            int prioLevel = 0;
            String prioColor = ChatColor.AQUA.toString();
            String prioLevelName = "null";

            switch (result.getType()) {
                case FAIL_SPIGOT:
                    getConsoleSender().sendMessage(MessageData.getPluginPrefix() + ChatColor.GOLD + "Warning: Could not contact Spigot to check if an update is available.");
                    break;
                case UPDATE_LOW:
                    prioLevel = 1;
                    prioLevelName = "minor";
                    break;
                case UPDATE_MEDIUM:
                    prioLevel = 2;
                    prioLevelName = "feature";
                    prioColor = ChatColor.GOLD.toString();
                    break;
                case UPDATE_HIGH:
                    prioLevel = 3;
                    prioLevelName = "MAJOR";
                    prioColor = ChatColor.RED.toString();
                    break;
                case DEV_BUILD:
                    getConsoleSender().sendMessage(MessageData.getPluginPrefix() + ChatColor.GOLD + "Warning: You are running an experimental/development build! Proceed with caution.");
                    break;
                case NO_UPDATE:
                    getConsoleSender().sendMessage(MessageData.getPluginPrefix() + ChatColor.RESET + "You are running the latest version.");
                    break;
                default:
                    break;
            }

            if (prioLevel > 0) {
                getConsoleSender().sendMessage( "\n" + prioColor +
                        "===============================================================================\n" +
                        "A " + prioLevelName + " update to InventoryRollbackPlus is available!\n" +
                        "Download at https://www.spigotmc.org/resources/inventoryrollbackplus-1-8-1-16-x.85811/\n" +
                        "(current: " + result.getCurrentVer() + ", latest: " + result.getLatestVer() + ")\n" +
                        "===============================================================================\n");
            }

        });
    }

    public void initBStats() {
        bStats();
    }

    @Override
    public void bStats() {
        Metrics metrics = new Metrics(this,  	9437);

        if (ConfigData.isbStatsEnabled())
            InventoryRollbackPlus.getInstance().getConsoleSender().sendMessage(MessageData.getPluginPrefix() + "bStats are enabled");

        metrics.addCustomChart(new SimplePie("database_type", () -> ConfigData.getSaveType().getName()));

        metrics.addCustomChart(new SimplePie("restore_to_player_enabled", () -> {
            if (ConfigData.isRestoreToPlayerButton()) {
                return "Enabled";
            } else {
                return "Disabled";
            }
        }));

        metrics.addCustomChart(new SimplePie("save_location", () -> {
            if (ConfigData.getFolderLocation() == InventoryRollback.getInstance().getDataFolder()) {
                return "Default";
            } else {
                return "Not Default";
            }
        }));

        metrics.addCustomChart(new SimplePie("storage_type", () -> {
            if (ConfigData.isMySQLEnabled()) {
                return "MySQL";
            } else {
                return "YAML";
            }
        }));

        metrics.addCustomChart(new SimplePie("time_zone", () -> {
            return ConfigData.getTimeZone().getID();
        }));

        metrics.addCustomChart(new SimplePie("allow_other_plugins_edit_death_inventory", () -> {
            return String.valueOf(ConfigData.isAllowOtherPluginEditDeathInventory());
        }));

        metrics.addCustomChart(new SimplePie("custom_online_mode", () -> {
            boolean vanillaOnline = this.getServer().getOnlineMode();
            boolean spigotProxyMode = false;

            boolean legacyPaperProxyEnabled = false;
            boolean legacyPaperProxyMode = false;

            boolean modernPaperProxyEnabled = false;
            boolean modernPaperProxyMode = false;

            File mainFolder = new File(System.getProperty("user.dir"));

            File spigotConfig = new File(mainFolder, "spigot.yml");
            if (spigotConfig.exists()) {
                YamlConfiguration config = YamlConfiguration.loadConfiguration(spigotConfig);
                spigotProxyMode = config.getBoolean("settings.bungeecord", false);
            }

            File legacyPaperConfig = new File(mainFolder, "paper.yml");
            if (legacyPaperConfig.exists()) {
                YamlConfiguration config = YamlConfiguration.loadConfiguration(legacyPaperConfig);
                legacyPaperProxyEnabled = config.getBoolean("settings.velocity-support.enabled", false);
                legacyPaperProxyMode = config.getBoolean("settings.velocity-support.online-mode", false);
            }

            File modernPaperConfig = new File(mainFolder, "config/paper-global.yml");
            if (modernPaperConfig.exists()) {
                YamlConfiguration config = YamlConfiguration.loadConfiguration(modernPaperConfig);
                modernPaperProxyEnabled = config.getBoolean("proxies.velocity.enabled", false);
                modernPaperProxyMode = config.getBoolean("proxies.velocity.online-mode", false);
            }

            if (modernPaperProxyEnabled) {
                if (modernPaperProxyMode) return "Modern Paper Proxy - Online";
                return "Modern Paper Proxy - Offline";
            }

            if (legacyPaperProxyEnabled) {
                if (legacyPaperProxyMode) return "Legacy Paper Proxy - Online";
                return "Legacy Paper Proxy - Offline";
            }

            if (spigotProxyMode) {
                if (vanillaOnline) return "Bungeecord - Online";
                return "Bungeecord - Offline";
            }

            if (vanillaOnline) return "Vanilla - Online";
            return "Vanilla - Offline";
        }));
    }

    // GETTERS

    public boolean isShuttingDown() {
        return shuttingDown.get();
    }

    public BukkitVersion getVersion() {
        return version;
    }

    public ConsoleCommandSender getConsoleSender() {
        return this.getServer().getConsoleSender();
    }

    public TimeZoneUtil getTimeZoneUtil() {
        return this.timeZoneUtil;
    }

    public ConfigData getConfigData() {
        return configData;
    }
}
