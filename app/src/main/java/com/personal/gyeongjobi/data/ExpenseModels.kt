package com.personal.gyeongjobi.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class RecordStatus(val label: String) {
    PENDING("확인 대기"),
    CONFIRMED("경조비 확정");

    companion object {
        fun fromString(value: String): RecordStatus {
            return entries.find { it.name.equals(value.trim(), ignoreCase = true) || it.label == value.trim() }
                ?: CONFIRMED
        }
    }
}

enum class EventType(val label: String, val emoji: String) {
    MARRIAGE("결혼/축의", "💍"),
    FUNERAL("부고/조의", "🕯️"),
    FIRST_BIRTHDAY("돌잔치/출산", "🎂"),
    BIRTHDAY("생신/환갑/칠순", "💐"),
    CELEBRATION("승진/개업/집들이", "🎊"),
    HOLIDAY("명절/용돈/선물", "🎁"),
    ETC("기타 경조", "✉️");

    companion object {
        fun fromLabelOrName(text: String): EventType {
            val cleaned = text.trim()
            return entries.find {
                it.name.equals(cleaned, ignoreCase = true) ||
                    it.label == cleaned ||
                    it.label.contains(cleaned) ||
                    cleaned.contains(it.label.substringBefore("/"))
            } ?: ETC
        }
    }
}

enum class RelationType(val label: String) {
    WORK("직장/동료"),
    FRIEND("친구/지인"),
    FAMILY("친척/가족"),
    CLUB("모임/동호회"),
    BUSINESS("거래처/업무"),
    ETC("기타");

    companion object {
        fun fromLabelOrName(text: String): RelationType {
            val cleaned = text.trim()
            return entries.find {
                it.name.equals(cleaned, ignoreCase = true) ||
                    it.label == cleaned ||
                    it.label.contains(cleaned) ||
                    cleaned.contains(it.label.substringBefore("/"))
            } ?: ETC
        }
    }
}

enum class RecordSource(val label: String) {
    NOTIFICATION("우리WON알림"),
    CUSTOM_CSV("양식업로드"),
    WOORI_CSV("우리은행내역"),
    MANUAL("직접입력")
}

@Entity(
    tableName = "expenses",
    indices = [Index(value = ["dedupKey"], unique = true)]
)
data class ExpenseEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val transactionDate: String,      // YYYY-MM-DD
    val transactionTime: String,      // HH:mm:ss
    val bankName: String,             // 분리된 은행명 (예: "신한", "국민", "우리")
    val recipientName: String,        // 분리된 개인 실명 (예: "조창헌")
    val rawDescription: String,       // 원본 적요 (예: "신한조창헌")
    val amount: Long,                 // 출금 금액 (원)
    val accountNumber: String = "",   // 출금 계좌번호
    val balanceAfter: Long? = null,   // 잔액
    val status: RecordStatus = RecordStatus.PENDING,
    val eventType: EventType = EventType.ETC,
    val relationType: RelationType = RelationType.ETC,
    val memo: String = "",
    val source: RecordSource = RecordSource.NOTIFICATION,
    val rawNotificationText: String = "",
    val dedupKey: String,             // 중복 방지 고유 키
    val createdAt: Long = System.currentTimeMillis()
)
