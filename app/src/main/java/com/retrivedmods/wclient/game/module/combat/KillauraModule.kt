package com.retrivedmods.wclient.game.module.combat

import com.retrivedmods.wclient.game.InterceptablePacket
import com.retrivedmods.wclient.game.Module
import com.retrivedmods.wclient.game.ModuleCategory
import com.retrivedmods.wclient.game.entity.*
import com.retrivedmods.wclient.game.friend.FriendManager
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.TransactionType
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryActionType
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class KillauraModule : Module("killaura", ModuleCategory.Combat) {

    // ========== AYARLAR ==========
    private var rangeValue by floatValue("range", 150f, 10f..200f)
    private var cpsValue by intValue("cps", 3, 1..6)
    private var playersOnly by boolValue("players_only", true)
    private var mobsOnly by boolValue("mobs_only", false)
    private var antiBot by boolValue("anti_bot", true)

    // Spoofing ayarları (tpAura her zaman açık, sadece mesafe ve yükseklik ayarlanabilir)
    private var keepDistance by floatValue("keep_distance", 1.2f, 0.5f..10f)
    private var tpYOffset by intValue("tp_y_offset", 1, -10..10)

    // ========== DURUM ==========
    private var lastAttackTime = 0L
    private val random = Random(System.currentTimeMillis())

    // ========== BOT TESPİTİ ==========
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

    // ========== ANA TETİKLEYİCİ ==========
    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled) return
        if (interceptablePacket.packet !is PlayerAuthInputPacket) return

        val now = System.currentTimeMillis()

        // Rastgele gecikme (anticheat bypass)
        val baseDelay = (1000.0 / cpsValue).toLong()
        val jitter = (baseDelay * 0.2).toLong()
        val delay = baseDelay + random.nextLong(-jitter, jitter)
        if (now - lastAttackTime < delay) return

        val targets = searchForTargets()
        if (targets.isEmpty()) return

        val target = targets.first()
        if (target is Player && FriendManager.isFriend(target.uuid)) return

        // ===== SALDIRI + POZİSYON SPOOFING (TEK SEFERLİK) =====
        attackWithSpoof(target)

        lastAttackTime = now
    }

    // ========== HEDEF TARAMA ==========
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

    // ========== SALDIRI + SPOOFING (TEK PAKET) ==========
    private fun attackWithSpoof(target: Entity) {
        val player = session.localPlayer
        val originalPos = player.vec3Position

        // 1. Hedefin yakınına gidecek pozisyonu hesapla (arkasına veya önüne)
        val targetPos = target.vec3Position
        val yawRad = Math.toRadians(target.vec3Rotation.y.toDouble()).toFloat()
        val behind = Vector3f.from(sin(yawRad), 0f, -cos(yawRad)).normalize()

        // Teleport pozisyonu (hedefin arkasına, keepDistance kadar yakın)
        val fakePos = Vector3f.from(
            targetPos.x + behind.x * keepDistance,
            targetPos.y + tpYOffset,
            targetPos.z + behind.z * keepDistance
        )

        // 2. Pozisyonu spoofle (MovePlayerPacket ile)
        session.clientBound(
            MovePlayerPacket().apply {
                runtimeEntityId = player.runtimeEntityId
                position = fakePos
                rotation = target.vec3Rotation // hedefe doğru dön
                mode = MovePlayerPacket.Mode.NORMAL
                onGround = false
                tick = player.tickExists
            }
        )

        // 3. Saldırı packet'ini gönder (InventoryTransactionPacket)
        val attackPacket = InventoryTransactionPacket().apply {
            transactionType = TransactionType.USE_ITEM
            actionType = InventoryActionType.ATTACK
            runtimeEntityId = target.runtimeEntityId
        }
        session.sendPacket(attackPacket)

        // 4. Hemen eski pozisyona geri dön
        session.clientBound(
            MovePlayerPacket().apply {
                runtimeEntityId = player.runtimeEntityId
                position = originalPos
                rotation = player.vec3Rotation // eski rotasyonu koru
                mode = MovePlayerPacket.Mode.NORMAL
                onGround = false
                tick = player.tickExists
            }
        )
    }

    // ========== MOB TESPİTİ ==========
    private fun EntityUnknown.isMob(): Boolean {
        return this.identifier in MobList.mobTypes
    }
}