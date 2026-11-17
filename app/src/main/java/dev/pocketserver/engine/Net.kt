package dev.pocketserver.engine

/** Small network helpers used by the UI and the panel. */
object Net {

    fun lanIp(): String = try {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<java.net.Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress && !it.isLoopbackAddress }
            ?.hostAddress ?: "127.0.0.1"
    } catch (_: Throwable) {
        "127.0.0.1"
    }

    fun panelUrl(): String = "http://127.0.0.1:${Prefs.panelPort}/?token=${Prefs.token}"

    fun lanPanelUrl(): String = "http://${lanIp()}:${Prefs.panelPort}/?token=${Prefs.token}"
}
