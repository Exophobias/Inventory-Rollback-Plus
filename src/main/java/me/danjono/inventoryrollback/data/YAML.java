package me.danjono.inventoryrollback.data;

import com.nuclyon.technicallycoded.inventoryrollback.InventoryRollbackPlus;
import me.danjono.inventoryrollback.config.ConfigData;
import me.danjono.inventoryrollback.config.MessageData;
import me.danjono.inventoryrollback.gui.InventoryName;
import me.danjono.inventoryrollback.inventory.RestoreInventory;
import me.danjono.inventoryrollback.inventory.SaveInventory;
import org.bukkit.ChatColor;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class YAML {

    private final UUID uuid;
    private final Long timestamp;
    private final File playerBackupFolder;
    private final File backupFile;
    private final YamlConfiguration data;

    private String mainInventory;
    private String armour;
    private String enderChest;
    private float xp;
    private double health;
    private int hunger;
    private float saturation;
    private String world;
    private double x;
    private double y;
    private double z;
    private LogType logType;
    private String packageVersion;
    private String deathReason;

    private static final String backupFolderName = "backups";

    /** A backup file is a millisecond timestamp and nothing else. Temp files and strays are not backups. */
    private static final Pattern BACKUP_FILE_NAME = Pattern.compile("^(\\d+)\\.yml$");

    /** How long a freshly written backup is ignored for, so we never read or purge a file mid-write. */
    private static final long WRITE_SETTLE_MS = 1000;

    /** Suffix for the scratch file an atomic save is staged through. */
    private static final String TEMP_SUFFIX = ".tmp";

    public YAML(UUID uuid, LogType logType, Long timestampIn) {
        this.uuid = uuid;
        this.logType = logType;
        this.timestamp = timestampIn;
        this.playerBackupFolder = getPlayerBackupLocation(logType, uuid);
        this.backupFile = new File (playerBackupFolder, timestamp + ".yml");
        this.data = YamlConfiguration.loadConfiguration(backupFile);
    }

    public static void createStorageFolders() {        
        //Create folder for where player inventories will be saved
        File savesFolder = new File(ConfigData.getFolderLocation().getAbsoluteFile(), backupFolderName);
        if(!savesFolder.exists())
            savesFolder.mkdir();

        //Create folder for joins
        File joinsFolder = new File(savesFolder, "joins");
        if(!joinsFolder.exists())
            joinsFolder.mkdir();

        //Create folder for quits
        File quitsFolder = new File(savesFolder, "quits");
        if(!quitsFolder.exists())
            quitsFolder.mkdir();

        //Create folder for deaths
        File deathsFolder = new File(savesFolder, "deaths");
        if(!deathsFolder.exists())
            deathsFolder.mkdir();

        //Create folder for world changes
        File worldChangesFolder = new File(savesFolder, "worldChanges");
        if(!worldChangesFolder.exists())
            worldChangesFolder.mkdir();

        //Create folder for force saves
        File forceSavesFolder = new File(savesFolder, "force");
        if(!forceSavesFolder.exists())
            forceSavesFolder.mkdir();
    }

    private static File getRootBackupsFolder() {
        return new File(ConfigData.getFolderLocation(), backupFolderName);
    }

    private static File getBackupFolderForLogType(LogType backupLogType) {
        File backupLocation = getRootBackupsFolder();

        if (backupLogType == LogType.JOIN) {
            backupLocation = new File(backupLocation, "joins");
        } else if (backupLogType == LogType.QUIT) {
            backupLocation = new File(backupLocation, "quits");
        } else if (backupLogType == LogType.DEATH) {
            backupLocation = new File(backupLocation, "deaths");
        } else if (backupLogType == LogType.WORLD_CHANGE) {
            backupLocation = new File(backupLocation, "worldChanges");
        } else if (backupLogType == LogType.FORCE) {
            backupLocation = new File(backupLocation, "force");
        }

        return backupLocation;
    }

    private static File getPlayerBackupLocation(LogType backupLogType, UUID playerUUID) {
        return new File(getBackupFolderForLogType(backupLogType), playerUUID.toString());
    }

    public boolean doesBackupTypeExist() {
        return getAmountOfBackups() > 0;
    }

    /**
     * Every readable backup in this player's folder, newest first.
     * <p>
     * This is the single definition of "a backup exists" - the count, the menu pages and the purge
     * all run off it. When they each filtered the folder their own way the count could disagree
     * with the list, which showed up as blank menu slots and off-by-one page counts.
     */
    private List<Long> listBackupTimestamps() {
        List<Long> timestamps = new ArrayList<>();

        File[] backupFiles = playerBackupFolder.listFiles();
        if (backupFiles == null) return timestamps;

        long currTime = System.currentTimeMillis();

        for (File file : backupFiles) {
            if (file.isDirectory())
                continue;

            Matcher matcher = BACKUP_FILE_NAME.matcher(file.getName());
            if (!matcher.matches())
                continue;

            long timestamp;
            try {
                timestamp = Long.parseLong(matcher.group(1));
            } catch (NumberFormatException ex) {
                // Timestamp too large to be one of ours
                continue;
            }

            // Make sure that the file hasn't been created in the last 1s: we could still be writing to it
            if (currTime - timestamp <= WRITE_SETTLE_MS)
                continue;

            timestamps.add(timestamp);
        }

        //Set timestamps in order
        Collections.sort(timestamps, Collections.reverseOrder());

        return timestamps;
    }

    public int getAmountOfBackups() {
        return listBackupTimestamps().size();
    }

    public List<Long> getSelectedPageTimestamps(int pageNumber) {
        List<Long> allTimeStamps = listBackupTimestamps();

        //Number of backups that will be on the page
        int backups = InventoryName.ROLLBACK_LIST.getSize() - 9;

        //Return all timestamps if list is the same size or less than the page max size
        if (allTimeStamps.size() <= backups)
            return allTimeStamps;

        List<Long> requiredTimestamps = new ArrayList<>();
        for (int i = (backups * (pageNumber - 1)); i < ((backups * (pageNumber - 1)) + backups); i++) {            
            if (i < allTimeStamps.size()) {
                requiredTimestamps.add(allTimeStamps.get(i));
            } else {
                break;
            }
        }

        return requiredTimestamps;
    }

    public void purgeExcessSaves(int deleteAmount) {
        List<Long> timeSaved = listBackupTimestamps();

        // Newest first, so the oldest saves - the ones we drop - are at the tail
        for (int i = 0; i < deleteAmount; i++) {
            if (timeSaved.isEmpty()) break;

            Long deleteTimestamp = timeSaved.remove(timeSaved.size() - 1);
            File expiredBackup = new File(playerBackupFolder, deleteTimestamp + ".yml");

            try {
                Files.deleteIfExists(expiredBackup.toPath());
            } catch (IOException ex) {
                InventoryRollbackPlus.getInstance().getLogger().log(Level.WARNING,
                        "Could not delete expired backup " + expiredBackup.getAbsolutePath(), ex);
            }
        }
    }

    public void setMainInventory(ItemStack[] items) {
        this.mainInventory = SaveInventory.toBase64(items);
    }

    public void setArmour(ItemStack[] items) {
        this.armour = SaveInventory.toBase64(items);
    }

    public void setEnderChest(ItemStack[] items) {
        this.enderChest = SaveInventory.toBase64(items);
    }

    public void setXP(float xp) {
        this.xp = xp;
    }

    public void setHealth(double health) {
        this.health = health;
    }

    public void setFoodLevel(int foodLevel) {
        this.hunger = foodLevel;
    }

    public void setSaturation(float saturation) {
        this.saturation = saturation;
    }

    public void setWorld(String world) {
        this.world = world;
    }

    public void setX(double x) {
        this.x = x;
    }

    public void setY(double y) {
        this.y = y;
    }

    public void setZ(double z) {
        this.z = z;
    }

    public void setLogType(LogType logType) {
        this.logType = logType;
    }

    public void setVersion(String packageVersion) {
        this.packageVersion = packageVersion;
    }

    public void setDeathReason(String deathReason) {
        this.deathReason = deathReason;
    }

    public ItemStack[] getMainInventory() {
        String base64 = data.getString("inventory");
        return RestoreInventory.getInventoryItems(getVersion(), base64);
    }

    public ItemStack[] getArmour() {
        String base64 = data.getString("armour");
        return RestoreInventory.getInventoryItems(getVersion(), base64);
    }

    public ItemStack[] getEnderChest() {
        String base64 = data.getString("enderchest");
        return RestoreInventory.getInventoryItems(getVersion(), base64);
    }

    /**
     * Converts a stored config value to a double, tolerating both the numbers current backups write
     * and the quoted strings older ones used. Returns null when the value is not numeric at all.
     * <p>
     * Neither built-in accessor covers both: {@code getDouble}/{@code getInt} return their default
     * for anything that is not a {@link Number}, so they silently zero a string-typed value, while
     * {@code Float.parseFloat(getString(...))} throws NPE when the key is missing entirely.
     */
    static Double toDouble(Object value) {
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).doubleValue();

        try {
            return Double.parseDouble(value.toString().trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    static Float toFloat(Object value) {
        Double parsed = toDouble(value);
        return parsed == null ? null : parsed.floatValue();
    }

    /** Reads a numeric field, falling back to 0 and warning if a value is present but unreadable. */
    private double readDouble(String path) {
        Object raw = data.get(path);
        Double parsed = toDouble(raw);

        if (parsed != null) return parsed;

        // A missing key is normal for older backups; a present-but-unparseable one is worth flagging.
        if (raw != null) {
            InventoryRollbackPlus.getInstance().getLogger().warning(
                    "Backup " + backupFile.getAbsolutePath() + " has a non-numeric " + path + " value '" + raw + "'");
        }

        return 0d;
    }

    private float readFloat(String path) {
        return (float) readDouble(path);
    }

    private int readInt(String path) {
        return (int) readDouble(path);
    }

    public float getXP() {
        return readFloat("xp");
    }

    public double getHealth() {
        return readDouble("health");
    }

    public int getFoodLevel() {
        return readInt("hunger");
    }

    public float getSaturation() {
        return readFloat("saturation");
    }

    public String getWorld() {
        return data.getString("location.world");
    }

    public double getX() {
        return readDouble("location.x");
    }

    public double getY() {
        return readDouble("location.y");
    }

    public double getZ() {
        return readDouble("location.z");
    }

    public LogType getSaveType() {
        String storedType = data.getString("logType");
        if (storedType == null) return null;

        try {
            return LogType.valueOf(storedType.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            InventoryRollbackPlus.getInstance().getLogger().warning(
                    "Backup " + backupFile.getAbsolutePath() + " has an unrecognised log type '" + storedType + "'");
            return null;
        }
    }

    public String getVersion() {
        return data.getString("version");
    }

    public String getDeathReason() {
        return data.getString("deathReason");
    }

    public void saveData() {
        data.set("inventory", mainInventory);
        data.set("armour", armour);
        data.set("enderchest", enderChest);
        data.set("xp", xp);
        data.set("health", health);
        data.set("hunger", hunger);
        data.set("saturation", saturation);
        data.set("location.world", world);
        data.set("location.x", x);
        data.set("location.y", y);
        data.set("location.z", z);
        data.set("logType", logType.name());
        data.set("version", packageVersion);
        data.set("deathReason", deathReason);

        Path target = backupFile.toPath();
        Path temp = target.resolveSibling(backupFile.getName() + TEMP_SUFFIX);

        try {
            // FileConfiguration#save would have done this for us, we stage the write ourselves now
            Files.createDirectories(target.getParent());

            // Write to a scratch file and move it into place, so a crash or a full disk part way
            // through can never leave a half-written backup where a good one used to be.
            Files.write(temp, data.saveToString().getBytes(StandardCharsets.UTF_8));

            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            // Clean up before logging: the logger call needs the plugin instance, and losing the
            // scratch file matters more than the message if that lookup ever fails.
            try {
                Files.deleteIfExists(temp);
            } catch (IOException cleanupEx) {
                // Nothing more to try. A stray .tmp does not match BACKUP_FILE_NAME, so it is inert -
                // never listed, restored or purged. Only a hard crash between the write and the move
                // above can strand one, and it costs a single unreferenced file.
            }

            InventoryRollbackPlus.getInstance().getLogger().log(Level.SEVERE,
                    "Failed to write backup " + backupFile.getAbsolutePath() + " - this backup was NOT saved!", e);
        }
    }

    public static String getBackupFolderName() {
        return backupFolderName;
    }

    public static void convertOldBackupData() {
        List<File> backupLocations = new ArrayList<>();
        backupLocations.add(new File(ConfigData.getFolderLocation(), "saves/deaths"));
        backupLocations.add(new File(ConfigData.getFolderLocation(), "saves/joins"));
        backupLocations.add(new File(ConfigData.getFolderLocation(), "saves/quits"));
        backupLocations.add(new File(ConfigData.getFolderLocation(), "saves/worldChanges"));
        backupLocations.add(new File(ConfigData.getFolderLocation(), "saves/force"));

        List<LogType> logTypeFiles = new ArrayList<>();
        int logTypeNumber = 0;
        logTypeFiles.add(LogType.DEATH);
        logTypeFiles.add(LogType.JOIN);
        logTypeFiles.add(LogType.QUIT);
        logTypeFiles.add(LogType.WORLD_CHANGE);
        logTypeFiles.add(LogType.FORCE);

        for (File backupFolders : backupLocations) {
            if (!backupFolders.exists()) {
                InventoryRollbackPlus.getInstance().getConsoleSender().sendMessage(MessageData.getPluginPrefix() + ChatColor.RED + "Backup folder does not exist at " + backupFolders.getAbsolutePath());
                logTypeNumber++;
                continue;
            }

            List<File> backupFiles = new ArrayList<>();

            //Add all YAML files to list
            for (File file : backupFolders.listFiles()) {
                if (file.isFile() && file.getName().substring(file.getName().indexOf('.')).equals(".yml")) {
                    backupFiles.add(file);
                }
            }

            LogType log = logTypeFiles.get(logTypeNumber);
            InventoryRollbackPlus.getInstance().getConsoleSender().sendMessage(MessageData.getPluginPrefix() + "Converting the backup location " + log.name());

            for (File backup : backupFiles) {
                YamlConfiguration data = new YamlConfiguration();
                
                try {
                    data.load(backup);
                } catch (InvalidConfigurationException | IOException e) {
                    InventoryRollbackPlus.getInstance().getConsoleSender().sendMessage(MessageData.getPluginPrefix() + ChatColor.RED + "Error converting backup file at " + backup.getAbsolutePath() + " - Invalid YAML format possibly from corruption.");
                    continue;
                }

                for (String time : data.getConfigurationSection("data").getKeys(false)) {
                    try {
                        Long timestamp = Long.parseLong(time);
                        UUID uuid = UUID.fromString(backup.getName().substring(0, backup.getName().length() - 4)); 

                        YAML yaml = new YAML(uuid, log, timestamp);

                        String packageVersion = data.getString("data." + timestamp + ".version");

                        yaml.setMainInventory(RestoreInventory.getInventoryItems(packageVersion, data.getString("data." + timestamp + ".inventory")));
                        yaml.setArmour(RestoreInventory.getInventoryItems(packageVersion, data.getString("data." + timestamp + ".armour")));
                        yaml.setEnderChest(RestoreInventory.getInventoryItems(packageVersion, data.getString("data." + timestamp + ".enderchest")));                    
                        yaml.setXP(Float.parseFloat(data.getString("data." + timestamp + ".xp")));
                        yaml.setHealth(data.getDouble("data." + timestamp + ".health"));
                        yaml.setFoodLevel(data.getInt("data." + timestamp + ".hunger"));
                        yaml.setSaturation(Float.parseFloat(data.getString("data." + timestamp + ".saturation")));
                        yaml.setWorld(data.getString("data." + timestamp + ".location.world"));
                        yaml.setX(data.getDouble("data." + timestamp + ".location.x"));
                        yaml.setY(data.getDouble("data." + timestamp + ".location.y"));
                        yaml.setZ(data.getDouble("data." + timestamp + ".location.z"));

                        String lt = data.getString("data." + timestamp + ".logType");
                        LogType logType = null;
                        if (lt.equalsIgnoreCase("WORLDCHANGE")) {
                            logType = LogType.WORLD_CHANGE;
                        } else {
                            logType = LogType.valueOf(lt);
                        }
                        yaml.setLogType(logType);

                        yaml.setVersion(packageVersion);
                        yaml.setDeathReason(data.getString("data." + timestamp + ".deathReason"));

                        yaml.saveData();
                    } catch (Exception e) {
                        InventoryRollbackPlus.getInstance().getConsoleSender().sendMessage(MessageData.getPluginPrefix() + ChatColor.RED + "Error converting backup file at " + backup.getAbsolutePath() + " on timestamp " + time);
                    }
                }
            }

            logTypeNumber++;

        }

        InventoryRollbackPlus.getInstance().getConsoleSender().sendMessage(MessageData.getPluginPrefix() + ChatColor.GREEN + "Conversion completed!");
    }

}
