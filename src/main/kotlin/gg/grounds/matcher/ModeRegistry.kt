package gg.grounds.matcher

import gg.grounds.domain.BandConfig
import gg.grounds.domain.ModeConfig
import gg.grounds.persistence.ValkeyQueue
import io.quarkus.runtime.StartupEvent
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.inject.Inject
import java.util.concurrent.ConcurrentHashMap
import org.jboss.logging.Logger

/**
 * The modes this matchmaker knows about.
 *
 * forge pushes these on deploy from the gamemode's `matchmaking:` block, via `UpsertQueue`. The map
 * here is the read path the matcher hits on every tick — but the write path now goes through Valkey
 * first (see `QueueService.upsertMode`), because a restart used to wipe this map silently: `/queue
 * duel` would then fail with an unknown mode and nobody would notice until a player tried it.
 *
 * There is no hot reload beyond that: a tick picks up whatever is current, and matches already
 * formed keep the parameters they were formed with.
 */
@ApplicationScoped
class ModeRegistry @Inject constructor(private val persistence: ValkeyQueue) {
    private val modes = ConcurrentHashMap<String, ModeConfig>()

    fun upsert(config: ModeConfig): Boolean = modes.put(config.modeId, config) == null

    fun find(modeId: String): ModeConfig? = modes[modeId]

    fun all(): Collection<ModeConfig> = modes.values

    /**
     * Seeds a 1v1 duel mode for local dev, so the service is useful before forge has pushed
     * anything. A no-op once "duel" is already registered — including by a persisted mode loaded a
     * moment ago — so this can never clobber a real config with the dev placeholder.
     */
    fun seedDuelForDev() {
        if (modes.containsKey("duel")) return
        upsert(
            ModeConfig(
                modeId = "duel",
                teamSize = 1,
                teamCount = 2,
                ranked = false,
                band = BandConfig(),
            )
        )
    }

    /**
     * Reload every persisted mode before the matcher or allocator get to run a single tick — a tick
     * against an empty registry is exactly the bug this exists to prevent.
     *
     * Priority PLATFORM_BEFORE - 1 runs this ahead of quarkus-scheduler's own StartupEvent observer
     * (SimpleScheduler starts at PLATFORM_BEFORE), which is the earliest a `@Scheduled` tick can
     * possibly fire. That ordering is the whole guarantee: without it, "before the matcher can run"
     * would just be a hope about relative startup timing.
     *
     * A Valkey that will not answer is not a silently-empty registry — it is a boot failure. The
     * exception here propagates out of the StartupEvent notification and Quarkus refuses to start.
     */
    fun loadPersisted(
        @Observes
        @Priority(jakarta.interceptor.Interceptor.Priority.PLATFORM_BEFORE - 1)
        event: StartupEvent
    ) {
        val persisted = persistence.loadModes()
        persisted.forEach { upsert(it) }
        seedDuelForDev()
        log.info("Loaded ${persisted.size} persisted mode(s) from Valkey")
    }

    companion object {
        private val log: Logger = Logger.getLogger(ModeRegistry::class.java)
    }
}
