package me.alpha432.oyvey.features.modules.combat;

import me.alpha432.oyvey.OyVey;
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
 * <p>All maths lives in {@link InterceptSolver}; this class only reads game state, calls the solver and performs
 * the throw. The throw uses the base's existing rotation pattern: set the player's rotation, use the item (the
 * use-item packet carries the current yaw/pitch), then put the rotation back, all inside one client tick so the
 * camera never visibly turns.
 */
public class PearlCatchModule extends Module {
    /** Vanilla: wind charge burst radius used for the knockback falloff. */
    private static final float DEFAULT_BURST_RADIUS = 1.2f;

    private final Setting<Float> range = num("Range", 48.0f, 8.0f, 96.0f);
    private final Setting<Integer> window = num("Window", 24, 4, 60);
    private final Setting<Float> hitRadius = num("HitRadius", 0.55f, 0.2f, 1.5f);
    private final Setting<Pick> pick = mode("Pick", Pick.CLOSEST);
    private final Setting<Boolean> ownPearls = bool("OwnPearls", false);
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
    private long clock;
    private int cooldownLeft;
    private String lastInfo;

    public PearlCatchModule() {
        super("PearlCatch", "Throws a wind charge to intercept ender pearls in flight.", Category.COMBAT);
    }

    @Override
    public void onEnable() {
        handled.clear();
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
        if (cooldownLeft > 0) {
            cooldownLeft--;
            return;
        }
        if (mc.screen != null || mc.player.isUsingItem()) return;

        Result charge = InventoryUtil.find(Items.WIND_CHARGE, inventory.getValue() ? FULL_SCOPE : HOTBAR_SCOPE);
        if (!charge.found()) return;

        List<Entity> pearls = mc.level.getEntities((Entity) null,
                mc.player.getBoundingBox().inflate(range.getValue()), this::isTargetPearl);
        if (pearls.isEmpty()) return;

        List<Charge> inFlight = windBursts.getValue() ? collectCharges() : List.of();
        int lead = extraTicks.getValue() + (autoLatency.getValue() ? pingTicks() : 0);

        Entity bestPearl = null;
        Solution best = null;
        for (Entity pearl : pearls) {
            Optional<Solution> solution = solveFor(pearl, inFlight, lead);
            if (solution.isEmpty()) continue;
            // most imminent intercept first
            if (best == null || solution.get().arrivalTick() < best.arrivalTick()) {
                best = solution.get();
                bestPearl = pearl;
            }
        }
        if (best == null) return;

        fire(charge, best);
        handled.put(bestPearl.getId(), clock + window.getValue());
        cooldownLeft = cooldown.getValue();
        lastInfo = "t+" + best.arrivalTick();

        if (debug.getValue()) {
            OyVey.LOGGER.info("[PearlCatch] pearl #{} -> yaw {} pitch {} | arrive t+{} (flight {}t, contact {}t) miss {} blocks | lead {}t | bursts {}",
                    bestPearl.getId(), fmt(best.yaw()), fmt(best.pitch()), best.arrivalTick(), best.flightTicks(),
                    fmt(best.contactTime()), fmt(best.miss()), lead, inFlight.size());
        }
    }

    private Optional<Solution> solveFor(Entity pearl, List<Charge> inFlight, int lead) {
        V3 pearlPos = v3(pearl.position());
        V3 pearlVel = v3(pearl.getDeltaMovement());
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

    private boolean isTargetPearl(Entity entity) {
        if (entity.getType() != EntityType.ENDER_PEARL || !entity.isAlive()) return false;
        if (handled.containsKey(entity.getId())) return false;
        if (!ownPearls.getValue() && entity instanceof Projectile projectile && projectile.getOwner() == mc.player)
            return false;
        // a pearl that has effectively stopped is not going to be intercepted mid-air
        return entity.getDeltaMovement().lengthSqr() > 1.0E-3;
    }

    private List<Charge> collectCharges() {
        List<Charge> out = new ArrayList<>();
        List<Entity> charges = mc.level.getEntities((Entity) null,
                mc.player.getBoundingBox().inflate(range.getValue()),
                e -> e.getType() == EntityType.WIND_CHARGE && e.isAlive());
        for (Entity e : charges) {
            out.add(new Charge(v3(e.position()), v3(e.getDeltaMovement())));
        }
        return out;
    }

    /** Silent throw: rotate, use, rotate back, all within this tick. */
    private void fire(Result charge, Solution solution) {
        float prevYaw = mc.player.getYRot();
        float prevPitch = mc.player.getXRot();
        float prevHead = mc.player.yHeadRot;

        OyVey.rotationManager.setPlayerRotations(solution.yaw(), solution.pitch());
        InventoryUtil.withSwap(charge, () -> mc.gameMode.useItem(mc.player, charge.hand()));
        if (silent.getValue()) {
            mc.player.setYRot(prevYaw);
            mc.player.setXRot(prevPitch);
            mc.player.yHeadRot = prevHead;
        }
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
