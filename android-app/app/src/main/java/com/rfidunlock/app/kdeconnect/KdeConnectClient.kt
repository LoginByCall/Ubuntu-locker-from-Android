package com.rfidunlock.app.kdeconnect

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import javax.net.ssl.SSLSocket

/**
 * Минимальный клиент протокола KDE Connect для отправки команд
 * (runcommand.request) на сопряжённый ПК с GSConnect и приёма ping.
 *
 * Модель соединения (как в KDE Connect LanLinkProvider):
 *  1. Телефон рассылает UDP identity (broadcast, порт 1716), указывая свой tcpPort.
 *  2. GSConnect, получив identity, открывает TCP-соединение к телефону.
 *  3. На принятом TCP-сокете телефон отправляет свой identity (плейнтекст),
 *     затем апгрейдит соединение до TLS в роли КЛИЕНТА.
 *  4. Далее обмен пакетами (pair, runcommand.request, ping) идёт по TLS.
 *
 * ВНИМАНИЕ: пара (pairing) и TLS требуют проверки на реальном устройстве
 * в одной сети с ПК — это нельзя оттестировать в окружении сборки.
 */
class KdeConnectClient(
    private val identity: DeviceIdentity,
    private val scope: CoroutineScope,
) {
    private val tag = "KdeConnectClient"

    private val trustManager = SslHelper.CapturingTrustManager()
    private val sslContext = SslHelper.createContext(identity, trustManager)

    private var serverSocket: ServerSocket? = null
    private var link: SSLSocket? = null
    private var listenJob: Job? = null

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state

    private val _incomingPings = MutableStateFlow<String?>(null)
    /** Последний полученный ping-текст (подтверждение от ПК). */
    val incomingPings: StateFlow<String?> = _incomingPings

    enum class ConnectionState { DISCONNECTED, LISTENING, CONNECTED, PAIRED }

    /** Запустить TCP-сервер и начать рассылку identity-броадкастов. */
    fun start() {
        if (listenJob?.isActive == true) return
        listenJob = scope.launch(Dispatchers.IO) {
            try {
                val ss = ServerSocket(NetworkPacket.DEFAULT_PORT)
                serverSocket = ss
                _state.value = ConnectionState.LISTENING
                broadcastIdentity(ss.localPort)
                while (isActive) {
                    val socket = ss.accept()
                    handleIncomingTcp(socket)
                }
            } catch (e: Exception) {
                Log.e(tag, "Сервер остановлен: ${e.message}")
                _state.value = ConnectionState.DISCONNECTED
            }
        }
    }

    fun stop() {
        listenJob?.cancel()
        runCatching { link?.close() }
        runCatching { serverSocket?.close() }
        link = null
        serverSocket = null
        _state.value = ConnectionState.DISCONNECTED
    }

    /** Рассылка UDP identity-пакета в broadcast. */
    private fun broadcastIdentity(tcpPort: Int) {
        runCatching {
            DatagramSocket().use { udp ->
                udp.broadcast = true
                val packet = NetworkPacket.identity(
                    identity.deviceId, identity.deviceName, tcpPort
                )
                val data = NetworkPacket.serialize(packet)
                val addr = InetAddress.getByName("255.255.255.255")
                udp.send(
                    DatagramPacket(data, data.size, addr, NetworkPacket.DEFAULT_PORT)
                )
            }
        }.onFailure { Log.e(tag, "broadcast failed: ${it.message}") }
    }

    private suspend fun handleIncomingTcp(plain: java.net.Socket) = withContext(Dispatchers.IO) {
        runCatching {
            // 1. Отправить свой identity плейнтекстом.
            val identPacket = NetworkPacket.identity(identity.deviceId, identity.deviceName)
            plain.getOutputStream().write(NetworkPacket.serialize(identPacket))
            plain.getOutputStream().flush()

            // 2. Апгрейд до TLS в роли клиента.
            val ssl = sslContext.socketFactory.createSocket(
                plain, plain.inetAddress.hostAddress, plain.port, true
            ) as SSLSocket
            SslHelper.configureSocket(ssl, asServer = false)
            ssl.startHandshake()
            link = ssl
            _state.value = ConnectionState.CONNECTED
            Log.i(tag, "TLS установлен с ${plain.inetAddress.hostAddress}")

            // 3. Слушать входящие пакеты (ping/pair).
            listenPackets(ssl)
        }.onFailure {
            Log.e(tag, "handleIncomingTcp failed: ${it.message}")
        }
    }

    private fun listenPackets(ssl: SSLSocket) {
        val reader = BufferedReader(InputStreamReader(ssl.inputStream, Charsets.UTF_8))
        while (!ssl.isClosed) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) continue
            runCatching {
                val json = JSONObject(line)
                when (json.optString("type")) {
                    NetworkPacket.TYPE_PAIR -> {
                        val paired = json.optJSONObject("body")?.optBoolean("pair") ?: false
                        if (paired) _state.value = ConnectionState.PAIRED
                    }
                    NetworkPacket.TYPE_PING -> {
                        val msg = json.optJSONObject("body")?.optString("message") ?: "Ping"
                        _incomingPings.value = msg
                    }
                }
            }
        }
    }

    /** Отправить запрос на сопряжение. */
    fun requestPair() = sendPacket(NetworkPacket.pair(true))

    /** Отправить запрос на выполнение команды Run Command по ключу. */
    fun sendCommand(commandKey: String) = sendPacket(NetworkPacket.runCommand(commandKey))

    private fun sendPacket(packet: JSONObject) {
        val current = link ?: run {
            Log.w(tag, "Нет активного соединения для отправки ${packet.optString("type")}")
            return
        }
        scope.launch(Dispatchers.IO) {
            runCatching {
                current.outputStream.write(NetworkPacket.serialize(packet))
                current.outputStream.flush()
            }.onFailure { Log.e(tag, "sendPacket failed: ${it.message}") }
        }
    }

    fun connectToHost(host: String) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                DatagramSocket().use { udp ->
                    udp.broadcast = false
                    val data = NetworkPacket.serialize(
                        NetworkPacket.identity(
                            identity.deviceId, identity.deviceName, NetworkPacket.DEFAULT_PORT
                        )
                    )
                    udp.send(
                        DatagramPacket(
                            data, data.size,
                            InetSocketAddress(host, NetworkPacket.DEFAULT_PORT)
                        )
                    )
                }
            }
        }
    }
}
