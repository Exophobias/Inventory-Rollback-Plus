package com.nuclyon.technicallycoded.inventoryrollback.commands.inventoryrollback;

import com.nuclyon.technicallycoded.inventoryrollback.InventoryRollbackPlus;
import com.nuclyon.technicallycoded.inventoryrollback.commands.IRPCommand;
import com.nuclyon.technicallycoded.inventoryrollback.util.SyncExecutor;
import me.danjono.inventoryrollback.InventoryRollback;
import me.danjono.inventoryrollback.config.ConfigData;
import me.danjono.inventoryrollback.config.MessageData;
import me.danjono.inventoryrollback.gui.menu.MainMenu;
import me.danjono.inventoryrollback.gui.menu.PlayerMenu;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.logging.Level;

public class RestoreSubCmd extends IRPCommand {

    public RestoreSubCmd(InventoryRollbackPlus mainIn) {
        super(mainIn);
    }

    @Override
    public void onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (sender instanceof Player) {
            if (sender.hasPermission("inventoryrollbackplus.viewbackups")) {
                if (!ConfigData.isEnabled()) {
                    sender.sendMessage(MessageData.getPluginPrefix() + MessageData.getPluginDisabled());
                    return;
                }
                Player staff = (Player) sender;
                openBackupMenu(sender, staff, args);
            } else {
                sender.sendMessage(MessageData.getPluginPrefix() + MessageData.getNoPermission());
            }
        } else {
            sender.sendMessage(MessageData.getPluginPrefix() + MessageData.getPlayerOnlyError());
        }
    }

    @SuppressWarnings("deprecation")
    private void openBackupMenu(CommandSender sender, Player staff, String[] args) {
        if (args.length <= 0 || args.length == 1) {
            try {
                openMainMenu(staff);
            } catch (NullPointerException e) {
                reportMenuFailure(sender, e);
            }
        } else if(args.length == 2) {
            String uuidStr = args[1];

            // Handle input of UUID
            if (uuidStr.length() == 36 || args[1].length() == 32) {

                // Handle malformed UUID
                if (args[1].length() == 32) {
                    String oldUuidStr = uuidStr;
                    uuidStr = oldUuidStr.substring(0, 8);
                    uuidStr += "-";
                    uuidStr += oldUuidStr.substring(8, 12);
                    uuidStr += "-";
                    uuidStr += oldUuidStr.substring(12, 16);
                    uuidStr += "-";
                    uuidStr += oldUuidStr.substring(16, 20);
                    uuidStr += "-";
                    uuidStr += oldUuidStr.substring(20);
                }

                OfflinePlayer rollbackPlayer;
                try {
                    // Looking up by UUID is a local construction, no profile fetch
                    rollbackPlayer = Bukkit.getOfflinePlayer(UUID.fromString(uuidStr));
                } catch (IllegalArgumentException e) {
                    sender.sendMessage(MessageData.getPluginPrefix() + MessageData.getError());
                    return;
                }

                openPlayerMenuSafely(sender, staff, rollbackPlayer);
            } else {
                // If not UUID length, assume it's a name. Resolving a name the server has not seen
                // before goes out to the Mojang API and blocks the calling thread, so it must not
                // happen on the tick - a single unknown name would otherwise freeze the server.
                String name = args[1];

                this.main.getServer().getScheduler().runTaskAsynchronously(this.main, () -> {
                    OfflinePlayer rollbackPlayer = Bukkit.getOfflinePlayer(name);

                    SyncExecutor.run(() -> {
                        // They may have logged off while we were waiting on Mojang
                        if (!staff.isOnline()) return;

                        openPlayerMenuSafely(sender, staff, rollbackPlayer);
                    });
                });
            }
        } else {
            sender.sendMessage(MessageData.getPluginPrefix() + MessageData.getError());
        }
    }

    private void openPlayerMenuSafely(CommandSender sender, Player staff, OfflinePlayer rollbackPlayer) {
        try {
            openPlayerMenu(staff, rollbackPlayer);
        } catch (NullPointerException e) {
            reportMenuFailure(sender, e);
        }
    }

    /** The menu failed to build. Tell the sender instead of silently doing nothing, and leave a trace to debug from. */
    private void reportMenuFailure(CommandSender sender, Exception ex) {
        this.main.getLogger().log(Level.WARNING, "Could not open the rollback menu", ex);
        sender.sendMessage(MessageData.getPluginPrefix() + MessageData.getError());
    }

    private void openMainMenu(Player staff) {
        MainMenu menu = new MainMenu(staff, 1);

        staff.openInventory(menu.getInventory());
        Bukkit.getScheduler().runTaskAsynchronously(InventoryRollback.getInstance(), menu::getMainMenu);
    }

    private void openPlayerMenu(Player staff, OfflinePlayer offlinePlayer) {
        PlayerMenu menu = new PlayerMenu(staff, offlinePlayer);

        staff.openInventory(menu.getInventory());
        Bukkit.getScheduler().runTaskAsynchronously(InventoryRollback.getInstance(), menu::getPlayerMenu);
    }

}
