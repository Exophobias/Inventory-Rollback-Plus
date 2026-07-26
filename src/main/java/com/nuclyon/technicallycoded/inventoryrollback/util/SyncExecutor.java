package com.nuclyon.technicallycoded.inventoryrollback.util;

import com.nuclyon.technicallycoded.inventoryrollback.InventoryRollbackPlus;
import org.bukkit.Bukkit;
import org.bukkit.plugin.IllegalPluginAccessException;

import java.util.concurrent.ExecutionException;
import java.util.logging.Level;

/**
 * Helper for pushing work back onto the server's main thread.
 * <p>
 * Bukkit inventories are not thread safe. Menu contents are built off the main thread so the
 * disk/database reads behind them do not stall a tick, but the resulting {@code setItem} calls
 * have to be applied on the primary thread - especially since the menus are opened for the
 * player before they are populated.
 */
public final class SyncExecutor {

    private SyncExecutor() {}

    /**
     * Runs the task on the main thread, immediately if we are already on it.
     * Silently drops the task if the plugin can no longer schedule work.
     */
    public static void run(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
            return;
        }

        InventoryRollbackPlus main = InventoryRollbackPlus.getInstance();

        // Nothing left to draw into if the server is going down with us.
        if (main == null || !main.isEnabled() || main.isShuttingDown()) return;

        try {
            main.getServer().getScheduler().runTask(main, task);
        } catch (IllegalPluginAccessException ex) {
            // Disabled between the check above and the submit - a reload with a menu open. The menu
            // is being torn down anyway, so there is nothing useful left to draw.
        }
    }

    /**
     * Runs the task on the main thread and blocks until it has finished.
     * <p>
     * For the cases where an async caller has to touch Bukkit API before it can continue - building
     * a menu's inventory, for instance. Returning the future's completion also publishes whatever
     * the task wrote back to the calling thread.
     */
    public static void runAndWait(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
            return;
        }

        InventoryRollbackPlus main = InventoryRollbackPlus.getInstance();

        if (main == null || !main.isEnabled() || main.isShuttingDown()) return;

        try {
            main.getServer().getScheduler().callSyncMethod(main, () -> {
                task.run();
                return null;
            }).get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException ex) {
            main.getLogger().log(Level.SEVERE, "Failed to run a task on the main thread", ex.getCause());
        } catch (IllegalPluginAccessException ex) {
            // Disabled between the check above and the submit; nothing left to build for.
        }
    }

}
