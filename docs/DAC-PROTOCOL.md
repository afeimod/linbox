# LinBox DAC 线协议（winedac.drv ↔ linbox_dac_bridge）

传输：`AF_UNIX SOCK_STREAM`，路径 `$PREFIX/tmp/linbox-dac.sock`。
frame：`[u32 magic][u32 type][u32 len][payload×len]`，magic=`0x44414332`。
fd：`SCM_RIGHTS` 附着于 sendmsg；fd 组随帧说明（`fd_count` 字段）。

> 同步约定（重要）：`DESKTOP_ALLOC` / `VK_IMAGE`(AHB 模式) 帧之后，
> 发送端立即调用 `AHardwareBuffer_sendHandleToUnixSocket` 补发序列化句柄；
> 接收端读完帧后必须第一时间 `AHardwareBuffer_recvHandleFromUnixSocket`
> 消费，不得先读后续帧。

## 消息一览

| type | 方向 | payload | fd |
|------|------|---------|-----|
| 1 HELLO | wine→app | `dac_hello{ver,w,h,fd_count,exe[256]}` | - |
| 100 HELLO_ACK | app→wine | `dac_hello_ack{ok,backend,api,max_slots}` | - |
| 2 DESKTOP_ALLOC | wine→app | `dac_buffer_meta{slot,w,h,stride,fmt,...}` | 后跟 AHB 句柄 |
| 3 DESKTOP_FREE | wine→app | `u32 slot` | - |
| 4 DESKTOP_PRESENT | wine→app | `dac_present{slot,surface_id=0,damage=1}` + `dac_rect×n` | -（fence=隐式） |
| 10 VK_SURFACE_NEW | wine→app | `dac_vk_surface_new{id,images,w,h,fmt,is_dmabuf}` | - |
| 11 VK_IMAGE | wine→app | `dac_buffer_meta{slot=idx,...}` | AHB: 后跟句柄 / dmabuf: 帧附 fd |
| 12 VK_SURFACE_DEL | wine→app | `u64 id` | - |
| 13 VK_PRESENT | wine→app | `dac_present{slot=idx,surface_id,damage=0}` | 帧附 acquire fence fd |
| 15 VK_LAYER_POS | wine→app | `dac_vk_layer_pos{id,x,y,w,h,z}` | - |
| 20 CURSOR | wine→app | `dac_cursor{visible,x,y,shape}` | - |
| 21 TITLE | wine→app | `{u64 hwnd, utf8 text[]}` | - |
| 30/31 PING/PONG | 双向 | - | - |
| 101 RELEASE | app→wine | `u32 slot` | 帧附 release fence fd（可无） |
| 102 VK_RELEASED | app→wine | `dac_present{slot=idx,surface_id}` | 帧附 release fence fd（可无） |
| 103 DESKTOP_RESIZE | app→wine | `{u32 w, u32 h}` | - |
| 110 INPUT_MOUSE | app→wine | `dac_input_mouse` | - |
| 111 INPUT_KEY | app→wine | `dac_input_key` | - |

## 语义要点

1. **HELLO** 握手后 bridge 回 `HELLO_ACK`，`backend` 决定呈现路径
   （0=SF 直合 / 1=AHB Canvas / 2=dmabuf EGL / 3=不可用）。wine 侧
   依此选择 AHB 或 dmabuf 内存后端。
2. **桌面槽位**：wine 维护 3 槽环形缓冲；`DESKTOP_PRESENT` 后该槽进入
   pending，bridge OnComplete 发 `RELEASE` 后复用。全 pending 时丢弃
   新帧（背压），保证不覆盖 SF 未读缓冲。
3. **VK_PRESENT 的 acquire fence**：由 wine 侧"转发提交"
   （wait 应用 present 信号量 → signal 可导出信号量 → SYNC_FD 导出）
   生成；bridge 将其交给 `ASurfaceTransaction_setBuffer` 的 fence 参数。
   fence_count=0 时 fence=-1（立即 latch）。
4. **VK_RELEASED**：bridge 从
   `ASurfaceTransactionStats_getPreviousReleaseFenceFd` 取释放 fence
   回传；wine 导入临时信号量并 `submit(wait, signal=应用acquire信号量)`。
5. **VK_LAYER_POS**：DXVK 窗口在虚拟桌面中的矩形（桌面坐标）；bridge
   `setPosition` + `setMatrix`（缩放）。z 默认 10（桌面层=0）。
6. **输入**：`INPUT_MOUSE.absolute=1` 时 x/y 为虚拟桌面绝对坐标；
   buttons 位序 L/R/M/X1/X2；wheel 单位 ±120=一格。
   `INPUT_KEY.keycode` 为 Android AKEYCODE_*，wine 侧查表转 vkey/scan。
7. **错误恢复**：任意端读帧失败即关连接；bridge 关闭时清空全部导入
   buffer；wine 端下次 `dac_desktop_ensure` 重新握手重连。

## 布局一致性

两侧结构体定义（`wine/dlls/winedac.drv/dac.h` 与
`app/src/main/cpp/dac/bridge.h`）必须逐字段一致；两端均为 1-index
自然对齐的小端 64 位布局，无位域、无指针、定长数组。改动协议必须
同步两份头文件并递增 `DAC_PROTOCOL_VERSION`。
