package com.dex.boxkoth.session;

import com.dex.boxkoth.config.PluginConfig.SelectionMode;
import com.dex.boxkoth.zone.Zone;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * State of one running KOTH event (§4.3). Main thread only, except {@link #state()} which is atomic.
 * Contains no Bukkit calls: the caller supplies the eligible set every tick.
 * <p>
 * The session length is real time (a monotonic clock), so a TPS drop does not stretch the event past its
 * schedule. Capture time is still counted in server ticks: a lag spike slows every clan equally.
 */
public final class KothSession {

    public enum State {RUNNING, ENDING, ENDED}

    /** Key for per-player time: time only counts for the clan it was earned for. */
    public record PlayerClan(UUID player, String clanId) {
    }

    /** Monotonic milliseconds: not affected by system clock changes (NTP, manual). */
    public static final LongSupplier MONOTONIC_MILLIS = () -> System.nanoTime() / 1_000_000L;

    private static final long MILLIS_PER_TICK = 50L;

    private final Zone zone;
    private final long durationMillis;
    private final LongSupplier clock;
    private final long endsAt;
    private final SelectionMode selectionMode;
    private final Random random;
    private final AtomicReference<State> state = new AtomicReference<>(State.RUNNING);

    private UUID controller;
    private String controllerClan;
    private PlayerClan controllerKey;
    private final LinkedHashSet<UUID> insideOrder = new LinkedHashSet<>();
    private final Map<String, Long> clanTicks = new HashMap<>();
    private final Map<String, Long> clanLastIncrementTick = new HashMap<>();
    private final Map<PlayerClan, Long> playerTicks = new HashMap<>();
    private final Map<PlayerClan, Long> playerLastIncrementTick = new HashMap<>();
    private final Set<PlayerClan> joinedDuringSession = new HashSet<>();
    private long elapsedTicks;
    /** Remaining whole seconds at the last display refresh, see {@link #secondChanged()}. */
    private long shownSecond = -1;

    private int consecutiveFailures;
    private boolean failureLogged;

    /**
     * @param durationMillis real-time length of the session
     * @param clock          milliseconds from a monotonic source ({@link #MONOTONIC_MILLIS}; a fake one in tests)
     */
    public KothSession(Zone zone, long durationMillis, SelectionMode selectionMode, Random random, LongSupplier clock) {
        this.zone = zone;
        this.durationMillis = durationMillis;
        this.clock = clock;
        this.endsAt = clock.getAsLong() + durationMillis;
        this.selectionMode = selectionMode;
        this.random = random;
    }

    /**
     * Runs one tick of §4.3.
     *
     * @param eligible eligible players inside the zone mapped to their current clan id, in iteration order
     * @return true if the controller changed during this tick
     */
    public boolean tick(Map<UUID, String> eligible) {
        UUID before = controller;
        String clanBefore = controllerClan;

        // 1. Maintain entry order.
        insideOrder.removeIf(uuid -> !eligible.containsKey(uuid));
        insideOrder.addAll(eligible.keySet());

        // 2. Validate controller.
        boolean lostThisTick = false;
        if (controller != null) {
            String clanNow = eligible.get(controller);
            if (clanNow == null || !clanNow.equals(controllerClan)) {
                clearController();
                lostThisTick = true;
            }
        }

        // 3. Select controller.
        if (controller == null && !eligible.isEmpty()) {
            UUID chosen;
            if (lostThisTick && selectionMode == SelectionMode.RANDOM) {
                chosen = randomElement(eligible.keySet());
            } else {
                chosen = insideOrder.iterator().next();
            }
            controller = chosen;
            controllerClan = eligible.get(chosen);
            controllerKey = new PlayerClan(chosen, controllerClan);
        }

        // 4. Accumulate.
        if (controller != null) {
            clanTicks.merge(controllerClan, 1L, Long::sum);
            clanLastIncrementTick.put(controllerClan, elapsedTicks);
            playerTicks.merge(controllerKey, 1L, Long::sum);
            playerLastIncrementTick.put(controllerKey, elapsedTicks);
        }

        // 5. Advance time.
        elapsedTicks++;
        return !Objects.equals(before, controller) || !Objects.equals(clanBefore, controllerClan);
    }

    private UUID randomElement(Set<UUID> set) {
        int index = random.nextInt(set.size());
        Iterator<UUID> it = set.iterator();
        for (int i = 0; i < index; i++) {
            it.next();
        }
        return it.next();
    }

    private void clearController() {
        controller = null;
        controllerClan = null;
        controllerKey = null;
    }

    /** Real time is up (checked after each tick, so the end lands on the first tick past the deadline). */
    public boolean isExpired() {
        return clock.getAsLong() >= endsAt;
    }

    /**
     * True once per change of the remaining whole seconds: the capture task refreshes displays then, so the
     * countdown moves with real time even when the server runs below 20 TPS.
     */
    public boolean secondChanged() {
        long seconds = (remainingMillis() + 999) / 1000;
        if (seconds == shownSecond) return false;
        shownSecond = seconds;
        return true;
    }

    /** RUNNING -> ENDING compare-and-set. Only the caller that wins may run the end routine (§16.3). */
    public boolean beginEnding() {
        return state.compareAndSet(State.RUNNING, State.ENDING);
    }

    /** Frees all per-session maps. */
    public void finish() {
        state.set(State.ENDED);
        clearController();
        insideOrder.clear();
        clanTicks.clear();
        clanLastIncrementTick.clear();
        playerTicks.clear();
        playerLastIncrementTick.clear();
        joinedDuringSession.clear();
    }

    /** Drops a disbanded clan from the ranking (§16.4). */
    public void removeClan(String clanId) {
        clanTicks.remove(clanId);
        clanLastIncrementTick.remove(clanId);
        playerTicks.keySet().removeIf(k -> k.clanId().equals(clanId));
        playerLastIncrementTick.keySet().removeIf(k -> k.clanId().equals(clanId));
    }

    public void recordJoin(UUID player, String clanId) {
        joinedDuringSession.add(new PlayerClan(player, clanId));
    }

    public boolean joinedDuringSession(UUID player, String clanId) {
        return joinedDuringSession.contains(new PlayerClan(player, clanId));
    }

    /** Clan ranking, best first. */
    public List<Ranking.Entry<String>> ranking() {
        return Ranking.order(clanTicks, clanLastIncrementTick);
    }

    /** Players who earned time for {@code clanId}, best first (MVP candidates, §6.1). */
    public List<Ranking.Entry<UUID>> playerRanking(String clanId) {
        Map<UUID, Long> ticks = new HashMap<>();
        Map<UUID, Long> last = new HashMap<>();
        for (Map.Entry<PlayerClan, Long> e : playerTicks.entrySet()) {
            if (e.getKey().clanId().equals(clanId)) {
                ticks.put(e.getKey().player(), e.getValue());
                last.put(e.getKey().player(), playerLastIncrementTick.get(e.getKey()));
            }
        }
        return Ranking.order(ticks, last);
    }

    public long playerTicks(UUID player, String clanId) {
        return playerTicks.getOrDefault(new PlayerClan(player, clanId), 0L);
    }

    public long clanTicks(String clanId) {
        return clanTicks.getOrDefault(clanId, 0L);
    }

    public Map<String, Long> clanTicksCopy() {
        return Map.copyOf(clanTicks);
    }

    public List<UUID> insideOrder() {
        return new ArrayList<>(insideOrder);
    }

    /** @return true when this failure crosses the given limit. */
    public boolean recordFailure(int limit) {
        return ++consecutiveFailures >= limit;
    }

    public void recordSuccess() {
        consecutiveFailures = 0;
    }

    /** @return true only the first time, so the stack trace is logged once. */
    public boolean markFailureLogged() {
        if (failureLogged) return false;
        failureLogged = true;
        return true;
    }

    public Zone zone() {
        return zone;
    }

    public State state() {
        return state.get();
    }

    public boolean isRunning() {
        return state.get() == State.RUNNING;
    }

    public UUID controller() {
        return controller;
    }

    public String controllerClan() {
        return controllerClan;
    }

    public long elapsedTicks() {
        return elapsedTicks;
    }

    /** Session length in 50 ms units (real time, not server ticks), for the tick-based display formatters. */
    public long durationTicks() {
        return durationMillis / MILLIS_PER_TICK;
    }

    /** Real time left in 50 ms units, rounded up; 0 once expired. */
    public long remainingTicks() {
        return (remainingMillis() + MILLIS_PER_TICK - 1) / MILLIS_PER_TICK;
    }

    private long remainingMillis() {
        return Math.max(0L, endsAt - clock.getAsLong());
    }
}
