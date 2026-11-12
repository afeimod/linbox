package com.linbox.apps.filemanager;

import java.io.EOFException;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * v3.3.31（用户需求："文件管理器加入读取 exe 等内部图标 —— 不是其他图标，
 * 是每个 exe 内部自带的图标"）：Windows PE 内嵌图标提取器（纯 Java 零依赖）。
 *
 * 解析链（与 Wine shell32 的 PrivateExtractIconsW 同语义，全部自研）：
 *
 *   MZ(DOS header) → e_lfanew → PE\0\0 签名 → COFF 头 → Optional Header
 *   （PE32 0x10b / PE32+ 0x20b）→ DataDirectory[2] 资源表 RVA →
 *   节表 RVA→文件偏移换算 → .rsrc 三级目录（类型 → 名称/ID → 语言）→
 *   RT_GROUP_ICON(14) → GRPICONDIR → 选最佳条目 → RT_ICON(3) →
 *   PNG（\x89PNG 魔数）或 DIB（BITMAPINFOHEADER）→ ARGB 像素。
 *
 *  * 兼容 PE32 / PE32+（64 位 exe）、命名条目（高位名称标志，按 Id 顺序
 *    跳过、无 Id 条目时回退首个命名条目）、多语言（取首个叶子）；
 *  * 资源目录项 OffsetToData 的高位=0 时存在两种流转解释（相对 .rsrc 起点偏移
 *    vs 映像 RVA）—— 按"哪种解释能落到 .rsrc 内且数据项字段自洽"自适应判定
 *    （畸形/手工打包器两种都见过，全部兜住）；
 *  * 图标选择：宽高 0 = 256px（Vista+ 约定），按面积降序逐一尝试
 *    （最佳条目损坏自动顺延次优）；
 *  * DIB 解码：1/4/8/16/24/32bpp、调色板（alpha 字节按规范为保留位，强制
 *    不透明、透明交给 AND 掩码）、bottom-up 与顶向下（biHeight<0）、行 4
 *    字节对齐、AND 掩码透明度、32bpp 全零 alpha 回退不透明、biHeight 双倍
 *    与单倍两种打包形态；
 *  * 独立 .ico 文件（ICONDIR 16 字节目录项 + 绝对偏移）同样支持；
 *  * 任何结构异常返回 null —— 调用方回退通用类型图标（不抛异常）。
 *
 * 为什么是 Java 而非 Kotlin：解析核心保持纯 JVM（无 android.* 依赖），
 * 可用 javac 直接编译并在桌面端对真实 exe / 构造 PE 做单元验证；Android
 * 侧仅 FileExplorerApp 的薄封装（int[] → Bitmap / PNG → BitmapFactory）。
 */
public final class PeIcon {

    /** 资源类型常量（winuser.h）。 */
    private static final int RT_ICON = 3;
    private static final int RT_GROUP_ICON = 14;

    private PeIcon() {
    }

    /** 提取结果：PNG 图标返回 [png] 原始字节（调用方交给 BitmapFactory）；DIB 返回 [argb]。 */
    public static final class Icon {
        public final int width;
        public final int height;
        /** DIB 解码结果（width*height，ARGB 0xAARRGGBB，行序自上而下）；PNG 时为 null。 */
        public final int[] argb;
        /** PNG 原始字节（Vista+ 大图标惯例）；DIB 时为 null。 */
        public final byte[] png;

        Icon(int width, int height, int[] argb, byte[] png) {
            this.width = width;
            this.height = height;
            this.argb = argb;
            this.png = png;
        }
    }

    /** PE 资源树/节表/数据的统一上下文（文件打开期间持有）。 */
    private static final class PeCtx {
        final RandomAccessFile raf;
        final long fileLen;
        final byte[] sects;      // 节表原始字节
        final int numSects;
        final int rsrcRva;       // .rsrc 节 RVA
        final int rsrcSize;
        final int rsrcFileOff;   // .rsrc 文件偏移（root 目录）
        final int rsrcFileEnd;   // .rsrc 数据可信末端

        PeCtx(RandomAccessFile raf, long fileLen, byte[] sects, int numSects,
              int rsrcRva, int rsrcSize) {
            this.raf = raf;
            this.fileLen = fileLen;
            this.sects = sects;
            this.numSects = numSects;
            this.rsrcRva = rsrcRva;
            this.rsrcSize = rsrcSize;
            this.rsrcFileOff = rvaToOffset(sects, numSects, rsrcRva);
            int end = 0;
            if (this.rsrcFileOff >= 0) {
                // .rsrc 节的原始数据末端（min(节 rawSize, 文件长度)）
                for (int i = 0; i < numSects; i++) {
                    int o = i * 40;
                    int virtAddr = le32(sects, o + 12);
                    int rawSize = le32(sects, o + 16);
                    int rawPtr = le32(sects, o + 20);
                    if (virtAddr == rsrcRva) {
                        end = (int) Math.min((long) rawPtr + rawSize, fileLen);
                        break;
                    }
                }
            }
            this.rsrcFileEnd = end > this.rsrcFileOff ? end : (int) fileLen;
        }

        /** RVA → 文件偏移（节表换算；越界/虚拟填充返回 -1）。 */
        int offOfRva(int rva) {
            int[] r = rvaToOffset2(sects, numSects, rva);
            return r == null ? -1 : r[0];
        }
    }

    /**
     * 从 PE 文件提取最佳内嵌图标。
     *
     * @param path    文件路径（exe/dll/scr/cpl/ocx…）
     * @param maxSize 期望的最大边长（0 = 不限制）；无不超过者取最大条目
     * @return 图标；无资源 / 结构损坏 / IO 异常返回 null
     */
    public static Icon extract(String path, int maxSize) {
        try (RandomAccessFile raf = new RandomAccessFile(path, "r")) {
            long length = raf.length();
            if (length < 64) return null;
            byte[] head = new byte[64];
            raf.readFully(head);
            if (head[0] != 'M' || head[1] != 'Z') return null;
            int eLfanew = le32(head, 0x3c);
            if (eLfanew <= 0 || eLfanew + 24L > length) return null;

            byte[] pe = new byte[24];
            raf.seek(eLfanew);
            raf.readFully(pe);
            if (pe[0] != 'P' || pe[1] != 'E' || pe[2] != 0 || pe[3] != 0) return null;
            int numSections = le16(pe, 6);
            int sizeOptHeader = le16(pe, 20);
            if (sizeOptHeader < 64 || numSections <= 0 || numSections > 96) return null;

            byte[] opt = new byte[sizeOptHeader];
            raf.seek(eLfanew + 24L);
            raf.readFully(opt);
            int magic = le16(opt, 0);
            int ddOff = (magic == 0x20b) ? 112 : 96; // PE32+ / PE32 的 DataDirectory 基址
            if (ddOff + 16 > sizeOptHeader) return null;
            int rsrcRva = le32(opt, ddOff + 16);
            int rsrcSize = le32(opt, ddOff + 20);
            if (rsrcRva <= 0 || rsrcSize <= 0) return null;

            byte[] sects = new byte[numSections * 40];
            raf.seek(eLfanew + 24L + sizeOptHeader);
            raf.readFully(sects);

            PeCtx ctx = new PeCtx(raf, length, sects, numSections, rsrcRva, rsrcSize);
            if (ctx.rsrcFileOff < 0) return null;

            // ① RT_GROUP_ICON：最佳分组 → nId → RT_ICON 数据
            long grpData = leafDataOffset(ctx, RT_GROUP_ICON, -1);
            if (grpData >= 0) {
                int[] de = dataEntryAt(ctx, grpData);
                if (de != null) {
                    byte[] grp = readAtRva(ctx, de[0], de[1], 1 << 20);
                    if (grp != null) {
                        Icon icon = fromGroupDir(ctx, grp, maxSize);
                        if (icon != null) return icon;
                    }
                }
            }
            // ② 兜底：无分组（个别安装器壳）→ 直接取首个 RT_ICON
            long IconData = leafDataOffset(ctx, RT_ICON, -1);
            if (IconData >= 0) {
                int[] de = dataEntryAt(ctx, IconData);
                if (de != null) {
                    int off = ctx.offOfRva(de[0]);
                    if (off >= 0) return readBlobIcon(raf, off, de[1], maxSize);
                }
            }
            return null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * 独立 .ico 文件（ICONDIR：idType=1，16 字节目录项 + dwImageOffset 绝对偏移）。
     */
    public static Icon extractIco(String path, int maxSize) {
        try (RandomAccessFile raf = new RandomAccessFile(path, "r")) {
            long length = raf.length();
            if (length < 6) return null;
            byte[] dir = new byte[6];
            raf.readFully(dir);
            if (le16(dir, 2) != 1) return null;
            int count = le16(dir, 4);
            if (count <= 0 || count > 256) return null;
            byte[] entries = new byte[count * 16];
            raf.seek(6);
            raf.readFully(entries);
            // 按面积降序逐一尝试（损坏条目顺延）
            Integer[] order = new Integer[count];
            for (int i = 0; i < count; i++) order[i] = i;
            java.util.Arrays.sort(order, (a, b) -> Integer.compare(
                    wOf(entries, b) * hOf(entries, b), wOf(entries, a) * hOf(entries, a)));
            Icon fallback = null;
            for (int idx : order) {
                int size = le32(entries, idx * 16 + 8);
                int off = le32(entries, idx * 16 + 12);
                if (off < 0 || size <= 0 || off + (long) size > length) continue;
                Icon icon = readBlobIcon(raf, off, size, maxSize);
                if (icon == null) continue;
                int w = wOf(entries, idx), h = hOf(entries, idx);
                if (maxSize <= 0 || (w <= maxSize && h <= maxSize)) return icon;
                if (fallback == null) fallback = icon;
            }
            return fallback;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static int wOf(byte[] e, int i) {
        int w = e[i * 16] & 0xff;
        return w == 0 ? 256 : w;
    }

    private static int hOf(byte[] e, int i) {
        int h = e[i * 16 + 1] & 0xff;
        return h == 0 ? 256 : h;
    }

    // ----------------------------------------------------------------------
    // 资源目录遍历（root → 类型 → 名称/ID → 语言 → 数据项）
    // ----------------------------------------------------------------------

    /**
     * 定位某类型（[typeId]）下指定资源 Id（[wantId]，-1 = 首个 Id 条目，无 Id
     * 条目时回退首个命名条目）的数据项【文件偏移】；找不到返回 -1。
     */
    private static long leafDataOffset(PeCtx ctx, int typeId, int wantId) throws IOException {
        // 第一级（类型）
        int[] e1 = findEntry(ctx, ctx.rsrcFileOff, typeId, true);
        if (e1 == null) return -1;
        if ((e1[1] & 0x80000000) == 0) return resolveDataPtr(ctx, e1[1]);
        int dir2 = ctx.rsrcFileOff + (e1[1] & 0x7fffffff);
        // 第二级（名称/ID）
        int[] e2 = (wantId >= 0)
                ? findEntry(ctx, dir2, wantId, true)
                : firstEntry(ctx, dir2);
        if (e2 == null) return -1;
        if ((e2[1] & 0x80000000) == 0) return resolveDataPtr(ctx, e2[1]);
        int dir3 = ctx.rsrcFileOff + (e2[1] & 0x7fffffff);
        // 第三级（语言）→ 首个条目 → 数据项
        int[] e3 = firstEntry(ctx, dir3);
        if (e3 == null) return -1;
        if ((e3[1] & 0x80000000) != 0) {
            // 个别打包器语言层再嵌一层目录 —— 下钻一层
            int[] e4 = firstEntry(ctx, ctx.rsrcFileOff + (e3[1] & 0x7fffffff));
            if (e4 == null || (e4[1] & 0x80000000) != 0) return -1;
            return resolveDataPtr(ctx, e4[1]);
        }
        return resolveDataPtr(ctx, e3[1]);
    }

    /** 目录（[dirOff] 文件偏移）内按 Id 找条目 → {NameOrId, OffsetToData}；[idOnly]=true 时跳过命名条目。 */
    private static int[] findEntry(PeCtx ctx, int dirOff, int id, boolean idOnly) throws IOException {
        byte[] dir = new byte[16];
        if (!readAt(ctx, dirOff, dir)) return null;
        int named = le16(dir, 12), ided = le16(dir, 14);
        int total = named + ided;
        if (total <= 0 || total > 4096) return null;
        byte[] entries = new byte[total * 8];
        if (!readAt(ctx, dirOff + 16, entries)) return null;
        for (int i = idOnly ? named : 0; i < total; i++) {
            if (le32(entries, i * 8) == id) {
                return new int[]{le32(entries, i * 8), le32(entries, i * 8 + 4)};
            }
        }
        return null;
    }

    /** 目录内首个条目（Id 条目优先，其次命名条目）。 */
    private static int[] firstEntry(PeCtx ctx, int dirOff) throws IOException {
        byte[] dir = new byte[16];
        if (!readAt(ctx, dirOff, dir)) return null;
        int named = le16(dir, 12), ided = le16(dir, 14);
        int total = named + ided;
        if (total <= 0 || total > 4096) return null;
        byte[] entry = new byte[8];
        if (!readAt(ctx, dirOff + 16 + (ided > 0 ? named * 8L : 0), entry)) return null;
        return new int[]{le32(entry, 0), le32(entry, 4)};
    }

    /**
     * 目录项 OffsetToData（高位=0，指向数据项记录）→ 数据项记录的【文件偏移】。
     *
     * 两种流转解释自适应（先 .rsrc 相对，后映像 RVA —— 以数据项字段自洽为准）：
     *  * A：rel = .rsrc 起点起的偏移（MS 文档字面）；
     *  * B：rel 为映像 RVA（多数打包器实践）。
     */
    private static long resolveDataPtr(PeCtx ctx, int offField) throws IOException {
        int rel = offField & 0x7fffffff;
        // A：.rsrc 相对
        int a = ctx.rsrcFileOff + rel;
        if (a >= ctx.rsrcFileOff && a + 16 <= ctx.rsrcFileEnd && dataEntrySanity(ctx, a)) {
            return a;
        }
        // B：映像 RVA → 节表换算
        int b = ctx.offOfRva(rel);
        if (b >= 0 && b + 16 <= ctx.rsrcFileEnd && dataEntrySanity(ctx, b)) {
            return b;
        }
        // 都不自洽时取 A（后续字段校验由调用方兜底）
        return (a >= ctx.rsrcFileOff && a + 16 <= ctx.fileLen) ? a : -1;
    }

    /** 该文件偏移处的 16 字节是否像 IMAGE_RESOURCE_DATA_ENTRY（RVA 落在 .rsrc 内、Size 合理）。 */
    private static boolean dataEntrySanity(PeCtx ctx, int off) throws IOException {
        int[] de = dataEntryAt(ctx, off);
        if (de == null) return false;
        int rva = de[0], size = de[1];
        return rva >= ctx.rsrcRva && rva < ctx.rsrcRva + ctx.rsrcSize && ctx.offOfRva(rva) >= 0;
    }

    /** 读 IMAGE_RESOURCE_DATA_ENTRY → {OffsetToData(RVA), Size}；异常返回 null。 */
    private static int[] dataEntryAt(PeCtx ctx, long off) throws IOException {
        byte[] de = new byte[16];
        if (off < 0 || off + 16 > ctx.fileLen) return null;
        ctx.raf.seek(off);
        ctx.raf.readFully(de);
        int rva = le32(de, 0);
        int size = le32(de, 4);
        if (rva <= 0 || size <= 0 || size > (1 << 26)) return null;
        return new int[]{rva, size};
    }

    /** RVA 处读 [size] 字节（上限 [cap]）；越界返回 null。 */
    private static byte[] readAtRva(PeCtx ctx, int rva, int size, int cap) throws IOException {
        if (size <= 0 || size > cap) return null;
        int off = ctx.offOfRva(rva);
        if (off < 0 || off + (long) size > ctx.fileLen) return null;
        byte[] buf = new byte[size];
        ctx.raf.seek(off);
        ctx.raf.readFully(buf);
        return buf;
    }

    private static boolean readAt(PeCtx ctx, long off, byte[] buf) throws IOException {
        if (off < 0 || off + buf.length > ctx.fileLen) return false;
        ctx.raf.seek(off);
        ctx.raf.readFully(buf);
        return true;
    }

    // ----------------------------------------------------------------------
    // GRPICONDIR → RT_ICON
    // ----------------------------------------------------------------------

    private static Icon fromGroupDir(PeCtx ctx, byte[] grp, int maxSize) throws IOException {
        if (grp.length < 6) return null;
        if (le16(grp, 2) != 1) return null; // idType 必须为 icon
        int count = le16(grp, 4);
        if (count <= 0 || count > 256 || grp.length < 6 + count * 14) return null;
        // 面积降序（宽高 0 = 256px）
        Integer[] order = new Integer[count];
        for (int i = 0; i < count; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Integer.compare(
                gW(grp, b) * gH(grp, b), gW(grp, a) * gH(grp, a)));
        Icon fallback = null;
        for (int idx : order) {
            int nId = le16(grp, 6 + idx * 14 + 12);
            long leaf = leafDataOffset(ctx, RT_ICON, nId);
            if (leaf < 0) continue;
            int[] de = dataEntryAt(ctx, leaf);
            if (de == null) continue;
            int off = ctx.offOfRva(de[0]);
            if (off < 0) continue;
            Icon icon = readBlobIcon(ctx.raf, off, de[1], maxSize);
            if (icon == null) continue;
            int w = gW(grp, idx), h = gH(grp, idx);
            if (maxSize <= 0 || (w <= maxSize && h <= maxSize)) return icon;
            if (fallback == null) fallback = icon;
        }
        return fallback;
    }

    private static int gW(byte[] grp, int i) {
        int w = grp[6 + i * 14] & 0xff;
        return w == 0 ? 256 : w;
    }

    private static int gH(byte[] grp, int i) {
        int h = grp[6 + i * 14 + 1] & 0xff;
        return h == 0 ? 256 : h;
    }

    // ----------------------------------------------------------------------
    // 图标数据解码（PNG 或 DIB）
    // ----------------------------------------------------------------------

    private static Icon readBlobIcon(RandomAccessFile raf, int fileOff, int size, int maxSize) throws IOException {
        if (size <= 0 || size > (1 << 26)) return null;
        byte[] blob = new byte[size];
        raf.seek(fileOff);
        raf.readFully(blob);
        return decodeIconBytes(blob, maxSize);
    }

    /** PE 资源与 .ico 共用：PNG 魔数走 PNG（调用方自解码），否则按 DIB 解为 ARGB。 */
    public static Icon decodeIconBytes(byte[] blob, int maxSize) {
        if (blob == null || blob.length < 8) return null;
        if ((blob[0] & 0xff) == 0x89 && blob[1] == 'P' && blob[2] == 'N' && blob[3] == 'G') {
            return new Icon(0, 0, null, blob);
        }
        return decodeDib(blob);
    }

    /** DIB（BITMAPINFOHEADER + 调色板 + XOR 位图 + AND 掩码）→ ARGB（自上而下行序）。 */
    private static Icon decodeDib(byte[] blob) {
        if (blob.length < 40) return null;
        int biSize = le32(blob, 0);
        if (biSize < 40 || biSize > 124 || biSize > blob.length) return null;
        int w = le32(blob, 4);
        int h2 = le32(blob, 8);
        if (w <= 0 || w > 4096 || h2 == 0) return null;
        boolean topDown = h2 < 0;
        int dibH = Math.abs(h2);
        if (dibH <= 0 || dibH > 8192) return null;
        int planes = le16(blob, 12);
        int bpp = le16(blob, 14);
        int compression = le32(blob, 16);
        if (compression != 0) return null;               // BI_RGB（BITFIELDS 图标极罕见，放弃）
        if (bpp != 1 && bpp != 4 && bpp != 8 && bpp != 16 && bpp != 24 && bpp != 32) return null;
        if (planes != 1 && planes != 0) return null;

        int clrUsed = le32(blob, 32);
        int paletteEntries = (bpp <= 8)
                ? (clrUsed > 0 ? Math.min(clrUsed, 1 << bpp) : (1 << bpp)) : 0;
        int paletteOff = biSize;
        // 调色板（BGRA，第 4 字节为保留位 —— 规范恒 0，透明度交给 AND 掩码）
        int[] palette = new int[paletteEntries];
        for (int i = 0; i < paletteEntries; i++) {
            int o = paletteOff + i * 4;
            if (o + 4 > blob.length) return null;
            int b = blob[o] & 0xff, g = blob[o + 1] & 0xff, r = blob[o + 2] & 0xff;
            palette[i] = 0xff000000 | (r << 16) | (g << 8) | b;
        }

        int xorRowBytes = ((w * bpp + 31) / 32) * 4;
        int maskRowBytes = ((w + 31) / 32) * 4;
        int xorOff = paletteOff + paletteEntries * 4;

        // biHeight 语义：规范 = XOR 高度 + AND 掩码高度（双倍）；少数打包器只写
        // XOR 高度。判据：双倍解释下 XOR+AND 全部放得下 → 取半；否则原值（无掩码）。
        int h;
        if (dibH % 2 == 0 && dibH >= 2) {
            int half = dibH / 2;
            int needHalf = xorOff + xorRowBytes * half + maskRowBytes * half;
            int needFull = xorOff + xorRowBytes * dibH;
            h = (needHalf <= blob.length || needFull > blob.length) ? half : dibH;
        } else {
            h = dibH;
        }
        if (h <= 0 || h > 4096) return null;
        int maskOff = xorOff + xorRowBytes * h;

        int[] argb = new int[w * h];
        boolean hasAlpha = false;
        for (int y = 0; y < h; y++) {
            int srcY = topDown ? y : (h - 1 - y);
            for (int x = 0; x < w; x++) {
                int px;
                switch (bpp) {
                    case 32: {
                        int o = xorOff + srcY * xorRowBytes + x * 4;
                        if (o + 4 > blob.length) return null;
                        int b = blob[o] & 0xff, g = blob[o + 1] & 0xff;
                        int r = blob[o + 2] & 0xff, a = blob[o + 3] & 0xff;
                        if (a != 0) hasAlpha = true;
                        px = (a << 24) | (r << 16) | (g << 8) | b;
                        break;
                    }
                    case 24: {
                        int o = xorOff + srcY * xorRowBytes + x * 3;
                        if (o + 3 > blob.length) return null;
                        px = 0xff000000
                                | ((blob[o + 2] & 0xff) << 16)
                                | ((blob[o + 1] & 0xff) << 8)
                                | (blob[o] & 0xff);
                        break;
                    }
                    case 8: {
                        int o = xorOff + srcY * xorRowBytes + x;
                        if (o >= blob.length) return null;
                        px = palette[blob[o] & 0xff];
                        break;
                    }
                    case 4: {
                        int o = xorOff + srcY * xorRowBytes + (x >> 1);
                        if (o >= blob.length) return null;
                        int idx = (x & 1) == 0 ? (blob[o] >> 4) & 0xf : blob[o] & 0xf;
                        px = palette[idx];
                        break;
                    }
                    case 1: {
                        int o = xorOff + srcY * xorRowBytes + (x >> 3);
                        if (o >= blob.length) return null;
                        int bit = (blob[o] >> (7 - (x & 7))) & 1;
                        px = palette[bit];
                        break;
                    }
                    case 16: {
                        int o = xorOff + srcY * xorRowBytes + x * 2;
                        if (o + 2 > blob.length) return null;
                        int v = le16(blob, o);
                        int r5 = (v >> 10) & 0x1f, g5 = (v >> 5) & 0x1f, b5 = v & 0x1f;
                        px = 0xff000000
                                | (((r5 << 3) | (r5 >> 2)) << 16)
                                | (((g5 << 3) | (g5 >> 2)) << 8)
                                | ((b5 << 3) | (b5 >> 2));
                        break;
                    }
                    default:
                        return null;
                }
                argb[y * w + x] = px;
            }
        }

        if (bpp == 32) {
            // 32bpp 全零 alpha → 老打包器不带 alpha（全不透明）
            if (!hasAlpha) {
                for (int i = 0; i < argb.length; i++) argb[i] |= 0xff000000;
            }
        } else {
            // AND 掩码：bit=1 → 透明（<=24bpp 图标透明像素的唯一来源）
            boolean anyTransparent = false;
            for (int y = 0; y < h; y++) {
                int srcY = topDown ? y : (h - 1 - y);
                for (int x = 0; x < w; x++) {
                    int o = maskOff + srcY * maskRowBytes + (x >> 3);
                    if (o >= blob.length) break;
                    if (((blob[o] >> (7 - (x & 7))) & 1) != 0) {
                        argb[y * w + x] &= 0x00ffffff;
                        anyTransparent = true;
                    }
                }
            }
            if (!anyTransparent) {
                for (int i = 0; i < argb.length; i++) argb[i] |= 0xff000000;
            }
        }
        return new Icon(w, h, argb, null);
    }

    // ----------------------------------------------------------------------
    // 节表 RVA → 文件偏移
    // ----------------------------------------------------------------------

    /** 返回 {文件偏移, 剩余 rawSize}；不属任何节 / 落在虚拟填充返回 null。 */
    private static int[] rvaToOffset2(byte[] sects, int n, int rva) {
        for (int i = 0; i < n; i++) {
            int o = i * 40;
            int virtSize = le32(sects, o + 8);
            int virtAddr = le32(sects, o + 12);
            int rawSize = le32(sects, o + 16);
            int rawPtr = le32(sects, o + 20);
            if (virtAddr <= 0 || rawPtr <= 0) continue;
            int span = Math.max(virtSize, rawSize);
            if (rva >= virtAddr && rva < virtAddr + span) {
                int delta = rva - virtAddr;
                if (delta >= rawSize) return null;
                return new int[]{rawPtr + delta, rawSize - delta};
            }
        }
        return null;
    }

    private static int rvaToOffset(byte[] sects, int n, int rva) {
        int[] r = rvaToOffset2(sects, n, rva);
        return r == null ? -1 : r[0];
    }

    // ----------------------------------------------------------------------
    // 小端读取
    // ----------------------------------------------------------------------

    private static int le16(byte[] b, int o) {
        return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8);
    }

    private static int le32(byte[] b, int o) {
        return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8)
                | ((b[o + 2] & 0xff) << 16) | ((b[o + 3] & 0xff) << 24);
    }
}
