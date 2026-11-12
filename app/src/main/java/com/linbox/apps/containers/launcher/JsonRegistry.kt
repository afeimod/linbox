package com.linbox.apps.containers.launcher

import com.linbox.apps.containers.ContainerData
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * v3.3.40：注册表 JSON 打入引擎（用户需求 1 —— “注册表打入方式用 json 的
 * 方式，而不是我现在 reg 的方式”）。
 *
 * ## 定位
 *
 * 旧链路（v3.2.x~v3.3.x）的每一次注册表写入都是**两件套**：App 内生成
 * 一份 `.reg` 文本（REGEDIT4 / WINE REGISTRY Version 2）写进
 * `drive_c/linbox/`，再拉起一个 `wine regedit /s` 客户端进程导入 ——
 * 每次 1~3 秒的进程拉起 + wineserver 握手开销，wineserver 还会把注册表
 * 缓存在内存里异步回写，导入顺序敏感。
 *
 * 新链路（上游发行包 WineRegistryConfigurator + WineRegExporter 模型移植，
 * 适配 LinBox 的容器布局）：**JSON 描述 → 直接文本合并进前缀的
 * user.reg / system.reg / userdef.reg**，零 wine 进程拉起：
 *
 * ```
 * [
 *   {
 *     "hive": "HKCU",                          // HKCU|HKLM|HKCR|HKU\.Default（长名同义）
 *     "key":  "Software\\Wine\\DllOverrides",   // JSON 字符串 → 实际单反斜杠
 *     "values": [
 *       {"name": "d3d9",    "type": "sz",     "data": "native,builtin"},
 *       {"name": "LogPixels", "type": "dword", "data": 96},
 *       {"name": "Mask",    "type": "qword",   "data": 4294967296},
 *       {"name": "Blob",    "type": "binary",  "data": "DEADBEEF"},
 *       {"name": "Path",    "type": "expand_sz", "data": "%SystemRoot%\\system32"},
 *       {"name": "List",    "type": "multi_sz",  "data": ["a", "b"]},
 *       {"name": "Old",     "type": "delete"}                  // 删单值
 *     ]
 *   },
 *   { "hive": "HKLM", "key": "...", "delete": true }          // 删整段
 * ]
 * ```
 *
 * ## hive → 前缀文件映射（wine WINE REGISTRY Version 2 布局）
 *
 *  - HKCU → `user.reg`（键路径原样 —— 文件内相对 `REGISTRY\\User\\<SID>`）；
 *  - HKLM → `system.reg`（键路径原样 —— 相对 `REGISTRY\\Machine`）；
 *  - HKCR → `system.reg`（等价 `Software\\Classes\\<key>`）；
 *  - HKU\\.Default → `userdef.reg`（`<key>` 相对 `REGISTRY\\User\\.Default`）。
 *
 * 值行编码（wine server/registry.c 的保存格式，与 regedit 导入产物逐字节
 * 同构）：`"名"="串"` / `"名"=dword:xxxxxxxx` / `"名"=hex(b):…` /
 * `"名"=hex:…` / `"名"=str(2):"…"` / `"名"=str(7):"a","b"`；名称与数据
 * 的 `\\`、`\"` 转义，非 ASCII 字节 `\xHH`。默认值用 `@`。
 *
 * ## wineserver 一致性
 *
 * wineserver 会把注册表缓存在内存并异步回写 —— 直接改文件前必须确认
 * server 不在（在则先 [WineServerUtils.stop] 三段式收尾落盘，与 上游发行包
 * WineServerUtils.stop → WineRegistryConfigurator 的顺序约定一致）。
 * 所有既有调用点均有幂等标记，本引擎只在值变化时落盘 —— 常态零开销。
 *
 * 全部方法在调用方线程执行（启动链 linbox-launcher / UI Dispatchers.IO）。
 */
object JsonRegistry {

    /** user.reg 的 wine 头（落段时缺失则补齐，保证 wine 可读）。 */
    private const val WINE_REG_HEADER = "WINE REGISTRY Version 2"

    // ------------------------------------------------------------------
    // 公共入口
    // ------------------------------------------------------------------

    /**
     * 把一组 JSON 注册表编辑合并进前缀。
     *
     * @param json 数组文本（schema 见类 KDoc）或 `{"edits":[…]}` 包装形态
     * @return null 成功；非 null 为错误消息（调用方写日志/重试）
     */
    fun apply(
        prefix: File,
        resolved: WineRuntime.Resolved?,
        container: ContainerData,
        env: Map<String, String>,
        json: String,
        log: (String) -> Unit = {}
    ): String? {
        return runCatching {
            val edits = parseEdits(json)
                ?: return@runCatching "JSON 注册表解析失败（schema：[{hive,key,values[{name,type,data}]}]）"
            if (edits.isEmpty()) return@runCatching null
            // 1) wineserver 一致性：server 在跑则先收尾（注册表内存缓存落盘）
            if (WineServerUtils.isServerRunning(container.prefixPath)) {
                log("注册表写入前收尾 wineserver（内存缓存落盘，防回写覆盖 JSON 直写）……")
                WineServerUtils.stop(resolved, container, env)
            }
            // 2) 按 hive 分组合并（同文件一批一次落盘）
            var touched = 0
            edits.groupBy { it.hive }.forEach { (hive, list) ->
                val target = targetFileFor(prefix, hive)
                    ?: return@runCatching "不支持的 hive：$hive（仅 HKCU/HKLM/HKCR/HKU\\.Default）"
                if (mergeIntoFile(target, hive, list)) touched++
            }
            log("注册表 JSON 直写完成：${edits.size} 段编辑 → $touched 个 hive 文件（零 wine 进程拉起）")
            null
        }.getOrElse { it.message ?: it.javaClass.simpleName }
    }

    /** 便捷形态：直接传 [JSONArray]。 */
    fun apply(
        prefix: File,
        resolved: WineRuntime.Resolved?,
        container: ContainerData,
        env: Map<String, String>,
        edits: JSONArray,
        log: (String) -> Unit = {}
    ): String? = apply(prefix, resolved, container, env, edits.toString(), log)

    /**
     * 从 .json 文件打入（/opt 生态包 / 用户侧资产：prefix 目录下的
     * .json 文件直接合并 —— “注册表打入用 JSON” 的外部入口）。
     * @return null 成功 / 错误消息。
     */
    fun applyJsonFile(
        file: File,
        prefix: File,
        resolved: WineRuntime.Resolved?,
        container: ContainerData,
        env: Map<String, String>,
        log: (String) -> Unit = {}
    ): String? {
        val text = runCatching { file.readText() }.getOrNull()
            ?: return "读取 ${file.name} 失败"
        return apply(prefix, resolved, container, env, text, log)
    }

    // ------------------------------------------------------------------
    // 解析（org.json —— Android 内建，无新增依赖）
    // ------------------------------------------------------------------

    /** 一段编辑（hive + 键 + 值列表 / 整段删除标记）。 */
    data class RegEdit(
        val hive: String,
        val key: String,
        val deleteKey: Boolean,
        val values: List<RegValue>
    )

    data class RegValue(
        val name: String,
        val type: String,
        val data: Any?
    )

    /** 解析 JSON 数组（顶层恒为数组；空文本 → 空表）。 */
    internal fun parseEdits(json: String): List<RegEdit>? {
        return runCatching {
            val trimmed = json.trim()
            if (trimmed.isEmpty()) return emptyList()
            val array = JSONArray(trimmed)
            val out = ArrayList<RegEdit>(array.length())
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val hive = normalizeHive(o.optString("hive"))
                val key = o.optString("key").trim()
                if (hive == null || key.isEmpty()) continue
                val values = ArrayList<RegValue>()
                val va = o.optJSONArray("values")
                if (va != null) {
                    for (j in 0 until va.length()) {
                        val vo = va.getJSONObject(j)
                        val name = vo.optString("name")
                        if (name.isEmpty()) continue
                        values.add(
                            RegValue(
                                name = name,
                                type = vo.optString("type", "sz").trim().lowercase(),
                                data = vo.opt("data")
                            )
                        )
                    }
                }
                out.add(
                    RegEdit(
                        hive = hive,
                        key = key,
                        deleteKey = o.optBoolean("delete", false),
                        values = values
                    )
                )
            }
            out
        }.getOrNull()
    }

    /** hive 长短名归一（→ "HKCU" / "HKLM" / "HKCR" / "HKU\\.Default"）。 */
    private fun normalizeHive(raw: String): String? {
        val h = raw.trim().uppercase()
        return when {
            h == "HKCU" || h == "HKEY_CURRENT_USER" -> "HKCU"
            h == "HKLM" || h == "HKEY_LOCAL_MACHINE" -> "HKLM"
            h == "HKCR" || h == "HKEY_CLASSES_ROOT" -> "HKCR"
            h == "HKU" || h == "HKEY_USERS" || h == "HKU\\.DEFAULT" ||
                h == "HKEY_USERS\\.DEFAULT" -> "HKU\\.Default"
            else -> null
        }
    }

    /** hive → 前缀注册表文件。 */
    private fun targetFileFor(prefix: File, hive: String): File? = when (hive) {
        "HKCU" -> File(prefix, "user.reg")
        "HKLM" -> File(prefix, "system.reg")
        "HKCR" -> File(prefix, "system.reg")
        "HKU\\.Default" -> File(prefix, "userdef.reg")
        else -> null
    }

    // ------------------------------------------------------------------
    // 文本合并（wine WINE REGISTRY Version 2 语义）
    // ------------------------------------------------------------------

    /**
     * 把一组同 hive 的编辑合并进目标文件。
     * @return true 有落盘；false 无匹配/无变化（或文件缺失时新建段）。
     */
    private fun mergeIntoFile(target: File, hive: String, edits: List<RegEdit>): Boolean {
        val lines = if (target.isFile) {
            runCatching { target.readLines() }.getOrDefault(emptyList()).toMutableList()
        } else mutableListOf(WINE_REG_HEADER).also {
            if (hive == "HKCU") it.add(";; All keys relative to REGISTRY\\User")
            else if (hive == "HKLM" || hive == "HKCR") it.add(";; All keys relative to REGISTRY\\Machine")
            else if (hive == "HKU\\.Default") it.add(";; All keys relative to REGISTRY\\User\\.Default")
            it.add("")
        }

        // hive 相对路径修正（HKCR 键 = HKLM\Software\Classes\…）
        fun regPath(e: RegEdit): String {
            val raw = e.key.replace('/', '\\')
            val p = if (hive == "HKCR" && !raw.startsWith("Software\\", true)) "Software\\Classes\\$raw" else raw
            return p.replace("\\", "\\\\")   // 文件内双反斜杠转义
        }

        var changed = false
        // 先处理整段删除，再写值（同一键先删后写 = 净写入）
        for (e in edits.filter { it.deleteKey }) {
            val path = regPath(e)
            if (removeSection(lines, path)) changed = true
        }
        for (e in edits.filter { !it.deleteKey && it.values.isNotEmpty() }) {
            val path = regPath(e)
            if (upsertSectionValues(lines, path, e.values)) changed = true
        }
        if (!changed) return false
        atomicWrite(target, lines)
        return true
    }

    /** 段头行匹配（`[路径] [时间戳]`；大小写不敏感 —— 注册表语义）。 */
    private val sectionPattern = Regex("^\\[([^\\]]*)\\](?:\\s+\\d+)?\\s*$")

    private fun sectionPathOf(line: String): String? =
        sectionPattern.find(line.trim())?.groupValues?.get(1)?.trim()

    /** 删除整段（段头 + 段体到下一个段头）。@return true 有删除。 */
    private fun removeSection(lines: MutableList<String>, path: String): Boolean {
        var i = 0
        var removed = false
        outer@ while (i < lines.size) {
            val p = sectionPathOf(lines[i])
            if (p == null || !p.equals(path, ignoreCase = true)) { i++; continue }
            removed = true
            lines.removeAt(i)
            // 段体：直到下一个段头
            while (i < lines.size && sectionPathOf(lines[i]) == null) {
                lines.removeAt(i)
            }
            continue@outer
        }
        return removed
    }

    /**
     * 段内 upsert 值（同名替换、缺名追加到段尾；`"名"=-` 语义的删除 =
     * 移除该值行）。段不存在 → 文件尾新段（带时间戳）。
     * @return true 有修改。
     */
    private fun upsertSectionValues(
        lines: MutableList<String>,
        path: String,
        values: List<RegValue>
    ): Boolean {
        var i = 0
        var changed = false
        while (i < lines.size) {
            val p = sectionPathOf(lines[i])
            if (p == null || !p.equals(path, ignoreCase = true)) { i++; continue }
            // 找到段：扫描到段尾（下一个段头/EOF），逐值 upsert
            var end = i + 1
            while (end < lines.size && sectionPathOf(lines[end]) == null) end++
            for (v in values) {
                val newLine = encodeValueLine(v)
                val namePat = valueNamePattern(v.name)
                var found = -1
                for (k in i + 1 until end) {
                    if (namePat.matches(lines[k].trim())) { found = k; break }
                }
                if (v.type == "delete") {
                    if (found >= 0) {
                        val span = valueSpan(lines, found)
                        repeat(span) { lines.removeAt(found) }
                        end -= span
                        changed = true
                    }
                } else if (found >= 0) {
                    if (lines[found].trim() != newLine) {
                        // 旧值可能是 wine 写出的多行 hex 续行 —— 整跨度替换
                        val span = valueSpan(lines, found)
                        repeat(span) { lines.removeAt(found) }
                        lines.add(found, newLine)
                        end += 1 - span
                        changed = true
                    }
                } else {
                    // 段体空行之后追加（保持 wine “段头后直接跟值”的形态）
                    lines.add(end.coerceAtMost(lines.size), newLine)
                    end++
                    changed = true
                }
            }
            return changed
        }
        // 段不存在 → 追加新段（头 + 时间戳 + 值行 + 空行）
        if (lines.isNotEmpty() && lines.last().isNotBlank()) lines.add("")
        lines.add("[$path] ${System.currentTimeMillis() / 1000}")
        values.filter { it.type != "delete" }.forEach { lines.add(encodeValueLine(it)) }
        lines.add("")
        return true
    }

    /** 值行前缀匹配（`"名"=` / `@=`；名称按文件内转义形态匹配）。 */
    private fun valueNamePattern(name: String): Regex {
        val escaped = escapeRegText(name)
        val quoted = if (name == "@") "@" else "\"$escaped\""
        return Regex("^${Regex.escape(quoted)}=.*$")
    }

    /**
     * 值行的跨度（含 hex 多行续行 —— 续行以尾部 `\\` 标记，
     * wine 写大 binary/qword 时会折行；替换必须整跨度替换）。
     */
    private fun valueSpan(lines: List<String>, idx: Int): Int {
        var n = 1
        var k = idx
        while (k + 1 < lines.size && lines[k].trimEnd().endsWith("\\")) {
            k++
            n++
        }
        return n
    }

    // ------------------------------------------------------------------
    // 值编码（wine 保存格式）
    // ------------------------------------------------------------------

    /** 单值行编码。 */
    internal fun encodeValueLine(v: RegValue): String {
        val namePart = if (v.name == "@") "@" else "\"${escapeRegText(v.name)}\""
        return namePart + "=" + when (v.type) {
            "sz", "string", "reg_sz" -> "\"${escapeRegText(asString(v.data))}\""
            "expand_sz", "expand", "expandable" -> "str(2):\"${escapeRegText(asString(v.data))}\""
            "multi_sz", "multi" -> {
                val parts = when (val d = v.data) {
                    is JSONArray -> List(d.length()) { asString(d.opt(it)) }
                    else -> asString(d).split(Regex(",(?![^,]*\\\")"))
                        .map { it.trim().removeSurrounding("\"") }
                        .filter { it.isNotEmpty() }
                }
                "str(7):" + parts.joinToString(",") { "\"${escapeRegText(it)}\"" }
            }
            "dword" -> "dword:" + hex32(dwordOf(v.data))
            "qword" -> "hex(b):" + bytesOfQword(v.data).joinToString(",") { hex2(it) }
            "binary" -> "hex:" + bytesOf(v.data).joinToString(",") { hex2(it) }
            "none" -> "hex(0):"
            else -> "\"${escapeRegText(asString(v.data))}\""
        }
    }

    private fun asString(data: Any?): String = when (data) {
        null -> ""
        is String -> data
        else -> data.toString()
    }

    private fun dwordOf(data: Any?): Long {
        val v = when (data) {
            is Int -> data.toLong()
            is Long -> data
            is String -> data.trim().removePrefix("0x").removePrefix("0X")
                .toLongOrNull(16) ?: data.toLongOrNull() ?: 0L
            else -> data.toString().toLongOrNull() ?: 0L
        }
        return v and 0xFFFFFFFFL
    }

    private fun hex32(v: Long): String =
        v.toString(16).padStart(8, '0').uppercase()

    private fun bytesOf(data: Any?): List<Int> = when (data) {
        is JSONArray -> List(data.length()) { (data.opt(it) as? Number)?.toInt() ?: 0 }
        is List<*> -> data.map { (it as? Number)?.toInt() ?: 0 }
        is String -> {
            val hex = data.replace(" ", "").removePrefix("0x").removePrefix("0X")
            if (hex.length % 2 == 0 && hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                List(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16) }
            } else emptyList()
        }
        else -> emptyList()
    }

    private fun bytesOfQword(data: Any?): List<Int> {
        val v = when (data) {
            is Long -> data
            is Int -> data.toLong()
            is String -> data.trim().removePrefix("0x").removePrefix("0X")
                .toLongOrNull(16) ?: data.toLongOrNull() ?: 0L
            else -> 0L
        }
        // 小端 8 字节
        return List(8) { ((v shr (it * 8)) and 0xFF).toInt() }
    }

    private fun hex2(v: Int): String = v.toString(16).padStart(2, '0').uppercase()

    /** 文本转义（wine 保存格式：`\\`、`\"`；非 ASCII 字节 `\xHH`）。 */
    private fun escapeRegText(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (b in s.toByteArray(Charsets.UTF_8)) {
            when {
                b == '\\'.code.toByte() -> sb.append("\\\\")
                b == '"'.code.toByte() -> sb.append("\\\"")
                b in 32..126 -> sb.append(b.toChar())
                else -> sb.append("\\x").append(hex2(b.toInt() and 0xFF))
            }
        }
        return sb.toString()
    }

    /** 原子落盘（tmp + rename；wine 进程并发持有文件的兼容语义）。 */
    private fun atomicWrite(f: File, lines: List<String>) {
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".linbox-jsonreg-tmp")
        tmp.writeText(lines.joinToString("\n") + "\n")
        if (!tmp.renameTo(f)) {
            tmp.copyTo(f, overwrite = true)
            tmp.delete()
        }
    }
}
