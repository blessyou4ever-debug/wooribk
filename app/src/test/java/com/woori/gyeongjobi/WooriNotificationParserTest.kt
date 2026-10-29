package com.woori.gyeongjobi

import com.woori.gyeongjobi.data.FilterConfig
import com.woori.gyeongjobi.parser.ParseOutcome
import com.woori.gyeongjobi.parser.WooriNotificationParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WooriNotificationParserTest {

    private val defaultFilter = FilterConfig(
        enabledAmounts = FilterConfig.DEFAULT_PRESET_AMOUNTS.toSet(),
        customAmounts = emptySet(),
        filterByAmountEnabled = true
    )

    @Test
    fun `타행 이체 시 은행 약칭과 개인 실명을 정확히 분리한다`() {
        val result = WooriNotificationParser.extractBankAndPersonalName("신한조창헌")
        requireNotNull(result)
        assertEquals("신한", result.bankName)
        assertEquals("조창헌", result.recipientName)

        val result2 = WooriNotificationParser.extractBankAndPersonalName("새마을김민수")
        requireNotNull(result2)
        assertEquals("새마을금고", result2.bankName)
        assertEquals("김민수", result2.recipientName)

        val result3 = WooriNotificationParser.extractBankAndPersonalName("홍길동")
        requireNotNull(result3)
        assertEquals("우리", result3.bankName)
        assertEquals("홍길동", result3.recipientName)
    }

    @Test
    fun `사용자 스크린샷의 비개인 출금 알림은 모두 제외된다`() {
        val nonPersonalSamples = listOf(
            "[출금] 네이버페이충전 10,000원 1002-957-741***계좌 잔액 3,439,561원 10/28 16:59:49",
            "[출금] 네이버페이충전 70,000원 1002-957-741***계좌 잔액 3,449,561원 10/28 10:02:18",
            "[출금] K B카드출금 207,114원 1002-957-741***계좌 잔액 4,919,561원 10/26 18:09:30",
            "[출금] NHBC카드대금 306,621원\n1002-957-741***계좌 잔액 5,126,675원 10/26\n17:29:32",
            "[출금] 현대10-063 9,310원 1002-957-741***계좌 잔액 5,433,296원 10/26 17:28:07"
        )

        for (raw in nonPersonalSamples) {
            val outcome = WooriNotificationParser.parseNotification("우리WON뱅킹 입출금알림", raw, defaultFilter, 2026)
            assertTrue("비개인 내역이 제외되어야 함: $raw", outcome is ParseOutcome.Ignored)
        }
    }

    @Test
    fun `개인 이체라도 경조비 금액 단위가 아니면 제외되고 설정 금액이면 수집된다`() {
        // 1,400,000원은 기본 필터(3/5/7/10/15/20/30/50/100만)에 없으므로 Ignored
        val raw140 = "[출금] 신한조창헌 1,400,000원 1002-957-741***계좌 잔액 3,519,561원 10/27 05:36:06"
        val outcome140 = WooriNotificationParser.parseNotification("우리WON뱅킹 입출금알림", raw140, defaultFilter, 2026)
        assertTrue(outcome140 is ParseOutcome.Ignored)

        // 100,000원 개인 이체는 Matched + 은행(신한)/이름(조창헌) 분리
        val raw10 = "[출금] 신한조창헌 100,000원 1002-957-741***계좌 잔액 3,519,561원 10/27 05:36:06"
        val outcome10 = WooriNotificationParser.parseNotification("우리WON뱅킹 입출금알림", raw10, defaultFilter, 2026)
        assertTrue(outcome10 is ParseOutcome.Matched)
        val entity = (outcome10 as ParseOutcome.Matched).entity
        assertEquals("신한", entity.bankName)
        assertEquals("조창헌", entity.recipientName)
        assertEquals(100_000L, entity.amount)
        assertEquals("2026-10-27", entity.transactionDate)
        assertEquals("05:36:06", entity.transactionTime)
    }
}
