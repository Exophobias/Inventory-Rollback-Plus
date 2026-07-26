package com.nuclyon.technicallycoded.inventoryrollback.util.serialization;

import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * These cover the difference between losing one item and losing a whole inventory, which for a
 * backup plugin is the difference that matters. Frames are built by hand because a real ItemStack
 * cannot be serialized without a running server.
 */
public class SerializationRecoveryTest {

    /** Little-endian, matching the wire format. */
    private static void writeInt(OutputStream os, int value) throws Exception {
        for (int i = 0; i < 4; i++) {
            os.write((value >> (i * 8)) & 0xFF);
        }
    }

    /**
     * Three slots: empty, an item whose bytes are not a valid object stream, empty.
     * The middle slot is length-prefixed correctly, so only that slot is unreadable.
     */
    private static String frameWithOneCorruptItem() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        writeInt(baos, 3);

        writeInt(baos, 0);                                  // slot 0, empty

        byte[] garbage = { 0x11, 0x22, 0x33, 0x44 };        // slot 1, not an object stream
        writeInt(baos, garbage.length);
        baos.write(garbage);

        writeInt(baos, 0);                                  // slot 2, empty

        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    @Test
    public void oneUnreadableItemDoesNotDiscardTheRest() throws Exception {
        DeserializationResult result = Version2Serialization.deserialize(frameWithOneCorruptItem());

        assertNotNull(result.getItems(), "The inventory must survive one bad item");
        assertEquals(3, result.getItems().length, "Every slot should still be present");
        assertTrue(result.isPartial(), "The result should report itself as partial");
        assertEquals(1, result.getFailedSlots(), "Exactly one slot should have failed");
        assertNull(result.getItems()[1], "The unreadable slot should be left empty");
        assertNotNull(result.getErrorMessage(), "A partial read should explain what was lost");
    }

    /**
     * Per-slot recovery must not weaken frame-level detection. A truncated stream always poisons
     * the most significant byte of a little-endian int, so the count reads back negative and the
     * allocation throws. That has to stay a total failure.
     */
    @Test
    public void aTruncatedFrameStillFailsWholesale() {
        String truncated = Base64.getEncoder().encodeToString(new byte[] { 0x01, 0x02 });

        DeserializationResult result = Version2Serialization.deserialize(truncated);

        assertNull(result.getItems(), "A damaged frame is not recoverable slot by slot");
        assertNotNull(result.getErrorMessage(), "A total failure should carry a message");
    }

    /**
     * The version was once read with substring(n, n + 1), so the counter would have broken
     * silently at 10 by reading "1" and handing a version 10 payload to the version 1 reader.
     */
    @Test
    public void versionPrefixIsNotCappedAtASingleDigit() {
        String payload = Base64.getEncoder().encodeToString("IRP_VERSION:10:whatever".getBytes());

        DeserializationResult result = ItemStackSerialization.deserializeData(null, payload);

        assertNull(result.getItems(), "An unknown version should not be decoded");
        assertNotNull(result.getErrorMessage());
        assertTrue(result.getErrorMessage().contains("10"),
                "The whole version number should be reported, got: " + result.getErrorMessage());
    }

    /**
     * V4 needs Paper's item NBT methods. They are absent from the spigot-api this builds against,
     * so CI exercises the fallback rather than the Paper path.
     */
    @Test
    public void paperFormatGatesItselfOnAvailability() {
        String encoded = Version4Serialization.serialize(new ItemStack[0]);

        if (Version4Serialization.isAvailable()) {
            assertNotNull(encoded, "V4 should encode where the Paper methods exist");
            return;
        }

        assertNull(encoded, "V4 must refuse to encode when the Paper methods are absent");

        DeserializationResult result = Version4Serialization.deserialize("anything");
        assertNull(result.getItems(), "V4 cannot be read without the Paper methods");
        assertNotNull(result.getErrorMessage(), "and it should say why");
    }

    /** The writer picks a format, stamps it, and the dispatcher reads it back. */
    @Test
    public void writtenPayloadRoundTripsThroughTheDispatcher() {
        String encoded = ItemStackSerialization.serialize(new ItemStack[0]);
        assertNotNull(encoded, "An empty inventory should encode successfully");

        DeserializationResult result = ItemStackSerialization.deserializeData(null, encoded);
        assertNotNull(result.getItems(), "The payload should decode");
        assertEquals(0, result.getItems().length);
        assertEquals(0, result.getFailedSlots());
    }

}
