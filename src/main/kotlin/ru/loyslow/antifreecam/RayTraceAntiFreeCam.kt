package ru.loyslow.antifreecam

import org.bukkit.FluidCollisionMode
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.attribute.Attribute
import org.bukkit.event.block.Action
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockDamageEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.util.RayTraceResult
import org.bukkit.util.Vector
import java.util.UUID

class RayTraceAntiFreeCam : JavaPlugin(), Listener {

    private companion object {
        const val BYPASS_PERMISSION = "raytraceantifreecam.bypass"
        private val ALL_FACES = arrayOf(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.WEST, BlockFace.EAST, BlockFace.UP, BlockFace.DOWN)
        private val FACE_SAMPLES = doubleArrayOf(0.2, 0.5, 0.8)
        private const val EPSILON = 1.0e-3
        private const val MIN_RAY_LENGTH = 1.0e-4
    }

    private var maxReachCap = 6.0
    private var extraReach = 0.75
    private var cacheTtlMs = 175L
    private var checkInteract = true
    private var checkBreak = true

    private val cache = HashMap<UUID, CachedDecision>()

    private data class CachedDecision(
        val worldId: UUID,
        val targetPos: Long,
        val playerPos: Long,
        val expiresAt: Long,
        val allowed: Boolean
    )

    override fun onEnable() {
        saveDefaultConfig()
        maxReachCap = config.getDouble("max-reach", 6.0)
        extraReach = config.getDouble("extra-reach", 0.75)
        cacheTtlMs = config.getLong("cache-ttl-ms", 175L).coerceAtLeast(0L)
        checkInteract = config.getBoolean("check.interact", true)
        checkBreak = config.getBoolean("check.break", true)
        server.pluginManager.registerEvents(this, this)
    }

    override fun onDisable() {
        cache.clear()
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEvent) {
        if (!checkInteract) return
        if (event.hand != EquipmentSlot.HAND) return

        val action = event.action
        if (action != Action.RIGHT_CLICK_BLOCK && action != Action.LEFT_CLICK_BLOCK) return

        val clicked = event.clickedBlock ?: return
        val player = event.player
        val interactionPoint = event.interactionPoint?.toVector()
        val allowed = canSeeBlock(player, clicked, interactionPoint, event.blockFace, true)

        putCache(player, clicked, allowed)

        if (!allowed) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDamage(event: BlockDamageEvent) {
        if (!checkBreak) return

        val player = event.player
        val block = event.block
        val cached = getCached(player, block)

        if (cached != null) {
            if (!cached) event.isCancelled = true
            return
        }

        val allowed = canSeeBlock(player, block, null, null, false)
        putCache(player, block, allowed)

        if (!allowed) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        if (!checkBreak) return

        val player = event.player
        val block = event.block
        val cached = getCached(player, block)

        if (cached != null) {
            if (!cached) event.isCancelled = true
            return
        }

        val allowed = canSeeBlock(player, block, null, null, false)
        putCache(player, block, allowed)

        if (!allowed) event.isCancelled = true
    }

    private fun canSeeBlock(player: Player, target: Block, interactionPoint: Vector?, faceOrNull: BlockFace?, fastMode: Boolean): Boolean {
        if (player.hasPermission(BYPASS_PERMISSION)) return true
        if (player.gameMode == GameMode.SPECTATOR) return true

        val world = player.world
        if (world !== target.world) return false

        val reach = minOf(maxReachCap, resolveBlockReach(player)) + maxOf(0.0, extraReach)
        val reachSquared = reach * reach

        val eyeLoc = player.eyeLocation
        val eye = eyeLoc.toVector()

        val minDistSq = distanceSqPointToBlockAabb(eye, target.x, target.y, target.z)
        if (minDistSq > reachSquared) return false

        if (isPointInsideBlockAabb(eye, target.x, target.y, target.z)) return true

        if (interactionPoint != null) {
            val p = clampIntoBlock(interactionPoint.clone(), target.x, target.y, target.z)
            if (lineClear(world, eyeLoc, eye, p, target)) return true
        }

        val center = Vector(target.x + 0.5, target.y + 0.5, target.z + 0.5)
        if (lineClear(world, eyeLoc, eye, center, target)) return true

        if (fastMode && faceOrNull != null) {
            if (faceGridVisible(world, eyeLoc, eye, target, faceOrNull)) return true
        } else {
            for (face in ALL_FACES) {
                if (faceGridVisible(world, eyeLoc, eye, target, face)) return true
            }
        }

        return false
    }

    private fun faceGridVisible(world: World, eyeLoc: Location, eye: Vector, b: Block, face: BlockFace): Boolean {
        val bx = b.x.toDouble()
        val by = b.y.toDouble()
        val bz = b.z.toDouble()

        val centerPoint = when (face) {
            BlockFace.NORTH -> Vector(bx + 0.5, by + 0.5, bz + EPSILON)
            BlockFace.SOUTH -> Vector(bx + 0.5, by + 0.5, bz + 1.0 - EPSILON)
            BlockFace.WEST -> Vector(bx + EPSILON, by + 0.5, bz + 0.5)
            BlockFace.EAST -> Vector(bx + 1.0 - EPSILON, by + 0.5, bz + 0.5)
            BlockFace.DOWN -> Vector(bx + 0.5, by + EPSILON, bz + 0.5)
            BlockFace.UP -> Vector(bx + 0.5, by + 1.0 - EPSILON, bz + 0.5)
            else -> return false
        }

        if (lineClear(world, eyeLoc, eye, centerPoint, b)) return true

        for (u in FACE_SAMPLES) {
            for (v in FACE_SAMPLES) {
                val p: Vector? = when (face) {
                    BlockFace.NORTH -> Vector(bx + u, by + v, bz + EPSILON)
                    BlockFace.SOUTH -> Vector(bx + u, by + v, bz + 1.0 - EPSILON)
                    BlockFace.WEST -> Vector(bx + EPSILON, by + v, bz + u)
                    BlockFace.EAST -> Vector(bx + 1.0 - EPSILON, by + v, bz + u)
                    BlockFace.DOWN -> Vector(bx + u, by + EPSILON, bz + v)
                    BlockFace.UP -> Vector(bx + u, by + 1.0 - EPSILON, bz + v)
                    else -> null
                }

                if (p != null && lineClear(world, eyeLoc, eye, p, b)) return true
            }
        }

        return false
    }

    private fun lineClear(world: World, eyeLoc: Location, eye: Vector, p: Vector, target: Block): Boolean {
        val dir = p.clone().subtract(eye)
        val len = dir.length()

        if (len < MIN_RAY_LENGTH) return true

        dir.multiply(1.0 / len)

        val startOffset = minOf(0.10, len * 0.25)
        val start = eyeLoc.clone().add(dir.x * startOffset, dir.y * startOffset, dir.z * startOffset)
        val maxDistance = len - startOffset - EPSILON

        if (maxDistance <= 0.0) return true

        val hit: RayTraceResult? = world.rayTraceBlocks(start, dir, maxDistance, FluidCollisionMode.NEVER, true)
        val hitBlock = hit?.hitBlock ?: return true

        return sameBlock(hitBlock, target)
    }

    private fun resolveBlockReach(player: Player): Double {
        return player.getAttribute(Attribute.BLOCK_INTERACTION_RANGE)?.value ?: 5.0
    }

    private fun putCache(player: Player, block: Block, allowed: Boolean) {
        val now = System.currentTimeMillis()

        cache[player.uniqueId] = CachedDecision(
            worldId = block.world.uid,
            targetPos = packBlockPos(block.x, block.y, block.z),
            playerPos = packBlockPos(player.location.blockX, player.location.blockY, player.location.blockZ),
            expiresAt = now + cacheTtlMs,
            allowed = allowed
        )
    }

    private fun getCached(player: Player, block: Block): Boolean? {
        val d = cache[player.uniqueId] ?: return null

        if (System.currentTimeMillis() > d.expiresAt) {
            cache.remove(player.uniqueId)
            return null
        }

        if (block.world.uid != d.worldId) return null
        if (packBlockPos(block.x, block.y, block.z) != d.targetPos) return null

        val playerPos = packBlockPos(player.location.blockX, player.location.blockY, player.location.blockZ)
        if (playerPos != d.playerPos) return null

        return d.allowed
    }

    private fun sameBlock(a: Block, b: Block): Boolean =
        a.world === b.world && a.x == b.x && a.y == b.y && a.z == b.z

    private fun packBlockPos(x: Int, y: Int, z: Int): Long =
        ((x.toLong() and 0x3FFFFFF) shl 38) or ((z.toLong() and 0x3FFFFFF) shl 12) or (y.toLong() and 0xFFF)

    private fun distanceSqPointToBlockAabb(p: Vector, bx: Int, by: Int, bz: Int): Double {
        val x = p.x
        val y = p.y
        val z = p.z

        val dx = if (x < bx) bx - x else if (x > bx + 1.0) x - (bx + 1.0) else 0.0
        val dy = if (y < by) by - y else if (y > by + 1.0) y - (by + 1.0) else 0.0
        val dz = if (z < bz) bz - z else if (z > bz + 1.0) z - (bz + 1.0) else 0.0

        return dx * dx + dy * dy + dz * dz
    }

    private fun isPointInsideBlockAabb(p: Vector, bx: Int, by: Int, bz: Int): Boolean =
        p.x >= bx && p.x <= bx + 1.0 && p.y >= by && p.y <= by + 1.0 && p.z >= bz && p.z <= bz + 1.0

    private fun clampIntoBlock(p: Vector, bx: Int, by: Int, bz: Int): Vector {
        p.x = maxOf(bx + EPSILON, minOf(bx + 1.0 - EPSILON, p.x))
        p.y = maxOf(by + EPSILON, minOf(by + 1.0 - EPSILON, p.y))
        p.z = maxOf(bz + EPSILON, minOf(bz + 1.0 - EPSILON, p.z))
        return p
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        cache.remove(event.player.uniqueId)
    }
}
