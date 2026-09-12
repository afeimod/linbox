package com.linbox.apps.minesweeper

import android.content.Context
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.SoundPool
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linbox.core.theme.LocalWinTheme
import com.linbox.core.window.AppDef
import com.linbox.core.window.LaunchMode
import com.linbox.core.window.WindowContentScope
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * 扫雷 —— 使用用户提供的扫雷贴图资源还原的经典扫雷。
 *
 * - 三种难度：初级 9x9/10 雷、中级 16x16/40 雷、高级 30x16/99 雷
 * - 首击安全：第一次点击的格子及其 8 邻域保证无雷
 * - 交互：轻点翻开 / 长按插旗（或打开"插旗模式"后轻点即插旗）
 * - 顶栏：LED 雷数计数器（c0-c9 贴图）+ 笑脸按钮（smile/sorrow/confused）+ LED 计时器
 * - 翻开空格自动涟漪展开；游戏结束展示全部地雷与错误旗（mineclick/minewrong）
 * - 音效：number_merge1-4.wav（SoundPool 短音效池）
 */
val MinesweeperApp = AppDef(
    id = "minesweeper",
    displayName = "扫雷",
    iconAsset = "app:minesweeper",
    launchMode = LaunchMode.FLOATING,
    // v2.17：与初级 9x9 的目标窗口尺寸一致（msWindowSize），切换到中级/高级时自动扩展
    defaultWidth = 366.dp,
    defaultHeight = 488.dp,
    pinnedToDesktop = true,
    pinnedToTaskbar = true
) { scope ->
    MinesweeperContent(scope)
}

// ============================================================
// 游戏模型
// ============================================================

/** 单个格子状态（不可变快照，供重组渲染） */
data class MsCell(
    val isMine: Boolean = false,
    val revealed: Boolean = false,
    val flagged: Boolean = false,
    /** 周围 8 格雷数 0..8 */
    val adjacent: Int = 0
)

/** 难度 */
data class MsDifficulty(val label: String, val cols: Int, val rows: Int, val mines: Int)

val MS_DIFFICULTIES = listOf(
    MsDifficulty("初级", 9, 9, 10),
    MsDifficulty("中级", 16, 16, 40),
    MsDifficulty("高级", 30, 16, 99)
)

enum class MsGameState { PLAYING, WON, LOST }

/**
 * 扫雷棋盘逻辑。
 * 首击安全：布雷延迟到第一次翻开，避开首击格及其 8 邻域。
 */
class MsBoard(val cols: Int, val rows: Int, val mineCount: Int) {

    var minesPlaced = false
        private set

    /** 被点击引爆的雷位置（游戏结束时渲染 mineclick） */
    var explodedAt: Pair<Int, Int>? = null
        private set

    /** 可变载体（逻辑层内部使用） */
    class MutableCell {
        var isMine = false
        var revealed = false
        var flagged = false
        var adjacent = 0
    }

    val cells: List<MutableCell> = List(cols * rows) { MutableCell() }

    fun idx(x: Int, y: Int): Int = y * cols + x
    fun inBounds(x: Int, y: Int): Boolean = x in 0 until cols && y in 0 until rows

    private fun neighbors(x: Int, y: Int): List<Pair<Int, Int>> {
        val out = mutableListOf<Pair<Int, Int>>()
        for (dy in -1..1) for (dx in -1..1) {
            if (dx == 0 && dy == 0) continue
            val nx = x + dx; val ny = y + dy
            if (inBounds(nx, ny)) out.add(nx to ny)
        }
        return out
    }

    /** 布雷：避开首击格及其邻域 */
    private fun placeMines(safeX: Int, safeY: Int) {
        val forbidden = mutableSetOf(safeX to safeY)
        neighbors(safeX, safeY).forEach { forbidden.add(it) }
        val allCells = (0 until cols).flatMap { x -> (0 until rows).map { y -> x to y } }
        val candidates = if (allCells.size - forbidden.size >= mineCount) {
            allCells.filter { it !in forbidden }
        } else {
            allCells.filter { it != (safeX to safeY) }
        }
        candidates.shuffled(Random(System.currentTimeMillis())).take(mineCount).forEach { (x, y) ->
            cells[idx(x, y)].isMine = true
        }
        for (y in 0 until rows) for (x in 0 until cols) {
            if (!cells[idx(x, y)].isMine) {
                cells[idx(x, y)].adjacent =
                    neighbors(x, y).count { (nx, ny) -> cells[idx(nx, ny)].isMine }
            }
        }
        minesPlaced = true
    }

    /** 翻开格子。返回游戏状态 */
    fun reveal(x: Int, y: Int): MsGameState {
        if (!inBounds(x, y)) return MsGameState.PLAYING
        val c = cells[idx(x, y)]
        if (c.flagged || c.revealed) return MsGameState.PLAYING

        if (!minesPlaced) placeMines(x, y)

        if (c.isMine) {
            c.revealed = true
            explodedAt = x to y
            return MsGameState.LOST
        }

        // 涟漪展开（BFS：翻开所有连通的 0 邻域格 + 其边界数字格）
        val queue = ArrayDeque<Pair<Int, Int>>()
        queue.add(x to y)
        while (queue.isNotEmpty()) {
            val (cx, cy) = queue.removeFirst()
            if (!inBounds(cx, cy)) continue
            val cell = cells[idx(cx, cy)]
            if (cell.revealed || cell.flagged || cell.isMine) continue
            cell.revealed = true
            if (cell.adjacent == 0) {
                neighbors(cx, cy).forEach { queue.add(it) }
            }
        }

        val won = cells.none { !it.isMine && !it.revealed }
        return if (won) MsGameState.WON else MsGameState.PLAYING
    }

    /** 插旗切换 */
    fun toggleFlag(x: Int, y: Int): Boolean {
        if (!inBounds(x, y)) return false
        val c = cells[idx(x, y)]
        if (c.revealed) return false
        c.flagged = !c.flagged
        return true
    }

    /** 已插旗数量 */
    fun flagCount(): Int = cells.count { it.flagged }

    /** Chord：数字格周围旗数=数字时，揭开周围未旗格 */
    fun chord(x: Int, y: Int): MsGameState {
        if (!inBounds(x, y)) return MsGameState.PLAYING
        val c = cells[idx(x, y)]
        if (!c.revealed || c.adjacent == 0) return MsGameState.PLAYING
        val ns = neighbors(x, y)
        val flags = ns.count { (nx, ny) -> cells[idx(nx, ny)].flagged }
        if (flags != c.adjacent) return MsGameState.PLAYING
        var state = MsGameState.PLAYING
        ns.forEach { (nx, ny) ->
            val n = cells[idx(nx, ny)]
            if (!n.flagged && !n.revealed) {
                val s = reveal(nx, ny)
                if (s != MsGameState.PLAYING) state = s
            }
        }
        return state
    }

    /** 游戏失败时：翻开所有雷；错误旗（非雷但插旗）标记为已翻开以渲染 minewrong */
    fun revealAllMines() {
        cells.forEach {
            if (it.isMine) it.revealed = true
            else if (it.flagged) it.revealed = true
        }
    }

    /** 胜利时自动给所有雷插旗 */
    fun flagAllMines() {
        cells.forEach { if (it.isMine) it.flagged = true }
    }

    /** 导出不可变快照（重组安全） */
    fun snapshot(): List<MsCell> = cells.map { MsCell(it.isMine, it.revealed, it.flagged, it.adjacent) }
}

// ============================================================
// 音效池
// ============================================================

private class MsSoundPool(context: Context) {
    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()
    private val ids: List<Int> = listOf(
        "minesweeper/number_merge1.wav",
        "minesweeper/number_merge2.wav",
        "minesweeper/number_merge3.wav",
        "minesweeper/number_merge4.wav"
    ).mapNotNull { path ->
        // 注意：不能 use{} 关闭 afd —— SoundPool 异步解码仍需读取该 fd
        runCatching { pool.load(context.assets.openFd(path), 1) }.getOrNull()
    }

    fun playReveal() {
        ids.randomOrNull()?.let { pool.play(it, 0.8f, 0.8f, 1, 0, 1f) }
    }

    fun release() = pool.release()
}

// ============================================================
// 贴图缓存
// ============================================================

/** 扫雷贴图缓存：一次性从 assets 解码全部瓦片/表情/LED 数字 */
private class MsAssets(private val context: Context) {
    private fun load(path: String): BitmapPainter? = runCatching {
        context.assets.open(path).use { BitmapPainter(BitmapFactory.decodeStream(it).asImageBitmap()) }
    }.getOrNull()

    val unopen: BitmapPainter? = load("minesweeper/unopen.png")
    val open: BitmapPainter? = load("minesweeper/open.png")
    val flag: BitmapPainter? = load("minesweeper/flag.png")
    val mine: BitmapPainter? = load("minesweeper/mine.png")
    val mineclick: BitmapPainter? = load("minesweeper/mineclick.png")
    val minewrong: BitmapPainter? = load("minesweeper/minewrong.png")
    val numbers: List<BitmapPainter?> = (1..8).map { load("minesweeper/_$it.png") }
    val smile: BitmapPainter? = load("minesweeper/smile.png")
    val sorrow: BitmapPainter? = load("minesweeper/sorrow.png")
    val confused: BitmapPainter? = load("minesweeper/confused.png")
    val digits: List<BitmapPainter?> = (0..9).map { load("minesweeper/c$it.png") }
    /** 负号（czz.png，7x23） */
    val minus: BitmapPainter? = load("minesweeper/czz.png")

    companion object {
        private var instance: MsAssets? = null
        fun get(context: Context): MsAssets =
            instance ?: MsAssets(context.applicationContext).also { instance = it }
    }
}

// ============================================================
// 主界面
// ============================================================

/**
 * v2.17：按难度计算窗口目标尺寸（dp）。
 *
 * 单元格基准：初级 36dp / 中级 31dp / 高级 27dp（30 列手机竖屏必然超出
 * 工作区，WindowChrome 会钳制到工作区宽度，棋盘区自带滚动兼容）。
 * 额外空间：棋盘内边距 26 + 顶栏 ≈64 + 底部工具栏 ≈58 + 窗口内边距 ≈16。
 */
private fun msWindowSize(d: MsDifficulty): Pair<Int, Int> {
    val cell = when {
        d.cols >= 24 -> 27
        d.cols >= 16 -> 31
        else -> 36
    }
    val boardW = cell * d.cols + 26
    val boardH = cell * d.rows + 26
    val chromeH = 64 + 58 + 16
    return (boardW + 16) to (boardH + chromeH)
}

@Composable
private fun MinesweeperContent(scope: WindowContentScope) {
    val theme = LocalWinTheme.current
    val context = LocalContext.current
    val assets = remember { MsAssets.get(context) }
    val sound = remember { MsSoundPool(context) }
    DisposableEffect(Unit) { onDispose { sound.release() } }

    // ===== 游戏状态 =====
    var difficultyIdx by remember { mutableStateOf(0) }
    val difficulty = MS_DIFFICULTIES[difficultyIdx]
    var board by remember { mutableStateOf(MsBoard(difficulty.cols, difficulty.rows, difficulty.mines)) }
    var gameState by remember { mutableStateOf(MsGameState.PLAYING) }
    /** 版本号：board 内部可变，靠 tick 强制重组 */
    var revision by remember { mutableStateOf(0) }
    var elapsedSec by remember { mutableStateOf(0) }
    var started by remember { mutableStateOf(false) }
    /** 插旗模式：开启后轻点=插旗（手机上更顺手） */
    var flagMode by remember { mutableStateOf(false) }
    /** 笑脸按钮按压状态（显示 confused） */
    var facePressed by remember { mutableStateOf(false) }

    // ===== v2.17：难度变化时窗口自动扩展/收缩 =====
    // 初级 9x9 窗口最小，中级 16x16 / 高级 30x16 棋盘变大，
    // 旧版窗口固定 480x620 导致高级棋盘大量滚动；这里按难度目标尺寸重设窗口。
    // 注意：只在目标尺寸与当前不同时提交，避免每次重组都触发刷新。
    LaunchedEffect(difficultyIdx) {
        val (w, h) = msWindowSize(MS_DIFFICULTIES[difficultyIdx])
        val state = scope.windowState
        if (state.width != w || state.height != h) {
            com.linbox.core.window.WindowManager.get().resizeWindow(state.id, w, h)
        }
    }

    // 计时器：开始后每秒 +1，游戏结束停止
    LaunchedEffect(started, gameState, difficultyIdx) {
        if (started && gameState == MsGameState.PLAYING) {
            while (true) {
                delay(1000)
                if (gameState == MsGameState.PLAYING) elapsedSec++
            }
        }
    }

    fun resetGame(idx: Int = difficultyIdx) {
        val d = MS_DIFFICULTIES[idx]
        board = MsBoard(d.cols, d.rows, d.mines)
        gameState = MsGameState.PLAYING
        elapsedSec = 0
        started = false
        revision++
    }

    fun onCellTap(x: Int, y: Int) {
        if (gameState != MsGameState.PLAYING) return
        val b = board
        if (flagMode) {
            if (b.toggleFlag(x, y)) {
                revision++
                sound.playReveal()
            }
            return
        }
        val cell = b.cells.getOrNull(b.idx(x, y)) ?: return
        if (cell.revealed) {
            // 已翻开的数字格：尝试 Chord 快速揭开
            val s = b.chord(x, y)
            if (s != MsGameState.PLAYING) gameState = s
            if (!started) started = true
            revision++
            sound.playReveal()
            return
        }
        if (cell.flagged) return
        if (!started) started = true
        val s = b.reveal(x, y)
        if (s == MsGameState.LOST) {
            b.revealAllMines()
            gameState = MsGameState.LOST
        } else if (s == MsGameState.WON) {
            b.flagAllMines()
            gameState = MsGameState.WON
        }
        revision++
        sound.playReveal()
    }

    fun onCellLongPress(x: Int, y: Int) {
        if (gameState != MsGameState.PLAYING) return
        if (board.toggleFlag(x, y)) {
            revision++
            sound.playReveal()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(theme.windowBackgroundColor)
    ) {
        // ===== 顶部状态栏：LED 雷数 / 笑脸 / LED 时间 =====
        MsTopBar(
            theme = theme,
            assets = assets,
            minesLeft = difficulty.mines - board.flagCount(),
            elapsedSec = elapsedSec,
            gameState = gameState,
            facePressed = facePressed,
            onFacePress = { facePressed = true },
            onFaceRelease = { facePressed = false },
            onFaceClick = { resetGame() }
        )

        // ===== 棋盘 =====
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val cellSize: Dp = with(LocalDensity.current) {
                val availW = maxWidth.toPx() - 24f
                val availH = maxHeight.toPx() - 24f
                val byW = availW / difficulty.cols
                val byH = availH / difficulty.rows
                minOf(byW, byH).toDp().coerceIn(24.dp, 44.dp)
            }
            val boardW = cellSize * difficulty.cols
            val boardH = cellSize * difficulty.rows
            val needScrollX = boardW > maxWidth - 8.dp
            val needScrollY = boardH > maxHeight - 8.dp

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(6.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .then(if (needScrollX) Modifier.horizontalScroll(rememberScrollState()) else Modifier)
                        .then(if (needScrollY) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                ) {
                    MsGrid(
                        board = board,
                        assets = assets,
                        cellSize = cellSize,
                        revision = revision,
                        onCellTap = ::onCellTap,
                        onCellLongPress = ::onCellLongPress
                    )
                }
            }
        }

        // ===== 底部工具栏：难度 / 插旗模式 / 重开 =====
        MsToolbar(
            theme = theme,
            difficultyIdx = difficultyIdx,
            onDifficultyChange = { idx ->
                difficultyIdx = idx
                resetGame(idx)
            },
            flagMode = flagMode,
            onFlagModeChange = { flagMode = it },
            onReset = { resetGame() }
        )
    }
}

// ============================================================
// 顶栏（LED 计数器 + 笑脸）
// ============================================================

@Composable
private fun MsTopBar(
    theme: com.linbox.core.theme.WinTheme,
    assets: MsAssets,
    minesLeft: Int,
    elapsedSec: Int,
    gameState: MsGameState,
    facePressed: Boolean,
    onFacePress: () -> Unit,
    onFaceRelease: () -> Unit,
    onFaceClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(theme.cardBackgroundColor)
            .border(1.dp, theme.dividerColor, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        LedCounter(assets = assets, value = minesLeft, allowNegative = true)

        // 笑脸按钮
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(theme.buttonBackgroundColor)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            onFacePress()
                            try { awaitRelease() } finally { onFaceRelease() }
                        },
                        onTap = { onFaceClick() }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            val face = when {
                gameState == MsGameState.LOST -> assets.sorrow
                facePressed -> assets.confused
                else -> assets.smile
            }
            if (face != null) {
                Image(painter = face, contentDescription = null, modifier = Modifier.size(36.dp))
            } else {
                Text("🙂", fontSize = 22.sp)
            }
        }

        LedCounter(assets = assets, value = elapsedSec, allowNegative = false)
    }
}

/** LED 数字计数器：c0-c9 贴图拼 3 位数（带负号支持） */
@Composable
private fun LedCounter(assets: MsAssets, value: Int, allowNegative: Boolean) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFF000000))
            .border(1.dp, Color(0xFF555555), RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val clamped = value.coerceIn(if (allowNegative) -99 else 0, 999)
        val negative = clamped < 0
        val digitsStr = kotlin.math.abs(clamped).toString().padStart(3, '0').takeLast(3)
        if (negative && assets.minus != null) {
            Image(painter = assets.minus, contentDescription = null, modifier = Modifier.height(18.dp))
        }
        digitsStr.forEach { ch ->
            val painter = assets.digits.getOrNull(ch - '0')
            if (painter != null) {
                Image(painter = painter, contentDescription = null, modifier = Modifier.height(18.dp))
            } else {
                Text(
                    text = ch.toString(),
                    color = Color(0xFFFF2222),
                    fontSize = 15.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 1.dp)
                )
            }
        }
    }
}

// ============================================================
// 棋盘网格
// ============================================================

@Composable
private fun MsGrid(
    board: MsBoard,
    assets: MsAssets,
    cellSize: Dp,
    revision: Int,
    onCellTap: (Int, Int) -> Unit,
    onCellLongPress: (Int, Int) -> Unit
) {
    // revision 变化时强制重建快照
    val snapshot = remember(board, revision) { board.snapshot() }
    val exploded = remember(board, revision) { board.explodedAt }

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFFC0C0C0))
            .padding(3.dp)
    ) {
        for (y in 0 until board.rows) {
            Row {
                for (x in 0 until board.cols) {
                    val cell = snapshot[y * board.cols + x]
                    MsCellView(
                        cell = cell,
                        isExploded = exploded == (x to y),
                        assets = assets,
                        size = cellSize,
                        onTap = { onCellTap(x, y) },
                        onLongPress = { onCellLongPress(x, y) }
                    )
                }
            }
        }
    }
}

@Composable
private fun MsCellView(
    cell: MsCell,
    isExploded: Boolean,
    assets: MsAssets,
    size: Dp,
    onTap: () -> Unit,
    onLongPress: () -> Unit
) {
    // 贴图选择：游戏结束态由 revealed+flagged+mine 组合表达（board 内已处理）
    val painter: BitmapPainter? = when {
        cell.revealed && cell.isMine && isExploded -> assets.mineclick
        cell.revealed && cell.isMine -> if (cell.flagged) assets.flag else assets.mine
        cell.flagged && cell.revealed && !cell.isMine -> assets.minewrong
        cell.flagged -> assets.flag
        cell.revealed -> if (cell.adjacent > 0) assets.numbers.getOrNull(cell.adjacent - 1) else assets.open
        else -> assets.unopen
    }
    // 兜底色块（贴图缺失时仍可玩）
    val fallbackColor = when {
        cell.revealed && cell.isMine -> Color(0xFFFF4444)
        cell.revealed -> Color(0xFFE0E0E0)
        else -> Color(0xFFBDBDBD)
    }

    Box(
        modifier = Modifier
            .size(size)
            .background(fallbackColor)
            .pointerInput(cell.revealed, cell.flagged) {
                detectTapGestures(
                    onTap = { onTap() },
                    onLongPress = { onLongPress() }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        if (painter != null) {
            Image(painter = painter, contentDescription = null, modifier = Modifier.fillMaxSize())
        } else if (cell.revealed && cell.adjacent > 0) {
            Text(
                text = cell.adjacent.toString(),
                color = listOf(
                    Color.Blue, Color(0xFF007B00), Color.Red, Color(0xFF00007B),
                    Color(0xFF7B0000), Color(0xFF007B7B), Color.Black, Color(0xFF7B7B7B)
                )[cell.adjacent - 1],
                fontSize = (size.value * 0.5f).sp,
                fontWeight = FontWeight.Bold
            )
        } else if (cell.flagged) {
            Text("🚩", fontSize = (size.value * 0.5f).sp)
        } else if (cell.revealed && cell.isMine) {
            Text("💥", fontSize = (size.value * 0.5f).sp)
        }
    }
}

// ============================================================
// 底部工具栏
// ============================================================

@Composable
private fun MsToolbar(
    theme: com.linbox.core.theme.WinTheme,
    difficultyIdx: Int,
    onDifficultyChange: (Int) -> Unit,
    flagMode: Boolean,
    onFlagModeChange: (Boolean) -> Unit,
    onReset: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // 难度分段选择
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(theme.cardBackgroundColor)
                .border(1.dp, theme.dividerColor, RoundedCornerShape(8.dp))
                .padding(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MS_DIFFICULTIES.forEachIndexed { i, d ->
                val active = i == difficultyIdx
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (active) theme.accentColor.copy(alpha = 0.2f) else Color.Transparent)
                        .clickable { onDifficultyChange(i) }
                        .padding(vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = d.label,
                        color = if (active) theme.accentColor else theme.secondaryTextColor,
                        fontSize = 12.sp,
                        fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                        maxLines = 1
                    )
                }
            }
        }

        MsToolButton(
            theme = theme,
            text = if (flagMode) "插旗中" else "插旗",
            active = flagMode,
            onClick = { onFlagModeChange(!flagMode) }
        )

        MsToolButton(
            theme = theme,
            text = "重开",
            active = false,
            onClick = onReset
        )
    }
}

@Composable
private fun MsToolButton(
    theme: com.linbox.core.theme.WinTheme,
    text: String,
    active: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (active) theme.accentColor.copy(alpha = 0.22f) else theme.buttonBackgroundColor
            )
            .border(
                1.dp,
                if (active) theme.accentColor.copy(alpha = 0.5f) else theme.dividerColor,
                RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (active) theme.accentColor else theme.buttonTextColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}
