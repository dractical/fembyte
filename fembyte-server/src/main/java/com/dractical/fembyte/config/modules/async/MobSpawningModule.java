package com.dractical.fembyte.config.modules.async;

import com.dractical.fembyte.config.ConfigCategory;
import com.dractical.fembyte.config.ConfigModule;

public class MobSpawningModule extends ConfigModule {

    public static boolean ENABLED = false;
    public static long CALCULATION_TIMEOUT_MS = 25L;

    private static String path() {
        return ConfigCategory.ASYNC.getBaseKeyName() + ".mob-spawning.";
    }

    @Override
    public void onLoaded() {
        ENABLED = config.getBoolean(
                path() + "enabled",
                false,
                ""
        );

        CALCULATION_TIMEOUT_MS = config.getLong(
                path() + "calculation-timeout-ms",
                25L,
                """
                        Maximum number of milliseconds to wait on the main thread for async mob spawning.
                        Set to 0 to disable the timeout and wait forever.
                        """
        );
    }
}
