package be.gabcon.dhsync.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class ClientConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.BooleanValue AUTO_DOWNLOAD;
    public static final ModConfigSpec.BooleanValue CONNECT_ON_COMPLETE;
    public static final ModConfigSpec.BooleanValue ALLOW_FALLBACK;
    public static final ModConfigSpec.BooleanValue INTERCEPT_MANAGED_CONNECTIONS;
    public static final ModConfigSpec.IntValue MAX_CONCURRENT_DOWNLOADS;
    public static final ModConfigSpec.LongValue OPTIONAL_DOWNLOAD_SPEED_LIMIT;
    public static final ModConfigSpec.LongValue MAX_DOWNLOAD_BYTES;
    public static final ModConfigSpec.ConfigValue<String> WORLD_ID;
    public static final ModConfigSpec.ConfigValue<String> REPOSITORY;
    public static final ModConfigSpec.ConfigValue<String> RELEASE_TAG;
    public static final ModConfigSpec.ConfigValue<String> MANIFEST_URL_OVERRIDE;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.push("gabcondhsync");
        ENABLED = b.define("enabled", true);
        AUTO_DOWNLOAD = b.define("autoDownload", true);
        CONNECT_ON_COMPLETE = b.define("connectOnComplete", true);
        ALLOW_FALLBACK = b.comment("If sync fails before any DB commit, allow normal Minecraft/DH connection.")
                .define("allowFallback", true);
        INTERCEPT_MANAGED_CONNECTIONS = b.comment("Intercept only server addresses registered in client-state.json.")
                .define("interceptManagedConnections", true);
        MAX_CONCURRENT_DOWNLOADS = b.defineInRange("maxConcurrentDownloads", 2, 1, 8);
        OPTIONAL_DOWNLOAD_SPEED_LIMIT = b.defineInRange("optionalDownloadSpeedLimit", 0L, 0L, Long.MAX_VALUE);
        MAX_DOWNLOAD_BYTES = b.defineInRange("maxDownloadBytes", 2L * 1024 * 1024 * 1024, 1L, Long.MAX_VALUE);
        WORLD_ID = b.define("worldId", "gabcon-main");
        REPOSITORY = b.define("repository", "misterblaecher/GabCon-DH-Sync");
        RELEASE_TAG = b.define("releaseTag", "gabcon-data-gabcon-main");
        MANIFEST_URL_OVERRIDE = b.comment("Leave blank to use the stable GitHub Release asset URL.")
                .define("manifestUrlOverride", "");
        b.pop();
        SPEC = b.build();
    }

    public static String defaultManifestUrl() {
        String override = MANIFEST_URL_OVERRIDE.get();
        if (override != null && !override.isBlank()) return override.trim();
        return "https://github.com/" + REPOSITORY.get() + "/releases/download/"
                + RELEASE_TAG.get() + "/manifest.json";
    }

    private ClientConfig() {}
}
