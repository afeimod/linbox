#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""极简 msgfmt：把 gettext .po 编译成 .mo（支持多行/复数/跳过 fuzzy）。

用法: python3 msgfmt.py input.po output.mo
"""
import sys


def parse_po(path):
    entries = []          # [(key_id, key_plural, [translations...])]
    state = None          # 'msgid' | 'msgid_plural' | 'msgstr' | 'msgstr[n]'
    cur_id = []
    cur_pl = []
    cur_strs = {}         # index -> list of lines
    cur_idx = 0
    fuzzy = False
    obsolete_next = False

    def flush():
        nonlocal state, cur_id, cur_pl, cur_strs, fuzzy
        if state is None:
            return
        mid = ''.join(cur_id)
        mpl = ''.join(cur_pl)
        strs = []
        n = 0
        while n in cur_strs:
            strs.append(''.join(cur_strs[n]))
            n += 1
        if mid != '' or strs:
            if not fuzzy and any(s for s in strs):
                entries.append((mid, mpl, strs))
        state = None
        cur_id, cur_pl, cur_strs = [], [], {}
        fuzzy = False

    def unq(s):
        # s 为 "...",... 内部（含外层引号之外的分隔）——逐段处理
        out = []
        i = 0
        in_str = False
        while i < len(s):
            c = s[i]
            if not in_str:
                if c == '"':
                    in_str = True
                i += 1
                continue
            if c == '"':
                in_str = False
                i += 1
                continue
            if c == '\\':
                if i + 1 < len(s):
                    d = s[i + 1]
                    if d == 'n':
                        out.append('\n')
                    elif d == 't':
                        out.append('\t')
                    elif d == 'r':
                        out.append('\r')
                    elif d == '\\':
                        out.append('\\')
                    elif d == '"':
                        out.append('"')
                    elif d == "'":
                        out.append("'")
                    else:
                        out.append(d)
                    i += 2
                    continue
            out.append(c)
            i += 1
        return ''.join(out)

    with open(path, 'rb') as f:
        data = f.read().decode('utf-8', errors='replace')
    lines = data.split('\n')

    for raw in lines:
        line = raw.strip()
        if not line:
            flush()
            continue
        if line.startswith('#'):
            if line.startswith('#,') and 'fuzzy' in line:
                fuzzy = True
            flush()  # 注释行结束上一条目
            continue
        if line.startswith('msgid_plural'):
            flush_state = state
            # 保存当前 msgid 不 flush 整条：切换到 plural 收集
            state = 'msgid_plural'
            cur_pl.append(unq(line[len('msgid_plural'):]))
            continue
        if line.startswith('msgid'):
            flush()
            state = 'msgid'
            cur_id.append(unq(line[len('msgid'):]))
            continue
        if line.startswith('msgstr['):
            flush()
            # msgstr[0] "..."
            bracket = line[len('msgstr['):]
            idx = int(bracket.split(']')[0])
            state = 'msgstr'
            cur_idx = idx
            cur_strs.setdefault(idx, []).append(unq(bracket[bracket.index(']') + 1:]))
            continue
        if line.startswith('msgstr'):
            flush()
            state = 'msgstr'
            cur_idx = 0
            cur_strs.setdefault(0, []).append(unq(line[len('msgstr'):]))
            continue
        # 续行（纯字符串）
        if state == 'msgid':
            cur_id.append(unq(line))
        elif state == 'msgid_plural':
            cur_pl.append(unq(line))
        elif state == 'msgstr':
            cur_strs.setdefault(cur_idx, []).append(unq(line))
    flush()
    return entries


def write_mo(entries, out_path):
    # 组装 (msgid, msgstr) 对；复数条目：msgid = "sg\0pl"，msgstr = "s0\0s1..."
    items = []
    for (mid, mpl, strs) in entries:
        if mpl:
            oid = mid.encode('utf-8') + b'\x00' + mpl.encode('utf-8')
            ostr = b'\x00'.join(s.encode('utf-8') for s in strs)
        else:
            oid = mid.encode('utf-8')
            ostr = strs[0].encode('utf-8') if strs else b''
        if not oid and not ostr:
            continue
        items.append((oid, ostr))

    items.sort(key=lambda kv: kv[0])

    n = len(items)
    ids = b''.join(k for k, _ in items)
    strs = b''.join(v for _, v in items)
    # 表区：N×(length,offset) 8 字节/条 + N×(length,offset)
    keystart = 7 * 4 + 16 * n
    valuestart = keystart + len(ids)
    koffsets = []
    voffsets = []
    kpos = 0
    vpos = 0
    for k, v in items:
        koffsets.append(len(k))
        koffsets.append(keystart + kpos)
        voffsets.append(len(v))
        voffsets.append(valuestart + vpos)
        kpos += len(k)
        vpos += len(v)

    output = bytearray()
    output += (0x950412de).to_bytes(4, 'little')   # magic
    output += (0).to_bytes(4, 'little')            # version
    output += n.to_bytes(4, 'little')
    output += (7 * 4).to_bytes(4, 'little')          # start of key table
    output += (7 * 4 + n * 8).to_bytes(4, 'little')  # start of value table
    output += (0).to_bytes(4, 'little')            # size of hash table
    output += (0).to_bytes(4, 'little')            # offset of hash table
    for off in koffsets:
        output += off.to_bytes(4, 'little')
    for off in voffsets:
        output += off.to_bytes(4, 'little')
    output += ids
    output += strs
    with open(out_path, 'wb') as f:
        f.write(bytes(output))
    return n


if __name__ == '__main__':
    src, dst = sys.argv[1], sys.argv[2]
    ents = parse_po(src)
    cnt = write_mo(ents, dst)
    print(f'{src} -> {dst}: {cnt} entries')
