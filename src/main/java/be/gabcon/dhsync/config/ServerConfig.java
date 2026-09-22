package be.gabcon.dhsync.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class ServerConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.ConfigValue<String> REPOSITORY;
    public static final ModConfigSpec.ConfigValue<String> WORLD_ID;
    public static final ModConfigSpec.IntValue PUBLISH_INTERVAL_MINUTES;
    public static final ModConfigSpec.IntValue CHANGED_REGION_THRESHOLD;
    public static final ModConfigSpec.BooleanValue NATIVE_DH_FALLBACK_ENABLED;
    public static final ModConfigSpec.BooleanValue AUTO_PUBLISH;
    public static final ModConfigSpec.LongValue MAX_DOWNLOAD_BYTES;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.push("gabcondhsync");
        ENABLED = b.define("enabled", true);
        REPOSITORY = b.define("repository", "misterblaecher/GabCon-DH-Sync");
        WORLD_ID = b.define("worldId", "gabcon-main");
        PUBLISH_INTERVAL_MINUTES = b.defineInRange("publishIntervalMinutes", 30, 1, 1440);
        CHANGED_REGION_THRESHOLD = b.defineInRange("changedRegionThreshold", 32, 1, 100000);
        NATIVE_DH_FALLBACK_ENABLED = b.define("nativeDhFallbackEnabled", true);
        AUTO_PUBLISH = b.comment("Ignored while the safe DH snapshot adapter is not implemented.").define("autoPublish", false);
        MAX_DOWNLOAD_BYTES = b.defineInRange("maxDownloadBytes", 2L * 1024 * 1024 * 1024, 1L, Long.MAX_VALUE);
        b.pop();
        SPEC = b.build();
    }

    private ServerConfig() {}
}
