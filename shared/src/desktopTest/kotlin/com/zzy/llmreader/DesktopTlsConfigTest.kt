package com.zzy.llmreader

import com.zzy.llmreader.web.BROWSER_TLS_VERSIONS
import com.zzy.llmreader.web.browserConnectionSpec
import okhttp3.ConnectionSpec
import okhttp3.TlsVersion
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 锁住「移除 Conscrypt 之后老设备仍能连上站点」这个不变量。
 *
 * 背景：Conscrypt 的四个 ABI 原生库占 APK 的 80%（8.13 MB），只为给
 * Android 7~9 补 TLS 1.3，所以把它移除了。代价是那些设备只能走 TLS 1.2，
 * 前提是 [BROWSER_TLS_VERSIONS] 里确实声明了 TLS 1.2 ——
 * OkHttp 的 `ConnectionSpec.apply` 会拿它跟平台实际支持的协议取交集，
 * 交集为空就直接抛异常，请求根本发不出去。
 *
 * 注：OkHttp 的 `ConnectionSpec.apply` / `tlsVersions` 是 internal，
 * 模块外拿不到，所以这里断言的是我们自己声明的协议清单（真正会被人改错的地方），
 * 外加一个 `isCompatible` 的连通性检查。
 */
class DesktopTlsConfigTest {

    private fun newSslSocket(): SSLSocket =
        (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket() as SSLSocket

    @Test
    fun `协议清单必须包含 TLS 1_2，否则老设备无法连接`() {
        assertTrue(
            BROWSER_TLS_VERSIONS.contains(TlsVersion.TLS_1_2),
            "TLS 1.2 被移除了。本工程已无 Conscrypt，Android 7~9 上没有 TLS 1.3，" +
                "交集会为空导致所有站点请求失败。当前清单：${BROWSER_TLS_VERSIONS.toList()}",
        )
    }

    @Test
    fun `协议清单也保留 TLS 1_3，新设备不应被降级`() {
        assertTrue(
            BROWSER_TLS_VERSIONS.contains(TlsVersion.TLS_1_3),
            "TLS 1.3 被移除了，新设备会被无谓降级到 1.2。当前清单：${BROWSER_TLS_VERSIONS.toList()}",
        )
    }

    @Test
    fun `完整 ConnectionSpec 与本机 TLS 栈兼容`() {
        val socket = newSslSocket()
        try {
            assertTrue(
                browserConnectionSpec().isCompatible(socket),
                "ConnectionSpec 与本机 TLS 栈不兼容，说明协议或加密套件配置有问题",
            )
        } finally {
            runCatching { socket.close() }
        }
    }

    @Test
    fun `保留了 COMPATIBLE_TLS 回退档`() {
        val socket = newSslSocket()
        try {
            // 客户端同时挂了 browserSpec / COMPATIBLE_TLS / CLEARTEXT 三档，
            // 这里确认回退档本身可用，避免有人顺手删掉它导致老设备彻底没退路。
            assertTrue(ConnectionSpec.COMPATIBLE_TLS.isCompatible(socket))
            assertTrue(ConnectionSpec.COMPATIBLE_TLS.isTls)
        } finally {
            runCatching { socket.close() }
        }
    }
}
