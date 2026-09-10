package com.zzy.llmreader

import android.app.Application
import com.zzy.llmreader.platform.AppDirs

class LlmReaderApp : Application() {

    /**
     * shared 模块的组合根。ViewModel / Activity 都从这里取仓储与客户端，
     * 桌面端在 `main()` 里构造同样的一个实例 —— 两端共用同一份核心逻辑。
     */
    val core: LlmReaderCore by lazy {
        LlmReaderCore(AppDirs(dataDir = filesDir, cacheDir = cacheDir))
    }

    // 说明：这里原本会安装 Conscrypt 作为首选 JSSE Provider，用来给 Android 7~9
    // （API 24~28）补上 TLS 1.3 支持。但它的四个 ABI 原生库合计 8.13 MB，
    // 占了整个 APK（10 MB）的 80%，而收益仅是老设备上的 TLS 1.3。
    //
    // 已移除：OkHttp 构造连接时会把 ConnectionSpec 里声明的协议版本与平台实际
    // 支持的交集作为启用协议（见 ConnectionSpec.apply），所以在 API 24~28 上会
    // 自动落回 TLS 1.2 —— 本项目支持的 8 个站点目前都支持 TLS 1.2，
    // 而 API 29+ 的 Android 自带 TLS 1.3，本来就不需要 Conscrypt。
}
