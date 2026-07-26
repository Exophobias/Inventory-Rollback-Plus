package me.danjono.inventoryrollback.data;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Backups have not always stored xp/saturation the same way. Current saves write them as numbers,
 * but older ones wrote quoted strings, which is why the original readers went through
 * Float.parseFloat(getString(...)).
 * <p>
 * Neither Bukkit accessor handles both: getDouble() returns its default for a String value (it
 * checks instanceof Number first, so a string-typed backup silently restores 0 xp), and getString()
 * returns null for a missing key (so parseFloat NPEs). These pin the behaviour of the reader that
 * covers both.
 */
public class YAMLNumericReadTest {

    @Test
    public void readsNumbersAsCurrentBackupsStoreThem() {
        assertEquals(4621.0f, YAML.toFloat(4621.0d), 0.0f);
        assertEquals(4621.0f, YAML.toFloat(4621.0f), 0.0f);
        assertEquals(4621.0f, YAML.toFloat(4621), 0.0f);
        assertEquals(0.0f, YAML.toFloat(0.0d), 0.0f);
    }

    @Test
    public void readsQuotedStringsAsOlderBackupsStoreThem() {
        assertEquals(4621.0f, YAML.toFloat("4621.0"), 0.0f);
        assertEquals(2.5f, YAML.toFloat("2.5"), 0.0f);
        assertEquals(4621.0f, YAML.toFloat(" 4621.0 "), 0.0f);
    }

    @Test
    public void reportsMissingAndUnparseableValuesRatherThanGuessing() {
        assertNull(YAML.toFloat(null));
        assertNull(YAML.toFloat("not a number"));
        assertNull(YAML.toFloat(""));
    }

    /** health and the location coordinates read as doubles, hunger truncates to an int. */
    @Test
    public void readsDoublesFromBothStorageForms() {
        assertEquals(20.0d, YAML.toDouble(20.0d), 0.0d);
        assertEquals(20.0d, YAML.toDouble("20.0"), 0.0d);
        assertEquals(14814.9d, YAML.toDouble(14814.9d), 0.0001d);
        assertEquals(-64.0d, YAML.toDouble("-64.0"), 0.0d);

        assertNull(YAML.toDouble(null));
        assertNull(YAML.toDouble("world_nether"));
    }

    @Test
    public void hungerTruncatesTheSameWayGetIntDid() {
        assertEquals(13, YAML.toDouble(13).intValue());
        assertEquals(13, YAML.toDouble("13").intValue());
        assertEquals(13, YAML.toDouble(13.9d).intValue());
    }

}
