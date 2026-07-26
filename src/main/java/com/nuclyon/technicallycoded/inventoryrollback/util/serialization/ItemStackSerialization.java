package com.nuclyon.technicallycoded.inventoryrollback.util.serialization;

import org.bukkit.inventory.ItemStack;

import java.util.Base64;

public class ItemStackSerialization {

    private static final String MODERN_SERIALIZATION_PREFIX = "IRP_VERSION:";

    /**
     * Which format new backups are written in: "auto", "3" or "4".
     * <p>
     * Pushed in by {@code ConfigData} when the config loads, rather than read back out of it, so
     * that encoding does not drag the whole config stack in behind it.
     */
    private static volatile String preferredFormat = "auto";

    public static void setPreferredFormat(String format) {
        preferredFormat = format == null ? "auto" : format;
    }

    /**
     * Encodes an inventory, or returns null when it could not be encoded at all.
     * <p>
     * A null return must abort the save. Concatenating a failed payload into the prefix would
     * produce the literal text {@code IRP_VERSION:3:null}, which stores and reads back as a
     * perfectly well-formed but empty backup, so a serialization failure would be indistinguishable
     * from an empty inventory until someone needed the backup.
     */
    public static String serialize(ItemStack[] items) {
        if (shouldWriteVersion4()) {
            String serialized = Version4Serialization.serialize(items);
            // A per-item failure inside V4 falls through to V3 rather than losing the backup.
            if (serialized != null) return wrap(Version4Serialization.ID, serialized);
        }

        String serialized = Version3Serialization.serialize(items);
        if (serialized == null) return null;

        return wrap(Version3Serialization.ID, serialized);
    }

    /**
     * V4 stores vanilla NBT and so survives Minecraft upgrades, but only Paper can write it.
     * The config key lets an operator pin V3 ahead of a planned downgrade, because an older jar
     * cannot read a V4 payload.
     */
    private static boolean shouldWriteVersion4() {
        if ("3".equals(preferredFormat)) return false;
        if ("4".equals(preferredFormat)) return true;

        return Version4Serialization.isAvailable();
    }

    private static String wrap(int version, String payload) {
        String data = MODERN_SERIALIZATION_PREFIX + version + ":" + payload;
        return Base64.getEncoder().encodeToString(data.getBytes());
    }

    public static DeserializationResult deserializeData(String packageVersion, String data) {
        if (data == null) {
            return new DeserializationResult(null, "Data is null");
        }

        byte[] decodedBytes;
        String decodedString = null;

        try {
            decodedBytes = Base64.getDecoder().decode(data);
            decodedString = new String(decodedBytes);
        } catch (Exception ignored) {}

        // Default to version 1 if no prefix is found
        String version = "1";
        String unprefixedData = data; // version 1

        // Modern serialization format:
        // IRP_VERSION:2:data-here
        if (decodedString != null && decodedString.startsWith(MODERN_SERIALIZATION_PREFIX)) {
            int prefixLen = MODERN_SERIALIZATION_PREFIX.length();
            int separator = decodedString.indexOf(':', prefixLen);

            // Read the version up to the separator rather than a single character, so the counter
            // does not silently break at 10.
            if (separator > prefixLen) {
                version = decodedString.substring(prefixLen, separator);
                unprefixedData = decodedString.substring(separator + 1);
            }
        }

        switch (version) {
            case "1":
                return new DeserializationResult(
                        Version1Serialization.stacksFromBase64(packageVersion, unprefixedData),
                        "");
            case "2":
                return Version2Serialization.deserialize(unprefixedData);
            case "3":
                return Version3Serialization.deserialize(unprefixedData);
            case "4":
                return Version4Serialization.deserialize(unprefixedData);
            default:
                return new DeserializationResult(null, "Unsupported serialization version: " + version);
        }

    }

}
