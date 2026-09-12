package com.wand.app.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive

/**
 * Store 基类：统一管理 CoroutineScope 生命周期。
 * 子类通过 scope.launch 启动协程，shutdown() 时自动取消。
 */
abstract class ScopedStore {
    protected var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private set

    open fun shutdown() = scope.cancel()

    /** Compose / Navigation 复用同一个 store 时，shutdown() 已经 cancel 过 scope，必须重建才能再 launch。 */
    protected fun ensureScope(): CoroutineScope {
        if (!scope.isActive) {
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        }
        return scope
    }
}
