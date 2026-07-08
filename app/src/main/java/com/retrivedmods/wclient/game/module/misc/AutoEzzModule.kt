package com.retrivedmods.wclient.game.module.misc

import com.retrivedmods.wclient.game.AccountManager
import com.retrivedmods.wclient.game.InterceptablePacket
import com.retrivedmods.wclient.game.Module
import com.retrivedmods.wclient.game.ModuleCategory
import com.retrivedmods.wclient.game.entity.LocalPlayer
import com.retrivedmods.wclient.game.entity.Player
import org.cloudburstmc.protocol.bedrock.data.entity.EntityEventType
import org.cloudburstmc.protocol.bedrock.packet.EntityEventPacket
import org.cloudburstmc.protocol.bedrock.packet.TextPacket

class AutoEzzModule : Module("auto_ezz", ModuleCategory.Misc) {

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled) return

        val packet = interceptablePacket.packet
        if (packet is EntityEventPacket && packet.type == EntityEventType.DEATH) {
            val entity = session.level.entityMap[packet.runtimeEntityId] ?: return

            if (entity is Player && entity !is LocalPlayer && !entity.isBot()) {
                val playerName = session.level.playerMap[entity.uuid]?.name ?: return
                sendChatMessage("EZZZ ABUZZBATOWN NEVER LOSES TO WEAK @$playerName")
            }
        }
    }

    private fun sendChatMessage(text: String) {
        val username = AccountManager.selectedAccount?.mcChain?.displayName ?: ""

        val textPacket = TextPacket()
        textPacket.type = TextPacket.Type.CHAT
        textPacket.sourceName = username
        textPacket.message = text
        textPacket.xuid = ""
        textPacket.platformChatId = ""
        textPacket.filteredMessage = ""
        session.serverBound(textPacket)
    }

    private fun Player.isBot(): Boolean {
        if (this is LocalPlayer) return false
        val playerList = session.level.playerMap[this.uuid] ?: return false
        return playerList.name.isBlank()
    }
}