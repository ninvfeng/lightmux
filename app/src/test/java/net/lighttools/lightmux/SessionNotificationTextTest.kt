package net.lighttools.lightmux

import net.lighttools.lightmux.service.SessionNotificationText
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionNotificationTextTest {

    private fun content(sessions: Int, transfers: Int, forwards: Int = 0): String =
        SessionNotificationText.content(
            sessions = sessions,
            transfers = transfers,
            forwards = forwards,
            sessionText = { "$it 个会话" },
            transferText = { "$it 个传输" },
            forwardText = { "$it 条转发" },
        )

    @Test
    fun `只有会话时不出现传输段`() {
        assertEquals("2 个会话", content(sessions = 2, transfers = 0))
    }

    @Test
    fun `只有传输时不出现会话段`() {
        // 用户可能关掉了所有会话但还在传一个大文件，这时通知上不该挂着「0 个会话」
        assertEquals("1 个传输", content(sessions = 0, transfers = 1))
    }

    @Test
    fun `两者都有时用点号连起来`() {
        assertEquals("3 个会话 · 2 个传输", content(sessions = 3, transfers = 2))
    }

    @Test
    fun `都是 0 时返回空串而不是一串 0`() {
        assertEquals("", content(sessions = 0, transfers = 0))
    }

    @Test
    fun `负数当成没有，不让脏数据变成文案`() {
        assertEquals("1 个会话", content(sessions = 1, transfers = -1))
    }

    @Test
    fun `只剩端口转发时通知照样说得清楚`() {
        // 会话全关了但转发还开着，服务不能停也不能显示空正文
        assertEquals("2 条转发", content(sessions = 0, transfers = 0, forwards = 2))
    }

    @Test
    fun `三段齐全时按会话、传输、转发排序`() {
        assertEquals(
            "1 个会话 · 2 个传输 · 3 条转发",
            content(sessions = 1, transfers = 2, forwards = 3),
        )
    }
}
