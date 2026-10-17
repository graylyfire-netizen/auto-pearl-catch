package me.alpha432.oyvey.features.modules.combat;

import me.alpha432.oyvey.OyVey;
import me.alpha432.oyvey.event.Stage;
import me.alpha432.oyvey.event.impl.entity.player.UpdateWalkingPlayerEvent;
import me.alpha432.oyvey.event.system.Subscribe;
import me.alpha432.oyvey.features.modules.Module;
import me.alpha432.oyvey.features.settings.Setting;
import me.alpha432.oyvey.util.inventory.InventoryUtil;
import me.alpha432.oyvey.util.inventory.Result;
import me.alpha432.oyvey.util.prediction.InterceptSolver;
import me.alpha432.oyvey.util.prediction.InterceptSolver.Burst;
import me.alpha432.oyvey.util.prediction.InterceptSolver.Charge;
import me.alpha432.oyvey.util.prediction.InterceptSolver.Pick;
import me.alpha432.oyvey.util.prediction.InterceptSolver.Request;
import me.alpha432.oyvey.util.prediction.InterceptSolver.Solution;
import me.alpha432.oyvey.util.prediction.InterceptSolver.V3;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static me.alpha432.oyvey.util.inventory.InventoryUtil.FULL_SCOPE;
import static me.alpha432.oyvey.util.inventory.InventoryUtil.HOTBAR_SCOPE;

/**
 * Throws a wind charge so that it meets an ender pearl that is already in flight.
 *
 * <p>All maths lives in {@link InterceptSolver}; this class reads game state, calls the solver and
 * schedules the throw.
 *
 * <h3>Why the throw runs at the movement-PRE stage</h3>
 *
 * <p>A use-item packet carries only the hand — the server spawns the wind charge along the last
 * player rotation it received. Vanilla sends rotation packets from {@code sendPosition()}, which
 * runs <i>after</i> the {@code UpdateWalkingPlayerEvent}'s PRE stage inside the client tick. So the
 * throw is scheduled at the end of one tick ({@link #onTick()}) and fired at the <b>next</b> tick's
 * PRE stage: the rotation set there is carried in the very next movement packet, and the base
 * rotation manager restores the camera right afterwards (POST stage), so the throw stays silent.
 * Firing at the end of the tick instead would have the rotation restored before any packet left,
 * and the charge would fly along the camera rather than the solved aim - which only ever "worked"
 * when the camera happened to point near the intercept.
 *
 * <p>{@link #THROW_DELAY_TICKS} absorbs the one-tick scheduling delay so the solver's assumed
 * charge spawn time stays correct.
 */
public class PearlCatchModule extends Module {
    /** Vanilla: wind charge burst radius used for the knockback falloff. */
    private static final float DEFAULT_BURST_RADIUS = 1.2f;

    /** Detection happens one tick before the throw (movement PRE of the next tick). */
    private static final int THROW_DELAY_TICKS = 1;

    /** How long (client ticks) an entity's position is kept for velocity estimation. */
    private static final int TRACK_TTL_TICKS = 64;

    private final Setting<Float> range = num("Range", 48.0f, 8.0f, 96.0f);
    private final Setting<Integer> window = num("Window", 24, 4, 60);
    private final Setting<Float> hitRadius = num("HitRadius", 0.55f, 0.2f, 1.5f);
    private final Setting<Pick> pick = mode("Pick", Pick.CLOSEST);
    private final Setting<Boolean> ownPearls = bool("OwnPearls", true);
    private final Setting<Boolean> autoLatency = bool("AutoLatency", true);
    private final Setting<Integer> extraTicks = num("ExtraTicks", 1, 0, 10);
    private final Setting<Boolean> windBursts = bool("WindBursts", true);
    private final Setting<Float> burstBoost = num("BurstBoost", 1.22f, 0.0f, 4.0f);
    private final Setting<Float> burstRadius = num("BurstRadius", DEFAULT_BURST_RADIUS, 0.5f, 3.0f);
    private final Setting<Boolean> lineOfSight = bool("LineOfSight", true);
    private final Setting<Integer> cooldown = num("Cooldown", 10, 0, 40);
    private final Setting<Boolean> inventory = bool("Inventory", false);
    private final Setting<Boolean> silent = bool("Silent", true);
    private final Setting<Boolean> debug = bool("Debug", false);

    /** pearl entity id -> client tick until which it is considered handled. */
    private final Map<Integer, Long> handled = new HashMap<>();
    /** entity id -> last scan tick and position, for observed per-tick velocity. */
    private final Map<Integer, Track> tracks = new HashMap<>();

    private record Track(long tick, V3 pos) {
    }

    private long clock;
    private int cooldownLeft;
    private String lastInfo;

    /** Throw scheduled by onTick, executed at the next movement PRE. */
    private Result pendingCharge;
    private Solution pendingSolution;
    private Entity pendingPearl;

    public PearlCatchModule() {
        super("PearlCatch", "Throws a wind charge to intercept ender pearls in flight.", Category.COMBAT);
    }

    @Override
    public void onEnable() {
        handled.clear();
        tracks.clear();
        clearPending();
        cooldownLeft = 0;
        lastInfo = null;
    }

    @Override
    public String getDisplayInfo() {
        return lastInfo;
    }

    @Override
    public void onTick() {
        if (nullCheck() || mc.gameMode == null) return;

        clock++;
        handled.values().removeIf(expiry -> expiry < clock);
        tracks.entrySet().removeIf(entry -> entry.getValue().tick() < clock - TRACK_TTL_TICKS);

        // scan and refresh observations even while cooling down, so velocities stay current
        List<Entity> pearls = collectPearls();
        List<Charge> inFlight = windBursts.getValue() ? collectCharges() : List.of();

        if (cooldownLeft > 0) {
            cooldownLeft--;
            return;
        }
        if (mc.screen != null || mc.player.isUsingItem() || pendingSolution != null) return;

        Result charge = InventoryUtil.find(Items.WIND_CHARGE, inventory.getValue() ? FULL_SCOPE : HOTBAR_SCOPE);
        if (!charge.found() || pearls.isEmpty()) return;

        int lead = extraTicks.getValue() + (autoLatency.getValue() ? pingTicks() : 0) + THROW_DELAY_TICKS;

        Entity bestPearl = null;
        Solution best = null;
        for (Entity pearl : pearls) {
            Optional<Solution> solution = solveFor(pearl, estimateVelocity(pearl), inFlight, lead);
            if (solution.isEmpty()) continue;
            // most imminent intercept first
            if (best == null || solution.get().arrivalTick() < best.arrivalTick()) {
                best = solution.get();
                bestPearl = pearl;
            }
        }
        if (best == null) return;

        scheduleFire(charge, bestPearl, best);
        handled.put(bestPearl.getId(), clock + window.getValue() + THROW_DELAY_TICKS);
        cooldownLeft = cooldown.getValue();
        lastInfo = "t+" + (best.arrivalTick() + THROW_DELAY_TICKS);

        if (debug.getValue()) {
            OyVey.LOGGER.info("[PearlCatch] scheduled pearl #{} -> yaw {} pitch {} | arrive t+{} (flight {}t, contact {}t) miss {} blocks | lead {}t | bursts {}",
                    bestPearl.getId(), fmt(best.yaw()), fmt(best.pitch()), best.arrivalTick() + THROW_DELAY_TICKS,
                    best.flightTicks(), fmt(best.contactTime()), fmt(best.miss()), lead, inFlight.size());
        }
    }

    /**
     * The scheduled throw executes here, in the movement PRE stage: whatever rotation is set now
     * travels in the movement packet that vanilla sends right after this point, so the server
     * spawns the wind charge along the solved aim. Camera restore happens at the POST stage
     * (handled by the base rotation manager), which keeps the throw silent.
     */
    @Subscribe
    public void onUpdateWalkingPlayerEvent(UpdateWalkingPlayerEvent event) {
        if (event.getStage() != Stage.PRE || pendingSolution == null) return;
        if (nullCheck() || mc.gameMode == null || mc.screen != null || mc.player.isUsingItem()) {
            clearPending();
            return;
        }

        // re-validate the charge at the moment of the throw (slots may have changed since the scan)
        Result charge = InventoryUtil.find(Items.WIND_CHARGE, inventory.getValue() ? FULL_SCOPE : HOTBAR_SCOPE);
        Solution solution = pendingSolution;
        Entity pearl = pendingPearl;
        clearPending();
        if (!charge.found()) {
            if (pearl != null) handled.remove(pearl.getId());
            if (debug.getValue()) OyVey.LOGGER.info("[PearlCatch] cancelled thrown: no wind charge at throw time");
            return;
        }

        OyVey.rotationManager.setPlayerRotations(solution.yaw(), solution.pitch());
        InventoryUtil.withSwap(charge, () -> mc.gameMode.useItem(mc.player, charge.hand()));
        // no local restore here: the base rotates your camera back after the packet is sent

        if (debug.getValue()) {
            OyVey.LOGGER.info("[PearlCatch] threw wind charge at pearl #{}: yaw {} pitch {} (server sees this rotation)",
                    pearl == null ? -1 : pearl.getId(), fmt(solution.yaw()), fmt(solution.pitch()));
        }
    }

    private void scheduleFire(Result charge, Entity pearl, Solution solution) {
        pendingCharge = charge;
        pendingPearl = pearl;
        pendingSolution = solution;
    }

    private void clearPending() {
        pendingCharge = null;
        pendingPearl = null;
        pendingSolution = null;
    }

    private List<Entity> collectPearls() {
        List<Entity> out = new ArrayList<>();
        List<Entity> found = mc.level.getEntities((Entity) null,
                mc.player.getBoundingBox().inflate(range.getValue()), this::isTargetPearl);
        for (Entity pearl : found) {
            track(pearl);
            out.add(pearl);
        }
        return out;
    }

    private List<Charge> collectCharges() {
        List<Charge> out = new ArrayList<>();
        List<Entity> charges = mc.level.getEntities((Entity) null,
                mc.player.getBoundingBox().inflate(range.getValue()),
                e -> e.getType() == EntityType.WIND_CHARGE && e.isAlive());
        for (Entity e : charges) {
            track(e);
            out.add(new Charge(v3(e.position()), estimateVelocity(e)));
        }
        return out;
    }

    private boolean isTargetPearl(Entity entity) {
        if (entity.getType() != EntityType.ENDER_PEARL || !entity.isAlive()) return false;
        if (handled.containsKey(entity.getId())) return false;
        if (!ownPearls.getValue() && entity instanceof Projectile projectile && projectile.getOwner() == mc.player)
            return false;
        // a pearl that has effectively stopped is not going to be intercepted mid-air
        // (length^2 > 1.0E-3 is the same as length > 0.0316 blocks per tick)
        return estimateVelocity(entity).length() > 3.17E-2;
    }

    private Optional<Solution> solveFor(Entity pearl, V3 pearlVel, List<Charge> inFlight, int lead) {
        V3 pearlPos = v3(pearl.position());
        int win = window.getValue();

        List<Burst> bursts = inFlight.isEmpty()
                ? List.of()
                : InterceptSolver.deriveBursts(pearlPos, pearlVel, inFlight, win, hitRadius.getValue(),
                burstRadius.getValue(), burstBoost.getValue());

        InterceptSolver.Clearance clearance = lineOfSight.getValue()
                ? (from, to) -> mc.level.clip(new ClipContext(vec(from), vec(to),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player)).getType() == HitResult.Type.MISS
                : InterceptSolver.Clearance.ALWAYS;

        Request request = new Request(
                pearlPos, pearlVel,
                v3(mc.player.getEyePosition()), v3(mc.player.getDeltaMovement()), mc.player.onGround(),
                lead, win, hitRadius.getValue(), pick.getValue(), bursts, clearance);
        return InterceptSolver.solve(request);
    }

    /**
     * Observed per-tick velocity: the client does not always keep a trustworthy
     * {@code getDeltaMovement()} on remote projectiles, so when a previous scan exists the
     * measured displacement per client tick is used instead. A fresh entity falls back to the
     * vanilla-reported movement.
     */
    private V3 estimateVelocity(Entity entity) {
        Track track = tracks.get(entity.getId());
        if (track == null || track.tick() >= clock) return v3(entity.getDeltaMovement());
        double dt = clock - track.tick();
        return v3(entity.position()).sub(track.pos()).scale(1.0 / dt);
    }

    private void track(Entity entity) {
        tracks.put(entity.getId(), new Track(clock, v3(entity.position())));
    }

    /** Round-trip latency in whole ticks (50 ms each). */
    private int pingTicks() {
        ClientPacketListener connection = mc.getConnection();
        if (connection == null) return 0;
        PlayerInfo info = connection.getPlayerInfo(mc.player.getUUID());
        return info == null ? 0 : Math.max(0, Math.round(info.getLatency() / 50.0f));
    }

    private static V3 v3(Vec3 v) {
        return new V3(v.x, v.y, v.z);
    }

    private static Vec3 vec(V3 v) {
        return new Vec3(v.x(), v.y(), v.z());
    }

    private static String fmt(double value) {
        return String.format("%.2f", value);
    }
}
