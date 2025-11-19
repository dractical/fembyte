package com.dractical.fembyte.concurrent.chunk;

import com.dractical.fembyte.concurrent.Async;
import com.dractical.fembyte.config.modules.async.ChunkSendModule;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class AsyncChunkSendDispatcher {

    private static final Logger LOGGER = LoggerFactory.getLogger(AsyncChunkSendDispatcher.class);
    private static final ConcurrentHashMap<UUID, PlayerContext> CONTEXTS = new ConcurrentHashMap<>();
    private static final AtomicInteger GLOBAL_IN_FLIGHT = new AtomicInteger();

    private AsyncChunkSendDispatcher() {
    }

    public static boolean trySend(ServerPlayer player, ServerLevel level, LevelChunk chunk) {
        if (!ChunkSendModule.ENABLED) {
            return false;
        }

        ServerGamePacketListenerImpl connection = player.connection;
        if (connection == null || !connection.isAcceptingMessages()) {
            return false;
        }

        PlayerContext context = CONTEXTS.computeIfAbsent(player.getUUID(), uuid -> new PlayerContext(player));
        return context.enqueue(level, chunk, connection);
    }

    public static void cancel(ServerPlayer player, int chunkX, int chunkZ) {
        if (!ChunkSendModule.ENABLED || !ChunkSendModule.CANCEL_ON_UNLOAD) {
            return;
        }

        PlayerContext context = CONTEXTS.get(player.getUUID());
        if (context != null) {
            context.cancel(ChunkPos.asLong(chunkX, chunkZ));
        }
    }

    public static void remove(ServerPlayer player) {
        PlayerContext context = CONTEXTS.remove(player.getUUID());
        if (context != null) {
            context.destroy();
        }
    }

    private static boolean reserveGlobalSlot() {
        int limit = ChunkSendModule.MAX_GLOBAL_IN_FLIGHT;
        if (limit <= 0) {
            GLOBAL_IN_FLIGHT.incrementAndGet();
            return true;
        }

        while (true) {
            int current = GLOBAL_IN_FLIGHT.get();
            if (current >= limit) {
                return false;
            }
            if (GLOBAL_IN_FLIGHT.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    private static void releaseGlobalSlot() {
        GLOBAL_IN_FLIGHT.updateAndGet(value -> Math.max(0, value - 1));
    }

    private static CompletableFuture<ClientboundLevelChunkWithLightPacket> preparePacket(
            ServerGamePacketListenerImpl connection,
            ServerLevel level,
            LevelChunk chunk
    ) {
        return switch (ChunkSendModule.EXECUTOR) {
            case VIRTUAL -> Async.supplyVirtual(() -> PlayerChunkSender.buildChunkPacket(connection, level, chunk));
            case CPU -> Async.supplyCpu(() -> PlayerChunkSender.buildChunkPacket(connection, level, chunk));
        };
    }

    private static final class PlayerContext {
        private final ServerPlayer player;
        private final AtomicInteger inFlight = new AtomicInteger();
        private final ConcurrentHashMap<Long, PendingTask> tasks = new ConcurrentHashMap<>();

        private PlayerContext(ServerPlayer player) {
            this.player = player;
        }

        boolean enqueue(ServerLevel level, LevelChunk chunk, ServerGamePacketListenerImpl connection) {
            long chunkKey = chunk.getPos().toLong();
            if (this.tasks.containsKey(chunkKey)) {
                return true;
            }

            if (!reservePerPlayerSlot(this.inFlight)) {
                return false;
            }

            if (!reserveGlobalSlot()) {
                this.inFlight.decrementAndGet();
                return false;
            }

            PendingTask task = new PendingTask(chunkKey, level, chunk, connection, this);
            PendingTask previous = this.tasks.putIfAbsent(chunkKey, task);
            if (previous != null) {
                releaseGlobalSlot();
                this.inFlight.decrementAndGet();
                return true;
            }

            task.start();
            return true;
        }

        void cancel(long chunkKey) {
            PendingTask task = this.tasks.get(chunkKey);
            if (task != null) {
                task.cancel();
            }
        }

        void destroy() {
            for (PendingTask task : this.tasks.values()) {
                task.cancel();
            }
            this.tasks.clear();
        }

        private static boolean reservePerPlayerSlot(AtomicInteger counter) {
            int limit = ChunkSendModule.MAX_IN_FLIGHT_PER_PLAYER;
            while (true) {
                int current = counter.get();
                if (limit > 0 && current >= limit) {
                    return false;
                }
                if (counter.compareAndSet(current, current + 1)) {
                    return true;
                }
            }
        }

        private void releasePerPlayerSlot() {
            this.inFlight.updateAndGet(value -> Math.max(0, value - 1));
        }

        private void handleCompletion(
                PendingTask task,
                ClientboundLevelChunkWithLightPacket packet,
                Throwable error
        ) {
            this.tasks.remove(task.chunkKey, task);
            releasePerPlayerSlot();
            releaseGlobalSlot();

            if (task.cancelled.get()) {
                return;
            }

            if (error != null) {
                LOGGER.warn(
                        "Failed to asynchronously prepare chunk packet for {} at {}",
                        this.player.getScoreboardName(),
                        new ChunkPos(task.chunkKey),
                        error
                );

                if (ChunkSendModule.FALLBACK_TO_SYNC && !this.player.isRemoved()) {
                    Async.onMain(() -> {
                        if (task.cancelled.get()) {
                            return;
                        }
                        if (!task.connection.isAcceptingMessages()) {
                            return;
                        }
                        PlayerChunkSender.sendChunk(task.connection, task.level, task.chunk);
                    });
                }
                return;
            }

            Runnable send = () -> {
                if (task.cancelled.get()) {
                    return;
                }
                if (!task.connection.isAcceptingMessages()) {
                    return;
                }
                PlayerChunkSender.sendBuiltChunk(task.connection, task.level, task.chunk, packet);
            };

            if (ChunkSendModule.SEND_ON_MAIN_THREAD) {
                Async.onMain(send);
            } else {
                send.run();
            }
        }
    }

    private static final class PendingTask {
        private final long chunkKey;
        private final ServerLevel level;
        private final LevelChunk chunk;
        private final ServerGamePacketListenerImpl connection;
        private final PlayerContext owner;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile CompletableFuture<ClientboundLevelChunkWithLightPacket> future;
        private volatile ScheduledFuture<?> timeout;

        private PendingTask(
                long chunkKey,
                ServerLevel level,
                LevelChunk chunk,
                ServerGamePacketListenerImpl connection,
                PlayerContext owner
        ) {
            this.chunkKey = chunkKey;
            this.level = level;
            this.chunk = chunk;
            this.connection = connection;
            this.owner = owner;
        }

        private void start() {
            CompletableFuture<ClientboundLevelChunkWithLightPacket> preparation;
            try {
                preparation = preparePacket(this.connection, this.level, this.chunk);
            } catch (Throwable throwable) {
                this.owner.handleCompletion(this, null, throwable);
                return;
            }

            this.future = preparation;
            armTimeout();
            preparation.whenComplete((packet, throwable) -> {
                cancelTimeout();
                this.owner.handleCompletion(this, packet, throwable);
            });
        }

        private void armTimeout() {
            long timeoutMs = ChunkSendModule.PREPARATION_TIMEOUT_MS;
            if (timeoutMs <= 0L) {
                return;
            }

            this.timeout = Async.schedule(() -> {
                if (this.cancelled.compareAndSet(false, true)) {
                    CompletableFuture<ClientboundLevelChunkWithLightPacket> prep = this.future;
                    if (prep != null) {
                        prep.cancel(true);
                    }
                }
            }, Duration.ofMillis(timeoutMs));
        }

        private void cancelTimeout() {
            ScheduledFuture<?> current = this.timeout;
            if (current != null) {
                current.cancel(false);
            }
        }

        private void cancel() {
            if (!this.cancelled.compareAndSet(false, true)) {
                return;
            }

            cancelTimeout();
            CompletableFuture<ClientboundLevelChunkWithLightPacket> preparation = this.future;
            if (preparation != null) {
                preparation.cancel(true);
            }
        }
    }
}

