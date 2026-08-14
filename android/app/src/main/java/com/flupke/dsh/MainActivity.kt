package com.flupke.dsh

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.flupke.dsh.connect.HostStore
import com.flupke.dsh.core.ConnectionManager
import com.flupke.dsh.core.CrashLog
import com.flupke.dsh.ui.DshApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class MainActivity : ComponentActivity() {

    // 连接/网络全走后台调度，主线程只做 UI（避免主线程被网络/WS 生命周期牵连）
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var connection: ConnectionManager
    private lateinit var hostStore: HostStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.init(applicationContext)
        connection = ConnectionManager(scope)
        hostStore = HostStore(applicationContext)
        // 调试/深链入口：am start --es dsh_host "http://192.168.x.x:3080"
        val debugHost = intent.getStringExtra("dsh_host")
        setContent {
            DshApp(connection = connection, hostStore = hostStore, debugHost = debugHost)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        connection.disconnect()
        scope.cancel()
    }
}
