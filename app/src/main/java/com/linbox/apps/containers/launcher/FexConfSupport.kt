package com.linbox.apps.containers.launcher

import android.content.Context
import org.json.JSONArray
import java.io.File

/**
 * v3.3.40：FEX 变量配置中心（用户需求 1 —— assets-ck 缺失项补全；
 * v3.4.4 资产统一：两套 JSON 合并进 assets `builtin/`，`linbox/fex/`
 * 目录删除，容器管理读写全部指向 builtin —— 与启动链 presetEnvVars
 * 的 `builtin/fex_presets.json`、上游发行包 assets/builtin 布局完全同源）。
 *
 * LinBox 与 上游发行包 同源（上游发行包 用 Weston，LinBox 用 X11）；其
 * assets-ck（= 上游发行包 assets/builtin）中的 `fex_env_vars.json` /
 * `fex_presets.json` 在 LinBox 侧一直没有被任何代码消费 —— arm64ec 线
 * 只有 applyFexPresetEnv 的 4 档硬编码映射（仅覆盖 6~7 个 FEX_* 变量），
 * 15 个官方 FEX 配置变量从未开放给用户。本类把这些资产接入容器管理：
 *
 *  * `builtin/fex_env_vars.json` —— 变量定义表（name / values /
 *    toggleSwitch / editText / defaultValue，上游发行包 同源 schema）；
 *  * `builtin/fex_presets.json` —— 四档预设（stability / compatibility /
 *    intermediate / performance，name/items[{key,value}] schema）。
 *
 * ## 生效链路（与 DY 配置完全同构）
 *
 *  * 写入：App 内部 conf `fex_active.conf`（[ApkConfStore.CONF_DIR]，行式
 *    `export FEX_XXX=值`）—— 启动链 [WineEnvironment.applyOptConfEnv]
 *    source 全部 *.conf 时自动 export（fex_active.conf 不在
 *    [ApkConfStore.SKIP] 内，天然入列）；写透同步 /opt 生态由
 *    [ApkConfStore.syncOut] 完成。
 *  * 优先级：FEX 配置页（conf）> 容器预设档（applyFexPresetEnv，
 *    putIfAbsent 在链路前段）> FEX 默认值；容器 env= 仍最高（applyOptConfEnv
 *    跳过用户 env= 已显式设置的键）。
 *  * 双通道：[WineEnvironment.ensureFexConfigFile] 同步把本页值映射进
 *    `$HOME/.fex-emu/Config.json`（libwow64fex/libarm64ecfex 的实证通道，
 *    FEX_* env 在部分构建上不生效 —— 双保险）。
 *
 * 全部方法在调用方线程执行（UI 侧 Dispatchers.IO）。
 */
object FexConfSupport {

    /** 变量定义表（APK assets `builtin/`，随包分发；上游发行包 assets-ck 同源）。 */
    const val ASSET_ENV_VARS = "builtin/fex_env_vars.json"

    /** 四档预设（APK assets `builtin/`）。 */
    const val ASSET_PRESETS = "builtin/fex_presets.json"

    /** 当前生效值文件（App 内部 conf；行式 export KEY=VALUE）。 */
    const val ACTIVE_CONF = "fex_active.conf"

    /** FEX 预设 id → 中文标签（Box64Preset 四档同款文案）。 */
    val PRESET_LABELS = linkedMapOf(
        "stability" to "🔒 稳定",
        "compatibility" to "⚖️ 兼容",
        "intermediate" to "🔀 进阶",
        "performance" to "🚀 性能"
    )

    /** 变量定义（上游发行包 fex_env_vars.json schema 的 Kotlin 形态）。 */
    data class FexVarDef(
        val name: String,
        val values: List<String>,
        val toggleSwitch: Boolean,
        val editText: Boolean,
        val defaultValue: String
    )

    // ------------------------------------------------------------------
    // assets 解析
    // ------------------------------------------------------------------

    /**
     * 变量定义表（assets 顺序）：
     * `[{"name":"FEX_TSOENABLED","values":["0","1"],"toggleSwitch":true,
     *   "defaultValue":"1"}, …]`（values/editText 缺省时为编辑框变量，
     * 如 FEX_MAXINST）。
     */
    fun loadVarDefs(context: Context): List<FexVarDef> {
        val json = readAsset(context, ASSET_ENV_VARS) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            val out = ArrayList<FexVarDef>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val name = o.optString("name")
                if (name.isBlank()) continue
                val values = runCatching {
                    val a = o.optJSONArray("values")
                    if (a == null) emptyList() else List(a.length()) { a.optString(it) }
                }.getOrDefault(emptyList())
                out.add(
                    FexVarDef(
                        name = name,
                        values = values,
                        toggleSwitch = o.optBoolean("toggleSwitch", false),
                        editText = o.optBoolean("editText", values.isEmpty()),
                        defaultValue = o.optString("defaultValue").ifBlank {
                            values.firstOrNull() ?: ""
                        }
                    )
                )
            }
            out
        }.getOrElse { emptyList() }
    }

    /**
     * 四档预设（assets 顺序；id → 键值对列表）：
     * `[{"name":"stability","items":[{"key":"FEX_TSOENABLED","value":"1"}…]}]`
     */
    fun loadPresets(context: Context): LinkedHashMap<String, List<Pair<String, String>>> {
        val json = readAsset(context, ASSET_PRESETS) ?: return LinkedHashMap()
        return runCatching {
            val arr = JSONArray(json)
            val out = LinkedHashMap<String, List<Pair<String, String>>>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val name = o.optString("name")
                if (name.isBlank()) continue
                val items = runCatching {
                    val a = o.optJSONArray("items")
                    if (a == null) emptyList() else List(a.length()) {
                        val io = a.getJSONObject(it)
                        io.optString("key") to io.optString("value")
                    }.filter { it.first.isNotBlank() }
                }.getOrDefault(emptyList())
                out[name] = items
            }
            out
        }.getOrElse { LinkedHashMap() }
    }

    private fun readAsset(context: Context, path: String): String? = runCatching {
        context.assets.open(path).bufferedReader().use { it.readText() }
    }.getOrNull()

    // ------------------------------------------------------------------
    // 当前生效值（fex_active.conf 读写；与 DY 配置同后端）
    // ------------------------------------------------------------------

    /** fex_active.conf 当前值（VAR → 值；无文件 → 空）。 */
    fun activeValues(): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        val f = File(ApkConfStore.CONF_DIR, ACTIVE_CONF)
        if (!f.isFile) return out
        ApkConfStore.parseConfLines(f).forEach { (k, v) -> out[k] = v }
        return out
    }

    /**
     * 整文件重写 fex_active.conf（wineguanli apply_config 同款 —— 定义表内
     * 的变量逐行 export，表外变量不写）+ 写透同步 /opt。
     * @return null 成功；非 null 为错误消息。
     */
    fun writeActive(values: List<Pair<String, String>>): String? {
        if (!ApkConfStore.ensure(null)) return "App 内部 conf 目录不可用（${ApkConfStore.CONF_DIR}）"
        return ApkConfStore.rewriteConf(ACTIVE_CONF) { lines ->
            lines.clear()
            values.forEach { (k, v) -> lines.add("export $k=$v") }
        }
    }

    /**
     * UI 展示用：定义表顺序 + 预设/缺省值兜底（fex_active.conf 缺该变量时
     * 用四档预设中当前选中档的值，再缺用 defaultValue —— 与 wineguanli
     * load_config 的回落语义一致）。
     *
     * @param defs 变量定义表
     * @param active fex_active.conf 当前值
     * @param presetName 当前选中预设 id（"自定义" 传 null）
     */
    fun composeCurrent(
        defs: List<FexVarDef>,
        active: Map<String, String>,
        presetName: String?
    ): LinkedHashMap<String, String> {
        val preset = presetName?.let { p -> GuanliPresetsCache[p] } ?: emptyMap()
        val out = LinkedHashMap<String, String>()
        defs.forEach { d ->
            out[d.name] = active[d.name]?.takeIf { it.isNotBlank() }
                ?: preset[d.name]?.takeIf { it.isNotBlank() }
                ?: d.defaultValue
        }
        return out
    }

    /** 预设缓存（assets 只读一次；由 [loadPresets] 填充）。 */
    @Volatile
    private var GuanliPresetsCache: Map<String, Map<String, String>> = emptyMap()

    /** 填充预设缓存（FEX 页装配时调用一次；context 仅首次需要）。 */
    fun warmPresets(context: Context) {
        if (GuanliPresetsCache.isEmpty()) {
            GuanliPresetsCache = loadPresets(context).mapValues { (_, items) ->
                items.toMap()
            }
        }
    }

    /** 预设缓存只读访问。 */
    fun presetValues(name: String): Map<String, String> =
        GuanliPresetsCache[name] ?: emptyMap()
}
