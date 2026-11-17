package io.github.sodarx.biliplay.core

/**
 * Recent-listening context for [RecommendationPolicy].
 *
 * All creator values must be produced by [RecommendationPolicy.creator], so callers and the policy
 * share one canonical identity. The policy only reads these collections and never mutates them.
 *
 * @property playedKeys keys ([Track.key]) of recently played tracks, newest context first.
 * @property playedCreators canonical creators of recently played tracks.
 * @property likedCreators canonical creators the user liked/favorited.
 * @property recentBatches keys shown by the latest recommendation batches, the newest batch first.
 */
data class RecommendationSignals(
    val playedKeys: Set<String> = emptySet(),
    val playedCreators: Set<String> = emptySet(),
    val likedCreators: Set<String> = emptySet(),
    val recentBatches: List<Set<String>> = emptyList(),
)

/**
 * Deterministic, Android-free selection policy for the home recommendation sections.
 *
 * This is a pure function of its arguments: no mutable state, no clock, no I/O, no Android types.
 * It never throws for hostile input (non-positive or huge counts, blank uploaders, duplicate keys)
 * and only looks at [Track.key], [Track.uploader] and [Track.uploaderId]; it does not parse titles.
 *
 * Priority, strongest first:
 * 1. **Unseen key**: a key that was neither played nor shown by the newest [RecentBatches] batches
 *    always outranks a key seen in either place. Seen keys are still available as a last-resort
 *    fallback so a short fresh pool never leaves the section empty.
 * 2. **Personalization**: at equal key freshness, creators in
 *    [RecommendationSignals.likedCreators] are preferred. This is a bonus, never a filter.
 * 3. **Repetition avoidance**: at equal key freshness, creators in
 *    [RecommendationSignals.playedCreators] are lightly down-weighted, so a batch does not keep
 *    returning the creator the user just heard.
 * 4. **Rotation**: [seed] only reorders candidates whose priority is identical. A new refresh can
 *    therefore surface a different set, while the same input and seed always give the exact same
 *    result.
 *
 * Within one batch every key is unique and each creator appears at most once. A creator only gets
 * a second song after the candidate creators can no longer fill the requested count, and repeated
 * creators are spread out instead of stacked next to each other.
 */
object RecommendationPolicy {
    /** How many newest entries of [RecommendationSignals.recentBatches] count as recent. */
    const val RecentBatches = 3

    // Key freshness must dominate every creator signal. The creator terms are intentionally small
    // so personalization/repetition can only reorder candidates of the same freshness.
    private const val UnseenKeyBonus = 1_000
    private const val LikedCreatorBonus = 30
    private const val PlayedCreatorPenalty = 20

    /**
     * Canonical creator identity: a real uid wins, otherwise the display name normalized to lower
     * case with whitespace removed. Never throws, so blank uploaders still group consistently.
     */
    fun creator(track: Track): String =
        if (track.uploaderId > 0) "uid:${track.uploaderId}"
        else "name:" + track.uploader.lowercase().filterNot { it.isWhitespace() }

    /**
     * Picks up to [count] tracks for a single recommendation section.
     *
     * Candidates are de-duplicated by [Track.key] first. The result has no duplicate key, uses at
     * most one song per creator while enough creators exist, prefers keys the user has not seen
     * recently, prefers liked creators at equal freshness, and falls back to played/recently shown
     * keys only when the fresh pool cannot fill [count]. Returns an empty list when [count] is not
     * positive or there is no candidate. The input is never modified.
     */
    fun pick(
        candidates: List<Track>,
        count: Int,
        signals: RecommendationSignals = RecommendationSignals(),
        seed: Int = 0,
    ): List<Track> = recommend(candidates, count, signals, seed, emptySet())

    /**
     * Same rules as [pick], for "more recommendations" pages or whole segments.
     *
     * Keys in [exclude] are a hard filter (typically songs already shown by the page or section),
     * so paging never duplicates them; they are not used even as a fallback. Keys from the newest
     * [RecommendationSignals.recentBatches] batches are only a soft fallback: they are used after
     * the fresh candidates, and only when [count] cannot otherwise be filled. Returns an empty
     * list when nothing outside [exclude] remains. The input is never modified.
     */
    fun section(
        candidates: List<Track>,
        count: Int,
        signals: RecommendationSignals = RecommendationSignals(),
        seed: Int = 0,
        exclude: Set<String> = emptySet(),
    ): List<Track> = recommend(candidates, count, signals, seed, exclude)

    /**
     * Same guarantees as [pick], but equal-priority candidates keep the caller's order instead of
     * the seeded rotation. Used for sections with an authoritative provider ordering (popularity,
     * relevance or "more" feed order) that must be diversified without being shuffled.
     */
    fun ranked(
        candidates: List<Track>,
        count: Int,
        signals: RecommendationSignals = RecommendationSignals(),
        seed: Int = 0,
        exclude: Set<String> = emptySet(),
    ): List<Track> = recommend(candidates, count, signals, seed, exclude, keepOrder = true)

    private fun recommend(
        candidates: List<Track>,
        count: Int,
        signals: RecommendationSignals,
        seed: Int,
        exclude: Set<String>,
        keepOrder: Boolean = false,
    ): List<Track> {
        if (count <= 0 || candidates.isEmpty()) return emptyList()
        val unique = ArrayList<Track>(candidates.size)
        val order = HashMap<String, Int>(candidates.size)
        val seen = HashSet<String>(candidates.size)
        for (track in candidates) {
            if (track.key in exclude) continue
            if (seen.add(track.key)) {
                order[track.key] = unique.size
                unique.add(track)
            }
        }
        if (unique.isEmpty()) return emptyList()
        val recent = recentKeys(signals.recentBatches)
        val ranked =
            unique.sortedWith(
                compareByDescending<Track> { priority(it, signals, recent) }
                    .thenBy {
                        if (keepOrder) order[it.key] ?: Int.MAX_VALUE
                        else rotation(it.key, seed)
                    }
                    .thenBy { it.key }
            )
        return fill(ranked, count)
    }

    private fun priority(
        track: Track,
        signals: RecommendationSignals,
        recentKeys: Set<String>,
    ): Int {
        var priority = 0
        if (track.key !in signals.playedKeys && track.key !in recentKeys) priority += UnseenKeyBonus
        val identity = creator(track)
        if (identity in signals.likedCreators) priority += LikedCreatorBonus
        if (identity in signals.playedCreators) priority -= PlayedCreatorPenalty
        return priority
    }

    /** Union of the newest [RecentBatches] batches only; builds a fresh set and never mutates input. */
    private fun recentKeys(batches: List<Set<String>>): Set<String> {
        if (batches.isEmpty()) return emptySet()
        val recent = HashSet<String>()
        var considered = 0
        for (batch in batches) {
            if (considered == RecentBatches) break
            recent.addAll(batch)
            considered++
        }
        return recent
    }

    /**
     * Greedy creator-capped fill. Each pass takes at most one more song per creator, so a full
     * round of creators is used before anyone repeats; the cap grows only when a pass adds nothing.
     */
    private fun fill(ranked: List<Track>, count: Int): List<Track> {
        val wanted = minOf(count, ranked.size)
        if (wanted <= 0) return emptyList()
        val rounds = ArrayList<List<Track>>()
        val pickedKeys = HashSet<String>()
        val creatorSongs = HashMap<String, Int>()
        var picked = 0
        var perCreator = 1
        while (picked < wanted) {
            val round = ArrayList<Track>()
            for (track in ranked) {
                if (picked + round.size >= wanted) break
                if (track.key in pickedKeys) continue
                val identity = creator(track)
                if ((creatorSongs[identity] ?: 0) >= perCreator) continue
                round.add(track)
                pickedKeys.add(track.key)
                creatorSongs[identity] = (creatorSongs[identity] ?: 0) + 1
            }
            if (round.isEmpty()) {
                if (perCreator >= ranked.size) break
                perCreator++
            } else {
                rounds.add(round)
                picked += round.size
            }
        }
        return spread(rounds)
    }

    /**
     * Concatenates the creator-capped rounds (every round holds at most one song per creator) and
     * rotates a round when its first creator would otherwise sit next to the same creator that
     * ended the previous round.
     */
    private fun spread(rounds: List<List<Track>>): List<Track> {
        val result = ArrayList<Track>()
        for (round in rounds) {
            if (round.isEmpty()) continue
            val ordered =
                if (result.isEmpty()) round
                else {
                    val previous = creator(result.last())
                    val start = round.indexOfFirst { creator(it) != previous }
                    if (start <= 0) round
                    else round.subList(start, round.size) + round.subList(0, start)
                }
            result.addAll(ordered)
        }
        return result
    }

    /**
     * Stable seeding: only breaks priority ties. Integer overflow is intentional and wraps the
     * same way on every JVM, so identical input and seed always produce identical output.
     */
    private fun rotation(key: String, seed: Int): Int {
        var hash = key.hashCode() * 31 + seed
        hash = hash xor (hash ushr 16)
        hash *= 0x85ebca6bL.toInt()
        hash = hash xor (hash ushr 13)
        hash *= 0xc2b2ae35L.toInt()
        hash = hash xor (hash ushr 16)
        return hash
    }
}
