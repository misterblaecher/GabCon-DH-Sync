package be.gabcon.dhsync.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class ClientConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.BooleanValue AUTO_DOWNLOAD;
    public static final ModConfigSpec.BooleanValue CONNECT_ON_COMPLETE;
    public static final ModConfigSpec.BooleanValue ALLOW_FALLBACK;
    public static final ModConfigSpec.IntValue MAX_CONCURRENT_DOWNLOADS;
    public static final ModConfigSpec.LongValue OPTIONAL_DOWNLOAD_SPEED_LIMIT;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.push("gabcondhsync");
        ENABLED = b.define("enabled", true);
        AUTO_DOWNLOAD = b.define("autoDownload", true);
        CONNECT_ON_COMPLETE = b.define("connectOnComplete", true);
        ALLOW_FALLBACK = b.define("allowFallback", true);
        MAX_CONCURRENT_DOWNLOADS = b.defineInRange("maxConcurrentDownloads", 2, 1, 8);
        OPTIONAL_DOWNLOAD_SPEED_LIMIT = b.defineInRange("optionalDownloadSpeedLimit", 0L, 0L, Long.MAX_VALUE);
        b.pop();
        SPEC = b.build();
    }

    private ClientConfig() {}
}
