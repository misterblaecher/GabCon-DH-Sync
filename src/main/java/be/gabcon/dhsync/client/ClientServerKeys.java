package be.gabcon.dhsync.client;

import net.minecraft.client.multiplayer.resolver.ServerAddress;

import java.util.Locale;

public final class ClientServerKeys {
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("Server address is blank");
        try {
            return of(ServerAddress.parseString(raw));
        } catch (RuntimeException ignored) {
            return raw.trim().toLowerCase(Locale.ROOT);
        }
    }

    public static String of(ServerAddress address) {
        String host = address.getHost().trim().toLowerCase(Locale.ROOT);
        if (host.contains(":") && !host.startsWith("[")) host = "[" + host + "]";
        return host + ":" + address.getPort();
    }

    private ClientServerKeys() {}
}
