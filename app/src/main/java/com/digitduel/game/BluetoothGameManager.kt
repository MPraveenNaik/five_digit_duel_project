package com.digitduel.game

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Manages Classic Bluetooth RFCOMM hosting, client connections, and line-delimited UTF-8 I/O.
 * All blocking socket operations run on background threads; callbacks are posted to the main Looper.
 */
class BluetoothGameManager(
    context: Context,
    private val listener: Listener
) {

    interface Listener {
        fun onHostingStarted()
        fun onConnecting(deviceName: String)
        fun onConnected(deviceName: String, isHost: Boolean)
        fun onMessageReceived(line: String)
        fun onDisconnected(reason: String)
        fun onError(message: String)
    }

    enum class State {
        IDLE,
        HOSTING,
        CONNECTING,
        CONNECTED
    }

    private val bluetoothManager: BluetoothManager? =
        context.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    val adapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

    private val mainHandler = Handler(Looper.getMainLooper())
    private val writeExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    @Volatile
    var currentState: State = State.IDLE
        private set

    @Volatile
    private var intentionalClose = false

    private var acceptThread: AcceptThread? = null
    private var connectThread: ConnectThread? = null
    private var connectedThread: ConnectedThread? = null

    @Synchronized
    @SuppressLint("MissingPermission")
    fun startHosting() {
        val btAdapter = adapter
        if (btAdapter == null) {
            postError("Bluetooth is not supported on this device.")
            return
        }
        closeActiveThreads(notifyDisconnect = false)
        intentionalClose = false
        currentState = State.HOSTING

        val thread = AcceptThread(btAdapter)
        acceptThread = thread
        thread.start()
    }

    @Synchronized
    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice) {
        val btAdapter = adapter
        if (btAdapter == null) {
            postError("Bluetooth is not supported on this device.")
            return
        }
        closeActiveThreads(notifyDisconnect = false)
        intentionalClose = false
        currentState = State.CONNECTING

        val deviceName = safeDeviceName(device)
        mainHandler.post { listener.onConnecting(deviceName) }

        val thread = ConnectThread(btAdapter, device)
        connectThread = thread
        thread.start()
    }

    fun sendLine(message: String) {
        val thread = connectedThread ?: return
        val sanitized = message.replace("\r", "").replace("\n", " ")
        writeExecutor.execute {
            thread.writeLine(sanitized)
        }
    }

    @Synchronized
    fun disconnect() {
        closeActiveThreads(notifyDisconnect = false)
        currentState = State.IDLE
    }

    @Synchronized
    fun shutdown() {
        disconnect()
        writeExecutor.shutdownNow()
    }

    @Synchronized
    private fun startConnected(socket: BluetoothSocket, isHost: Boolean) {
        acceptThread = null
        connectThread = null
        connectedThread?.cancel()

        currentState = State.CONNECTED
        val thread = ConnectedThread(socket, isHost)
        connectedThread = thread
        thread.start()
    }

    @Synchronized
    private fun closeActiveThreads(notifyDisconnect: Boolean) {
        intentionalClose = !notifyDisconnect
        acceptThread?.cancel()
        acceptThread = null
        connectThread?.cancel()
        connectThread = null
        connectedThread?.cancel()
        connectedThread = null
    }

    @Synchronized
    private fun handleConnectionLost(reason: String) {
        val wasActive = currentState != State.IDLE
        currentState = State.IDLE
        connectedThread = null
        acceptThread = null
        connectThread = null
        if (wasActive && !intentionalClose) {
            mainHandler.post { listener.onDisconnected(reason) }
        }
    }

    private fun postError(message: String) {
        mainHandler.post { listener.onError(message) }
    }

    @SuppressLint("MissingPermission")
    private fun safeDeviceName(device: BluetoothDevice?): String {
        return try {
            device?.name?.takeIf { it.isNotBlank() } ?: device?.address ?: "Opponent"
        } catch (_: SecurityException) {
            "Opponent"
        }
    }

    @SuppressLint("MissingPermission")
    private inner class AcceptThread(
        private val btAdapter: BluetoothAdapter
    ) : Thread("DigitDuel-Accept") {

        private var serverSocket: BluetoothServerSocket? = null

        override fun run() {
            val server = try {
                btAdapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, GAME_UUID)
            } catch (e: SecurityException) {
                currentState = BluetoothGameManager.State.IDLE
                postError("Bluetooth permission missing while hosting.")
                return
            } catch (e: IOException) {
                if (!intentionalClose) {
                    currentState = BluetoothGameManager.State.IDLE
                    postError("Failed to start Bluetooth server: ${e.message ?: "I/O error"}")
                }
                return
            }
            serverSocket = server
            mainHandler.post { listener.onHostingStarted() }

            val clientSocket: BluetoothSocket? = try {
                server.accept()
            } catch (e: IOException) {
                if (!intentionalClose) {
                    currentState = BluetoothGameManager.State.IDLE
                    postError("Hosting stopped or failed while waiting for player.")
                }
                null
            } finally {
                try {
                    server.close()
                } catch (_: IOException) {
                }
            }

            if (clientSocket != null && !intentionalClose) {
                startConnected(clientSocket, isHost = true)
            }
        }

        fun cancel() {
            try {
                serverSocket?.close()
            } catch (_: IOException) {
            }
        }
    }

    @SuppressLint("MissingPermission")
    private inner class ConnectThread(
        private val btAdapter: BluetoothAdapter,
        private val device: BluetoothDevice
    ) : Thread("DigitDuel-Connect") {

        private var socket: BluetoothSocket? = null

        override fun run() {
            try {
                if (btAdapter.isDiscovering) {
                    btAdapter.cancelDiscovery()
                }
            } catch (_: SecurityException) {
            }

            val clientSocket = try {
                device.createRfcommSocketToServiceRecord(GAME_UUID)
            } catch (e: SecurityException) {
                currentState = BluetoothGameManager.State.IDLE
                postError("Bluetooth permission missing while connecting.")
                return
            } catch (e: IOException) {
                if (!intentionalClose) {
                    currentState = BluetoothGameManager.State.IDLE
                    postError("Unable to create Bluetooth socket: ${e.message ?: "I/O error"}")
                }
                return
            }
            socket = clientSocket

            try {
                clientSocket.connect()
            } catch (e: IOException) {
                try {
                    clientSocket.close()
                } catch (_: IOException) {
                }
                if (!intentionalClose) {
                    currentState = BluetoothGameManager.State.IDLE
                    postError("Could not connect to ${safeDeviceName(device)}. Make sure the host is waiting.")
                }
                return
            } catch (e: SecurityException) {
                try {
                    clientSocket.close()
                } catch (_: IOException) {
                }
                currentState = BluetoothGameManager.State.IDLE
                postError("Bluetooth permission denied while connecting.")
                return
            }

            if (!intentionalClose) {
                startConnected(clientSocket, isHost = false)
            }
        }

        fun cancel() {
            try {
                socket?.close()
            } catch (_: IOException) {
            }
        }
    }

    private inner class ConnectedThread(
        private val socket: BluetoothSocket,
        private val isHost: Boolean
    ) : Thread("DigitDuel-Connected") {

        private var reader: BufferedReader? = null
        private var outputStream: OutputStream? = null

        override fun run() {
            try {
                reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
                outputStream = socket.outputStream
            } catch (e: IOException) {
                cancel()
                handleConnectionLost("Connection stream error.")
                return
            }

            val remoteName = safeDeviceName(socket.remoteDevice)
            mainHandler.post { listener.onConnected(remoteName, isHost) }

            try {
                while (!isInterrupted && !intentionalClose) {
                    val line = reader?.readLine() ?: break
                    mainHandler.post { listener.onMessageReceived(line) }
                }
                handleConnectionLost("Opponent disconnected.")
            } catch (e: IOException) {
                handleConnectionLost("Bluetooth connection lost.")
            } finally {
                cancel()
            }
        }

        fun writeLine(line: String) {
            val out = outputStream ?: return
            try {
                synchronized(this) {
                    out.write((line + "\n").toByteArray(Charsets.UTF_8))
                    out.flush()
                }
            } catch (e: IOException) {
                cancel()
                handleConnectionLost("Failed to send message; connection lost.")
            }
        }

        fun cancel() {
            try {
                reader?.close()
            } catch (_: IOException) {
            }
            try {
                outputStream?.close()
            } catch (_: IOException) {
            }
            try {
                socket.close()
            } catch (_: IOException) {
            }
        }
    }

    companion object {
        val GAME_UUID: UUID = UUID.fromString("8a4f2c10-6b7e-4d3a-9e21-5f8c1d7a3b90")
        private const val SERVICE_NAME = "DigitDuel"
    }
}
