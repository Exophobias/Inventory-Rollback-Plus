package me.danjono.inventoryrollback.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.Nullable;

/**
 * Marks an inventory as one of this plugin's menus, and says which one.
 * <p>
 * The listeners used to identify menus by comparing the view title against the five configured
 * menu names. That misfires in both directions: a player can name a chest "Rollbacks" and have
 * their clicks handled by the menu code, and renaming a menu in the config silently detaches its
 * listener. A holder travels with the inventory itself, so neither is possible.
 */
public class IRPMenuHolder implements InventoryHolder {

    private final InventoryName type;

    private Inventory inventory;

    public IRPMenuHolder(InventoryName type) {
        this.type = type;
    }

    public InventoryName getType() {
        return this.type;
    }

    /**
     * Bukkit takes the holder when the inventory is created, so the back-reference can only be set
     * once that call returns.
     */
    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public @Nullable Inventory getInventory() {
        return this.inventory;
    }

    /** The menu this inventory belongs to, or null if it is not one of ours. */
    public static @Nullable InventoryName typeOf(@Nullable Inventory inventory) {
        if (inventory == null) return null;

        InventoryHolder holder = inventory.getHolder();
        return holder instanceof IRPMenuHolder ? ((IRPMenuHolder) holder).getType() : null;
    }

}
