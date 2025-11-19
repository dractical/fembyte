package com.dractical.fembyte.config.modules.async;

import com.dractical.fembyte.config.ConfigCategory;
import com.dractical.fembyte.config.ConfigModule;

public class ChunkSendModule extends ConfigModule {

    public static boolean ENABLED = false;
    public static int MAX_IN_FLIGHT_PER_PLAYER = 6;
    public static int MAX_GLOBAL_IN_FLIGHT = 128;
    public static long PREPARATION_TIMEOUT_MS = 0L;
    public static boolean CANCEL_ON_UNLOAD = true;
    public static boolean SEND_ON_MAIN_THREAD = true;
    public static boolean FALLBACK_TO_SYNC = true;
    public static Executor EXECUTOR = Executor.CPU;

    public enum Executor {
        CPU,
        VIRTUAL
    }

    private static String path() {
        return ConfigCategory.ASYNC.getBaseKeyName() + ".chunk-sending.";
    }

    @Override
    public void onLoaded() {
        ENABLED = config.getBoolean(
                path() + "enabled",
                false,
                """
                        Enables async chunk packet preparation and dispatch.
                        """
        );

        MAX_IN_FLIGHT_PER_PLAYER = config.getInt(
                path() + "max-in-flight-per-player",
                6,
                """
                        Maximum number of chunk packets that may be prepared in parallel for a single player.
                        """
        );

        MAX_GLOBAL_IN_FLIGHT = config.getInt(
                path() + "max-global-in-flight",
                256,
                """
                        Hard upper bound for concurrently prepared chunk packets across the entire server.
                        Set to 0 to disable it entirely.
                        """
        );

        PREPARATION_TIMEOUT_MS = config.getLong(
                path() + "preparation-timeout-ms",
                0L,
                """
                        Cancels an async preparation if it takes longer than the configured number of milliseconds.
                        Set to 0 to disable it.
                        """
        );

        CANCEL_ON_UNLOAD = config.getBoolean(
                path() + "cancel-when-chunk-unloads",
                true,
                """
                        When enabled, pending chunk packets are cancelled if the chunk needs to be unloaded before preparation is done.
                        """
        );

        SEND_ON_MAIN_THREAD = config.getBoolean(
                path() + "send-on-main-thread",
                false,
                """
                        When true, the prepared chunk packet is flushed back on the main server thread.
                        """
        );

        FALLBACK_TO_SYNC = config.getBoolean(
                path() + "fallback-to-sync",
                true,
                """
                        If async fails for any reason, fall back to the vanilla path.
                        You should probably keep this enabled.
                        """
        );

        EXECUTOR = config.getEnum(
                path() + "executor",
                Executor.CPU,
                """
                        Controls which executor is used for chunk preparation. CPU uses a bounded worker pool
                        while VIRTUAL launches lightweight virtual threads.
                        """
        );
    }
}
