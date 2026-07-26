package com.nuclyon.technicallycoded.inventoryrollback.util.serialization;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.Base64;

/**
 * Items stored as vanilla NBT, through Paper's {@code ItemStack#serializeAsBytes} and
 * {@code ItemStack.deserializeBytes}.
 * <p>
 * Those methods embed a {@code DataVersion} in the root compound, so on read Paper runs the item
 * through the same data fixer the server uses on its own world data. That is the only mechanism
 * here that survives a Minecraft upgrade. V1 to V3 are Java serialization of Bukkit's
 * configuration map and have no equivalent, which is why every backup has to record the server
 * version it was written on.
 * <p>
 * The methods are absent from spigot-api and from Paper 1.14.4 and older, so both handles are
 * resolved once at class load and {@link #isAvailable()} gates every write. When it is false the
 * writer stays on V3, which is why V3 remains a supported write format rather than read-only.
 * <p>
 * Framing is identical to V2: an item count, then per slot a length and that many bytes. There is
 * deliberately no outer GZIP, because Paper already writes each item through
 * {@code NbtIo.writeCompressed} and a second pass would cost CPU for almost nothing.
 */
public class Version4Serialization {

    public static final int ID = 4;

    private static final MethodHandle SERIALIZE_AS_BYTES;
    private static final MethodHandle DESERIALIZE_BYTES;

    static {
        MethodHandle serialize = null;
        MethodHandle deserialize = null;

        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            serialize = lookup.unreflect(ItemStack.class.getMethod("serializeAsBytes"));
            deserialize = lookup.unreflect(ItemStack.class.getMethod("deserializeBytes", byte[].class));
        } catch (Throwable ignored) {
            // Not Paper, or a Paper older than 1.15.2. Callers fall back to V3.
        }

        SERIALIZE_AS_BYTES = serialize;
        DESERIALIZE_BYTES = deserialize;
    }

    /** Whether this server exposes the Paper item NBT methods. */
    public static boolean isAvailable() {
        return SERIALIZE_AS_BYTES != null && DESERIALIZE_BYTES != null;
    }

    /**
     * An empty slot. Paper refuses to serialize an empty stack
     * ({@code Preconditions.checkArgument(!isEmpty())}), and its reader hands back an AIR stack
     * rather than null, so both directions have to be normalised here.
     */
    private static boolean isEmpty(ItemStack item) {
        return item == null || item.getType() == Material.AIR || item.getAmount() <= 0;
    }

    /** Returns null when this format is unavailable or the inventory could not be encoded. */
    public static String serialize(ItemStack[] items) {
        if (!isAvailable() || items == null) return null;

        try {
            byte[] serializedBytes = serializeBytes(items);
            return Base64.getEncoder().encodeToString(serializedBytes);
        } catch (Throwable ex) {
            return null;
        }
    }

    public static byte[] serializeBytes(ItemStack[] items) throws Throwable {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        writeInt(baos, items.length);

        for (ItemStack item : items) {
            if (isEmpty(item)) {
                writeInt(baos, 0);
                continue;
            }

            byte[] serializedItem = (byte[]) SERIALIZE_AS_BYTES.invoke(item);
            writeInt(baos, serializedItem.length);
            baos.write(serializedItem);
        }

        baos.close();
        return baos.toByteArray();
    }

    public static DeserializationResult deserialize(String data) {
        if (!isAvailable()) {
            return DeserializationResult.failure(
                    "This backup was written with Paper item data, which this server cannot read. "
                            + "Restore it on Paper 1.15.2 or newer.");
        }

        try {
            byte[] b64decoded = Base64.getDecoder().decode(data);
            return deserialize(new ByteArrayInputStream(b64decoded));
        } catch (Exception e) {
            return DeserializationResult.failure("Failed to deserialize item stack: " + e.getMessage());
        }
    }

    public static DeserializationResult deserialize(InputStream in) throws IOException {
        // As in V2, a truncated frame yields a negative count and throws rather than being
        // recovered slot by slot.
        int itemCount = readInt(in);
        ItemStack[] items = new ItemStack[itemCount];

        int failedSlots = 0;
        String firstError = null;

        for (int i = 0; i < items.length; i++) {
            int length = readInt(in);

            if (length == 0) {
                items[i] = null;
                continue;
            }

            byte[] serializedItem = new byte[length];
            for (int j = 0; j < length; j++) {
                serializedItem[j] = (byte) in.read();
            }

            try {
                ItemStack item = (ItemStack) DESERIALIZE_BYTES.invoke(serializedItem);
                // The reader returns an empty stack, never null, and the menus treat null as empty.
                items[i] = isEmpty(item) ? null : item;
            } catch (Throwable ex) {
                // An id the data fixer cannot rename throws here rather than degrading, so per-slot
                // isolation is what keeps one removed modded item from costing the whole inventory.
                items[i] = null;
                failedSlots++;
                if (firstError == null) firstError = String.valueOf(ex.getMessage());
            }
        }

        if (failedSlots > 0) {
            return DeserializationResult.partial(items, failedSlots,
                    failedSlots + " item(s) could not be read and were left empty. First error: " + firstError);
        }

        return new DeserializationResult(items, null);
    }

    private static int readInt(InputStream is) throws IOException {
        int result = 0;
        for (int i = 0; i < 4; i++) {
            result |= (is.read() & 0xFF) << (i * 8);
        }
        return result;
    }

    private static void writeInt(OutputStream os, int value) throws IOException {
        for (int i = 0; i < 4; i++) {
            os.write((value >> (i * 8)) & 0xFF);
        }
    }

}
