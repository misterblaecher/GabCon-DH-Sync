package be.gabcon.dhsync.server;

import net.neoforged.fml.ModList;

import java.lang.reflect.Method;
import java.util.Optional;

public final class DhCompatibility {
    public static final String EXPECTED_MOD_VERSION = "3.3.2";
    public static final String EXPECTED_API_VERSION = "7.2.0";
    public static final String LEGACY_MOD_VERSION = "3.3.1";
    public static final String LEGACY_API_VERSION = "7.1.0";

    public record Status(boolean present, String modVersion, String apiVersion, boolean compatible, String detail) {}

    public static Status detect() {
        Optional<String> modVersion = ModList.get().getModContainerById("distanthorizons")
                .map(c -> c.getModInfo().getVersion().toString());
        if (modVersion.isEmpty()) return new Status(false, "absent", "absent", false, "Distant Horizons is not loaded");

        String api = detectApiVersion();
        boolean current = EXPECTED_MOD_VERSION.equals(modVersion.get()) && EXPECTED_API_VERSION.equals(api);
        boolean legacy = LEGACY_MOD_VERSION.equals(modVersion.get()) && LEGACY_API_VERSION.equals(api);
        boolean ok = current || legacy;

        String detail;
        if (current) {
            detail = "supported current DH/API pair";
        } else if (legacy) {
            detail = "supported legacy DH/API pair";
        } else {
            detail = "expected DH " + EXPECTED_MOD_VERSION + " / API " + EXPECTED_API_VERSION
                    + " (legacy " + LEGACY_MOD_VERSION + " / " + LEGACY_API_VERSION + " also accepted)";
        }
        return new Status(true, modVersion.get(), api, ok, detail);
    }

    private static String detectApiVersion() {
        try {
            Class<?> api = Class.forName("com.seibel.distanthorizons.api.DhApi");
            Method major = api.getMethod("getApiMajorVersion");
            Method minor = api.getMethod("getApiMinorVersion");
            Method patch = api.getMethod("getApiPatchVersion");
            return major.invoke(null) + "." + minor.invoke(null) + "." + patch.invoke(null);
        } catch (ReflectiveOperationException | LinkageError e) {
            return "unknown";
        }
    }

    private DhCompatibility() {}
}
