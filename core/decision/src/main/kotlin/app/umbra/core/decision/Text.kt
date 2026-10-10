package app.umbra.core.decision

/**
 * Tiny language utilities for the heuristic engine: tokenisation (English words, CJK unigrams and
 * bigrams), light stemming, and a small synonym table that folds device-automation vocabulary onto
 * shared concept tokens so "crashed", "fatal exception" and "ANR" all mean `crash`.
 */
internal object Text {
    private val STOP = setOf(
        "a", "an", "the", "is", "are", "was", "were", "be", "been", "to", "of", "in", "on", "at", "for", "and", "or", "it", "its",
        "this", "that", "these", "those", "with", "as", "by", "from", "do", "does", "did", "has", "have", "had", "which", "what",
        "who", "whom", "there", "here", "then", "than", "so", "if", "into", "any", "can", "will", "would", "should", "may", "i", "you",
        "we", "they", "he", "she", "my", "your", "our", "their", "me", "us", "them", "also", "just", "very", "now", "currently", "still",
    )

    val NEGATIONS = setOf(
        "not", "no", "never", "without", "cannot", "cant", "can't", "isn't", "isnt", "doesn't", "doesnt", "didn't", "didnt", "won't",
        "wont", "unable", "absent", "missing", "neither", "nor", "none", "nothing", "fails", "不", "沒", "没", "無", "无", "未",
    )

    private val CONCEPTS: Map<String, Set<String>> = mapOf(
        "crash" to setOf("crash", "crashed", "crashes", "crashing", "exception", "fatal", "anr", "frozen", "freeze", "stopped", "unresponsive", "崩潰", "崩溃", "當機", "闪退", "閃退"),
        "error" to setOf("error", "errors", "failed", "failure", "failing", "fail", "invalid", "denied", "incorrect", "wrong", "bad", "problem", "錯誤", "错误", "失敗", "失败"),
        "success" to setOf("success", "successful", "succeeded", "ok", "done", "completed", "complete", "saved", "finished", "ready", "成功", "完成", "已儲存", "已保存"),
        "loading" to setOf("loading", "spinner", "progress", "wait", "waiting", "please", "載入", "加载", "載入中", "加载中"),
        "permission" to setOf("permission", "permissions", "allow", "grant", "access", "權限", "权限", "允許", "允许"),
        "dialog" to setOf("dialog", "popup", "modal", "alert", "prompt", "sheet", "對話框", "对话框"),
        "login" to setOf("login", "signin", "sign-in", "log-in", "sign", "authenticate", "password", "username", "登入", "登录", "密碼", "密码"),
        "settings" to setOf("settings", "setting", "preferences", "config", "configuration", "設定", "设置"),
        "install" to setOf("install", "installed", "installing", "setup", "update", "upgrade", "安裝", "安装"),
        "cancel" to setOf("cancel", "dismiss", "close", "deny", "no", "取消", "關閉", "关闭"),
        "confirm" to setOf("confirm", "accept", "agree", "yes", "continue", "proceed", "確認", "确认", "同意", "繼續", "继续"),
        "retry" to setOf("retry", "again", "reload", "refresh", "重試", "重试"),
        "keyboard" to setOf("keyboard", "ime", "typing", "type", "text", "input", "鍵盤", "键盘", "輸入", "输入"),
        "scroll" to setOf("scroll", "swipe", "more", "list", "feed", "scrollable", "捲動", "滚动"),
        "network" to setOf("network", "offline", "connection", "internet", "wifi", "timeout", "網路", "网络", "離線", "离线"),
        "delete" to setOf("delete", "remove", "erase", "clear", "wipe", "刪除", "删除"),
        "send" to setOf("send", "post", "publish", "share", "submit", "傳送", "发送", "發送"),
        "buy" to setOf("buy", "purchase", "pay", "payment", "checkout", "subscribe", "order", "支付", "付款", "購買", "购买"),
    )

    private val CANONICAL: Map<String, String> = buildMap {
        for ((concept, words) in CONCEPTS) for (w in words) putIfAbsent(w, concept)
    }

    /** Ordered tokens: stop words removed, stems and synonyms folded, negations kept (see [NEGATIONS]). */
    fun tokens(text: String): List<String> {
        val out = ArrayList<String>()
        val lower = text.lowercase()
        val word = StringBuilder()
        val cjk = StringBuilder()

        fun flushWord() {
            if (word.isEmpty()) return
            val w = word.toString()
            word.setLength(0)
            if (w in NEGATIONS) { out += w; return }
            if (w in STOP) return
            out += fold(w)
        }

        fun flushCjk() {
            if (cjk.isEmpty()) return
            val s = cjk.toString()
            cjk.setLength(0)
            if (s in NEGATIONS) out += s
            for (c in s) out += CANONICAL[c.toString()] ?: c.toString()
            for (i in 0 until s.length - 1) {
                val bi = s.substring(i, i + 2)
                out += CANONICAL[bi] ?: bi
            }
        }

        for (ch in lower) {
            when {
                isCjk(ch) -> { flushWord(); cjk.append(ch) }
                ch.isLetterOrDigit() || ch == '\'' || ch == '-' -> { flushCjk(); word.append(ch) }
                else -> { flushWord(); flushCjk() }
            }
        }
        flushWord(); flushCjk()
        return out
    }

    fun isNegation(token: String): Boolean = token in NEGATIONS

    private fun fold(w: String): String {
        CANONICAL[w]?.let { return it }
        val stem = stem(w)
        return CANONICAL[stem] ?: stem
    }

    private fun stem(w: String): String = when {
        w.length > 5 && w.endsWith("ing") -> w.dropLast(3)
        w.length > 4 && w.endsWith("ed") -> w.dropLast(2)
        w.length > 4 && w.endsWith("es") -> w.dropLast(2)
        w.length > 3 && w.endsWith("s") && !w.endsWith("ss") -> w.dropLast(1)
        else -> w
    }

    private fun isCjk(c: Char): Boolean = c.code in 0x2E80..0x9FFF || c.code in 0xF900..0xFAFF || c.code in 0x3040..0x30FF
}
