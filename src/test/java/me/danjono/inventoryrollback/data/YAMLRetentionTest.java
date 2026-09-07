package me.danjono.inventoryrollback.data;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class YAMLRetentionTest {
    @TempDir Path directory;

    private YAML backup(long timestamp) {
        return new YAML(UUID.randomUUID(), LogType.JOIN, timestamp, directory.toFile(),
                Logger.getAnonymousLogger());
    }

    @Test void serializationRefusalPreservesAllPreviousBackups() throws Exception {
        Files.writeString(directory.resolve("1.yml"), "oldest");
        Files.writeString(directory.resolve("2.yml"), "newest");
        YAML next = backup(System.currentTimeMillis());
        java.lang.reflect.Field field = YAML.class.getDeclaredField("failedFields");
        field.setAccessible(true);
        @SuppressWarnings("unchecked") List<String> failed = (List<String>) field.get(next);
        failed.add("inventory");
        assertFalse(next.saveAndRetain(2));
        assertEquals("oldest", Files.readString(directory.resolve("1.yml")));
        assertEquals("newest", Files.readString(directory.resolve("2.yml")));
    }

    @Test void failedPublicationPreservesPreviousBackups() throws Exception {
        Files.writeString(directory.resolve("1.yml"), "oldest");
        Files.writeString(directory.resolve("2.yml"), "newest");
        long timestamp = System.currentTimeMillis();
        Path blocked = Files.createDirectory(directory.resolve(timestamp + ".yml"));
        Files.writeString(blocked.resolve("obstruction"), "keep");
        assertFalse(backup(timestamp).saveAndRetain(2));
        assertTrue(Files.exists(directory.resolve("1.yml")));
        assertTrue(Files.exists(directory.resolve("2.yml")));
        assertFalse(Files.exists(directory.resolve(timestamp + ".yml.tmp")));
    }

    @Test void successfulFreshBackupCountsImmediatelyAndRetainsExactlyTheNewest() throws Exception {
        assertTrue(backup(1).saveAndRetain(2));
        assertTrue(backup(2).saveAndRetain(2));
        long timestamp = System.currentTimeMillis();
        assertTrue(backup(timestamp).saveAndRetain(2));
        assertFalse(Files.exists(directory.resolve("1.yml")));
        assertTrue(Files.exists(directory.resolve("2.yml")));
        assertTrue(Files.readString(directory.resolve(timestamp + ".yml")).contains("logType: JOIN"));
    }

    @Test void delayedOlderSaveDoesNotDisplaceNewerBackupsOrDeleteTempFiles() throws Exception {
        assertTrue(backup(2).saveAndRetain(2));
        assertTrue(backup(3).saveAndRetain(2));
        Files.writeString(directory.resolve("0.yml.tmp"), "pending");
        assertTrue(backup(1).saveAndRetain(2));
        assertFalse(Files.exists(directory.resolve("1.yml")));
        assertTrue(Files.exists(directory.resolve("2.yml")));
        assertTrue(Files.exists(directory.resolve("3.yml")));
        assertEquals("pending", Files.readString(directory.resolve("0.yml.tmp")));
    }

    @Test void unlimitedRetentionKeepsEveryPublishedBackup() throws Exception {
        assertTrue(backup(1).saveAndRetain(0));
        assertTrue(backup(2).saveAndRetain(0));
        assertTrue(backup(3).saveAndRetain(0));
        assertEquals(3, backup(4).getAmountOfBackups());
    }
}
