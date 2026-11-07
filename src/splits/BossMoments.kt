package com.engineerclient.splits

class BossMoments {

    var watcherHandle: Stamp? = null
        private set
    private var watcherMoved: Stamp? = null

    private var allIn: Stamp? = null

    fun reset() {
        watcherHandle = null; watcherMoved = null
        allIn = null
    }

    fun onChat(msg: String, at: Stamp) {
        if (msg == WATCHER_HANDLE && watcherHandle == null) watcherHandle = at
    }

    fun onWatcherMoved(at: Stamp) { if (watcherHandle != null && watcherMoved == null) watcherMoved = at }

    val waitingForWatcher: Boolean get() = watcherHandle != null && watcherMoved == null

    fun onEveryoneInCore(at: Stamp) { if (allIn == null) allIn = at }

    val waitingForCore: Boolean get() = allIn == null

    private companion object {
        const val WATCHER_HANDLE = "[BOSS] The Watcher: Let's see how you can handle this."
    }
}
