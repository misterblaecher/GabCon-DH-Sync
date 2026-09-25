package be.gabcon.dhsync.client;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class DhClientLocator {
    private static final String DATABASE_NAME = "DistantHorizons.sqlite";

    public static Map<String, Path> locateLoadedDatabases() throws Exception {
        Object worldProxy = getWorldProxy();
        boolean loaded = (boolean) invoke(worldProxy, "worldLoaded");
        if (!loaded) throw new IllegalStateException("Distant Horizons world is not loaded yet");

        Object wrappersObject = invoke(worldProxy, "getAllLoadedLevelWrappers");
        if (!(wrappersObject instanceof Iterable<?> wrappers)) {
            throw new IllegalStateException("Unexpected DH level wrapper collection");
        }

        Map<String, Path> result = new LinkedHashMap<>();
        for (Object wrapper : wrappers) {
            String dimension = String.valueOf(invoke(wrapper, "getDimensionName"));
            File saveFolder = (File) invoke(wrapper, "getDhSaveFolder");
            if (saveFolder == null) continue;
            Path db = saveFolder.toPath().resolve(DATABASE_NAME).toAbsolutePath().normalize();
            if (Files.isRegularFile(db)) result.put(dimension, db);
        }
        if (result.isEmpty()) throw new IllegalStateException("No loaded Distant Horizons databases were found");
        return Map.copyOf(result);
    }

    private static Object getWorldProxy() throws Exception {
        Class<?> delayed = Class.forName("com.seibel.distanthorizons.api.DhApi$Delayed");
        Field field = delayed.getField("worldProxy");
        Object proxy = field.get(null);
        if (proxy == null) throw new IllegalStateException("Distant Horizons worldProxy is not initialized");
        return proxy;
    }

    private static Object invoke(Object target, String method) throws Exception {
        try {
            Method m = target.getClass().getMethod(method);
            return m.invoke(target);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) throw ex;
            if (cause instanceof Error err) throw err;
            throw e;
        }
    }

    private DhClientLocator() {}
}
