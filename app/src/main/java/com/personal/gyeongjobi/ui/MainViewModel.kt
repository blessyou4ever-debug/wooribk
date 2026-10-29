package com.personal.gyeongjobi.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.personal.gyeongjobi.backup.BackupImportManager
import com.personal.gyeongjobi.data.AppDatabase
import com.personal.gyeongjobi.data.EventType
import com.personal.gyeongjobi.data.ExpenseEntity
import com.personal.gyeongjobi.data.FilterConfig
import com.personal.gyeongjobi.data.FilterPreferences
import com.personal.gyeongjobi.data.RecordSource
import com.personal.gyeongjobi.data.RecordStatus
import com.personal.gyeongjobi.data.RelationType
import com.personal.gyeongjobi.parser.ParseOutcome
import com.personal.gyeongjobi.parser.WooriNotificationParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = AppDatabase.getInstance(application).expenseDao()
    private val filterPrefs = FilterPreferences(application)

    val allExpenses: StateFlow<List<ExpenseEntity>> = dao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val filterConfig: StateFlow<FilterConfig> = filterPrefs.configFlow

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    fun clearToast() {
        _toastMessage.value = null
    }

    fun confirmOrUpdateExpense(
        expense: ExpenseEntity,
        bankName: String,
        recipientName: String,
        amount: Long,
        transactionDate: String,
        eventType: EventType,
        relationType: RelationType,
        memo: String
    ) {
        viewModelScope.launch {
            val updated = expense.copy(
                bankName = bankName.trim().ifBlank { "우리" },
                recipientName = recipientName.trim(),
                amount = amount,
                transactionDate = transactionDate.trim(),
                status = RecordStatus.CONFIRMED,
                eventType = eventType,
                relationType = relationType,
                memo = memo.trim()
            )
            dao.update(updated)
            _toastMessage.value = "[${updated.bankName}] ${updated.recipientName} (${WooriNotificationParser.formatWon(updated.amount)}) 내역이 저장되었습니다."
        }
    }

    fun addManualExpense(
        bankName: String,
        recipientName: String,
        amount: Long,
        transactionDate: String,
        eventType: EventType,
        relationType: RelationType,
        memo: String
    ) {
        viewModelScope.launch {
            val nowTime = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
            val cleanBank = bankName.trim().ifBlank { "우리" }
            val cleanName = recipientName.trim()
            val dedupKey = "MANUAL_${transactionDate}_${nowTime}_${cleanBank}${cleanName}_${amount}_${System.currentTimeMillis()}"

            val entity = ExpenseEntity(
                transactionDate = transactionDate.ifBlank { LocalDate.now().toString() },
                transactionTime = nowTime,
                bankName = cleanBank,
                recipientName = cleanName,
                rawDescription = "$cleanBank$cleanName",
                amount = amount,
                status = RecordStatus.CONFIRMED,
                eventType = eventType,
                relationType = relationType,
                memo = memo.trim(),
                source = RecordSource.MANUAL,
                dedupKey = dedupKey
            )
            dao.insertIgnore(entity)
            _toastMessage.value = "[${entity.bankName}] ${entity.recipientName} 내역을 추가했습니다."
        }
    }

    fun deleteExpense(expense: ExpenseEntity) {
        viewModelScope.launch {
            dao.delete(expense)
            _toastMessage.value = "[${expense.bankName}] ${expense.recipientName} 내역을 삭제했습니다."
        }
    }

    fun deleteAllPending() {
        viewModelScope.launch {
            dao.deleteAllPending()
            _toastMessage.value = "확인 대기 중인 내역을 모두 비웠습니다."
        }
    }

    fun toggleAmountFilter(amount: Long) {
        filterPrefs.toggleAmount(amount)
    }

    fun addCustomAmountFilter(amount: Long) {
        filterPrefs.addCustomAmount(amount)
        _toastMessage.value = "${WooriNotificationParser.formatWon(amount)} 금액 필터가 추가되었습니다."
    }

    fun removeCustomAmountFilter(amount: Long) {
        filterPrefs.removeCustomAmount(amount)
    }

    fun setFilterByAmountEnabled(enabled: Boolean) {
        filterPrefs.setFilterByAmountEnabled(enabled)
    }

    fun simulateNotificationBatch() {
        viewModelScope.launch {
            val sampleNotifications = listOf(
                "[출금] 네이버페이충전 10,000원 1002-957-741***계좌 잔액 3,439,561원 10/28 16:59:49",
                "[출금] 네이버페이충전 70,000원 1002-957-741***계좌 잔액 3,449,561원 10/28 10:02:18",
                "[출금] 신한조창헌 1,400,000원 1002-957-741***계좌 잔액 3,519,561원 10/27 05:36:06",
                "[출금] K B카드출금 207,114원 1002-957-741***계좌 잔액 4,919,561원 10/26 18:09:30",
                "[출금] NHBC카드대금 306,621원 1002-957-741***계좌 잔액 5,126,675원 10/26 17:29:32",
                "[출금] 현대10-063 9,310원 1002-957-741***계좌 잔액 5,433,296원 10/26 17:28:07",
                "[출금] 신한조창헌 100,000원 1002-957-741***계좌 잔액 3,419,561원 10/28 18:10:00",
                "[출금] 국민김민지 50,000원 1002-957-741***계좌 잔액 3,369,561원 10/28 19:22:15",
                "[출금] 농협박성훈 200,000원 1002-957-741***계좌 잔액 3,169,561원 10/29 11:05:30",
                "[출금] 이수진 300,000원 1002-957-741***계좌 잔액 2,869,561원 10/29 11:40:12"
            )

            var matchedCount = 0
            var ignoredCount = 0
            val config = filterPrefs.currentConfig()

            for (raw in sampleNotifications) {
                when (val outcome = WooriNotificationParser.parseNotification("우리WON뱅킹 입출금알림", raw, config)) {
                    is ParseOutcome.Matched -> {
                        val rowId = dao.insertIgnore(outcome.entity)
                        if (rowId != -1L) matchedCount++
                    }
                    is ParseOutcome.Ignored -> ignoredCount++
                }
            }
            _toastMessage.value = "알림 10건 테스트 완료: 경조비 후보 ${matchedCount}건 수집, 비개인/비대상 ${ignoredCount}건 제외"
        }
    }

    fun testSingleNotificationText(rawText: String) {
        viewModelScope.launch {
            val outcome = WooriNotificationParser.parseNotification(
                title = "우리WON뱅킹 입출금알림",
                body = rawText,
                filterConfig = filterPrefs.currentConfig()
            )
            when (outcome) {
                is ParseOutcome.Matched -> {
                    val id = dao.insertIgnore(outcome.entity)
                    if (id != -1L) {
                        _toastMessage.value = "수집 성공: [${outcome.entity.bankName}] ${outcome.entity.recipientName} (${WooriNotificationParser.formatWon(outcome.entity.amount)})"
                    } else {
                        _toastMessage.value = "이미 동일한 내역이 존재합니다."
                    }
                }
                is ParseOutcome.Ignored -> {
                    _toastMessage.value = "제외됨: ${outcome.reason}"
                }
            }
        }
    }

    fun writeSampleTemplateToUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val csv = BackupImportManager.buildSampleTemplateCsv()
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(csv.toByteArray(Charsets.UTF_8))
                }
            }.onSuccess {
                _toastMessage.value = "과거 내역 업로드용 CSV 양식 파일을 저장했습니다."
            }.onFailure { e ->
                _toastMessage.value = "양식 저장 실패: ${e.localizedMessage}"
            }
        }
    }

    fun exportAllExpensesCsvToUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val list = dao.getAllOnce()
                val csv = BackupImportManager.exportExpensesToCsv(list)
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(csv.toByteArray(Charsets.UTF_8))
                }
                list.size
            }.onSuccess { count ->
                _toastMessage.value = "총 ${count}건의 내역을 엑셀 호환 CSV로 내보냈습니다."
            }.onFailure { e ->
                _toastMessage.value = "CSV 내보내기 실패: ${e.localizedMessage}"
            }
        }
    }

    fun exportFullBackupJsonToUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val list = dao.getAllOnce()
                val json = BackupImportManager.exportFullBackupJson(list, filterPrefs.currentConfig())
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(json.toByteArray(Charsets.UTF_8))
                }
                list.size
            }.onSuccess { count ->
                _toastMessage.value = "총 ${count}건의 내역과 필터 설정을 백업(JSON)했습니다."
            }.onFailure { e ->
                _toastMessage.value = "백업 실패: ${e.localizedMessage}"
            }
        }
    }

    fun importBackupJsonFromUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val raw = BackupImportManager.readTextFromUri(getApplication(), uri)
                val (entities, restoredFilter) = BackupImportManager.parseFullBackupJson(raw)
                if (restoredFilter != null) {
                    filterPrefs.restoreFromBackup(
                        enabledAmounts = restoredFilter.enabledAmounts,
                        customAmounts = restoredFilter.customAmounts,
                        filterByAmountEnabled = restoredFilter.filterByAmountEnabled
                    )
                }
                val insertResults = dao.insertAllIgnore(entities)
                val insertedCount = insertResults.count { it != -1L }
                insertedCount to entities.size
            }.onSuccess { (inserted, total) ->
                _toastMessage.value = "백업 복원 완료: 전체 ${total}건 중 신규 ${inserted}건 추가됨"
            }.onFailure { e ->
                _toastMessage.value = "백업 파일 복원 실패: ${e.localizedMessage}"
            }
        }
    }

    fun importPastHistoryCsvFromUri(uri: Uri, applyFilterOnBankExport: Boolean = true) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val raw = BackupImportManager.readTextFromUri(getApplication(), uri)
                val entities = BackupImportManager.parseUploadedHistoryText(
                    rawContent = raw,
                    filterConfig = filterPrefs.currentConfig(),
                    applyFilterOnBankExport = applyFilterOnBankExport
                )
                val insertResults = dao.insertAllIgnore(entities)
                val insertedCount = insertResults.count { it != -1L }
                insertedCount to entities.size
            }.onSuccess { (inserted, total) ->
                _toastMessage.value = "과거 내역 업로드 완료: 추출 ${total}건 중 신규 ${inserted}건 등록됨"
            }.onFailure { e ->
                _toastMessage.value = "업로드 파일 처리 실패: ${e.localizedMessage}"
            }
        }
    }
}
