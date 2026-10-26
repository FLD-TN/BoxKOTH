package com.dex.boxkoth.util;

import org.bukkit.plugin.Plugin;

import java.util.logging.Logger;

/** Startup report of dependency versions, with a warning for versions this build was not tested with. */
public final class DependencyCheck {

    public static final String TESTED_BOXCLANS = "1.0.0";
    public static final String TESTED_PLACEHOLDER_API = "2.11.6";
    public static final String TESTED_DECENT_HOLOGRAMS = "2.8.17";

    private DependencyCheck() {
    }

    /** Logs detected versions and warns about untested ones. */
    public static void report(Plugin self, String clansDetails) {
        Logger log = self.getLogger();
        String clans = version(self, "BoxClans");
        String papi = version(self, "PlaceholderAPI");
        String dh = version(self, "DecentHolograms");
        log.info("Dependencies: BoxClans " + clans + " (" + clansDetails + "), PlaceholderAPI " + papi
                + ", DecentHolograms " + dh);
        warnIfUntested(log, "BoxClans", clans, TESTED_BOXCLANS);
        warnIfUntested(log, "PlaceholderAPI", papi, TESTED_PLACEHOLDER_API);
        warnIfUntested(log, "DecentHolograms", dh, TESTED_DECENT_HOLOGRAMS);
    }

    private static void warnIfUntested(Logger log, String name, String running, String tested) {
        if (!tested.equals(running)) {
            log.warning(name + " " + running + " is installed but ClanKoth was tested with " + name + " " + tested
                    + ". It will probably work; run a short test KOTH on a staging server after updates.");
        }
    }

    private static String version(Plugin self, String name) {
        Plugin p = self.getServer().getPluginManager().getPlugin(name);
        return p == null ? "(missing)" : p.getPluginMeta().getVersion();
    }
}
