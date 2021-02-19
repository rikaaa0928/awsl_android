package network.bilibili.awsl


import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.*
import android.os.Build
import android.os.ParcelFileDescriptor
import tun2socks.PacketFlow
import tun2socks.Tun2socks
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.prefs.Preferences
import kotlin.concurrent.thread
import com.beust.klaxon.Klaxon


open class SimpleVpnService : VpnService() {

    private var configString: String = ""
    private var pfd: ParcelFileDescriptor? = null
    private var inputStream: FileInputStream? = null
    private var outputStream: FileOutputStream? = null
    private var buffer = ByteBuffer.allocate(1501)
    @Volatile
    private var running = false
    private lateinit var bgThread: Thread

    private val cm by lazy { this.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }

    private var underlyingNetwork: Network? = null
        set(value) {
            setUnderlyingNetworks(if (value == null) null else arrayOf(value))
            field = value
        }

    companion object {
        private val defaultNetworkRequest = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                .build()
    }

    private val defaultNetworkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            underlyingNetwork = network
        }

//        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities?) {
//            underlyingNetwork = network
//        }

        override fun onLost(network: Network) {
            underlyingNetwork = null
        }
    }
    // todo remake config
    data class Config(val outbounds: List<Outbound>? = null,
                      val outboundDetour: List<Outbound>? = null,
                      val outbound: Outbound? = null,
                      val dns: Dns? = null)

    data class Dns(val servers: List<Any>? = null)
    data class Outbound(val protocol: String = "", val settings: Settings? = null)
    data class Settings(val vnext: List<Server?>? = null)
    data class Server(val address: String? = null)

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(contxt: Context?, intent: Intent?) {
            when (intent?.action) {
                "stop_vpn" -> {
                    stopVPN()
                }
                "ping" -> {
                    if (running) {
                        sendBroadcast(Intent("pong"))
                    }
                }
            }
        }
    }

    private fun stopVPN() {
        pfd?.close()
        pfd = null
        inputStream = null
        outputStream = null
        running = false
        sendBroadcast(Intent("vpn_stopped"))
//        Preferences.putBool(applicationContext, getString(R.string.vpn_is_running), false)
        stopSelf()
    }

    class Flow(stream: FileOutputStream?) : PacketFlow {
        private val flowOutputStream = stream
        override fun writePacket(pkt: ByteArray?) {
            flowOutputStream?.write(pkt)
        }
    }

    class Service(service: VpnService) : VpnService() {
        private val vpnService = service
        override fun protect(fd: Int): Boolean {
            return vpnService.protect(fd)
        }
    }

    private fun handlePackets() {
        while (running) {
            try {
                val n = inputStream?.read(buffer.array())
                n?.let { it } ?: return
                if (n > 0) {
                    buffer.limit(n)
                    Tun2socks.inputPacket(buffer.array())
                    buffer.clear()
                }
            } catch (e: Exception) {
                println("failed to read bytes from TUN fd")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        registerReceiver(broadcastReceiver, IntentFilter("stop_vpn"))
        registerReceiver(broadcastReceiver, IntentFilter("ping"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        configString = ""// todo config

        bgThread = thread(start = true) {
            val config = try { Klaxon().parse<Config>(configString) } catch (e: Exception) {
                sendBroadcast(android.content.Intent("vpn_start_err_config"))
                stopVPN()
                return@thread
            }
            if (config != null) {
                if (config.dns == null || config.dns.servers == null || config.dns.servers.size == 0) {
                    println("must configure dns servers since v2ray will use localhost if there isn't any dns servers")
                    sendBroadcast(Intent("vpn_start_err_dns"))
                    stopVPN()
                    return@thread
                }

                config.dns.servers.forEach {
                    val dnsServer = it as? String
                    if (dnsServer != null && dnsServer == "localhost") {
                        println("using local dns resolver is not allowed since it will cause infinite loop")
                        sendBroadcast(Intent("vpn_start_err_dns"))
                        stopVPN()
                        return@thread
                    }
                }
            } else {
                println("parsing v2ray config failed")
                sendBroadcast(Intent("vpn_start_err"))
                stopVPN()
                return@thread
            }


            val builder = Builder().setSession("Kitsunebi")
                    .setMtu(1500)
                    .addAddress("10.233.233.233", 30)
                    .addDnsServer("8.8.8.8")
                    .addRoute("0.0.0.0", 0)


            pfd = builder.establish()

            cm.requestNetwork(defaultNetworkRequest, defaultNetworkCallback)

            inputStream = FileInputStream(pfd!!.fileDescriptor)
            outputStream = FileOutputStream(pfd!!.fileDescriptor)

            val flow = Flow(outputStream)


            // todo get listen
            Tun2socks.startSocks(flow,"127.0.0.1",1080)
            sendBroadcast(Intent("vpn_started"))
//            Preferences.putBool(applicationContext, getString(R.string.vpn_is_running), true)

            running = true
            handlePackets()
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(broadcastReceiver)
    }

    override fun onRevoke() {
        super.onRevoke()
        stopVPN()
    }
}