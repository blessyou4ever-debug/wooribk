package com.woori.gyeongjobi.parser

import com.woori.gyeongjobi.data.ExpenseEntity
import com.woori.gyeongjobi.data.FilterConfig
import com.woori.gyeongjobi.data.RecordSource
import com.woori.gyeongjobi.data.RecordStatus
import java.time.LocalDate

/**
 * 은행명과 받는 사람 실명을 분리한 결과
 */
data class BankAndRecipient(
    val bankName: String,
    val recipientName: String,
    val rawDescription: String
)

/**
 * 파싱 결과 (통과 또는 필터링 사유 반환)
 */
sealed class ParseOutcome {
    data class Matched(val entity: ExpenseEntity) : ParseOutcome()
    data class Ignored(val reason: String) : ParseOutcome()
}

object WooriNotificationParser {

    const val WOORI_PACKAGE_NAME = "com.wooribank.smart.npib"

    /**
     * 우리WON뱅킹 타행이체 시 이름 앞에 붙는 은행 약칭 목록 (길이순 정렬로 최장 일치 우선)
     * 예: "신한조창헌" -> 은행: "신한", 이름: "조창헌"
     * 예: "새마을김철수" -> 은행: "새마을", 이름: "김철수"
     */
    val BANK_PREFIXES: List<String> = listOf(
        "카카오뱅크", "케이뱅크", "토스뱅크", "새마을금고", "아이엠뱅크", "SC제일",
        "새마을", "우체국", "카카오", "아이엠",
        "신한", "국민", "농협", "하나", "기업", "우리", "카뱅", "토스", "케뱅", "케이",
        "부산", "대구", "경남", "광주", "전북", "제주", "수협", "신협", "씨티", "제일", "산업", "산림", "저축",
        "KB", "NH", "IBK", "SC", "BNK", "DGB", "JB"
    ).sortedByDescending { it.length }

    /**
     * 개인 이체가 아닌 카드대금/간편결제/충전/공과금/상호명 제외 키워드
     */
    val NON_PERSONAL_KEYWORDS: List<String> = listOf(
        "페이", "충전", "카드", "대금", "출금", "보험", "관리비", "자동이체", "원리금", "이자",
        "세금", "지방세", "국세", "건강보험", "국민연금", "도시가스", "전기요금", "수도", "통신",
        "할부", "결제", "수수료", "ATM", "CD", "편의점", "마트", "백화점", "택시", "코레일",
        "쿠팡", "배달", "요기요", "배민", "네이버", "카카오페이", "토스페이", "삼성페이",
        "주식회사", "(주)", "유한회사", "법인", "병원", "약국", "학원", "관리단", "조합", "재단", "협회"
    )

    /**
     * 우리WON뱅킹 입출금알림 본문 정규식:
     * 예시 1: [출금] 신한조창헌 1,400,000원 1002-957-741***계좌 잔액 3,519,561원 10/27 05:36:06
     * 예시 2 (줄바꿈 포함):
     * [출금] NHBC카드대금 306,621원
     * 1002-957-741***계좌 잔액 5,126,675원 10/26
     * 17:29:32
     */
    private val NOTIFICATION_REGEX = Regex(
        pattern = """\[출금\]\s*(.+?)\s+([0-9,]+)원\s+([0-9*\-]+)계좌\s+잔액\s*([0-9,]+)원\s+(\d{2}/\d{2})\s+(\d{2}:\d{2}:\d{2})""",
        options = setOf(RegexOption.DOT_MATCHES_ALL)
    )

    /**
     * 계좌/잔액 정보가 축약된 알림 대응 보조 정규식
     */
    private val FALLBACK_WITHDRAWAL_REGEX = Regex(
        pattern = """\[출금\]\s*(.+?)\s+([0-9,]+)원.*?(?:(\d{2}/\d{2})\s+(\d{2}:\d{2}(?::\d{2})?))?""",
        options = setOf(RegexOption.DOT_MATCHES_ALL)
    )

    private val PURE_KOREAN_NAME_REGEX = Regex("^[가-힣]{2,4}$")

    /**
     * 적요 문자열(예: "신한조창헌", "김철수", "네이버페이충전", "K B카드출금")을 분석하여
     * 개인 이체 여부를 판별하고 은행명과 이름을 분리합니다.
     */
    fun extractBankAndPersonalName(rawDescription: String): BankAndRecipient? {
        val normalized = rawDescription.replace("\\s+".toRegex(), " ").trim()
        if (normalized.isEmpty()) return null

        // 1. 공백 제거 버전
        val compact = normalized.replace(" ", "")

        // 2. 숫자나 특수문자(-, _, (, ) 등)가 포함되어 있으면 개인 성명이 아님 (예: 현대10-063)
        if (compact.any { it.isDigit() || it == '-' || it == '_' || it == '(' || it == ')' || it == '/' }) {
            return null
        }

        // 3. 비개인 키워드 포함 여부 검사 (예: 네이버페이충전, KB카드출금, NHBC카드대금)
        val upperCompact = compact.uppercase()
        if (NON_PERSONAL_KEYWORDS.any { keyword -> upperCompact.contains(keyword.uppercase()) }) {
            return null
        }

        // 4. 은행 약칭 접두사 분리 시도 (예: "신한조창헌" -> 은행: "신한", 이름: "조창헌")
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

        // 5. 은행 접두사가 없는 경우 순수 한글 2~4글자 실명인지 확인 (우리은행 당행 이체 또는 실명만 찍힌 경우)
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

    /**
     * 우리WON뱅킹 알림 텍스트를 파싱하고 개인 여부 및 금액 단위 필터를 적용합니다.
     */
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

        // 줄바꿈을 공백으로 정규화하여 매칭
        val singleLine = rawText.replace(Regex("\\s+"), " ").trim()

        val match = NOTIFICATION_REGEX.find(singleLine)
        if (match != null) {
            val rawRecipient = match.groupValues[1].trim()
            val amountStr = match.groupValues[2].replace(",", "")
            val accountNum = match.groupValues[3].trim()
            val balanceStr = match.groupValues[4].replace(",", "")
            val mmDd = match.groupValues[5].trim() // 예: 10/27
            val timeStr = match.groupValues[6].trim() // 예: 05:36:06

            val amount = amountStr.toLongOrNull()
                ?: return ParseOutcome.Ignored("금액 파싱 실패: $amountStr")
            val balance = balanceStr.toLongOrNull()

            // 개인 입금(수취인) 판별 및 은행/이름 분리
            val bankAndRecipient = extractBankAndPersonalName(rawRecipient)
                ?: return ParseOutcome.Ignored("개인 이체 내역이 아닙니다: '$rawRecipient'")

            // 설정된 경조비 금액 단위 필터 확인
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

        // 보조 정규식 시도
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
