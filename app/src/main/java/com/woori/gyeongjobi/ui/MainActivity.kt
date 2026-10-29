package com.woori.gyeongjobi.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.woori.gyeongjobi.data.EventType
import com.woori.gyeongjobi.data.ExpenseEntity
import com.woori.gyeongjobi.data.FilterConfig
import com.woori.gyeongjobi.data.RecordStatus
import com.woori.gyeongjobi.data.RelationType
import com.woori.gyeongjobi.parser.WooriNotificationParser
import com.woori.gyeongjobi.service.WooriNotificationService
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }

        setContent {
            val wooriColorScheme = lightColorScheme(
                primary = Color(0xFF0067AC),
                onPrimary = Color.White,
                primaryContainer = Color(0xFFD4E9FF),
                onPrimaryContainer = Color(0xFF001E38),
                secondary = Color(0xFF2B5C8F),
                background = Color(0xFFF6F8FB),
                surface = Color.White
            )
            MaterialTheme(colorScheme = wooriColorScheme) {
                GyeongjobiAppScreen()
            }
        }
    }
}

enum class MainTab(val title: String) {
    PENDING("확인 대기"),
    LEDGER("경조비 장부"),
    STATS("통계/인물"),
    SETTINGS("백업/설정")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GyeongjobiAppScreen(vm: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val allExpenses by vm.allExpenses.collectAsStateWithLifecycle()
    val filterConfig by vm.filterConfig.collectAsStateWithLifecycle()
    val toastMsg by vm.toastMessage.collectAsStateWithLifecycle()

    LaunchedEffect(toastMsg) {
        toastMsg?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            vm.clearToast()
        }
    }

    val pendingList = remember(allExpenses) {
        allExpenses.filter { it.status == RecordStatus.PENDING }
    }
    val confirmedList = remember(allExpenses) {
        allExpenses.filter { it.status == RecordStatus.CONFIRMED }
    }

    var currentTab by remember { mutableStateOf(MainTab.PENDING) }
    var editingExpense by remember { mutableStateOf<ExpenseEntity?>(null) }
    var showManualAddDialog by remember { mutableStateOf(false) }

    // 알림 접근 권한 활성화 상태 감지
    var hasNotificationAccess by remember {
        mutableStateOf(isNotificationServiceEnabled(context))
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasNotificationAccess = isNotificationServiceEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "우리WON 경조비 매니저",
                            fontWeight = FontWeight.Bold,
                            fontSize = 19.sp
                        )
                        Text(
                            text = "확정 ${confirmedList.size}건 (${WooriNotificationParser.formatWon(confirmedList.sumOf { it.amount })}) · 대기 ${pendingList.size}건",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.88f)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showManualAddDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "직접 내역 추가",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = Color.White
                )
            )
        },
        bottomBar = {
            NavigationBar(containerColor = Color.White) {
                MainTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = currentTab == tab,
                        onClick = { currentTab = tab },
                        icon = {
                            BadgedBox(
                                badge = {
                                    if (tab == MainTab.PENDING && pendingList.isNotEmpty()) {
                                        Badge { Text(pendingList.size.toString()) }
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = when (tab) {
                                        MainTab.PENDING -> Icons.Default.NotificationsActive
                                        MainTab.LEDGER -> Icons.Default.MenuBook
                                        MainTab.STATS -> Icons.Default.PieChart
                                        MainTab.SETTINGS -> Icons.Default.Settings
                                    },
                                    contentDescription = tab.title
                                )
                            }
                        },
                        label = { Text(tab.title) }
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (!hasNotificationAccess) {
                NotificationPermissionBanner(
                    onOpenSettings = {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }
                )
            }

            when (currentTab) {
                MainTab.PENDING -> PendingTabScreen(
                    pendingList = pendingList,
                    onConfirmClick = { editingExpense = it },
                    onDeleteClick = { vm.deleteExpense(it) },
                    onSimulateBatch = { vm.simulateNotificationBatch() },
                    onTestSingleText = { vm.testSingleNotificationText(it) },
                    onClearAllPending = { vm.deleteAllPending() }
                )

                MainTab.LEDGER -> ConfirmedLedgerTabScreen(
                    confirmedList = confirmedList,
                    onEditClick = { editingExpense = it },
                    onDeleteClick = { vm.deleteExpense(it) },
                    onAddManualClick = { showManualAddDialog = true }
                )

                MainTab.STATS -> StatisticsTabScreen(
                    confirmedList = confirmedList
                )

                MainTab.SETTINGS -> BackupAndSettingsTabScreen(
                    vm = vm,
                    filterConfig = filterConfig,
                    hasNotificationAccess = hasNotificationAccess,
                    onOpenNotificationSettings = {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }
                )
            }
        }
    }

    if (editingExpense != null) {
        ConfirmOrEditExpenseDialog(
            initialExpense = editingExpense!!,
            onDismiss = { editingExpense = null },
            onSave = { bank, name, amount, date, eventType, relationType, memo ->
                vm.confirmOrUpdateExpense(
                    expense = editingExpense!!,
                    bankName = bank,
                    recipientName = name,
                    amount = amount,
                    transactionDate = date,
                    eventType = eventType,
                    relationType = relationType,
                    memo = memo
                )
                editingExpense = null
            },
            onDelete = {
                vm.deleteExpense(editingExpense!!)
                editingExpense = null
            }
        )
    }

    if (showManualAddDialog) {
        ManualAddExpenseDialog(
            onDismiss = { showManualAddDialog = false },
            onAdd = { bank, name, amount, date, eventType, relationType, memo ->
                vm.addManualExpense(bank, name, amount, date, eventType, relationType, memo)
                showManualAddDialog = false
            }
        )
    }
}

@Composable
fun NotificationPermissionBanner(onOpenSettings: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF4E5))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.WarningAmber,
                contentDescription = null,
                tint = Color(0xFFE65100)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "우리WON뱅킹 알림 읽기 권한이 필요합니다",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = Color(0xFF4E342E)
                )
                Text(
                    text = "권한을 허용하면 출금 알림 중 개인 경조비 내역만 자동으로 모아줍니다.",
                    fontSize = 12.sp,
                    color = Color(0xFF6D4C41)
                )
            }
            TextButton(onClick = onOpenSettings) {
                Text("권한 설정", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun PendingTabScreen(
    pendingList: List<ExpenseEntity>,
    onConfirmClick: (ExpenseEntity) -> Unit,
    onDeleteClick: (ExpenseEntity) -> Unit,
    onSimulateBatch: () -> Unit,
    onTestSingleText: (String) -> Unit,
    onClearAllPending: () -> Unit
) {
    var showSimulatorSheet by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        // 상단 안내 및 테스트 바
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "새로 감지된 후보 (${pendingList.size}건)",
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = { showSimulatorSheet = !showSimulatorSheet },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(if (showSimulatorSheet) "테스트 닫기" else "알림 테스트", fontSize = 12.sp)
                }
                if (pendingList.isNotEmpty()) {
                    TextButton(onClick = onClearAllPending) {
                        Text("모두 비우기", fontSize = 12.sp, color = Color(0xFFC62828))
                    }
                }
            }
        }

        if (showSimulatorSheet) {
            NotificationSimulatorCard(
                onSimulateBatch = onSimulateBatch,
                onTestSingleText = onTestSingleText
            )
        }

        if (pendingList.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.CheckCircleOutline,
                        contentDescription = null,
                        modifier = Modifier.size(54.dp),
                        tint = Color(0xFF90A4AE)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "확인 대기 중인 출금 알림이 없습니다.",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        color = Color(0xFF455A64)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "우리WON뱅킹에서 설정 금액(5만/10만/20만원 등)이 개인에게 출금되면 이곳에 자동으로 표시됩니다.",
                        fontSize = 13.sp,
                        color = Color(0xFF78909C)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = onSimulateBatch) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("예시 알림 데이터로 지금 바로 테스트해보기")
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(pendingList, key = { it.id }) { item ->
                    PendingExpenseCard(
                        expense = item,
                        onConfirm = { onConfirmClick(item) },
                        onDelete = { onDeleteClick(item) }
                    )
                }
            }
        }
    }
}

@Composable
fun NotificationSimulatorCard(
    onSimulateBatch: () -> Unit,
    onTestSingleText: (String) -> Unit
) {
    var sampleInput by remember {
        mutableStateOf("[출금] 신한조창헌 100,000원 1002-957-741***계좌 잔액 3,519,561원 10/27 05:36:06")
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFEAF3FF))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "우리WON뱅킹 알림 파싱 테스트기",
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = Color(0xFF004B87)
            )
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = sampleInput,
                onValueChange = { sampleInput = it },
                label = { Text("알림 원문 입력") },
                modifier = Modifier.fillMaxWidth(),
                textStyle = LocalTextStyle.current.copy(fontSize = 12.sp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { onTestSingleText(sampleInput) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("위 문구 파싱 테스트", fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = onSimulateBatch,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("스크린샷+샘플 10건 일괄 테스트", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun PendingExpenseCard(
    expense: ExpenseEntity,
    onConfirm: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = expense.bankName,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = expense.recipientName,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "(원본: ${expense.rawDescription})",
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                }
                Text(
                    text = WooriNotificationParser.formatWon(expense.amount),
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "일시: ${expense.transactionDate} ${expense.transactionTime} · 출처: ${expense.source.label}",
                fontSize = 12.sp,
                color = Color(0xFF607D8B)
            )
            if (expense.rawNotificationText.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = expense.rawNotificationText,
                    fontSize = 11.sp,
                    color = Color(0xFF90A4AE),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onDelete,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFC62828))
                ) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("경조비 아님 (삭제)")
                }
                Button(
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("경조비 확정·분류")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmedLedgerTabScreen(
    confirmedList: List<ExpenseEntity>,
    onEditClick: (ExpenseEntity) -> Unit,
    onDeleteClick: (ExpenseEntity) -> Unit,
    onAddManualClick: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedEventFilter by remember { mutableStateOf<EventType?>(null) }

    val filtered = remember(confirmedList, searchQuery, selectedEventFilter) {
        confirmedList.filter { item ->
            val matchesEvent = selectedEventFilter == null || item.eventType == selectedEventFilter
            val q = searchQuery.trim()
            val matchesQuery = q.isEmpty() ||
                item.recipientName.contains(q, ignoreCase = true) ||
                item.bankName.contains(q, ignoreCase = true) ||
                item.memo.contains(q, ignoreCase = true) ||
                item.relationType.label.contains(q, ignoreCase = true)
            matchesEvent && matchesQuery
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 검색창
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("이름, 은행, 관계, 메모로 검색") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Clear, contentDescription = "지우기")
                    }
                }
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        )

        // 경조 구분 필터 칩
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item {
                FilterChip(
                    selected = selectedEventFilter == null,
                    onClick = { selectedEventFilter = null },
                    label = { Text("전체 (${confirmedList.size})") }
                )
            }
            items(EventType.entries) { type ->
                FilterChip(
                    selected = selectedEventFilter == type,
                    onClick = {
                        selectedEventFilter = if (selectedEventFilter == type) null else type
                    },
                    label = { Text("${type.emoji} ${type.label}") }
                )
            }
        }

        // 필터 합계 요약 바
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(10.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "조회된 경조비 총 ${filtered.size}건",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp
                )
                Text(
                    text = WooriNotificationParser.formatWon(filtered.sumOf { it.amount }),
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        if (filtered.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "표시할 경조비 확정 내역이 없습니다.",
                        color = Color.Gray,
                        fontSize = 15.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(onClick = onAddManualClick) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("직접 내역 추가하기")
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filtered, key = { it.id }) { item ->
                    ConfirmedExpenseRowCard(
                        expense = item,
                        onClick = { onEditClick(item) },
                        onDelete = { onDeleteClick(item) }
                    )
                }
            }
        }
    }
}

@Composable
fun ConfirmedExpenseRowCard(
    expense: ExpenseEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${expense.eventType.emoji} ${expense.eventType.label}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "· ${expense.relationType.label}",
                        fontSize = 12.sp,
                        color = Color(0xFF546E7A)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = Color(0xFFECEFF1),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = expense.bankName,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF37474F)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = expense.recipientName,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    )
                }
                if (expense.memo.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = expense.memo,
                        fontSize = 12.sp,
                        color = Color(0xFF455A64)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${expense.transactionDate} ${expense.transactionTime}",
                    fontSize = 11.sp,
                    color = Color(0xFF90A4AE)
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = WooriNotificationParser.formatWon(expense.amount),
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 16.sp,
                    color = Color(0xFF1A237E)
                )
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "삭제",
                        tint = Color(0xFFB0BEC5)
                    )
                }
            }
        }
    }
}

@Composable
fun StatisticsTabScreen(confirmedList: List<ExpenseEntity>) {
    val totalAmount = remember(confirmedList) { confirmedList.sumOf { it.amount } }
    val byEvent = remember(confirmedList) {
        confirmedList.groupBy { it.eventType }
            .mapValues { (_, list) -> list.sumOf { it.amount } to list.size }
            .entries
            .sortedByDescending { it.value.first }
    }
    val byRelation = remember(confirmedList) {
        confirmedList.groupBy { it.relationType }
            .mapValues { (_, list) -> list.sumOf { it.amount } to list.size }
            .entries
            .sortedByDescending { it.value.first }
    }
    val byPerson = remember(confirmedList) {
        confirmedList.groupBy { "${it.recipientName} (${it.bankName})" }
            .mapValues { (_, list) -> list.sumOf { it.amount } to list.size }
            .entries
            .sortedByDescending { it.value.first }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "누적 경조비 지출 총액",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = WooriNotificationParser.formatWon(totalAmount),
                        color = Color.White,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "총 ${confirmedList.size}건 · 인물 ${byPerson.size}명",
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 12.sp
                    )
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("경조 구분별 지출", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    if (byEvent.isEmpty()) {
                        Text("확정된 내역이 없습니다.", fontSize = 13.sp, color = Color.Gray)
                    } else {
                        byEvent.forEach { (eventType, pair) ->
                            val (sum, count) = pair
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("${eventType.emoji} ${eventType.label} (${count}건)", fontSize = 14.sp)
                                Text(
                                    WooriNotificationParser.formatWon(sum),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("관계별 지출", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    if (byRelation.isEmpty()) {
                        Text("확정된 내역이 없습니다.", fontSize = 13.sp, color = Color.Gray)
                    } else {
                        byRelation.forEach { (relationType, pair) ->
                            val (sum, count) = pair
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("${relationType.label} (${count}건)", fontSize = 14.sp)
                                Text(
                                    WooriNotificationParser.formatWon(sum),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("인물별 누적 경조비 내역", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    if (byPerson.isEmpty()) {
                        Text("확정된 내역이 없습니다.", fontSize = 13.sp, color = Color.Gray)
                    } else {
                        byPerson.forEach { (personKey, pair) ->
                            val (sum, count) = pair
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("$personKey · ${count}회", fontSize = 14.sp)
                                Text(
                                    WooriNotificationParser.formatWon(sum),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BackupAndSettingsTabScreen(
    vm: MainViewModel,
    filterConfig: FilterConfig,
    hasNotificationAccess: Boolean,
    onOpenNotificationSettings: () -> Unit
) {
    var customManWonInput by remember { mutableStateOf("") }
    val todayCompact = remember { LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) }

    // 1. 샘플 양식 CSV 저장 런처
    val sampleTemplateLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) vm.writeSampleTemplateToUri(uri)
    }

    // 2. 과거 내역 CSV 업로드 런처 (전용 양식 & 우리은행 엑셀/CSV 모두 호환)
    val importCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) vm.importPastHistoryCsvFromUri(uri, applyFilterOnBankExport = true)
    }

    // 3. 기기 전체 백업 JSON 저장 런처
    val exportBackupJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) vm.exportFullBackupJsonToUri(uri)
    }

    // 4. 기기 백업 JSON 복원 런처
    val importBackupJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) vm.importBackupJsonFromUri(uri)
    }

    // 5. 전체 장부 엑셀용 CSV 내보내기 런처
    val exportAllCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) vm.exportAllExpensesCsvToUri(uri)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. 수집 금액 단위 커스터마이징 카드
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("경조비 수집 금액 단위 설정", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text(
                            "체크된 금액 단위가 개인에게 출금되었을 때만 경조비 후보로 수집합니다.",
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                    }
                    Switch(
                        checked = filterConfig.filterByAmountEnabled,
                        onCheckedChange = { vm.setFilterByAmountEnabled(it) }
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    filterConfig.allAvailableAmounts.forEach { amount ->
                        val isSelected = filterConfig.enabledAmounts.contains(amount)
                        val isCustom = filterConfig.customAmounts.contains(amount)
                        val labelText = if (amount % 10_000L == 0L) {
                            "${amount / 10_000L}만원"
                        } else {
                            WooriNotificationParser.formatWon(amount)
                        }

                        FilterChip(
                            selected = isSelected,
                            onClick = { vm.toggleAmountFilter(amount) },
                            label = { Text(labelText) },
                            trailingIcon = if (isCustom) {
                                {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "삭제",
                                        modifier = Modifier
                                            .size(16.dp)
                                            .clickable { vm.removeCustomAmountFilter(amount) }
                                    )
                                }
                            } else null
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = customManWonInput,
                        onValueChange = { customManWonInput = it.filter { ch -> ch.isDigit() } },
                        label = { Text("직접 금액 추가 (단위: 만원, 예: 25)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            val manWon = customManWonInput.toLongOrNull()
                            if (manWon != null && manWon > 0) {
                                vm.addCustomAmountFilter(manWon * 10_000L)
                                customManWonInput = ""
                            }
                        }
                    ) {
                        Text("추가")
                    }
                }
            }
        }

        // 2. 과거 내역 업로드 & 양식 제공 카드
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text("과거 내역 업로드 (전용 양식 & 우리은행 내역)", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "앱 전용 CSV 양식 또는 우리은행에서 내려받은 거래내역 CSV 파일을 모두 지원합니다. 타행이체(예: 신한조창헌)도 은행과 이름을 자동으로 분리해 가져옵니다.",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { sampleTemplateLauncher.launch("과거내역_업로드_양식.csv") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("과거 내역 업로드용 CSV 양식 내려받기")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { importCsvLauncher.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel", "*/*")) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.UploadFile, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("과거 내역 CSV 파일 업로드 (전용양식 / 우리은행내역)")
                }
            }
        }

        // 3. 내 기기 백업 및 불러오기 카드
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text("내 기기 백업 및 복원", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "경조비 장부 전체 내역과 맞춤 금액 필터 설정을 내 기기에 JSON 파일로 백업하거나 엑셀(CSV)로 내보낼 수 있습니다.",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { exportBackupJsonLauncher.launch("경조비내역_백업_$todayCompact.json") },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("기기 백업 저장(JSON)", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = { importBackupJsonLauncher.launch(arrayOf("application/json", "text/*", "*/*")) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("백업 불러오기(복원)", fontSize = 12.sp)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { exportAllCsvLauncher.launch("경조비장부_전체_$todayCompact.csv") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.TableView, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("엑셀용 전체 경조비 장부 내보내기 (CSV)")
                }
            }
        }

        // 4. 알림 권한 상태 카드
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("우리WON뱅킹 알림 연동 상태", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(
                        text = if (hasNotificationAccess) "정상 작동 중 (알림 접근 허용됨)" else "권한 미허용 (알림 접근 권한 필요)",
                        fontSize = 12.sp,
                        color = if (hasNotificationAccess) Color(0xFF2E7D32) else Color(0xFFC62828)
                    )
                }
                OutlinedButton(onClick = onOpenNotificationSettings) {
                    Text("권한 설정 열기")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ConfirmOrEditExpenseDialog(
    initialExpense: ExpenseEntity,
    onDismiss: () -> Unit,
    onSave: (String, String, Long, String, EventType, RelationType, String) -> Unit,
    onDelete: () -> Unit
) {
    var bankName by remember { mutableStateOf(initialExpense.bankName) }
    var recipientName by remember { mutableStateOf(initialExpense.recipientName) }
    var amountText by remember { mutableStateOf(initialExpense.amount.toString()) }
    var dateText by remember { mutableStateOf(initialExpense.transactionDate) }
    var selectedEvent by remember { mutableStateOf(initialExpense.eventType) }
    var selectedRelation by remember { mutableStateOf(initialExpense.relationType) }
    var memo by remember { mutableStateOf(initialExpense.memo) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (initialExpense.status == RecordStatus.PENDING) "경조비 내역 확정 및 분류" else "경조비 내역 수정",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = bankName,
                        onValueChange = { bankName = it },
                        label = { Text("은행") },
                        singleLine = true,
                        modifier = Modifier.weight(0.42f)
                    )
                    OutlinedTextField(
                        value = recipientName,
                        onValueChange = { recipientName = it },
                        label = { Text("이름 (받는분)") },
                        singleLine = true,
                        modifier = Modifier.weight(0.58f)
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { amountText = it.filter { ch -> ch.isDigit() } },
                        label = { Text("금액(원)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(0.5f)
                    )
                    OutlinedTextField(
                        value = dateText,
                        onValueChange = { dateText = it },
                        label = { Text("일자 (YYYY-MM-DD)") },
                        singleLine = true,
                        modifier = Modifier.weight(0.5f)
                    )
                }

                Text("경조 구분", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    EventType.entries.forEach { type ->
                        FilterChip(
                            selected = selectedEvent == type,
                            onClick = { selectedEvent = type },
                            label = { Text("${type.emoji} ${type.label}", fontSize = 12.sp) }
                        )
                    }
                }

                Text("관계", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    RelationType.entries.forEach { rel ->
                        FilterChip(
                            selected = selectedRelation == rel,
                            onClick = { selectedRelation = rel },
                            label = { Text(rel.label, fontSize = 12.sp) }
                        )
                    }
                }

                OutlinedTextField(
                    value = memo,
                    onValueChange = { memo = it },
                    label = { Text("메모 (예: 대학 동기 결혼식, 외삼촌 조의금)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val amt = amountText.toLongOrNull() ?: initialExpense.amount
                    if (recipientName.isNotBlank() && amt > 0L) {
                        onSave(bankName, recipientName, amt, dateText, selectedEvent, selectedRelation, memo)
                    }
                }
            ) {
                Text("확정 저장")
            }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFC62828))
                ) {
                    Text("내역 삭제")
                }
                TextButton(onClick = onDismiss) {
                    Text("취소")
                }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ManualAddExpenseDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String, Long, String, EventType, RelationType, String) -> Unit
) {
    var bankName by remember { mutableStateOf("우리") }
    var recipientName by remember { mutableStateOf("") }
    var amountText by remember { mutableStateOf("100000") }
    var dateText by remember { mutableStateOf(LocalDate.now().toString()) }
    var selectedEvent by remember { mutableStateOf(EventType.MARRIAGE) }
    var selectedRelation by remember { mutableStateOf(RelationType.FRIEND) }
    var memo by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("경조비 지출 직접 추가", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = bankName,
                        onValueChange = { bankName = it },
                        label = { Text("은행") },
                        singleLine = true,
                        modifier = Modifier.weight(0.42f)
                    )
                    OutlinedTextField(
                        value = recipientName,
                        onValueChange = { recipientName = it },
                        label = { Text("이름 (받는분)") },
                        singleLine = true,
                        modifier = Modifier.weight(0.58f)
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { amountText = it.filter { ch -> ch.isDigit() } },
                        label = { Text("금액(원)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(0.5f)
                    )
                    OutlinedTextField(
                        value = dateText,
                        onValueChange = { dateText = it },
                        label = { Text("일자 (YYYY-MM-DD)") },
                        singleLine = true,
                        modifier = Modifier.weight(0.5f)
                    )
                }

                Text("경조 구분", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    EventType.entries.forEach { type ->
                        FilterChip(
                            selected = selectedEvent == type,
                            onClick = { selectedEvent = type },
                            label = { Text("${type.emoji} ${type.label}", fontSize = 12.sp) }
                        )
                    }
                }

                Text("관계", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    RelationType.entries.forEach { rel ->
                        FilterChip(
                            selected = selectedRelation == rel,
                            onClick = { selectedRelation = rel },
                            label = { Text(rel.label, fontSize = 12.sp) }
                        )
                    }
                }

                OutlinedTextField(
                    value = memo,
                    onValueChange = { memo = it },
                    label = { Text("메모") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val amt = amountText.toLongOrNull() ?: 0L
                    if (recipientName.isNotBlank() && amt > 0L) {
                        onAdd(bankName, recipientName, amt, dateText, selectedEvent, selectedRelation, memo)
                    }
                }
            ) {
                Text("추가")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("취소")
            }
        }
    )
}

fun isNotificationServiceEnabled(context: Context): Boolean {
    val cn = ComponentName(context, WooriNotificationService::class.java)
    val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
    return flat != null && flat.contains(cn.flattenToString())
}
