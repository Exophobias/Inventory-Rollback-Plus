package com.nuclyon.technicallycoded.inventoryrollback;

import com.tcoded.lightlibs.bukkitversion.BukkitVersion;
import com.tcoded.lightlibs.bukkitversion.MCVersion;

/** Resolves only versions represented by the bundled version library. */
final class ServerVersionSupport {

    private ServerVersionSupport() {}

    static BukkitVersion resolve(String serverVersion) {
        if (serverVersion == null) return null;
        MCVersion minecraftVersion = MCVersion.fromServerVersion(serverVersion);
        return minecraftVersion == null ? null : minecraftVersion.toBukkitVersion();
    }
}
