package com.nuclyon.technicallycoded.inventoryrollback;

import com.tcoded.lightlibs.bukkitversion.BukkitVersion;
import com.tcoded.lightlibs.bukkitversion.MCVersion;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerVersionSupportTest {

    @Test
    void resolvesRecordedPaper263StartupVersion() {
        BukkitVersion version = ServerVersionSupport.resolve("26.3-32-0c803ba (MC: 26.3)");
        assertEquals(BukkitVersion.v26_R5, version);
        assertEquals(MCVersion.v26_3, version.getMcVersions()[0]);
        // Modern GUI items must take the Bukkit PDC route, never 26.3's obfuscated NMS route.
        assertTrue(version.greaterOrEqThan(MCVersion.v1_20_4.toBukkitVersion()));
    }

    @Test
    void preservesPreviousVersionMapping() {
        assertEquals(BukkitVersion.v26_R4,
                ServerVersionSupport.resolve("26.2-121-deadbee (MC: 26.2)"));
    }

    @Test
    void unknownVersionsCannotSelectAnOlderCompatibilityBranch() {
        assertNull(ServerVersionSupport.resolve("26.4-1-deadbee (MC: 26.4)"));
        assertNull(ServerVersionSupport.resolve(null));
    }
}
