/*
 *     Kraft: Lightweight Minecraft client for Android featuring modules support and other task automation
 *     Copyright (C) 2020  Cubxity
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package dev.cubxity.kraft.mc.impl.local

import com.github.steveice10.mc.protocol.MinecraftProtocol
import com.github.steveice10.mc.protocol.packet.ingame.client.ClientChatPacket
import com.github.steveice10.mc.protocol.packet.ingame.client.player.ClientPlayerPositionRotationPacket
import com.github.steveice10.mc.protocol.packet.ingame.server.ServerChatPacket
import com.github.steveice10.mc.protocol.packet.ingame.server.ServerJoinGamePacket
import com.github.steveice10.mc.protocol.packet.ingame.server.entity.ServerEntityDestroyPacket
import com.github.steveice10.mc.protocol.packet.ingame.server.entity.ServerEntityMetadataPacket
import com.github.steveice10.mc.protocol.packet.ingame.server.entity.player.ServerPlayerHealthPacket
import com.github.steveice10.mc.protocol.packet.ingame.server.entity.player.ServerPlayerPositionRotationPacket
import com.github.steveice10.mc.protocol.packet.ingame.server.entity.spawn.ServerSpawnObjectPacket
import com.github.steveice10.mc.protocol.packet.ingame.server.entity.spawn.ServerSpawnPlayerPacket
import com.github.steveice10.packetlib.Client
import com.github.steveice10.packetlib.event.session.ConnectedEvent
import com.github.steveice10.packetlib.event.session.DisconnectedEvent
import com.github.steveice10.packetlib.event.session.PacketReceivedEvent
import com.github.steveice10.packetlib.event.session.SessionAdapter
import com.github.steveice10.packetlib.packet.Packet
import com.github.steveice10.packetlib.tcp.TcpSessionFactory
import dev.cubxity.kraft.db.entity.Session
import dev.cubxity.kraft.mc.GameSession
import dev.cubxity.kraft.mc.entitiy.Entity
import dev.cubxity.kraft.mc.entitiy.SelfPlayer
import dev.cubxity.kraft.mc.impl.entity.BaseEntity
import dev.cubxity.kraft.mc.impl.entity.SelfPlayerImpl
import kotlinx.coroutines.*
import java.util.*

class LocalGameSession(override val info: Session) : SessionAdapter(), GameSession, CoroutineScope {
    override val coroutineContext = Dispatchers.Default + Job()
    private var client: Client? = null
    private var job: Job? = null

    private var entityId: Int? = null
    override var player: SelfPlayer? = null
    override val entities = mutableMapOf<Int, Entity>()
    override val isActive: Boolean
        get() = client?.session?.isConnected == true

    private val listeners = mutableListOf<GameSession.Listener>()

    override fun connect(clientToken: UUID) {
        if (client != null) disconnect()

        val account = info.account
        val protocol = MinecraftProtocol(account.username, "$clientToken", account.accessToken)
        val client = Client(info.serverHost, info.serverPort, protocol, TcpSessionFactory())
        client.session.addListener(this)
        client.session.connect()

        job = launch {
            while (true) {
                listeners.forEach { it.onTick() }
                delay(50)
            }
        }

        this.client = client
    }

    override fun disconnect() {
        client?.apply {
            if (session.isConnected) session.disconnect("Disconnected.")
            session.removeListener(this@LocalGameSession)
            client = null

            entityId = null
            player = null
            entities.clear()
        }
    }

    override fun connected(event: ConnectedEvent) {
        listeners.forEach { it.onConnect() }
    }

    override fun disconnected(event: DisconnectedEvent) {
        listeners.forEach { it.onDisconnect(event.reason) }
    }

    override fun sendMessage(message: String) {
        client?.session?.send(ClientChatPacket(message))
    }

    override fun addListener(listener: GameSession.Listener) {
        listeners += listener
    }

    override fun removeListener(listener: GameSession.Listener) {
        listeners -= listener
    }

    override fun packetReceived(event: PacketReceivedEvent) {
        when (val packet: Packet = event.getPacket()) {
            is ServerChatPacket -> listeners.forEach { it.onChat(packet.message) }
            is ServerJoinGamePacket -> {
                entityId = packet.entityId
            }
            is ServerPlayerHealthPacket -> {
                player?.apply {
                    health = packet.health
                    food = packet.food
                    foodSaturation = packet.saturation
                }
            }
            is ServerPlayerPositionRotationPacket -> {
                client?.session?.send(
                    ClientPlayerPositionRotationPacket(
                        false,
                        packet.x,
                        packet.y,
                        packet.z,
                        packet.yaw,
                        packet.pitch
                    )
                )
            }
            is ServerSpawnPlayerPacket -> {
                val player = SelfPlayerImpl(packet.entityId, packet.uuid)
                player.x = packet.x
                player.y = packet.y
                player.z = packet.z
                player.pitch = packet.pitch
                player.yaw = packet.yaw

                entities[packet.entityId] = player
                if (packet.entityId == entityId) this.player = player
            }
            is ServerSpawnObjectPacket -> {
                val entity = when (packet.type) {
                    else -> BaseEntity(packet.entityId, packet.uuid)
                }
                entity.data = packet.data

                entity.x = packet.x
                entity.y = packet.y
                entity.z = packet.z
                entity.pitch = packet.pitch
                entity.yaw = packet.yaw
                entity.velocityX = packet.motionX
                entity.velocityY = packet.motionY
                entity.velocityZ = packet.motionZ

                entities[packet.entityId] = entity

                listeners.forEach { it.onEntitySpawn(entity) }
            }
            is ServerEntityDestroyPacket -> {
                packet.entityIds.forEach { entityId ->
                    val entity = entities.remove(entityId) ?: return@forEach
                    listeners.forEach { it.onEntityDestroy(entity) }
                }
            }
            is ServerEntityMetadataPacket -> {
                val entity = entities[packet.entityId] ?: return
                entity.metadata = packet.metadata
                listeners.forEach { it.onEntityUpdate(entity) }
            }
        }
    }
}