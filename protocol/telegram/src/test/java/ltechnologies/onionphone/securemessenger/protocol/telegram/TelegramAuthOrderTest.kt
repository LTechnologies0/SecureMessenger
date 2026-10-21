package ltechnologies.onionphone.securemessenger.protocol.telegram

import kotlinx.coroutines.runBlocking
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramAuthOrderTest {

    @Test
    fun callersMustAddProxyBeforeSetTdlibParameters() = runBlocking {
        val callOrder = mutableListOf<String>()
        val mockClient = recordingClient(callOrder)

        val facade = TdLibFacade(mockClient)
        // Production (TelegramProtocol.onAuthorizationState) awaits configureProxy,
        // and only then sends SetTdlibParameters. TDLib starts networking on that request.
        assertTrue(facade.configureProxy("127.0.0.1", 9050, null, null))
        assertNull(
            facade.setParameters("/tmp/tdlib", 12345, "test-hash", ByteArray(32)),
        )

        assertEquals(listOf("GetProxies", "AddProxy", "SetTdlibParameters"), callOrder)
    }

    @Test
    fun configureProxy_addsThenRemovesStaleProxies() = runBlocking {
        val requests = mutableListOf<String>()
        val mockClient = object : TdLibClient {
            override fun send(request: TdApi.Function<*>, handler: (TdApi.Object?) -> Unit) {
                when (request) {
                    is TdApi.GetProxies -> {
                        requests.add("GetProxies")
                        handler(
                            TdApi.AddedProxies(
                                arrayOf(
                                    TdApi.AddedProxy().apply {
                                        id = 7
                                        proxy = TdApi.Proxy(
                                            "127.0.0.1",
                                            9050,
                                            TdApi.ProxyTypeSocks5("", ""),
                                        )
                                    },
                                ),
                            ),
                        )
                    }
                    is TdApi.RemoveProxy -> {
                        requests.add("RemoveProxy:${request.proxyId}")
                        handler(TdApi.Ok())
                    }
                    is TdApi.DisableProxy -> requests.add("DisableProxy")
                    is TdApi.AddProxy -> {
                        requests.add("AddProxy")
                        handler(
                            TdApi.AddedProxy().apply {
                                id = 42
                                proxy = request.proxy
                                isEnabled = true
                            },
                        )
                    }
                    else -> handler(TdApi.Ok())
                }
            }

            override fun setUpdateHandler(handler: (TdApi.Object) -> Unit) = Unit

            override fun close() = Unit
        }

        val facade = TdLibFacade(mockClient)
        assertTrue(facade.configureProxy("127.0.0.1", 18202, null, null))
        assertFalse(requests.contains("DisableProxy"))
        // Add-first avoids clearnet gap on live sessions; then remove stale ids.
        assertEquals(listOf("GetProxies", "AddProxy", "RemoveProxy:7"), requests)
    }

    @Test
    fun configureProxy_getProxiesErrorDoesNotAddProxy() = runBlocking {
        val requests = mutableListOf<String>()
        val mockClient = object : TdLibClient {
            override fun send(request: TdApi.Function<*>, handler: (TdApi.Object?) -> Unit) {
                when (request) {
                    is TdApi.GetProxies -> {
                        requests.add("GetProxies")
                        handler(TdApi.Error(500, "proxy list failed"))
                    }
                    is TdApi.AddProxy -> requests.add("AddProxy")
                    else -> handler(TdApi.Ok())
                }
            }

            override fun setUpdateHandler(handler: (TdApi.Object) -> Unit) = Unit

            override fun close() = Unit
        }

        val facade = TdLibFacade(mockClient)
        assertFalse(facade.configureProxy("127.0.0.1", 9050, null, null))
        assertEquals(listOf("GetProxies"), requests)
    }

    @Test
    fun disableProxy_onlyOnExplicitDisconnect() {
        val requests = mutableListOf<String>()
        val mockClient = object : TdLibClient {
            override fun send(request: TdApi.Function<*>, handler: (TdApi.Object?) -> Unit) {
                when (request) {
                    is TdApi.DisableProxy -> requests.add("DisableProxy")
                    else -> handler(TdApi.Ok())
                }
            }

            override fun setUpdateHandler(handler: (TdApi.Object) -> Unit) = Unit

            override fun close() = Unit
        }

        val facade = TdLibFacade(mockClient)
        facade.disableProxy()
        assertEquals(listOf("DisableProxy"), requests)
    }

    private fun recordingClient(callOrder: MutableList<String>): TdLibClient =
        object : TdLibClient {
            override fun send(request: TdApi.Function<*>, handler: (TdApi.Object?) -> Unit) {
                when (request) {
                    is TdApi.GetProxies -> {
                        callOrder.add("GetProxies")
                        handler(TdApi.AddedProxies(emptyArray()))
                    }
                    is TdApi.RemoveProxy -> {
                        callOrder.add("RemoveProxy")
                        handler(TdApi.Ok())
                    }
                    is TdApi.AddProxy -> {
                        callOrder.add("AddProxy")
                        handler(
                            TdApi.AddedProxy().apply {
                                proxy = request.proxy
                                isEnabled = true
                            },
                        )
                    }
                    is TdApi.SetTdlibParameters -> {
                        callOrder.add("SetTdlibParameters")
                        handler(TdApi.Ok())
                    }
                    else -> handler(TdApi.Ok())
                }
            }

            override fun setUpdateHandler(handler: (TdApi.Object) -> Unit) = Unit

            override fun close() = Unit
        }
}
