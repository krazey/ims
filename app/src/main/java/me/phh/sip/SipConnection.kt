//SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import android.net.IpSecManager
import android.net.IpSecTransform
import android.net.Network
import android.telephony.Rlog
import java.io.FileDescriptor
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.StandardProtocolFamily
import java.nio.ByteBuffer
import java.nio.channels.Channel
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectableChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.spi.SelectorProvider

/* wrapper around sockets + establish ipsec tunnel given ipsec helpers */
private const val SIP_TCP_CONNECT_TIMEOUT_MS = 10_000
private const val SIP_IPSEC_CLEANUP_TAG = "PHH SipConnection"

private fun closeQuietly(label: String, close: () -> Unit) {
    try {
        close()
    } catch (t: Throwable) {
        Rlog.d(SIP_IPSEC_CLEANUP_TAG, "Failed to close $label", t)
    }
}

private fun abortTcpSocketFirst(socket: Socket, label: String) {
    // On Samsung IWLAN fallback, IpSecService.deleteTunnelInterface() can hold the
    // global IpSecService monitor for minutes. If we call removeTransportModeTransforms()
    // first, this close path can block behind that monitor and the still-open TCP socket
    // keeps the dying ipsecX netdev referenced. Force the TCP fd closed first, then try
    // to detach/deactivate transforms best-effort afterwards.
    closeQuietly("$label setSoLinger(0)") { socket.setSoLinger(true, 0) }
    closeQuietly("$label shutdownOutput") { socket.shutdownOutput() }
    closeQuietly("$label shutdownInput") { socket.shutdownInput() }
    closeQuietly(label) { socket.close() }
}

private fun closeUdpSocketFirst(socket: DatagramSocket, label: String) {
    closeQuietly(label) { socket.close() }
}


private fun removeTcpTransportModeTransforms(
    ipSecManager: IpSecManager?,
    socket: Socket,
    label: String,
) {
    val manager = ipSecManager ?: return
    try {
        Rlog.d(SIP_IPSEC_CLEANUP_TAG, "Removing IPsec transforms from $label")
        manager.removeTransportModeTransforms(socket)
    } catch (t: Throwable) {
        Rlog.d(SIP_IPSEC_CLEANUP_TAG, "Failed to remove IPsec transforms from $label", t)
    }
}

private fun removeUdpTransportModeTransforms(
    ipSecManager: IpSecManager?,
    socket: DatagramSocket,
    label: String,
) {
    val manager = ipSecManager ?: return
    try {
        Rlog.d(SIP_IPSEC_CLEANUP_TAG, "Removing IPsec transforms from $label")
        manager.removeTransportModeTransforms(socket)
    } catch (t: Throwable) {
        Rlog.d(SIP_IPSEC_CLEANUP_TAG, "Failed to remove IPsec transforms from $label", t)
    }
}

private fun removeFdTransportModeTransforms(
    ipSecManager: IpSecManager?,
    socketFd: FileDescriptor,
    label: String,
) {
    val manager = ipSecManager ?: return
    try {
        Rlog.d(SIP_IPSEC_CLEANUP_TAG, "Removing IPsec transforms from $label")
        manager.removeTransportModeTransforms(socketFd)
    } catch (t: Throwable) {
        Rlog.d(SIP_IPSEC_CLEANUP_TAG, "Failed to remove IPsec transforms from $label", t)
    }
}


interface SipConnection {
    fun close()
    fun enableIpsec(
        ipSecBuilder: IpSecTransform.Builder,
        ipSecManager: IpSecManager,
        clientSpiC: IpSecManager.SecurityParameterIndex,
        serverSpiS: IpSecManager.SecurityParameterIndex
    )
    fun gLocalAddr(): InetAddress
    fun connect(remotePort: Int)
    fun gWriter(): OutputStream
    fun gReader(): SipReader
    fun gLocalPort(): Int
    fun getChannel(): SelectableChannel
}

class SipConnectionTcp(
    val network: Network,
    val remoteAddr: InetAddress,
    val _localAddr: InetAddress? = null,
    val _localPort: Int = 0
) : SipConnection {
    val socket: Socket
    /* redefine public localAddr/port for when not specified in argument */
    var localAddr: InetAddress
    var localPort: Int
    var remotePort: Int = 0
    private var writer: OutputStream? = null
    private var reader: SipReader? = null
    // we need to keep the transform around or the ipsec transform
    // gets destroyed while still in use
    lateinit var inTransform: IpSecTransform
    lateinit var outTransform: IpSecTransform

    private var ipSecManager: IpSecManager? = null

    var connected = false

    init {
        socket = network.socketFactory.createSocket()
        if (_localAddr != null) {
            socket.bind(InetSocketAddress(_localAddr, _localPort))
        }
        localAddr = socket.localAddress
        localPort = socket.localPort
    }

    override fun connect(remotePort: Int) {
        this.remotePort = remotePort
        socket.connect(InetSocketAddress(remoteAddr, remotePort), SIP_TCP_CONNECT_TIMEOUT_MS)
        if (_localAddr == null) {
            // localAddr/Port only valid after connect if no explicit bind
            localAddr = socket.localAddress
            localPort = socket.localPort
        }
        writer = socket.getOutputStream()
        reader = socket.getInputStream().sipReader()
        connected = true
    }

    override fun gWriter(): OutputStream {
        if (!connected || socket.isClosed) {
            throw java.io.IOException(
                "TCP SIP writer is unavailable because the socket is not connected/open; " +
                    "remote=$remoteAddr:$remotePort local=$localAddr:$localPort connected=$connected closed=${socket.isClosed}"
            )
        }
        return writer ?: throw java.io.IOException(
            "TCP SIP writer is unavailable because connect did not publish a writer; " +
                "remote=$remoteAddr:$remotePort local=$localAddr:$localPort connected=$connected closed=${socket.isClosed}"
        )
    }

    override fun gReader(): SipReader {
        if (!connected || socket.isClosed) {
            throw java.io.IOException(
                "TCP SIP reader is unavailable because the socket is not connected/open; " +
                    "remote=$remoteAddr:$remotePort local=$localAddr:$localPort connected=$connected closed=${socket.isClosed}"
            )
        }
        return reader ?: throw java.io.IOException(
            "TCP SIP reader is unavailable because connect did not publish a reader; " +
                "remote=$remoteAddr:$remotePort local=$localAddr:$localPort connected=$connected closed=${socket.isClosed}"
        )
    }

    override fun gLocalPort(): Int {
        return localPort
    }

    override fun getChannel(): SelectableChannel {
        return socket.channel
    }

    override fun close() {
        connected = false
        abortTcpSocketFirst(socket, "TCP client socket")
        removeTcpTransportModeTransforms(ipSecManager, socket, "TCP client socket")
        if (this::inTransform.isInitialized) {
            closeQuietly("TCP client inTransform") { inTransform.close() }
        }
        if (this::outTransform.isInitialized) {
            closeQuietly("TCP client outTransform") { outTransform.close() }
        }
    }

    override fun enableIpsec(
        ipSecBuilder: IpSecTransform.Builder,
        ipSecManager: IpSecManager,
        clientSpiC: IpSecManager.SecurityParameterIndex,
        serverSpiS: IpSecManager.SecurityParameterIndex
    ) {
        // Can only do this before connecting?
        check(!connected)
        this.ipSecManager = ipSecManager
        inTransform = ipSecBuilder.buildTransportModeTransform(remoteAddr, clientSpiC)
        ipSecManager.applyTransportModeTransform(socket, IpSecManager.DIRECTION_IN, inTransform)
        outTransform = ipSecBuilder.buildTransportModeTransform(localAddr, serverSpiS)
        ipSecManager.applyTransportModeTransform(socket, IpSecManager.DIRECTION_OUT, outTransform)
    }

    override fun gLocalAddr(): InetAddress {
        return localAddr
    }
}

class SipConnectionTcpServer(
    val network: Network,
    val remoteAddr: InetAddress,
    val localAddr: InetAddress,
    val localPort: Int
) {
    val serverSocket: ServerSocket
    val serverSocketFd: FileDescriptor
    lateinit var inTransform: IpSecTransform
    lateinit var outTransform: IpSecTransform

    private var ipSecManager: IpSecManager? = null
    private val acceptedClients = java.util.concurrent.CopyOnWriteArrayList<Socket>()


    init {
        serverSocket = ServerSocket()
        serverSocket.bind(InetSocketAddress(localAddr, localPort))
        serverSocketFd =
            serverSocket.javaClass.getMethod("getFileDescriptor\$").invoke(serverSocket)
                as FileDescriptor
        network.bindSocket(serverSocketFd)
    }

    data class AcceptedTcpSipFlow(
        val socket: Socket,
        val reader: SipReader,
        val writer: OutputStream,
    )

    fun accept(): AcceptedTcpSipFlow {
        val client = serverSocket.accept()
        acceptedClients.add(client)
        return AcceptedTcpSipFlow(
            socket = client,
            reader = client.getInputStream().sipReader(),
            writer = client.getOutputStream(),
        )
    }

    fun closeAccepted(client: Socket) {
        abortTcpSocketFirst(client, "TCP server accepted socket")
        removeTcpTransportModeTransforms(ipSecManager, client, "TCP server accepted socket")
        acceptedClients.remove(client)
    }

    fun enableIpsec(
        ipSecManager: IpSecManager,
        inTransform: IpSecTransform,
        outTransform: IpSecTransform
    ) {
        this.ipSecManager = ipSecManager
        this.inTransform = inTransform
        ipSecManager.applyTransportModeTransform(
            serverSocketFd,
            IpSecManager.DIRECTION_IN,
            inTransform
        )
        this.outTransform = outTransform
        ipSecManager.applyTransportModeTransform(
            serverSocketFd,
            IpSecManager.DIRECTION_OUT,
            outTransform
        )
    }

        fun close() {
        val clients = acceptedClients.toList()
        for (client in clients) {
            abortTcpSocketFirst(client, "TCP server accepted socket")
        }
        acceptedClients.clear()
        closeQuietly("TCP server listen socket") { serverSocket.close() }
        removeFdTransportModeTransforms(ipSecManager, serverSocketFd, "TCP server listen socket")
        for (client in clients) {
            removeTcpTransportModeTransforms(ipSecManager, client, "TCP server accepted socket")
        }
        if (this::inTransform.isInitialized) {
            closeQuietly("TCP server inTransform") { inTransform.close() }
        }
        if (this::outTransform.isInitialized) {
            closeQuietly("TCP server outTransform") { outTransform.close() }
        }
    }

    fun getChannel(): SelectableChannel {
        return serverSocket.channel
    }
}

class SipConnectionUdp(
    val network: Network,
    val remoteAddr: InetAddress,
    val _localAddr: InetAddress? = null,
    val _localPort: Int = 0,
) : SipConnection {
    val socket: DatagramSocket
    /* redefine public localAddr/port for when not specified in argument */
    var localAddr: InetAddress
    var localPort: Int
    var remotePort: Int = 0
    lateinit var writer: OutputStream
    lateinit var reader: SipReader
    // we need to keep the transform around or the ipsec transform
    // gets destroyed while still in use
    lateinit var inTransform: IpSecTransform
    lateinit var outTransform: IpSecTransform

    private var ipSecManager: IpSecManager? = null

    var connected = false

    init {
        val channel = DatagramChannel.open(if(remoteAddr is Inet6Address) StandardProtocolFamily.INET6 else StandardProtocolFamily.INET)
        if (_localAddr != null) {
            channel.bind(InetSocketAddress(_localAddr, _localPort))
        }
        socket = channel.socket()
        network.bindSocket(socket)

        localAddr = socket.localAddress
        localPort = socket.localPort
    }

    override fun connect(remotePort: Int) {
        this.remotePort = remotePort
        // Note: DO NOT connect, because the answers might come back from a different IP than where we sent to
        //socket.connect(InetSocketAddress(remoteAddr, remotePort))
        if (_localAddr == null) {
            // localAddr/Port only valid after connect if no explicit bind
            localAddr = socket.localAddress
            localPort = socket.localPort
        }
        writer = object: OutputStream() {
            override fun write(p0: Int) {
                write(byteArrayOf(p0.toByte()))
            }
            override fun write(p0: ByteArray) {
                // Send using the datagram channel
                socket.channel.send(ByteBuffer.wrap(p0), InetSocketAddress(remoteAddr, remotePort))
            }
        }
        reader = object: InputStream() {
            val currentDgram = DatagramPacket(ByteArray(128*1024), 128*1024)
            var currentPosition = 0
            var currentSize = 0

            fun recvPacket() {
                // select()
                select(listOf(getChannel()))
                socket.receive(currentDgram)
                currentPosition = 0
                currentSize = currentDgram.length
            }

            override fun read(): Int {
                if (currentPosition >= currentSize) {
                    recvPacket()
                }
                val ret =  currentDgram.data[currentPosition++].toInt()
                return ret
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (currentPosition >= currentSize) {
                    recvPacket()
                }
                val toRead = minOf(len, currentSize - currentPosition)
                currentDgram.data.copyInto(b, off, currentPosition, currentPosition + toRead)
                currentPosition += toRead
                return toRead
            }
        }.sipReader()
        connected = true
    }

    override fun gWriter(): OutputStream {
        return writer
    }

    override fun gReader(): SipReader {
        return reader
    }

    override fun gLocalPort(): Int {
        return localPort
    }

    override fun getChannel(): SelectableChannel {
        return socket.channel
    }

    override fun close() {
        closeUdpSocketFirst(socket, "UDP client socket")
        removeUdpTransportModeTransforms(ipSecManager, socket, "UDP client socket")
        if (this::inTransform.isInitialized) {
            closeQuietly("UDP client inTransform") { inTransform.close() }
        }
        if (this::outTransform.isInitialized) {
            closeQuietly("UDP client outTransform") { outTransform.close() }
        }
    }

    override fun enableIpsec(
        ipSecBuilder: IpSecTransform.Builder,
        ipSecManager: IpSecManager,
        clientSpiC: IpSecManager.SecurityParameterIndex,
        serverSpiS: IpSecManager.SecurityParameterIndex
    ) {
        // Can only do this before connecting?
        check(!connected)
        this.ipSecManager = ipSecManager
        inTransform = ipSecBuilder.buildTransportModeTransform(remoteAddr, clientSpiC)
        ipSecManager.applyTransportModeTransform(socket, IpSecManager.DIRECTION_IN, inTransform)
        outTransform = ipSecBuilder.buildTransportModeTransform(localAddr, serverSpiS)
        ipSecManager.applyTransportModeTransform(socket, IpSecManager.DIRECTION_OUT, outTransform)
    }

    override fun gLocalAddr(): InetAddress {
        return localAddr
    }
}

class SipConnectionUdpServer(
    val network: Network,
    val remoteAddr: InetAddress,
    val localAddr: InetAddress,
    val localPort: Int) {

    val socket: DatagramSocket
    val socketFd : FileDescriptor
    lateinit var inTransform: IpSecTransform
    lateinit var outTransform: IpSecTransform

    private var ipSecManager: IpSecManager? = null

    init {
        val channel = DatagramChannel.open(if(remoteAddr is Inet6Address) StandardProtocolFamily.INET6 else StandardProtocolFamily.INET)
        channel.bind(InetSocketAddress(localAddr, localPort))
        socket = channel.socket()
        network.bindSocket(socket)
        socketFd =
            socket.javaClass.getMethod("getFileDescriptor\$").invoke(socket)
                as FileDescriptor
    }

    fun gReader(): SipReader {
        return object: InputStream() {
            val currentDgram = DatagramPacket(ByteArray(128*1024), 128*1024)
            var currentPosition = 0
            var currentSize = 0

            fun recvPacket() {
                // select()
                select(listOf(getChannel()))
                socket.receive(currentDgram)
                currentPosition = 0
                currentSize = currentDgram.length
            }

            override fun read(): Int {
                if (currentPosition >= currentSize) {
                    recvPacket()
                }
                val ret = currentDgram.data[currentPosition++].toInt()
                return ret
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (currentPosition >= currentSize) {
                    recvPacket()
                }
                val toRead = minOf(len, currentSize - currentPosition)
                currentDgram.data.copyInto(b, off, currentPosition, currentPosition + toRead)
                currentPosition += toRead
                return toRead
            }
        }.sipReader()
    }

    fun enableIpsec(
        ipSecManager: IpSecManager,
        inTransform: IpSecTransform,
        outTransform: IpSecTransform
    ) {
        this.ipSecManager = ipSecManager
        this.inTransform = inTransform
        ipSecManager.applyTransportModeTransform(
            socketFd,
            IpSecManager.DIRECTION_IN,
            inTransform
        )
        this.outTransform = outTransform
        ipSecManager.applyTransportModeTransform(
            socketFd,
            IpSecManager.DIRECTION_OUT,
            outTransform
        )
    }

        fun close() {
        closeUdpSocketFirst(socket, "UDP server socket")
        removeUdpTransportModeTransforms(ipSecManager, socket, "UDP server socket")
        removeFdTransportModeTransforms(ipSecManager, socketFd, "UDP server fd")
        if (this::inTransform.isInitialized) {
            closeQuietly("UDP server inTransform") { inTransform.close() }
        }
        if (this::outTransform.isInitialized) {
            closeQuietly("UDP server outTransform") { outTransform.close() }
        }
    }

    fun getChannel(): SelectableChannel {
        return socket.channel
    }
}

fun select(channels: List<SelectableChannel>): Int {
    var returnValue = -1
    Selector.open().use { selector ->
        for (channel in channels) {
            channel.configureBlocking(false)
            channel.register(selector, SelectionKey.OP_READ)
        }

        val nSelectedKeys = selector.select()
        for (key in selector.selectedKeys()) {
            if (key.isReadable) {
                val index = channels.indexOf(key.channel())
                if (index != -1) {
                    Rlog.e("PHH", "When selecting got result $index")
                    returnValue = index
                    break
                }
            }
        }
    }
    for (channel in channels) {
        channel.configureBlocking(true)
    }

    return returnValue
}
