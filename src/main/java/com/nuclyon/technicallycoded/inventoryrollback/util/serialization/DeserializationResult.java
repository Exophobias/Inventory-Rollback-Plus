package com.nuclyon.technicallycoded.inventoryrollback.util.serialization;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

public class DeserializationResult {

    public static DeserializationResult failure(String errorMessage) {
        return new DeserializationResult(null, errorMessage);
    }

    /**
     * Some slots decoded and some did not. The array is usable and the failed slots are empty.
     * <p>
     * This is deliberately not a failure. Losing one unreadable item is a far better outcome than
     * discarding a player's whole death inventory because a single stack could not be parsed.
     */
    public static DeserializationResult partial(ItemStack[] items, int failedSlots, String errorMessage) {
        return new DeserializationResult(items, errorMessage, failedSlots);
    }

    private final ItemStack[] items;
    private final String errorMessage;
    private final int failedSlots;

    public DeserializationResult(ItemStack[] items, String errorMessage) {
        this(items, errorMessage, 0);
    }

    private DeserializationResult(ItemStack[] items, String errorMessage, int failedSlots) {
        this.items = items;
        this.errorMessage = errorMessage;
        this.failedSlots = failedSlots;
    }

    /** Null only when nothing could be recovered at all. */
    @Nullable
    public ItemStack[] getItems() {
        return items;
    }

    @Nullable
    public String getErrorMessage() {
        return errorMessage;
    }

    /** How many slots could not be decoded and were left empty. */
    public int getFailedSlots() {
        return failedSlots;
    }

    /** True when the inventory came back usable but incomplete. */
    public boolean isPartial() {
        return items != null && failedSlots > 0;
    }

}
