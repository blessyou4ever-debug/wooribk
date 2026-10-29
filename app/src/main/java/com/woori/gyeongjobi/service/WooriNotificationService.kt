package com.woori.gyeongjobi.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import com.woori.gyeongjobi.data.AppDatabase
import com.woori.gyeongjobi.data.ExpenseEntity
import com.woori.gyeongjobi.data.FilterPreferences
import com.woori.gyeongjobi.parser.ParseOutcome
import com.woori.gyeongjobi.parser.WooriNotificationParser
import com.woori.gyeongjobi.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class WooriNotificationService : NotificationListenerService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val packageName = sbn.packageName ?: return
        // 우리WON뱅킹 패키지 여부 확인
        if (packageName != WooriNotificationParser.WOORI_PACKAGE_NAME) {
            return
        }

        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val normalText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val body = if (!bigText.isNullOrBlank()) bigText else normalText.orEmpty()

        // 입출금알림 제목 또는 [출금] 키워드 확인
        if (!title.contains("입출금") && !body.contains("[출금]")) {
            return
        }

        val filterPrefs = FilterPreferences(applicationContext)
        val outcome = WooriNotificationParser.parseNotification(
            title = title,
            body = body,
            filterConfig = filterPrefs.currentConfig()
        )

        if (outcome is ParseOutcome.Matched) {
            serviceScope.launch {
                val dao = AppDatabase.getInstance(applicationContext).expenseDao()
                val insertedId = dao.insertIgnore(outcome.entity)
                if (insertedId != -1L) {
                    showDetectedNotification(outcome.entity)
                }
            }
        }
    }

    private fun showDetectedNotification(entity: ExpenseEntity) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "gyeongjobi_detect_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "경조비 지출 감지 알림",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "우리WON뱅킹 출금 알림에서 경조비 후보가 감지되었을 때 알려줍니다."
            }
            manager.createNotificationChannel(channel)
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            entity.dedupKey.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("경조비 지출 후보 감지 (${WooriNotificationParser.formatWon(entity.amount)})")
            .setContentText("[${entity.bankName}] ${entity.recipientName}님께 출금된 내역을 확인하거나 삭제하세요.")
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        manager.notify(entity.dedupKey.hashCode(), notification)
    }
}
