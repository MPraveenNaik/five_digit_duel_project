package com.digitduel.game

import android.os.Handler
import android.os.Looper
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Manages internet multiplayer rooms using a 6-character Room Code over TLS MQTT pub/sub.
 * All network I/O runs on a dedicated single-thread executor; callbacks post to the main Looper.
 */
class OnlineRoomManager(
    private val listener: Listener
) {

    interface Listener {
        fun onRoomCreating()
        fun onRoomCreated(roomCode: String)
        fun onRoomJoining(roomCode: String)
        fun onRoomConnected(opponentName: String, isHost: Boolean)
        fun onRoomMessageReceived(line: String)
        fun onRoomDisconnected(reason: String)
        fun onRoomError(message: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val sessionGen = AtomicLong(0L)
    private val outboundSeq = AtomicInteger(0)

    @Volatile
    private var mqttClient: MqttClient? = null

    @Volatile
    private var activeRoomCode: String = ""

    @Volatile
    private var publishTopic: String = ""

    @Volatile
    private var localPeerId: String = ""

    @Volatile
    private var localPlayerName: String = "Player"

    @Volatile
    private var remotePeerId: String? = null

    @Volatile
    private var lastReceivedSeq: Int = 0

    @Volatile
    private var isHostRole: Boolean = false

    @Volatile
    private var isConnectedSession: Boolean = false

    @Volatile
    private var intentionalClose: Boolean = false

    private var joinTimeoutRunnable: Runnable? = null

    fun createRoom(playerName: String) {
        val generation = sessionGen.incrementAndGet()
        val sanitizedName = PlayerPrefs.sanitizeName(playerName)
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.post { listener.onRoomCreating() }

        executor.execute {
            closeCurrentClientQuietly(sendLeave = false)
            if (sessionGen.get() != generation) return@execute

            intentionalClose = false
            isHostRole = true
            isConnectedSession = false
            localPlayerName = sanitizedName
            localPeerId = UUID.randomUUID().toString().replace("-", "").take(8)
            remotePeerId = null
            lastReceivedSeq = 0
            outboundSeq.set(0)

            for (index in BROKERS.indices) {
                if (sessionGen.get() != generation) return@execute
                val code = GameLogic.generateRoomCode(prefix = BROKER_PREFIXES[index])
                val subTopic = topicToHost(code)
                val pubTopic = topicToGuest(code)
                val client = connectToBroker(
                    brokerUrl = BROKERS[index],
                    subscribeTopic = subTopic,
                    publishTopicForWill = pubTopic,
                    generation = generation
                )
                if (client != null) {
                    mqttClient = client
                    activeRoomCode = code
                    publishTopic = pubTopic
                    mainHandler.post {
                        if (sessionGen.get() == generation) {
                            listener.onRoomCreated(code)
                        }
                    }
                    return@execute
                }
            }

            if (sessionGen.get() == generation) {
                mainHandler.post {
                    if (sessionGen.get() == generation) {
                        listener.onRoomError("Unable to reach online game server. Check your internet connection.")
                    }
                }
            }
        }
    }

    fun joinRoom(rawRoomCode: String, playerName: String) {
        val code = GameLogic.normalizeRoomCode(rawRoomCode)
        if (!GameLogic.isValidRoomCode(code)) {
            listener.onRoomError("Enter a valid 6-character room code.")
            return
        }

        val generation = sessionGen.incrementAndGet()
        val sanitizedName = PlayerPrefs.sanitizeName(playerName)
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.post { listener.onRoomJoining(code) }

        executor.execute {
            closeCurrentClientQuietly(sendLeave = false)
            if (sessionGen.get() != generation) return@execute

            intentionalClose = false
            isHostRole = false
            isConnectedSession = false
            localPlayerName = sanitizedName
            localPeerId = UUID.randomUUID().toString().replace("-", "").take(8)
            remotePeerId = null
            lastReceivedSeq = 0
            outboundSeq.set(0)

            val brokerUrl = BROKERS[GameLogic.brokerIndexForRoomCode(code)]
            val subTopic = topicToGuest(code)
            val pubTopic = topicToHost(code)
            val client = connectToBroker(
                brokerUrl = brokerUrl,
                subscribeTopic = subTopic,
                publishTopicForWill = pubTopic,
                generation = generation
            )

            if (client == null) {
                if (sessionGen.get() == generation) {
                    mainHandler.post {
                        if (sessionGen.get() == generation) {
                            listener.onRoomError("Unable to reach online game server. Check your internet connection.")
                        }
                    }
                }
                return@execute
            }

            mqttClient = client
            activeRoomCode = code
            publishTopic = pubTopic

            publishEnvelope(client, pubTopic, "SYS_JOIN|$sanitizedName")

            mainHandler.post {
                if (sessionGen.get() != generation) return@post
                val timeout = Runnable {
                    if (sessionGen.get() == generation && !isConnectedSession) {
                        disconnect()
                        listener.onRoomError("Room $code not found or host did not respond.")
                    }
                }
                joinTimeoutRunnable = timeout
                mainHandler.postDelayed(timeout, JOIN_TIMEOUT_MS)
            }
        }
    }

    fun sendLine(message: String) {
        val generation = sessionGen.get()
        val client = mqttClient ?: return
        val topic = publishTopic
        if (topic.isEmpty()) return

        executor.execute {
            if (sessionGen.get() != generation || !isConnectedSession) return@execute
            val ok = publishEnvelope(client, topic, message)
            if (!ok && !intentionalClose && sessionGen.get() == generation) {
                handleUnexpectedDrop("Failed to send move; internet connection lost.", generation)
            }
        }
    }

    fun disconnect() {
        val generation = sessionGen.incrementAndGet()
        intentionalClose = true
        isConnectedSession = false
        cancelJoinTimeout()

        executor.execute {
            closeCurrentClientQuietly(sendLeave = true)
            if (sessionGen.get() == generation) {
                remotePeerId = null
                activeRoomCode = ""
                publishTopic = ""
            }
        }
    }

    fun shutdown() {
        disconnect()
        executor.shutdownNow()
    }

    private fun connectToBroker(
        brokerUrl: String,
        subscribeTopic: String,
        publishTopicForWill: String,
        generation: Long
    ): MqttClient? {
        val clientId = "dd_${localPeerId}_${System.currentTimeMillis()}"
        return try {
            val client = MqttClient(brokerUrl, clientId, MemoryPersistence())
            client.setCallback(object : MqttCallback {
                override fun connectionLost(cause: Throwable?) {
                    if (!intentionalClose && sessionGen.get() == generation) {
                        executor.execute {
                            handleUnexpectedDrop("Online connection lost.", generation)
                        }
                    }
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    if (intentionalClose || sessionGen.get() != generation) return
                    val raw = message?.payload?.toString(Charsets.UTF_8) ?: return
                    handleIncomingRaw(raw, generation)
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })

            val options = MqttConnectOptions().apply {
                isCleanSession = true
                connectionTimeout = 8
                keepAliveInterval = 20
                isAutomaticReconnect = false
                val willPayload = GameLogic.formatRoomEnvelope(localPeerId, 0, "SYS_LEAVE")
                    .toByteArray(Charsets.UTF_8)
                setWill(publishTopicForWill, willPayload, 1, false)
            }

            client.connect(options)
            client.subscribe(subscribeTopic, 1)
            client
        } catch (_: Exception) {
            null
        }
    }

    private fun handleIncomingRaw(raw: String, generation: Long) {
        val envelope = GameLogic.parseRoomEnvelope(raw) ?: return
        if (envelope.senderId == localPeerId) return

        val payload = envelope.payload
        when {
            payload.startsWith("SYS_JOIN|") -> {
                if (!isHostRole) return
                val guestName = payload.substringAfter("SYS_JOIN|", "Opponent")
                    .trim().ifEmpty { "Opponent" }
                val currentRemote = remotePeerId
                if (currentRemote == null) {
                    remotePeerId = envelope.senderId
                    lastReceivedSeq = envelope.seq
                    isConnectedSession = true
                    mqttClient?.let { client ->
                        executor.execute {
                            if (sessionGen.get() == generation) {
                                publishEnvelope(client, publishTopic, "SYS_ACCEPT|$localPlayerName")
                            }
                        }
                    }
                    mainHandler.post {
                        if (sessionGen.get() == generation) {
                            listener.onRoomConnected(guestName, isHost = true)
                        }
                    }
                } else if (currentRemote != envelope.senderId) {
                    mqttClient?.let { client ->
                        executor.execute {
                            if (sessionGen.get() == generation) {
                                publishEnvelope(client, publishTopic, "SYS_FULL|${envelope.senderId}")
                            }
                        }
                    }
                }
            }

            payload.startsWith("SYS_ACCEPT|") -> {
                if (isHostRole || isConnectedSession) return
                val hostName = payload.substringAfter("SYS_ACCEPT|", "Opponent")
                    .trim().ifEmpty { "Opponent" }
                remotePeerId = envelope.senderId
                lastReceivedSeq = envelope.seq
                isConnectedSession = true
                cancelJoinTimeout()
                mainHandler.post {
                    if (sessionGen.get() == generation) {
                        listener.onRoomConnected(hostName, isHost = false)
                    }
                }
            }

            payload.startsWith("SYS_FULL|") -> {
                if (isHostRole || isConnectedSession) return
                val targetPeer = payload.substringAfter("SYS_FULL|", "")
                if (targetPeer == localPeerId) {
                    disconnect()
                    mainHandler.post {
                        listener.onRoomError("Room $activeRoomCode already has 2 players.")
                    }
                }
            }

            payload == "SYS_LEAVE" -> {
                if (envelope.senderId == remotePeerId) {
                    handleUnexpectedDrop("Opponent left the room.", generation)
                }
            }

            else -> {
                if (envelope.senderId != remotePeerId) return
                if (envelope.seq <= lastReceivedSeq) return
                lastReceivedSeq = envelope.seq
                mainHandler.post {
                    if (sessionGen.get() == generation) {
                        listener.onRoomMessageReceived(payload)
                    }
                }
            }
        }
    }

    private fun publishEnvelope(client: MqttClient, topic: String, payload: String): Boolean {
        return try {
            val seq = outboundSeq.incrementAndGet()
            val encoded = GameLogic.formatRoomEnvelope(localPeerId, seq, payload)
            val msg = MqttMessage(encoded.toByteArray(Charsets.UTF_8)).apply {
                qos = 1
                isRetained = false
            }
            client.publish(topic, msg)
            true
        } catch (_: MqttException) {
            false
        }
    }

    private fun handleUnexpectedDrop(reason: String, generation: Long) {
        if (sessionGen.get() != generation || intentionalClose) return
        val wasWaitingOrConnected = mqttClient != null || isConnectedSession
        sessionGen.incrementAndGet()
        isConnectedSession = false
        cancelJoinTimeout()
        closeCurrentClientQuietly(sendLeave = false)
        remotePeerId = null
        activeRoomCode = ""
        publishTopic = ""

        if (wasWaitingOrConnected) {
            mainHandler.post {
                listener.onRoomDisconnected(reason)
            }
        }
    }

    private fun closeCurrentClientQuietly(sendLeave: Boolean) {
        val client = mqttClient ?: return
        val topic = publishTopic
        mqttClient = null
        try {
            if (sendLeave && client.isConnected && topic.isNotEmpty() && isConnectedSession) {
                val leavePayload = GameLogic.formatRoomEnvelope(localPeerId, 0, "SYS_LEAVE")
                    .toByteArray(Charsets.UTF_8)
                val msg = MqttMessage(leavePayload).apply {
                    qos = 1
                    isRetained = false
                }
                client.publish(topic, msg)
            }
        } catch (_: Exception) {
        }
        try {
            if (client.isConnected) {
                client.disconnectForcibly(800, 800)
            }
        } catch (_: Exception) {
        }
        try {
            client.close(true)
        } catch (_: Exception) {
        }
    }

    private fun cancelJoinTimeout() {
        mainHandler.post {
            joinTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
            joinTimeoutRunnable = null
        }
    }

    private fun topicToHost(code: String): String = "digitduel/v1/room/$code/to_host"
    private fun topicToGuest(code: String): String = "digitduel/v1/room/$code/to_guest"

    companion object {
        private val BROKERS = listOf(
            "ssl://broker.emqx.io:8883",
            "ssl://broker.hivemq.com:8883",
            "ssl://test.mosquitto.org:8883"
        )
        private val BROKER_PREFIXES = charArrayOf('E', 'H', 'M')
        private const val JOIN_TIMEOUT_MS = 12_000L
    }
}
