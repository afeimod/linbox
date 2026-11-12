#!/usr/bin/env python3
"""ARM64X/ARM64EC 混合 PE 探测器（wine 11 新 WoW64 产物校验用）。

背景：wine 11 以 --enable-archs=arm64ec,aarch64,... 构建时，arm64ec 的 PE 代码
以 ARM64X 混合形式并入 aarch64-windows/ 的 DLL（与真实 Windows on ARM 的系统
DLL 一致），不再产出独立的 arm64ec-windows/ 目录。

原理：混合模块的 load config（PE 数据目录第 10 项）中 CHPEMetadataPointer 非 0
（winebuild 对 arm64ec 目标总是写入 __chpe_metadata）。
  * PE32+ (0x20B)：CHPEMetadataPointer 位于 load config 偏移 0xC8（8 字节）
  * PE32  (0x10B)：位于偏移 0x70（4 字节）

用法: python3 verify-arm64x.py <dll1> [dll2 ...]
退出码: 0 = 全部为混合模块; 1 = 存在非混合/解析失败
"""
import struct
import sys


def probe(path):
    """返回 (描述, 是否混合)。任何解析异常都视为非混合。"""
    try:
        with open(path, "rb") as fh:
            data = fh.read(8 * 1024 * 1024)
        if data[:2] != b"MZ":
            return "not MZ", False
        e = struct.unpack_from("<I", data, 0x3C)[0]
        if data[e:e + 4] != b"PE\x00\x00":
            return "no PE signature", False
        coff = e + 4
        machine, nsec = struct.unpack_from("<HH", data, coff)
        opt_size = struct.unpack_from("<H", data, coff + 16)[0]
        opt = coff + 20
        pe32p = struct.unpack_from("<H", data, opt)[0] == 0x20B
        dd_off = opt + (0x70 if pe32p else 0x60)
        lc_rva = struct.unpack_from("<I", data, dd_off + 80)[0]  # 目录项 10 = LOAD_CONFIG
        sec_off = opt + opt_size
        off = None
        for i in range(nsec):
            s = sec_off + i * 40
            vsize, va, rsize, rraw = struct.unpack_from("<IIII", data, s + 8)
            if va <= lc_rva < va + max(vsize, rsize):
                off = rraw + (lc_rva - va)
                break
        if off is None:
            return f"machine=0x{machine:x}, load config 未映射", False
        cfg_size = struct.unpack_from("<I", data, off)[0]
        if pe32p and cfg_size >= 0xD0:
            chpe = struct.unpack_from("<Q", data, off + 0xC8)[0]
        elif not pe32p and cfg_size >= 0x74:
            chpe = struct.unpack_from("<I", data, off + 0x70)[0]
        else:
            chpe = 0
        return f"machine=0x{machine:x}, CHPE=0x{chpe:x}", chpe != 0
    except Exception as ex:  # noqa: BLE001
        return f"error: {ex}", False


def main(argv):
    if len(argv) < 2:
        print("用法: verify-arm64x.py <dll> [...]")
        return 2
    ok = True
    for path in argv[1:]:
        info, hybrid = probe(path)
        tag = "[OK] ARM64X 混合" if hybrid else "[MISS] 非混合/无法解析"
        print(f"  {tag} {path} ({info})")
        if not hybrid:
            ok = False
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
