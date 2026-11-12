package com.linbox.apps.containers.launcher

import java.io.File

/**
 * v3.3.3：/opt/conf 生态配置读写（用户问题 5："容器编辑所有东西都没有
 * 读取 opt 下的相关配置，也无法更改选项来改变 opt 下的配置"）。
 *
 * v3.3.39【conf 统一到 APK 内部端】：后端整体切换到 [ApkConfStore] ——
 * 全部读写落在 App 私有存储 /data/data/com.linbox/files/linbox-conf/
 * （assets 种子 + UI 编辑 + 生态一次性导入），**不再要求 /opt 生态包
 * 存在**（旧版 root()==null 时整页「未检测到 /opt 生态」不可编辑）。
 * 每次写入成功后由 ApkConfStore 写透同步到 /opt 生态 conf —— 终端链
 * （linbox-wine load_opt_confs）与生态脚本读到的永远是同一份值；
 * 终端侧直接改 /opt/conf 的值在启动链按 mtime 新者胜同样生效
 * （兼容容器管理 / 终端两侧的一切更改）。
 *
 * 语义对照 wineguanli.py（PyQt 容器管理器，参考实现）：
 *  - env.conf / locale.conf / cores.conf 均为 sh source 语义的行式配置：
 *    `export KEY="VALUE"`（locale.conf 是单行裸值）；
 *  - 写 = 逐行扫描替换同名键，无则文件尾追加（save_mangohud_config
 *    同款 replace-or-append）；删 = 剔除同名行后回写；
 *  - 解析仅识别 `KEY=VALUE` / `export KEY=VALUE` 行（与启动链
 *    [WineEnvironment.readOptConfs] 同源容错），引号剥离。
 *
 * 路径解析与启动链**完全同源**（[ApkConfStore.mergedConfMap] 的
 * App 内部那一侧）—— UI 里改的就是启动链读的那一份，杜绝"改了不生效"。
 * 全部方法在调用方线程执行（UI 侧自行 Dispatchers.IO）。
 */
object OptConfSupport {

    /**
     * conf 根目录 —— v3.3.39 起恒为 App 内部 [ApkConfStore.CONF_ROOT]
     * （ensure 种子后必存在；root() 不再返回 null，conf 编辑永远可用）。
     * 生态包探测（prefix/dxvk1/驱动 tar 等）继续走
     * [WineEnvironment.optRoot]，与本方法无关。
     */
    fun root(): File? {
        if (!ApkConfStore.ensure(null)) return null
        return File(ApkConfStore.CONF_ROOT)
    }

    /** conf 文件目录（App 内部；与 /opt 生态 conf/ 布局同构）。 */
    fun confDir(): File? = root()?.let { File(it, "conf") }

    // ------------------------------------------------------------------
    // env.conf：通用 KEY=VALUE 读写
    // ------------------------------------------------------------------

    /** env.conf 现有条目（按文件顺序；无文件 → 空）。 */
    fun envEntries(): List<Pair<String, String>> = ApkConfStore.envEntries()

    /**
     * 写入/更新 env.conf 的一个键（replace-or-append + 写透同步 /opt）。
     * @return null 成功；非 null 为错误消息。
     */
    fun writeEnvKey(key: String, value: String): String? {
        val k = key.trim()
        if (k.isEmpty() || !Regex("^[A-Za-z_][A-Za-z0-9_]*$").matches(k)) {
            return "无效变量名: \"$key\"（仅允许字母/数字/下划线，且不以数字开头）"
        }
        return ApkConfStore.rewriteConf("env.conf") { lines ->
            val quoted = "\"${value.trim()}\""
            var replaced = false
            for (i in lines.indices) {
                val existing = keyOf(lines[i]) ?: continue
                if (existing == k) {
                    lines[i] = "export $k=$quoted"
                    replaced = true
                }
            }
            if (!replaced) lines.add("export $k=$quoted")
        }
    }

    /** 删除 env.conf 的一个键（+ 写透同步 /opt）。@return null 成功 / 错误消息。 */
    fun removeEnvKey(key: String): String? =
        ApkConfStore.rewriteConf("env.conf") { lines ->
            lines.removeAll { keyOf(it) == key }
        }

    // ------------------------------------------------------------------
    // cores.conf：PRIMARY_CORES（CPU 核心绑定，如 "0-3" / "4,5"）
    // ------------------------------------------------------------------

    fun primaryCores(): String? = ApkConfStore.primaryCores()

    fun writePrimaryCores(value: String): String? =
        ApkConfStore.rewriteConf("cores.conf") { lines ->
            val quoted = "\"${value.trim()}\""
            var replaced = false
            for (i in lines.indices) {
                if (keyOf(lines[i]) == "PRIMARY_CORES") {
                    lines[i] = "export PRIMARY_CORES=$quoted"
                    replaced = true
                }
            }
            if (!replaced) lines.add("export PRIMARY_CORES=$quoted")
        }

    /**
     * cores.conf 双键回写 —— wineguanli.py save_advanced_settings 同款
     * （PRIMARY_CORES + SECONDARY_CORES 两行），更多设置·CPU核心页保存时
     * 同步写 App 内部 conf（+ 写透 /opt），生态脚本与启动链
     * （readOptConfs source）都能读到同一份。
     */
    fun writeCores(primary: String, secondary: String): String? =
        ApkConfStore.rewriteConf("cores.conf") { lines ->
            val p = "export PRIMARY_CORES=\"${primary.trim()}\""
            val s = "export SECONDARY_CORES=\"${secondary.trim()}\""
            var fp = false
            var fs = false
            for (i in lines.indices) {
                when (keyOf(lines[i])) {
                    "PRIMARY_CORES" -> { lines[i] = p; fp = true }
                    "SECONDARY_CORES" -> { lines[i] = s; fs = true }
                }
            }
            if (!fp) lines.add(p)
            if (!fs) lines.add(s)
        }

    // ------------------------------------------------------------------
    // locale.conf：单行裸值（语言）
    // ------------------------------------------------------------------

    /** 当前 locale.conf 值（复用启动链同源解析；无 → null）。 */
    fun locale(): String? = ApkConfStore.locale()

    fun writeLocale(value: String): String? = ApkConfStore.writeLocale(value)

    // ------------------------------------------------------------------
    // DY 配置（v3.3.4，用户问题 2：wineguanli.py DynarecEditor 同源移植）
    //   custom.conf         —— 变量定义表：export VAR=候选值1,候选值2…
    //   dynarec_active.conf —— 当前生效值：export VAR=值
    //   UI 按定义表渲染（0/1 → 启用勾选；多值 → 下拉），应用时整文件
    //   重写 active.conf（wineguanli apply_config 同款），启动链
    //   readOptConfs 按固定顺序 source 它 —— 改的就是启动用的那份。
    // ------------------------------------------------------------------

    /** custom.conf 缺省定义表（wineguanli.py DynarecEditor.default_config 逐字移植）。 */
    val DY_DEFAULT_CUSTOM_CONF = listOf(
        "export BOX64_DYNAREC_ALIGNED_ATOMICS=0,1",
        "export BOX64_DYNAREC_BIGBLOCK=0,1,2,3",
        "export BOX64_DYNAREC_FASTNAN=0,1",
        "export BOX64_DYNAREC_SAFEFLAGS=0,1,2",
        "export BOX64_DYNAREC_STRONGMEM=0,1,2,3",
        "export BOX64_DYNAREC_WAIT=0,1",
        "export BOX64_DYNAREC_X87DOUBLE=0,1",
        "export BOX64_DYNAREC_CALLRET=0,1",
        "export BOX64_DYNAREC_FASTROUND=0,1",
        "export BOX64_IGNOREINT3=0,1",
        "export BOX64_DYNAREC_FORWARD=128,256,512,1024",
        "export BOX64_AVX=0,1,2",
        "export BOX64_UNITYPLAYER=0,1",
        "export BOX64_DYNAREC_WEAKBARRIER=0,1,2",
        "export BOX86_DYNAREC_ALIGNED_ATOMICS=0,1",
        "export BOX86_DYNAREC_BIGBLOCK=0,1,2,3",
        "export BOX86_DYNAREC_FASTNAN=0,1",
        "export BOX86_DYNAREC_SAFEFLAGS=0,1,2",
        "export BOX86_DYNAREC_STRONGMEM=0,1,2,3",
        "export BOX86_DYNAREC_WAIT=0,1",
        "export BOX86_DYNAREC_X87DOUBLE=0,1",
        "export BOX86_DYNAREC_CALLRET=0,1",
        "export BOX86_DYNAREC_FASTROUND=0,1",
        "export BOX86_IGNOREINT3=0,1"
    ).joinToString("\n") + "\n"

    /** custom.conf 是否存在（不存在时 UI 可先落缺省定义表）。 */
    fun dyCustomExists(): Boolean = ApkConfStore.confFile("custom.conf").isFile

    /** custom.conf 缺失时写入缺省定义表（wineguanli load_config 同款自建）。@return null 成功。 */
    fun ensureDyCustomConf(): String? =
        ApkConfStore.ensureDyCustomConf(DY_DEFAULT_CUSTOM_CONF)

    /** custom.conf 变量定义（VAR → 候选值列表，保持文件顺序；无文件 → 空）。 */
    fun dyVariables(): LinkedHashMap<String, List<String>> = ApkConfStore.dyVariables()

    /** dynarec_active.conf 当前值（VAR → 值；无文件 → 空）。 */
    fun dyActive(): LinkedHashMap<String, String> = ApkConfStore.dyActive()

    /**
     * 应用 DY 配置：整文件重写 dynarec_active.conf（wineguanli apply_config
     * 同款 —— 定义表内的变量逐行 export，表外变量不写；+ 写透同步 /opt）。
     * @return null 成功；否则错误消息。
     */
    fun writeDynarecActive(values: List<Pair<String, String>>): String? =
        ApkConfStore.writeDynarecActive(values)

    /** 添加变量定义（custom.conf 追加 export VAR=候选值）。@return null 成功。 */
    fun appendDyVariable(name: String, valuesCsv: String): String? =
        ApkConfStore.rewriteConf("custom.conf") { lines ->
            lines.add("export ${name.trim()}=${valuesCsv.trim()}")
        }

    /** 删除变量定义（custom.conf 剔除同名行）。@return null 成功。 */
    fun removeDyVariable(name: String): String? =
        ApkConfStore.rewriteConf("custom.conf") { lines ->
            lines.removeAll { keyOf(it) == name }
        }

    /** 恢复 custom.conf 缺省定义表（wineguanli reset_default 同款）。@return null 成功。 */
    fun resetDyCustomConf(): String? {
        if (!ApkConfStore.ensure(null)) return "App 内部 conf 目录不可用"
        return try {
            ApkConfStore.confFile("custom.conf").let { f ->
                f.parentFile?.mkdirs()
                val tmp = File(f.parentFile, f.name + ".linbox-tmp")
                tmp.writeText(DY_DEFAULT_CUSTOM_CONF)
                if (!tmp.renameTo(f)) {
                    tmp.copyTo(f, overwrite = true)
                    tmp.delete()
                }
            }
            ApkConfStore.syncOut("custom.conf")
            null
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
    }

    // ------------------------------------------------------------------
    // FEX 配置（v3.3.40：assets-ck 的 fex_env_vars/fex_presets 接入 ——
    // [FexConfSupport] 的写入后端；与 DY 配置同一套 conf 存储）
    // ------------------------------------------------------------------

    /**
     * 删除 fex_active.conf（FEX 页「重置默认」—— 启动链回落容器预设档 /
     * 变量默认值）。@return null 成功 / 错误消息。
     */
    fun removeFexActive(): String? {
        if (!ApkConfStore.ensure(null)) return "App 内部 conf 目录不可用"
        return try {
            val f = ApkConfStore.confFile(FexConfSupport.ACTIVE_CONF)
            if (f.isFile && !f.delete()) "fex_active.conf 删除失败（权限/占用）" else null
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
    }

    // ------------------------------------------------------------------
    // 内部：行式 conf 解析（键名提取；解析器与启动链同源）
    // ------------------------------------------------------------------

    private fun keyOf(line: String): String? {
        var l = line.trim()
        if (l.isEmpty() || l.startsWith("#")) return null
        if (l.startsWith("export ")) l = l.substring(7).trim()
        val eq = l.indexOf('=')
        if (eq <= 0) return null
        val k = l.substring(0, eq).trim()
        return k.ifEmpty { null }
    }
}
