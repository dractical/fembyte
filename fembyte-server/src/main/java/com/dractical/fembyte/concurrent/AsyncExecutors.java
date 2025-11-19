package com.dractical.fembyte.concurrent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ScheduledExecutorService;

public final class AsyncExecutors {
    private static final Logger logger = LoggerFactory.getLogger("Fembyte-Async");
    private static final ExecutorService CPU_EXECUTOR;
    private static final ExecutorService VIRTUAL_EXECUTOR;
    private static final ScheduledExecutorService SCHEDULER;

    private static volatile MainThreadExecutor MAIN_THREAD_EXECUTOR;

    static {
        int parallelism = Math.max(2, Runtime.getRuntime().availableProcessors() - 1);

        CPU_EXECUTOR = new ForkJoinPool(
                parallelism,
                ForkJoinPool.defaultForkJoinWorkerThreadFactory,
                AsyncExecutors::handleUncaughtException,
                true
        );

        VIRTUAL_EXECUTOR = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual()
                        .name("fembyte-virt-", 0)
                        .uncaughtExceptionHandler(AsyncExecutors::handleUncaughtException)
                        .factory()
        );

        SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "fembyte-scheduler");
            t.setDaemon(true);
            t.setUncaughtExceptionHandler(AsyncExecutors::handleUncaughtException);
            return t;
        });
    }

    private AsyncExecutors() {
    }

    public static ExecutorService cpuExecutor() {
        return CPU_EXECUTOR;
    }

    public static ExecutorService virtualExecutor() {
        return VIRTUAL_EXECUTOR;
    }

    public static ScheduledExecutorService scheduler() {
        return SCHEDULER;
    }

    public static void setMainThreadExecutor(MainThreadExecutor executor) {
        MAIN_THREAD_EXECUTOR = Objects.requireNonNull(executor, "executor");
    }

    public static MainThreadExecutor mainThreadExecutor() {
        MainThreadExecutor executor = MAIN_THREAD_EXECUTOR;
        if (executor == null) {
            throw new IllegalStateException(
                    "MainThreadExecutor has not been set."
            );
        }
        return executor;
    }

    private static void handleUncaughtException(Thread t, Throwable e) {
        logger.error("Uncaught exception in thread " + t.getName() + ": " + e.getMessage(), e);
    }

    public static void shutdownAll() {
        CPU_EXECUTOR.shutdown();
        VIRTUAL_EXECUTOR.shutdown();
        SCHEDULER.shutdown();
    }
}
