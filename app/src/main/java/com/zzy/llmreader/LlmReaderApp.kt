package com.zzy.llmreader

import android.app.Application
import android.util.Log
import com.zzy.llmreader.platform.AppDirs
import org.conscrypt.Conscrypt
import java.security.Security

class LlmReaderApp : Application() {

    /**
     * shared 模块的组合根。ViewModel / Activity 都从这里取仓储与客户端，
     * 桌面端在 `main()` 里构造同样的一个实例 —— 两端共用同一份核心逻辑。
     */
    val core: LlmReaderCore by lazy {
        LlmReaderCore(AppDirs(dataDir = filesDir, cacheDir = cacheDir))
    }

    override fun onCreate() {
        super.onCreate()
        runCatching {
            Security.insertProviderAt(Conscrypt.newProvider(), 1)
        }.onFailure { Log.w("LlmReaderApp", "Conscrypt install failed", it) }
    }
}
