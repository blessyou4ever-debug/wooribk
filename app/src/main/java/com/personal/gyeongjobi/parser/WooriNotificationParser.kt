package com.personal.gyeongjobi.parser

import com.personal.gyeongjobi.data.ExpenseEntity
import com.personal.gyeongjobi.data.FilterConfig
import com.personal.gyeongjobi.data.RecordSource
import com.personal.gyeongjobi.data.RecordStatus
import java.time.LocalDate

data class BankAndRecipient(
    val bankName: String,
    val recipientName: String,
    val rawDescription: String
)

sealed class ParseOutcome {
    data class Matched(val entity: ExpenseEntity) : ParseOutcome()
    data class Ignored(val reason: String) : ParseOutcome()
}

object WooriNotificationParser {

    const val WOORI_PACKAGE_NAME = "com.wooribank.smart.npib"

    val BANK_PREFIXES: List<String> = listOf(
        "카카오뱅크", "케이뱅크", "토스뱅크", "새마을금고", "아이엠뱅크", "SC제일",
        "새마을", "우체국", "카카오", "아이엠",
        "신한", "국민", "농협", "하나", "기업", "우리", "카뱅", "토스", "케뱅", "케이",
        "부산", "대구", "경남", "광주", "전북", "제주", "수협", "신협", "씨티", "제일", "산업", "산림", "저축",
        "KB", "NH", "IBK", "SC", "BNK", "DGB", "JB"
    ).sortedByDescending { it.length }

    val NON_PERSONAL_KEYWORDS: List<String> = listOf(
        "페이", "충전", "카드", "대금", "출금", "보험", "관리비", "자동이체", "원리금", "이자",
        "세금", "지방세", "국세", "건강보험", "국민연금", "도시가스", "전기요금", "수도", "통신",
        "할부", "결제", "수수료", "ATM", "CD", "편의점", "마트", "백화점", "택시", "코레일",
        "쿠팡", "배달", "요기요", "배민", "네이버", "카카오페이", "토스페이", "삼성페이",
        "주식회사", "(주)", "유한회사", "법인", "병원", "약국", "학원", "관리단", "조합", "재단", "협회"
    )

    private val NOTIFICATION_REGEX = Regex(
        pattern = """\[출금\]\s*(.+?)\s+([0-9,]+)원\s+([0-9*\-]+)계좌\s+잔액\s*([0-9,]+)원\s+(\d{2}/\d{2})\s+(\d{2}:\d{2}:\d{2})""",
        options = setOf(RegexOption.DOT_MATCHES_ALL)
    )

    private val FALLBACK_WITHDRAWAL_REGEX = Regex(
        pattern = """\[출금\]\s*(.+?)\s+([0-9,]+)원.*?(?:(\d{2}/\d{2})\s+(\d{2}:\d{2}(?::\d{2})?))?""",
        options = setOf(RegexOption.DOT_MATCHES_ALL)
    )

    private val PURE_KOREAN_NAME_REGEX = Regex("^[가-힣]{2,4}$")

    fun extractBankAndPersonalName(rawDescription: String): BankAndRecipient? {
        val normalized = rawDescription.replace("\s+".toRegex(), " ").trim()
        if (normalized.isEmpty()) return null

        val compact = normalized.replace(" ", "")

        if (compact.any { it.isDigit() || it == '-' || it == '_' || it == '(' || it == ')' || it == '/' }) {
            return null
        }

        val upperCompact = compact.uppercase()
        if (NON_PERSONAL_KEYWORDS.any { keyword -> upperCompact.contains(keyword.uppercase()) }) {
            return null
        }

        for (prefix in BANK_PREFIXES) {
            if (upperCompact.startsWith(prefix.uppercase()) && compact.length > prefix.length) {
                val remainder = compact.substring(prefix.length)
                if (PURE_KOREAN_NAME_REGEX.matches(remainder) && !NON_PERSONAL_KEYWORDS.any { remainder.contains(it) }) {
                    val displayBank = normalizeBankName(prefix)
                    return BankAndRecipient(
                        bankName = displayBank,
                        recipientName = remainder,
                        rawDescription = normalized
                    )
                }
            }
        }

        if (PURE_KOREAN_NAME_REGEX.matches(compact)) {
            return BankAndRecipient(
                bankName = "우리",
                recipientName = compact,
                rawDescription = normalized
            )
        }

        return null
    }

    private fun normalizeBankName(prefix: String): String {
        return when (prefix.uppercase()) {
            "KB" -> "국민"
            "NH" -> "농협"
            "IBK" -> "기업"
            "SC", "SC제일", "제일" -> "SC제일"
            "카뱅", "카카오", "카카오뱅크" -> "카카오뱅크"
            "케뱅", "케이", "케이뱅크" -> "케이뱅크"
            "토스", "토스뱅크" -> "토스뱅크"
            "새마을", "새마을금고" -> "새마을금고"
            "아이엠", "아이엠뱅크", "DGB", "대구" -> "iM뱅크(대구)"
            "BNK" -> "부산/경남"
            else -> prefix
        }
    }

    fun parseNotification(
        title: String?,
        body: String?,
        filterConfig: FilterConfig,
        referenceYear: Int = LocalDate.now().year
    ): ParseOutcome {
        val rawText = body?.trim().orEmpty()
        if (rawText.isEmpty()) {
            return ParseOutcome.Ignored("알림 본문이 비어 있습니다.")
        }

        if (!rawText.contains("[출금]")) {
            return ParseOutcome.Ignored("출금 내역이 아닙니다 ([출금] 태그 없음).")
        }

        val singleLine = rawText.replace(Regex("\s+"), " ").trim()

        val match = NOTIFICATION_REGEX.find(singleLine)
        if (match != null) {
            val rawRecipient = match.groupValues[1].trim()
            val amountStr = match.groupValues[2].replace(",", "")
            val accountNum = match.groupValues[3].trim()
            val balanceStr = match.groupValues[4].replace(",", "")
            val mmDd = match.groupValues[5].trim()
            val timeStr = match.groupValues[6].trim()

            val amount = amountStr.toLongOrNull()
                ?: return ParseOutcome.Ignored("금액 파싱 실패: $amountStr")
            val balance = balanceStr.toLongOrNull()

            val bankAndRecipient = extractBankAndPersonalName(rawRecipient)
                ?: return ParseOutcome.Ignored("개인 이체 내역이 아닙니다: '$rawRecipient'")

            if (!filterConfig.isAmountAllowed(amount)) {
                return ParseOutcome.Ignored("설정된 경조비 금액 단위가 아닙니다: ${formatWon(amount)}")
            }

            val formattedDate = convertMmDdToIsoDate(mmDd, referenceYear)
            val dedupKey = "${formattedDate}_${timeStr}_${bankAndRecipient.rawDescription}_${amount}_${balance ?: 0L}"

            return ParseOutcome.Matched(
                ExpenseEntity(
                    transactionDate = formattedDate,
                    transactionTime = timeStr,
                    bankName = bankAndRecipient.bankName,
                    recipientName = bankAndRecipient.recipientName,
                    rawDescription = bankAndRecipient.rawDescription,
                    amount = amount,
                    accountNumber = accountNum,
                    balanceAfter = balance,
                    status = RecordStatus.PENDING,
                    source = RecordSource.NOTIFICATION,
                    rawNotificationText = singleLine,
                    dedupKey = dedupKey
                )
            )
        }

        val fallback = FALLBACK_WITHDRAWAL_REGEX.find(singleLine)
            ?: return ParseOutcome.Ignored("우리WON뱅킹 출금 알림 형식이 아닙니다.")

        val rawRecipient = fallback.groupValues[1].trim()
        val amount = fallback.groupValues[2].replace(",", "").toLongOrNull()
            ?: return ParseOutcome.Ignored("금액 파싱 실패")
        val mmDd = fallback.groupValues[3].ifBlank {
            val today = LocalDate.now()
            "%02d/%02d".format(today.monthValue, today.dayOfMonth)
        }
        val timeStr = fallback.groupValues[4].ifBlank { "00:00:00" }

        val bankAndRecipient = extractBankAndPersonalName(rawRecipient)
            ?: return ParseOutcome.Ignored("개인 이체 내역이 아닙니다: '$rawRecipient'")

        if (!filterConfig.isAmountAllowed(amount)) {
            return ParseOutcome.Ignored("설정된 경조비 금액 단위가 아닙니다: ${formatWon(amount)}")
        }

        val formattedDate = convertMmDdToIsoDate(mmDd, referenceYear)
        val dedupKey = "${formattedDate}_${timeStr}_${bankAndRecipient.rawDescription}_${amount}"

        return ParseOutcome.Matched(
            ExpenseEntity(
                transactionDate = formattedDate,
                transactionTime = timeStr,
                bankName = bankAndRecipient.bankName,
                recipientName = bankAndRecipient.recipientName,
                rawDescription = bankAndRecipient.rawDescription,
                amount = amount,
                status = RecordStatus.PENDING,
                source = RecordSource.NOTIFICATION,
                rawNotificationText = singleLine,
                dedupKey = dedupKey
            )
        )
    }

    fun convertMmDdToIsoDate(mmDd: String, referenceYear: Int = LocalDate.now().year): String {
        val parts = mmDd.split("/", "-", ".")
        if (parts.size == 2) {
            val month = parts[0].toIntOrNull() ?: 1
            val day = parts[1].toIntOrNull() ?: 1
            return "%04d-%02d-%02d".format(referenceYear, month, day)
        } else if (parts.size == 3) {
            val year = if (parts[0].length == 2) 2000 + (parts[0].toIntOrNull() ?: 26) else (parts[0].toIntOrNull() ?: referenceYear)
            val month = parts[1].toIntOrNull() ?: 1
            val day = parts[2].toIntOrNull() ?: 1
            return "%04d-%02d-%02d".format(year, month, day)
        }
        return LocalDate.now().toString()
    }

    fun formatWon(amount: Long): String = "%,d원".format(amount)
}
