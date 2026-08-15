package com.flupke.dsh.core

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.flupke.dsh.R

/** 任务完成系统提醒（Agent 回合结束 / 后台任务完成时触发）。 */
object NotificationHelper {

    private const val CHANNEL_ID = "dsh-task-complete"
    private const val NOTIFICATION_ID = 1001

    /** 创建通知渠道（幂等）。 */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "任务完成提醒", NotificationManager.IMPORTANCE_HIGH)
                        .apply { description = "Agent 完成任务或后台任务结束时提醒" },
                )
            }
        }
    }

    /** 发送一条"任务完成"通知；无通知权限或未创建渠道时静默跳过。 */
    fun notifyTaskDone(context: Context, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_dsh)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFICATION_ID, notif)
    }
}
