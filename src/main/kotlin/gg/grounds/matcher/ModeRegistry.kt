package gg.grounds.matcher

import gg.grounds.domain.BandConfig
import gg.grounds.domain.ModeConfig
import jakarta.enterprise.context.ApplicationScoped
import java.util.concurrent.ConcurrentHashMap

/**
 * The modes this matchmaker knows about.
 *
 * forge pushes these on deploy from the gamemode's `matchmaking:` block. There is no hot reload: a
 * tick picks up whatever is current, and matches already formed keep the parameters they were
 * formed with.
 *
 * In-memory on purpose. This is configuration, not state — losing it costs a redeploy of the
 * gamemode, not a player's place in a queue. Everything that must survive a restart lives in
 * Valkey.
 */
@ApplicationScoped
class ModeRegistry {
    private val modes = ConcurrentHashMap<String, ModeConfig>()

    fun upsert(config: ModeConfig): Boolean = modes.put(config.modeId, config) == null

    fun find(modeId: String): ModeConfig? = modes[modeId]

    fun all(): Collection<ModeConfig> = modes.values

    /**
     * Seeds a 1v1 duel mode for local dev, so the service is useful before forge has pushed
     * anything. Overwritten by the first real UpsertQueue.
     */
    fun seedDuelForDev() {
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
}
