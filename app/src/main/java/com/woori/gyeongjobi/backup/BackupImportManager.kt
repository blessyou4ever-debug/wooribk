package com.woori.gyeongjobi.backup

import android.content.Context
import android.net.Uri
import com.woori.gyeongjobi.data.EventType
import com.woori.gyeongjobi.data.ExpenseEntity
import com.woori.gyeongjobi.data.FilterConfig
import com.woori.gyeongjobi.data.RecordSource
import com.woori.gyeongjobi.data.RecordStatus
import com.woori.gyeongjobi.data.RelationType
import com.woori.gyeongjobi.parser.ParseOutcome
import com.woori.gyeongjobi.parser.WooriNotificationParser
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.Charset
import java.time.LocalDate

data class ImportSummary(
    val importedCount: Int,
    val skippedDuplicateOrFilteredCount: Int,
    val message: String
)

/**
 * 향후 구글 드라이브 등 클라우드 백업 확장을 위한 인터페이스
 */
interface CloudBackupProvider {
    val providerName: String
    suspend fun uploadBackupJson(jsonPayload: String): Result<String>
    suspend fun downloadLatestBackupJson(): Result<String>
}

object BackupImportManager {

    private const val UTF8_BOM = "\uFEFF"

    /**
     * 1. 과거 내역 업로드용 표준 CSV 양식 샘플 생성 (엑셀 한글 깨짐 방지 UTF-8 BOM 포함)
     */
    fun buildSampleTemplateCsv(): String {
        val sb = StringBuilder()
        sb.append(UTF8_BOM)
        sb.appendLine("일자,시간,은행,이름,금액,경조구분,관계,메모,상태")
        sb.appendLine("2026-10-27,14:30:00,신한,조창헌,100000,결혼/축의,직장/동료,개발팀 조창헌 매니저 결혼식,경조비 확정")
        sb.appendLine("2026-10-15,11:20:00,국민,김민지,50000,돌잔치/출산,친구/지인,대학 동기 첫째 돌잔치,경조비 확정")
        sb.appendLine("2026-09-28,18:10:00,농협,박철수,200000,부고/조의,친척/가족,외삼촌 조의금,경조비 확정")
        sb.appendLine("2026-09-10,09:00:00,우리,이영희,300000,생신/환갑/칠순,친척/가족,고모 칠순 생신,경조비 확정")
        return sb.toString()
    }

    /**
     * 2. 현재 저장된 경조비 내역 전체를 엑셀 호환 CSV로 내보내기
     */
    fun exportExpensesToCsv(expenses: List<ExpenseEntity>): String {
        val sb = StringBuilder()
        sb.append(UTF8_BOM)
        sb.appendLine("일자,시간,은행,이름,금액,경조구분,관계,메모,상태,계좌번호,원본적요")
        for (item in expenses) {
            sb.appendLine(
                listOf(
                    escapeCsv(item.transactionDate),
                    escapeCsv(item.transactionTime),
                    escapeCsv(item.bankName),
                    escapeCsv(item.recipientName),
                    item.amount.toString(),
                    escapeCsv(item.eventType.label),
                    escapeCsv(item.relationType.label),
                    escapeCsv(item.memo),
                    escapeCsv(item.status.label),
                    escapeCsv(item.accountNumber),
                    escapeCsv(item.rawDescription)
                ).joinToString(",")
            )
        }
        return sb.toString()
    }

    /**
     * 3. 기기 전체 백업용 JSON 생성 (내역 + 금액 필터 설정 포함)
     */
    fun exportFullBackupJson(expenses: List<ExpenseEntity>, filterConfig: FilterConfig): String {
        val root = JSONObject()
        root.put("backupVersion", 1)
        root.put("exportedAt", System.currentTimeMillis())

        val filterObj = JSONObject().apply {
            put("filterByAmountEnabled", filterConfig.filterByAmountEnabled)
            put("enabledAmounts", JSONArray(filterConfig.enabledAmounts.sorted()))
            put("customAmounts", JSONArray(filterConfig.customAmounts.sorted()))
        }
        root.put("filterConfig", filterObj)

        val itemsArray = JSONArray()
        for (item in expenses) {
            val obj = JSONObject().apply {
                put("transactionDate", item.transactionDate)
                put("transactionTime", item.transactionTime)
                put("bankName", item.bankName)
                put("recipientName", item.recipientName)
                put("rawDescription", item.rawDescription)
                put("amount", item.amount)
                put("accountNumber", item.accountNumber)
                if (item.balanceAfter != null) put("balanceAfter", item.balanceAfter)
                put("status", item.status.name)
                put("eventType", item.eventType.name)
                put("relationType", item.relationType.name)
                put("memo", item.memo)
                put("source", item.source.name)
                put("rawNotificationText", item.rawNotificationText)
                put("dedupKey", item.dedupKey)
            }
            itemsArray.put(obj)
        }
        root.put("expenses", itemsArray)
        return root.toString(2)
    }

    /**
     * 4. JSON 백업 파일 파싱 및 복원 데이터 추출
     */
    fun parseFullBackupJson(jsonText: String): Pair<List<ExpenseEntity>, FilterConfig?> {
        val cleaned = jsonText.removePrefix(UTF8_BOM).trim()
        val root = JSONObject(cleaned)

        val restoredFilter = root.optJSONObject("filterConfig")?.let { fObj ->
            val filterEnabled = fObj.optBoolean("filterByAmountEnabled", true)
            val enabledArr = fObj.optJSONArray("enabledAmounts") ?: JSONArray()
            val customArr = fObj.optJSONArray("customAmounts") ?: JSONArray()

            val enabledSet = mutableSetOf<Long>()
            for (i in 0 until enabledArr.length()) {
                enabledArr.optLong(i, -1L).takeIf { it > 0 }?.let { enabledSet.add(it) }
            }
            val customSet = mutableSetOf<Long>()
            for (i in 0 until customArr.length()) {
                customArr.optLong(i, -1L).takeIf { it > 0 }?.let { customSet.add(it) }
            }
            FilterConfig(
                enabledAmounts = if (enabledSet.isEmpty()) FilterConfig.DEFAULT_PRESET_AMOUNTS.toSet() else enabledSet,
                customAmounts = customSet,
                filterByAmountEnabled = filterEnabled
            )
        }

        val list = mutableListOf<ExpenseEntity>()
        val arr = root.optJSONArray("expenses") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val date = obj.optString("transactionDate", LocalDate.now().toString())
            val time = obj.optString("transactionTime", "00:00:00")
            val bank = obj.optString("bankName", "우리")
            val name = obj.optString("recipientName", "")
            val rawDesc = obj.optString("rawDescription", "$bank$name")
            val amount = obj.optLong("amount", 0L)
            if (name.isBlank() || amount <= 0L) continue

            val dedupKey = obj.optString("dedupKey").ifBlank {
                "${date}_${time}_${rawDesc}_${amount}"
            }

            list.add(
                ExpenseEntity(
                    transactionDate = date,
                    transactionTime = time,
                    bankName = bank,
                    recipientName = name,
                    rawDescription = rawDesc,
                    amount = amount,
                    accountNumber = obj.optString("accountNumber", ""),
                    balanceAfter = if (obj.has("balanceAfter")) obj.optLong("balanceAfter") else null,
                    status = RecordStatus.fromString(obj.optString("status", "CONFIRMED")),
                    eventType = EventType.fromLabelOrName(obj.optString("eventType", "ETC")),
                    relationType = RelationType.fromLabelOrName(obj.optString("relationType", "ETC")),
                    memo = obj.optString("memo", ""),
                    source = RecordSource.entries.find { it.name == obj.optString("source") } ?: RecordSource.CUSTOM_CSV,
                    rawNotificationText = obj.optString("rawNotificationText", ""),
                    dedupKey = dedupKey
                )
            )
        }
        return list to restoredFilter
    }

    /**
     * 5. 과거 내역 CSV 업로드 통합 파서
     * - 지원 형식 A: 앱 전용 CSV 양식 (`일자,시간,은행,이름,금액,경조구분,관계,메모,상태`)
     * - 지원 형식 B: 우리은행 거래내역 엑셀/CSV 내보내기 파일 (`거래일시/거래일자`, `적요/기재내용/받는분`, `출금금액/찾으신금액` 등 자동 인식 + 개인/금액 필터 적용)
     * - 지원 형식 C: 우리WON뱅킹 알림 텍스트 묶음
     */
    fun parseUploadedHistoryText(
        rawContent: String,
        filterConfig: FilterConfig,
        applyFilterOnBankExport: Boolean = true
    ): List<ExpenseEntity> {
        val lines = rawContent.removePrefix(UTF8_BOM)
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (lines.isEmpty()) return emptyList()

        // A. 알림 원문 텍스트 파일인 경우
        if (lines.any { it.contains("[출금]") && it.contains("계좌") }) {
            return lines.mapNotNull { line ->
                when (val res = WooriNotificationParser.parseNotification(null, line, filterConfig)) {
                    is ParseOutcome.Matched -> res.entity
                    is ParseOutcome.Ignored -> null
                }
            }
        }

        // 헤더 라인 탐색 (우리은행 CSV 내보내기는 상단에 계좌 요약 줄이 있을 수 있음)
        var headerIndex = 0
        for (i in lines.indices) {
            val l = lines[i]
            if ((l.contains("일자") || l.contains("일시") || l.contains("날짜")) &&
                (l.contains("금액") || l.contains("출금") || l.contains("찾으신"))
            ) {
                headerIndex = i
                break
            }
        }

        val headerCols = splitCsvLine(lines[headerIndex]).map { it.replace(" ", "") }
        val isCustomTemplate = headerCols.any { it.contains("경조구분") || (headerCols.contains("은행") && headerCols.contains("이름")) }

        return if (isCustomTemplate) {
            parseCustomTemplateRows(lines.drop(headerIndex + 1), headerCols)
        } else {
            parseWooriBankExportRows(
                rows = lines.drop(headerIndex + 1),
                headerCols = headerCols,
                filterConfig = filterConfig,
                applyFilter = applyFilterOnBankExport
            )
        }
    }

    private fun parseCustomTemplateRows(
        rows: List<String>,
        headerCols: List<String>
    ): List<ExpenseEntity> {
        val dateIdx = headerCols.indexOfFirst { it.contains("일자") || it.contains("날짜") }.takeIf { it >= 0 } ?: 0
        val timeIdx = headerCols.indexOfFirst { it.contains("시간") }.takeIf { it >= 0 } ?: 1
        val bankIdx = headerCols.indexOfFirst { it.contains("은행") }.takeIf { it >= 0 } ?: 2
        val nameIdx = headerCols.indexOfFirst { it.contains("이름") || it.contains("성명") || it.contains("받는분") }.takeIf { it >= 0 } ?: 3
        val amountIdx = headerCols.indexOfFirst { it.contains("금액") || it.contains("출금") }.takeIf { it >= 0 } ?: 4
        val eventIdx = headerCols.indexOfFirst { it.contains("경조") || it.contains("구분") }.takeIf { it >= 0 } ?: 5
        val relationIdx = headerCols.indexOfFirst { it.contains("관계") }.takeIf { it >= 0 } ?: 6
        val memoIdx = headerCols.indexOfFirst { it.contains("메모") || it.contains("비고") }.takeIf { it >= 0 } ?: 7
        val statusIdx = headerCols.indexOfFirst { it.contains("상태") }.takeIf { it >= 0 } ?: 8

        val results = mutableListOf<ExpenseEntity>()
        for ((rowNum, line) in rows.withIndex()) {
            val cols = splitCsvLine(line)
            if (cols.size <= amountIdx) continue

            val rawDate = cols.getOrNull(dateIdx).orEmpty().trim()
            val rawTime = cols.getOrNull(timeIdx).orEmpty().trim().ifBlank { "12:00:00" }
            val rawBank = cols.getOrNull(bankIdx).orEmpty().trim()
            val rawName = cols.getOrNull(nameIdx).orEmpty().trim()
            val rawAmount = cols.getOrNull(amountIdx).orEmpty().replace(",", "").replace("원", "").trim()
            val amount = rawAmount.toLongOrNull() ?: continue
            if (amount <= 0L) continue

            // 만약 은행란이 비어 있고 이름란에 "신한조창헌"처럼 합쳐져 있다면 자동 분리
            val separated = if (rawBank.isBlank()) {
                WooriNotificationParser.extractBankAndPersonalName(rawName)
            } else null

            val finalBank = separated?.bankName ?: rawBank.ifBlank { "우리" }
            val finalName = separated?.recipientName ?: rawName
            if (finalName.isBlank()) continue

            val eventType = EventType.fromLabelOrName(cols.getOrNull(eventIdx).orEmpty())
            val relationType = RelationType.fromLabelOrName(cols.getOrNull(relationIdx).orEmpty())
            val memo = cols.getOrNull(memoIdx).orEmpty().trim()
            val statusText = cols.getOrNull(statusIdx).orEmpty().trim()
            val status = if (statusText.contains("대기") || statusText.equals("PENDING", ignoreCase = true)) {
                RecordStatus.PENDING
            } else {
                RecordStatus.CONFIRMED
            }

            val normalizedDate = normalizeDateString(rawDate)
            val dedupKey = "${normalizedDate}_${rawTime}_${finalBank}${finalName}_${amount}_${rowNum}"

            results.add(
                ExpenseEntity(
                    transactionDate = normalizedDate,
                    transactionTime = rawTime,
                    bankName = finalBank,
                    recipientName = finalName,
                    rawDescription = "$finalBank$finalName",
                    amount = amount,
                    status = status,
                    eventType = eventType,
                    relationType = relationType,
                    memo = memo,
                    source = RecordSource.CUSTOM_CSV,
                    dedupKey = dedupKey
                )
            )
        }
        return results
    }

    /**
     * 우리은행 엑셀/CSV 거래내역 파일 파싱
     * 일반 컬럼 구성 예: [거래일시, 적요, 기재내용(또는 받는분/의뢰인), 찾으신금액(출금), 맡기신금액(입금), 거래후잔액, 취급점]
     */
    private fun parseWooriBankExportRows(
        rows: List<String>,
        headerCols: List<String>,
        filterConfig: FilterConfig,
        applyFilter: Boolean
    ): List<ExpenseEntity> {
        val dateTimeIdx = headerCols.indexOfFirst { it.contains("거래일시") || it.contains("거래일자") || it.contains("일자") || it.contains("날짜") }
            .takeIf { it >= 0 } ?: 0

        // 받는분/기재내용/적요 후보 컬럼들
        val descIndices = headerCols.mapIndexedNotNull { idx, name ->
            if (name.contains("기재내용") || name.contains("받는분") || name.contains("의뢰인") || name.contains("적요") || name.contains("내용")) idx else null
        }.ifEmpty { listOf(1, 2) }

        val withdrawalIdx = headerCols.indexOfFirst {
            it.contains("찾으신") || it.contains("출금") || it.contains("지급") || it == "금액"
        }.takeIf { it >= 0 } ?: 3

        val balanceIdx = headerCols.indexOfFirst { it.contains("잔액") }

        val results = mutableListOf<ExpenseEntity>()
        for (line in rows) {
            val cols = splitCsvLine(line)
            if (cols.size <= withdrawalIdx) continue

            val amountStr = cols.getOrNull(withdrawalIdx).orEmpty()
                .replace(",", "")
                .replace("원", "")
                .trim()
            val amount = amountStr.toLongOrNull() ?: continue
            if (amount <= 0L) continue

            if (applyFilter && !filterConfig.isAmountAllowed(amount)) {
                continue
            }

            // 기재내용/적요 컬럼들 중 개인 이름(은행+이름)으로 판별되는 항목 탐색
            val matchedPerson = descIndices.firstNotNullOfOrNull { idx ->
                val cell = cols.getOrNull(idx).orEmpty().trim()
                WooriNotificationParser.extractBankAndPersonalName(cell)
            } ?: continue

            val rawDateTime = cols.getOrNull(dateTimeIdx).orEmpty().trim()
            val (datePart, timePart) = splitDateTime(rawDateTime)
            val balance = if (balanceIdx >= 0) {
                cols.getOrNull(balanceIdx)?.replace(",", "")?.replace("원", "")?.trim()?.toLongOrNull()
            } else null

            val dedupKey = "${datePart}_${timePart}_${matchedPerson.rawDescription}_${amount}_${balance ?: 0L}"

            results.add(
                ExpenseEntity(
                    transactionDate = datePart,
                    transactionTime = timePart,
                    bankName = matchedPerson.bankName,
                    recipientName = matchedPerson.recipientName,
                    rawDescription = matchedPerson.rawDescription,
                    amount = amount,
                    balanceAfter = balance,
                    status = RecordStatus.PENDING,
                    source = RecordSource.WOORI_CSV,
                    dedupKey = dedupKey
                )
            )
        }
        return results
    }

    /**
     * URI에서 텍스트를 읽을 때 UTF-8 및 한글 엑셀(EUC-KR / CP949) 인코딩 자동 감지
     */
    fun readTextFromUri(context: Context, uri: Uri): String {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return ""
        if (bytes.isEmpty()) return ""

        // UTF-8 BOM 확인
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, Charsets.UTF_8)
        }

        val utf8Text = String(bytes, Charsets.UTF_8)
        // UTF-8 디코딩 시 깨진 문자(\uFFFD)가 없으면 UTF-8 사용, 있으면 CP949(EUC-KR)로 디코딩
        return if (!utf8Text.contains('\uFFFD')) {
            utf8Text
        } else {
            String(bytes, Charset.forName("MS949"))
        }
    }

    private fun splitDateTime(raw: String): Pair<String, String> {
        val trimmed = raw.trim()
        val parts = trimmed.split(" ", "T")
        val dateStr = normalizeDateString(parts.firstOrNull().orEmpty())
        val timeStr = parts.getOrNull(1)?.trim()?.ifBlank { "12:00:00" } ?: "12:00:00"
        return dateStr to timeStr
    }

    private fun normalizeDateString(raw: String): String {
        val cleaned = raw.trim().replace(".", "-").replace("/", "-")
        val parts = cleaned.split("-").filter { it.isNotBlank() }
        return when (parts.size) {
            3 -> {
                val y = if (parts[0].length == 2) 2000 + (parts[0].toIntOrNull() ?: 26) else (parts[0].toIntOrNull() ?: LocalDate.now().year)
                val m = parts[1].toIntOrNull() ?: 1
                val d = parts[2].toIntOrNull() ?: 1
                "%04d-%02d-%02d".format(y, m, d)
            }
            2 -> WooriNotificationParser.convertMmDdToIsoDate(cleaned)
            else -> {
                if (cleaned.length == 8 && cleaned.all { it.isDigit() }) {
                    "${cleaned.substring(0, 4)}-${cleaned.substring(4, 6)}-${cleaned.substring(6, 8)}"
                } else {
                    LocalDate.now().toString()
                }
            }
        }
    }

    fun splitCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' -> {
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                        current.append('"')
                        i++
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                (c == ',' || c == '\t') && !inQuotes -> {
                    result.add(current.toString().trim())
                    current.clear()
                }
                else -> current.append(c)
            }
            i++
        }
        result.add(current.toString().trim())
        return result
    }

    private fun escapeCsv(value: String): String {
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"${value.replace("\"", "\"\"")}\""
        }
        return value
    }
}
