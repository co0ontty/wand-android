package com.wand.app.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Store 基类：统一管理 CoroutineScope 生命周期。
 * 子类通过 scope.launch 启动协程，shutdown() 时自动取消。
 */
abstract class ScopedStore {
    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    open fun shutdown() = scope.cancel()
}
