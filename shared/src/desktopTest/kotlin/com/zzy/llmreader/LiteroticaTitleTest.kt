package com.zzy.llmreader

import com.zzy.llmreader.web.cleanLiteroticaTitle
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Literotica 标题清洗的回归测试。
 *
 * 用例全部来自真实接口（`literotica.com/api/3/search/stories`，抓 100 条），
 * 旧实现 `substringBeforeLast(" - ")` 会毁掉其中 7 条。
 */
class LiteroticaTitleTest {

    @Test
    fun `标题里的破折号不能被当成章节分隔符`() {
        // 旧实现把这条截成「5」
        assertEquals(
            "5 - Mommy Holding My Cock While I Pee",
            cleanLiteroticaTitle("5 - Mommy Holding My Cock While I Pee"),
        )
        // 旧实现截成「White Slave Rebecca」
        assertEquals(
            "White Slave Rebecca - Rage",
            cleanLiteroticaTitle("White Slave Rebecca - Rage"),
        )
    }

    @Test
    fun `真正的章节后缀才去掉`() {
        assertEquals(
            "Adventures of a Girlbuck",
            cleanLiteroticaTitle("Adventures of a Girlbuck - Ch. 01"),
        )
        assertEquals("Some Story", cleanLiteroticaTitle("Some Story - Chapter 12"))
        assertEquals("Some Story", cleanLiteroticaTitle("Some Story - Part 3"))
        assertEquals("某篇", cleanLiteroticaTitle("某篇 - 第5章"))
        assertEquals("Some Story", cleanLiteroticaTitle("Some Story - 2"))
    }

    @Test
    fun `站点后缀被去掉`() {
        assertEquals("Some Story", cleanLiteroticaTitle("Some Story - Literotica.com"))
    }

    @Test
    fun `空标题有兜底`() {
        assertEquals("Literotica 小说", cleanLiteroticaTitle(""))
        assertEquals("Literotica 小说", cleanLiteroticaTitle("   "))
    }
}
