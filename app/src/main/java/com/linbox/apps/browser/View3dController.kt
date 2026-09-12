package com.linbox.apps.browser

import android.webkit.JavascriptInterface

/**
 * v2.16.3：浏览器"3D 视角旋转"（鼠标视角模式）控制器。
 *
 * ## v2.16.3 重做 —— 参照 GameBox（github.com/afeimod/GameBox）实现
 * v2.16.2 的实现"注入 mousemove 但游戏视角不动"的根因：FPS/3D 网页游戏
 * （Unity WebGL / Three.js PointerLockControls 等）的标准转视角流程是
 * 玩家点击画布 → 调用 canvas.requestPointerLock() → 收到 pointerlockchange
 * 进入锁定态 → 之后才在 mousemove 里读 movementX/movementY 转相机。
 * Android WebView 不支持 Pointer Lock：游戏锁定永远失败
 * （pointerLockElement 恒为 null），注入的 mousemove 被游戏忽略。
 *
 * 参照 GameBox 修复（三个关键点）：
 * 1. **模拟 Pointer Lock**：hook HTMLElement.prototype.requestPointerLock，
 *    立即置 document.pointerLockElement 并派发 pointerlockchange —— 游戏
 *    点击画布即"锁定成功"，后续 mousemove(movementX/Y) 正常被消费；
 * 2. **派发目标**：mousemove 发到 pointerLockElement || canvas ||
 *    [id*="game"] || [id*="flash"] || body，并同时派发到 document
 *    （兼容监听在 document 上的游戏）；clientX/clientY 用屏幕中心；
 * 3. **旁路不拦截**：Native 侧观察触摸累计增量，触摸事件照常给页面
 *    （tap/click 不受影响；配合注入 CSS overflow:hidden +
 *    canvas{touch-action:none} 防止拖动时页面滚动/选择）。
 *
 * ## 通道结构（保留 v2.16.2 的高性能双向通道）
 * - Java → 页面：ZoomPinchLayout 旁路观察单指拖动，把增量喂给
 *   [accumulate]（UI 线程，只累积不跨 JNI）；
 * - 页面 → Java：引擎在 onPageFinished 注入 [LOOK_SETUP_SCRIPT]，脚本用
 *   requestAnimationFrame 循环调用 [Bridge.pull]（@JavascriptInterface 同步
 *   JNI，单帧一次取走增量字符串），JS 侧派发鼠标事件。
 *   相比逐 move 事件 evaluateJavascript（每秒 60+ 次跨 JNI 异步排队堆积，
 *   GameBox 注释里的同款教训），每帧固定一次同步调用，开销恒定不积压。
 * - 仅可见页面的 rAF 运行（后台标签被 Chromium 暂停）→ 多标签天然安全。
 *
 * ## 小数增量语义（GameBox 同款）
 * JavaScript MouseEvent.movementX/Y 是整数：native 侧 float 累积，
 * pull 时取整数部分派发、小数余数保留在累加器 —— 慢速拖动每帧不足
 * 1px 的增量不会丢失（旧实现 toInt() 后清零，慢拖 movementX 恒 0）。
 *
 * ## iframe 传播（GameBox 没有的增强）
 * 大量 4399 类游戏把游戏本体放在 iframe 里：主 frame 的 rAF pull 取走
 * 增量后，同时转发给已安装同款函数的 same-origin 子 frame
 * （低频扫描新 iframe；跨域 frame 静默跳过）。
 */
object View3dController {

    /** 鼠标视角模式是否开启（BrowserContent 的 SideEffect 从设置同步） */
    @Volatile
    var enabled: Boolean = false

    /** 视角灵敏度（0.2..3.0）：拖动像素增量倍率 */
    @Volatile
    var sensitivity: Float = 1f

    // ===== 增量累加器（UI 线程写，JS 桥线程读 → 锁保护） =====
    private val lock = Any()
    /** 待派发的增量（已乘灵敏度，float 累积；pull 取整后余数保留） */
    private var accDx = 0f
    private var accDy = 0f

    /**
     * 视角拖动增量（ZoomPinchLayout 旁路观察 ACTION_MOVE 时喂入）。
     * 乘灵敏度后累积；不派发 press/release —— pointer-lock 类游戏只需
     * mousemove（GameBox 同款语义），误发 mousedown 反而会触发游戏点击。
     */
    fun accumulate(dx: Float, dy: Float) {
        if (dx == 0f && dy == 0f) return
        val s = sensitivity
        synchronized(lock) {
            accDx += dx * s
            accDy += dy * s
        }
    }

    /** 模式切换/导航时清空残留增量 */
    fun reset() {
        synchronized(lock) {
            accDx = 0f
            accDy = 0f
        }
    }

    /**
     * JS 桥：页面注入脚本每帧调用一次，取走累积的视角增量。
     * 返回格式 "dx,dy"（整数；小数余数保留在累加器，慢拖不丢增量）。
     * 模式关闭时恒返回 "0,0"（脚本零派发）。
     */
    class Bridge {
        @JavascriptInterface
        fun pull(): String {
            if (!enabled) return "0,0"
            return synchronized(View3dController.lock) {
                val ix = accDx.toInt()
                val iy = accDy.toInt()
                accDx -= ix
                accDy -= iy
                "$ix,$iy"
            }
        }
    }

    /** 注册到每个 WebView 的 JS 桥（window.__linboxLookBridge） */
    val bridge = Bridge()

    /**
     * 页面注入脚本（onPageFinished，http/file 页）。幂等。
     *
     * 结构（参照 GameBox CAMERA_ROTATION_SCRIPT + 我们的 rAF pull 桥）：
     * 1. [__linboxSetup]：自包含安装函数 —— 模拟 Pointer Lock API + CSS
     *    防滚动 + __linboxDispatchLook 派发函数（幂等，可装任意 frame）；
     * 2. 主 frame：直接执行安装 + rAF 循环 pull 桥增量 → 派发并转发给
     *    已安装的 same-origin iframe（子 frame 不起 rAF，防抢增量）；
     * 3. 子 frame 安装：把 __linboxSetup 序列化成源码（toString()）在
     *    子 frame 全局 eval 执行 —— 函数体内自由变量（window/document/
     *    HTMLElement 等）绑定到子 frame 的全局（跨域 frame eval 抛异常
     *    静默跳过）；低频扫描（rAF 计数 + 启动后 1s/3s 兜底）捕捉新 iframe。
     */
    val LOOK_SETUP_SCRIPT: String = """
        (function(){
          function __linboxSetup() {
            if (window.__linboxLook) return;
            window.__linboxLook = true;

            // === 模拟 Pointer Lock API（移动端 WebView 不支持原生锁定） ===
            if (!window.__linboxPointerLockHooked) {
              window.__linboxPointerLockHooked = true;
              try {
                HTMLElement.prototype.requestPointerLock = function() {
                  window.__linboxLocked = true;
                  document.pointerLockElement = this;
                  try { document.dispatchEvent(new Event('pointerlockchange')); } catch(e) {}
                  return Promise.resolve();
                };
              } catch(e) {}
              try {
                document.exitPointerLock = function() {
                  window.__linboxLocked = false;
                  document.pointerLockElement = null;
                  try { document.dispatchEvent(new Event('pointerlockchange')); } catch(e) {}
                };
              } catch(e) {}
              try {
                Object.defineProperty(document, 'pointerLockElement', {
                  get: function() { return window.__linboxLockEl || null; },
                  set: function(v) { window.__linboxLockEl = v; },
                  configurable: true
                });
              } catch(e) {}
            }

            // === CSS：禁拖选/禁滚动/canvas 禁默认触摸（v2.18 改为动态开合） ===
            // 样式表常驻，内容由“视角拖动进行中”动态切换：拖动中启用
            // 防滚动/禁选，拖动结束或模式关闭即恢复 —— 普通网页全程可正常
            // 滚动与选择（v2.18 修复“部分网页无法上下滑动”）。
            try {
              if (!document.getElementById('__linboxLookStyle')) {
                var st = document.createElement('style');
                st.id = '__linboxLookStyle';
                st.textContent = '';
                window.__linboxLookStyleEl = st;
                (document.head || document.documentElement).appendChild(st);
              }
            } catch(e) {}
            window.__linboxLookApplyLock = function(on) {
              try {
                var el = window.__linboxLookStyleEl ||
                         document.getElementById('__linboxLookStyle');
                if (el) {
                  el.textContent = on
                    ? 'body{-webkit-user-select:none;user-select:none;-webkit-touch-callout:none;overflow:hidden !important;}canvas{touch-action:none !important;}'
                    : '';
                }
              } catch(e) {}
            };

            // === 视角增量派发（GameBox __cameraRotate 同款目标选择） ===
            window.__linboxDispatchLook = function(dx, dy) {
              var target = document.pointerLockElement;
              if (!target) {
                target = document.querySelector('canvas') ||
                         document.querySelector('[id*="game"]') ||
                         document.querySelector('[id*="flash"]') ||
                         document.body;
              }
              if (!target) return;
              var evt = new MouseEvent('mousemove', {
                bubbles: true,
                cancelable: true,
                view: window,
                clientX: window.innerWidth / 2,
                clientY: window.innerHeight / 2,
                movementX: Math.round(dx),
                movementY: Math.round(dy)
              });
              try { target.dispatchEvent(evt); } catch(e) {}
              try { document.dispatchEvent(evt); } catch(e) {}
            };
          }

          if (!window.__linboxLook) __linboxSetup();

          // === v2.19：停止 pull 循环（模式关闭时由 native 调用，普通网页零开销） ===
          window.__linboxLookStop = function() {
            try {
              if (window.__linboxLookRaf) {
                cancelAnimationFrame(window.__linboxLookRaf);
              }
            } catch(e) {}
            window.__linboxLookRaf = 0;
            try { window.__linboxLookApplyLock(false); } catch(e) {}
          };

          // === same-origin iframe 安装（自包含源码在子 frame 全局 eval） ===
          // v2.19：挂在 window 上，重复注入时复用（rAF 循环引用 window 版本）
          window.__linboxLookFrames = [];
          window.__linboxInstallInto = function(w) {
            if (!w) return;
            try { void w.location.href; } catch(e) { return; } // 跨域 → 跳过
            try {
              if (!w.__linboxLook) {
                w.eval('(' + __linboxSetup.toString() + ')()');
              }
              if (w.__linboxDispatchLook && window.__linboxLookFrames.indexOf(w) < 0) {
                window.__linboxLookFrames.push(w);
              }
            } catch(e) {}
          };
          window.__linboxScanFrames = function() {
            try {
              var list = document.querySelectorAll('iframe');
              for (var i = 0; i < list.length; i++) {
                try { window.__linboxInstallInto(list[i].contentWindow); } catch(e) {}
              }
            } catch(e) {}
          };

          // === 主 frame：rAF 循环 pull 桥增量（子 frame 不 pull，防抢增量） ===
          // v2.19：每次注入都（重）启动循环 —— 关闭时 __linboxLookStop 停掉，
          // 再次开启重新注入即可恢复；普通页面全程不注入、零开销
          if (window.parent === window) {
            try {
              if (window.__linboxLookRaf) {
                cancelAnimationFrame(window.__linboxLookRaf);
              }
            } catch(e) {}
            window.__linboxLookRaf = 0;
            var b = window.__linboxLookBridge;
            if (b) {
              var tick = 0;
              var lockOn = false;
              function loop() {
                try {
                  var r = b.pull();
                  // v2.18：防滚动 CSS 随拖动状态动态开合（非拖动时页面可滚动）
                  var active = !!(r && r !== '0,0');
                  if (active !== lockOn) {
                    lockOn = active;
                    window.__linboxLookApplyLock(active);
                  }
                  if (r && r !== '0,0') {
                    var a = r.split(',');
                    var dx = parseFloat(a[0]) || 0;
                    var dy = parseFloat(a[1]) || 0;
                    if (dx !== 0 || dy !== 0) {
                      window.__linboxDispatchLook(dx, dy);
                      // 转发给已安装的 same-origin iframe（游戏在 iframe 里的场景）
                      var fr = window.__linboxLookFrames;
                      for (var i = 0; i < fr.length; i++) {
                        var f = fr[i];
                        if (f && typeof f.__linboxDispatchLook === 'function') {
                          try { f.__linboxDispatchLook(dx, dy); } catch(e) {}
                        }
                      }
                    }
                  }
                  // 低频扫描新 iframe（约每 2s 一次，querySelectorAll 开销可忽略）
                  if ((++tick % 120) === 0 && window.__linboxScanFrames) {
                    window.__linboxScanFrames();
                  }
                } catch(e) {}
                window.__linboxLookRaf = requestAnimationFrame(loop);
              }
              window.__linboxLookRaf = requestAnimationFrame(loop);
              if (window.__linboxScanFrames) window.__linboxScanFrames();
              setTimeout(function() {
                try { window.__linboxScanFrames && window.__linboxScanFrames(); } catch(e) {}
              }, 1000);
              setTimeout(function() {
                try { window.__linboxScanFrames && window.__linboxScanFrames(); } catch(e) {}
              }, 3000);
            }
          }
        })();
    """.trimIndent()
}
