package com.retrivedmods.wclient.game.module.combat

import com.retrivedmods.wclient.game.InterceptablePacket
import com.retrivedmods.wclient.game.Module
import com.retrivedmods.wclient.game.ModuleCategory
import com.retrivedmods.wclient.game.entity.*
import com.retrivedmods.wclient.game.friend.FriendManager
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class KillauraModule : Module("killaura", ModuleCategory.Combat) {

    private var rangeValue by floatValue("range", 150f, 10f..200f)
    private var cpsValue by intValue("cps", 3, 1..6)
    private var packets by intValue("packets", 1, 1..1)
    private var playersOnly by boolValue("players_only", true)
    private var mobsOnly by boolValue("mobs_only", false)
    private var antiBot by boolValue("anti_bot", true)

    private var tpAuraEnabled by boolValue("tp_aura", true)
    private var teleportBehind by boolValue("tp_behind", true)
    private var tpSpeed by intValue("tp_speed", 50, 10..200)
    private var tpYOffset by intValue("tp_y_offset", 1, -10..10)
    private var keepDistance by floatValue("keep_distance", 1.2f, 0.5f..10f)

    private var strafe by boolValue("strafe", false)
    private val strafeSpeed by floatValue("strafe_speed", 2.5f, 1f..4f)
    private val strafeRadius by floatValue("strafe_radius", 2.5f, 1f..6f)

    private var lastAttackTime = 0L
    private var tpCooldown = 0L
    private var strafeAngle = 0f
    private val random = Random(System.currentTimeMillis())

    private fun Player.isBot(): Boolean {
        if (this is LocalPlayer) return false
        val playerListEntry = session.level.playerMap[this.uuid] ?: return true
        val name = playerListEntry.name?.toString() ?: ""
        if (name.isBlank()) return true
        val xuid = playerListEntry.xuid ?: ""
        if (xuid.isEmpty() || xuid == "0") return true
        if (name.trim().isEmpty()) return true
        return false
    }

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled) return
        if (interceptablePacket.packet !is PlayerAuthInputPacket) return

        val now = System.currentTimeMillis()

        val baseDelay = (1000.0 / cpsValue).toLong()
        val jitter = (baseDelay * 0.2).toLong()
        val delay = baseDelay + random.nextLong(-jitter, jitter)
        if (now - lastAttackTime < delay) return

        val food = session.localPlayer.foodLevel
        val effectiveDelay = if (food < 6) delay * 2 else delay
        if (now - lastAttackTime < effectiveDelay) return

        val targets = searchForTargets()
        if (targets.isEmpty()) return

        val target = targets.first()

        if (target is Player && FriendManager.isFriend(target.uuid)) return

        // 1. ROTATION SPOOFING (ATTACK ÖNCESİ)
        rotateToTarget(target)

        // 2. SALDIRI PAKETİ
        repeat(packets) {
            session.localPlayer.attack(target)
        }

        // 3. POZİSYON SPOOFING (SONRA)
        if (tpAuraEnabled && now - tpCooldown >= tpSpeed) {
            teleportTo(target)
            tpCooldown = now
        }

        // 4. STRAFE
        if (strafe) strafeAroundTarget(target)

        lastAttackTime = now
    }

    private fun searchForTargets(): List<Entity> {
        val player = session.localPlayer
        return session.level.entityMap.values
            .filter { it.distance(player) <= rangeValue }
            .filter { it.isTarget() }
            .sortedBy { it.distance(player) }
    }

    private fun Entity.isTarget(): Boolean {
        return when (this) {
            is LocalPlayer -> false
            is Player -> {
                if (!playersOnly) return false
                if (antiBot && isBot()) return false
                true
            }
            is EntityUnknown -> mobsOnly && isMob()
            else -> false
        }
    }

    private fun rotateToTarget(target: Entity) {
        val player = session.localPlayer
        val targetPos = target.vec3Position
        val playerPos = player.vec3Position
        
        val dx = targetPos.x - playerPos.x
        val dz = targetPos.z - playerPos.z
        val dy = (targetPos.y + 1.6f) - playerPos.y // Head height

        val yaw = Math.toDegrees(Math.atan2(dz, dx).toDouble()).toFloat() - 90f
        val pitch = -Math.toDegrees(Math.atan2(dy, Math.sqrt((dx*dx + dz*dz).toDouble())).toDouble()).toFloat()

        player.rotationYaw = yaw
        player.rotationPitch = pitch.coerceIn(-90f, 90f)
    }

    private fun teleportTo(entity: Entity) {
        val player = session.localPlayer
        val pos = entity.vec3Position

        val yawRad = Math.toRadians(entity.vec3Rotation.y.toDouble()).toFloat()
        val behind = Vector3f.from(sin(yawRad), 0f, -cos(yawRad)).normalize()

        val tpPos = if (teleportBehind) {
            Vector3f.from(
                pos.x + behind.x * keepDistance,
                pos.y + tpYOffset,
                pos.z + behind.z * keepDistance
            )
        } else {
            val dir = pos.sub(player.vec3Position).normalize()
            Vector3f.from(
                pos.x - dir.x * keepDistance,
                pos.y + tpYOffset,
                pos.z - dir.z * keepDistance
            )
        }

        session.clientBound(
            MovePlayerPacket().apply {
                runtimeEntityId = player.runtimeEntityId
                position = tpPos
                rotation = entity.vec3Rotation
                mode = MovePlayerPacket.Mode.NORMAL
                onGround = false
                tick = player.tickExists
            }
        )
    }

    private fun strafeAroundTarget(entity: Entity) {
        val pos = entity.vec3Position
        strafeAngle += strafeSpeed
        if (strafeAngle >= 360f) strafeAngle -= 360f

        val x = strafeRadius * cos(strafeAngle)
        val z = strafeRadius * sin(strafeAngle)

        session.clientBound(
            MovePlayerPacket().apply {
                runtimeEntityId = session.localPlayer.runtimeEntityId
                position = pos.add(x.toFloat(), 0f, z.toFloat())
                rotation = Vector3f.ZERO
                mode = MovePlayerPacket.Mode.NORMAL
                onGround = true
                tick = session.localPlayer.tickExists
            }
        )
    }

    private fun EntityUnknown.isMob(): Boolean {
        return this.identifier in MobList.mobTypes
    }
}