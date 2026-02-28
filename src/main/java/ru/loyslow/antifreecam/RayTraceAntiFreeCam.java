package ru.loyslow.antifreecam;

import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class RayTraceAntiFreeCam extends JavaPlugin implements Listener {

    private static final String BYPASS_PERMISSION = "raytraceantifreecam.bypass";

    private double maxReachCap;
    private double extraReach;
    private long cacheTtlMs;
    private boolean checkInteract;
    private boolean checkBreak;

    private final Map<UUID, CachedDecision> cache = new HashMap<>();

    private static final class CachedDecision {
        UUID worldId;
        long packedPos;
        long expiresAt;
        boolean allowed;
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        maxReachCap = getConfig().getDouble("max-reach", 6.0);
        extraReach = getConfig().getDouble("extra-reach", 0.75);
        cacheTtlMs = getConfig().getLong("cache-ttl-ms", 175);
        checkInteract = getConfig().getBoolean("check.interact", true);
        checkBreak = getConfig().getBoolean("check.break", true);

        getServer().getPluginManager().registerEvents(this, this);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (!checkInteract) return;
        if (event.getHand() != null && event.getHand() != EquipmentSlot.HAND) return;

        Block clicked = event.getClickedBlock();
        if (clicked == null) return;

        switch (event.getAction()) {
            case RIGHT_CLICK_BLOCK, LEFT_CLICK_BLOCK -> {
                Player player = event.getPlayer();

                Location ipLoc = event.getInteractionPoint();
                Vector interactionPoint = (ipLoc == null) ? null : ipLoc.toVector();

                boolean allowed = canSeeBlock(player, clicked, interactionPoint, event.getBlockFace(), true);

                putCache(player, clicked, allowed);

                if (!allowed) event.setCancelled(true);
            }
            default -> {}
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(BlockDamageEvent event) {
        if (!checkBreak) return;

        Player player = event.getPlayer();
        Block block = event.getBlock();

        Boolean cached = getCached(player, block);
        if (cached != null) {
            if (!cached) event.setCancelled(true);
            return;
        }

        boolean allowed = canSeeBlock(player, block, null, null, false);
        putCache(player, block, allowed);

        if (!allowed) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!checkBreak) return;

        Player player = event.getPlayer();
        Block block = event.getBlock();

        Boolean cached = getCached(player, block);
        if (cached != null) {
            if (!cached) event.setCancelled(true);
            return;
        }

        boolean allowed = canSeeBlock(player, block, null, null, false);
        putCache(player, block, allowed);

        if (!allowed) event.setCancelled(true);
    }

    private boolean canSeeBlock(Player player, Block target, Vector interactionPoint, org.bukkit.block.BlockFace faceOrNull, boolean fastMode) {
        if (player == null || target == null) return true;

        if (player.hasPermission(BYPASS_PERMISSION)) return true;
        if (player.getGameMode() == GameMode.SPECTATOR) return true;

        World world = player.getWorld();
        if (!world.equals(target.getWorld())) return false;

        double reach = Math.min(maxReachCap, resolveBlockReach(player)) + Math.max(0.0, extraReach);

        Location eyeLoc = player.getEyeLocation();
        Vector eye = eyeLoc.toVector();

        double minDistSq = distanceSqPointToBlockAabb(eye, target.getX(), target.getY(), target.getZ());
        if (minDistSq > reach * reach) return false;

        if (isPointInsideBlockAabb(eye, target.getX(), target.getY(), target.getZ())) return true;

        if (interactionPoint != null) {
            Vector p = clampIntoBlock(interactionPoint.clone(), target.getX(), target.getY(), target.getZ());
            if (lineClear(world, eyeLoc, eye, p, target)) return true;
        }

        if (fastMode && faceOrNull != null) {
            if (faceGridVisible(world, eyeLoc, eye, target, faceOrNull)) return true;
        } else {
            for (org.bukkit.block.BlockFace f : ALL_FACES) {
                if (faceGridVisible(world, eyeLoc, eye, target, f)) return true;
            }
        }

        Vector center = new Vector(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);
        return lineClear(world, eyeLoc, eye, center, target);
    }

    private static final org.bukkit.block.BlockFace[] ALL_FACES = new org.bukkit.block.BlockFace[] {
            org.bukkit.block.BlockFace.NORTH,
            org.bukkit.block.BlockFace.SOUTH,
            org.bukkit.block.BlockFace.WEST,
            org.bukkit.block.BlockFace.EAST,
            org.bukkit.block.BlockFace.UP,
            org.bukkit.block.BlockFace.DOWN
    };

    private boolean faceGridVisible(World world, Location eyeLoc, Vector eye, Block b, org.bukkit.block.BlockFace face) {
        double[] t = {0.2, 0.5, 0.8};
        double eps = 1.0e-3;

        double bx = b.getX();
        double by = b.getY();
        double bz = b.getZ();

        for (double u : t) {
            for (double v : t) {
                Vector p = switch (face) {
                    case NORTH -> new Vector(bx + u, by + v, bz + eps);
                    case SOUTH -> new Vector(bx + u, by + v, bz + 1.0 - eps);
                    case WEST  -> new Vector(bx + eps, by + v, bz + u);
                    case EAST  -> new Vector(bx + 1.0 - eps, by + v, bz + u);
                    case DOWN  -> new Vector(bx + u, by + eps, bz + v);
                    case UP    -> new Vector(bx + u, by + 1.0 - eps, bz + v);
                    default    -> null;
                };
                if (p != null && lineClear(world, eyeLoc, eye, p, b)) return true;
            }
        }
        return false;
    }

    private boolean lineClear(World world, Location eyeLoc, Vector eye, Vector p, Block target) {
        Vector dir = p.clone().subtract(eye);
        double len = dir.length();
        if (len < 1.0e-4) return true;

        dir.multiply(1.0 / len);

        double startOffset = Math.min(0.10, len * 0.25);
        Location start = eyeLoc.clone().add(dir.getX() * startOffset, dir.getY() * startOffset, dir.getZ() * startOffset);

        double max = len - startOffset - 1.0e-3;
        if (max <= 0.0) return true;

        RayTraceResult hit = world.rayTraceBlocks(
                start,
                dir,
                max,
                FluidCollisionMode.NEVER,
                true
        );

        if (hit == null || hit.getHitBlock() == null) return true;

        return sameBlock(hit.getHitBlock(), target);
    }

    private double resolveBlockReach(Player player) {
        AttributeInstance inst = player.getAttribute(Attribute.BLOCK_INTERACTION_RANGE);
        if (inst != null) return inst.getValue();
        return 5.0;
    }

    private void putCache(Player player, Block block, boolean allowed) {
        CachedDecision d = new CachedDecision();
        d.worldId = block.getWorld().getUID();
        d.packedPos = packBlockPos(block.getX(), block.getY(), block.getZ());
        d.expiresAt = System.currentTimeMillis() + Math.max(0L, cacheTtlMs);
        d.allowed = allowed;
        cache.put(player.getUniqueId(), d);
    }

    private Boolean getCached(Player player, Block block) {
        CachedDecision d = cache.get(player.getUniqueId());
        if (d == null) return null;

        long now = System.currentTimeMillis();
        if (now > d.expiresAt) return null;

        if (!block.getWorld().getUID().equals(d.worldId)) return null;

        long key = packBlockPos(block.getX(), block.getY(), block.getZ());
        if (key != d.packedPos) return null;

        return d.allowed;
    }

    private boolean sameBlock(Block a, Block b) {
        return a.getWorld().equals(b.getWorld())
                && a.getX() == b.getX()
                && a.getY() == b.getY()
                && a.getZ() == b.getZ();
    }

    private static long packBlockPos(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (long) (y & 0xFFF);
    }

    private static double distanceSqPointToBlockAabb(Vector p, int bx, int by, int bz) {
        double x = p.getX(), y = p.getY(), z = p.getZ();

        double dx = 0.0;
        if (x < bx) dx = bx - x;
        else if (x > bx + 1.0) dx = x - (bx + 1.0);

        double dy = 0.0;
        if (y < by) dy = by - y;
        else if (y > by + 1.0) dy = y - (by + 1.0);

        double dz = 0.0;
        if (z < bz) dz = bz - z;
        else if (z > bz + 1.0) dz = z - (bz + 1.0);

        return dx * dx + dy * dy + dz * dz;
    }

    private static boolean isPointInsideBlockAabb(Vector p, int bx, int by, int bz) {
        double x = p.getX(), y = p.getY(), z = p.getZ();
        return x >= bx && x <= bx + 1.0
            && y >= by && y <= by + 1.0
            && z >= bz && z <= bz + 1.0;
    }

    private static Vector clampIntoBlock(Vector p, int bx, int by, int bz) {
        double eps = 1.0e-3;
        double minX = bx + eps, maxX = bx + 1.0 - eps;
        double minY = by + eps, maxY = by + 1.0 - eps;
        double minZ = bz + eps, maxZ = bz + 1.0 - eps;

        p.setX(Math.max(minX, Math.min(maxX, p.getX())));
        p.setY(Math.max(minY, Math.min(maxY, p.getY())));
        p.setZ(Math.max(minZ, Math.min(maxZ, p.getZ())));
        return p;
    }
}