#!/usr/bin/env python3
"""Kotlin 结构校验：块注释嵌套计数 + 花括号/圆括号配平 + 字符串字面量感知。

针对 LinBox CI 曾折在的「块注释未闭合（Missing '}' / Unclosed comment）」
类错误：v3.2.3 轮 WineEnvironment.kt/WineProbePatch.kt 因 KDoc 里写了
`bin/*-preloader` 这类含 `/*` 的字面量，Kotlin 嵌套注释计数导致外层注释
永不闭合。本脚本按 Kotlin 语义模拟（块注释可嵌套、行注释、字符串与
字符转义），输出配平状态与可疑行号。
"""
import sys


def check(path: str) -> int:
    src = open(path, encoding="utf-8").read()
    i, n = 0, len(src)
    line = 1
    # 状态机：normal / blockcomment(depth) / linecomment / string / char
    state = "normal"
    depth = 0
    braces = parens = brackets = 0
    stack = []
    errors = []

    def enter(s):
        pass

    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ""
        if c == "\n":
            line += 1
            if state == "linecomment":
                state = "normal"
            i += 1
            continue
        if state == "normal":
            if c == "/" and nxt == "*":
                state = "blockcomment"
                depth = 1
                i += 2
                continue
            if c == "/" and nxt == "/":
                state = "linecomment"
                i += 2
                continue
            if c == '"':
                # 三引号原始串
                if src.startswith('"""', i):
                    state = "rawstring"
                    i += 3
                    continue
                state = "string"
                i += 1
                continue
            if c == "'":
                state = "char"
                i += 1
                continue
            if c == "{":
                braces += 1
                stack.append(("{", line))
            elif c == "}":
                braces -= 1
                if stack and stack[-1][0] == "{":
                    stack.pop()
                else:
                    errors.append("L%d: 出现 '}' 无对应 '{'" % line)
            elif c == "(":
                parens += 1
                stack.append(("(", line))
            elif c == ")":
                parens -= 1
                if stack and stack[-1][0] == "(":
                    stack.pop()
                else:
                    errors.append("L%d: 出现 ')' 无对应 '('" % line)
            elif c == "[":
                brackets += 1
                stack.append(("[", line))
            elif c == "]":
                brackets -= 1
                if stack and stack[-1][0] == "[":
                    stack.pop()
                else:
                    errors.append("L%d: 出现 ']' 无对应 '['" % line)
        elif state == "blockcomment":
            if c == "/" and nxt == "*":
                depth += 1
                i += 2
                continue
            if c == "*" and nxt == "/":
                depth -= 1
                if depth == 0:
                    state = "normal"
                i += 2
                continue
        elif state == "rawstring":
            if src.startswith('"""', i):
                state = "normal"
                i += 3
                continue
        elif state == "string":
            if c == "\\":
                i += 2
                continue
            if c == '"':
                state = "normal"
        elif state == "char":
            if c == "\\":
                i += 2
                continue
            if c == "'":
                state = "normal"
        i += 1

    if state == "blockcomment" or depth > 0:
        errors.append(f"EOF: 块注释未闭合（depth={depth}, state={state}）")
    if state in ("string", "char", "rawstring"):
        errors.append(f"EOF: 字符串未闭合（state={state}）")
    if braces != 0:
        errors.append(f"EOF: 花括号不平衡 {{}}={braces}（未闭合: {stack[-8:]}）")
    if parens != 0:
        errors.append(f"EOF: 圆括号不平衡 ()={parens}")
    if brackets != 0:
        errors.append(f"EOF: 方括号不平衡 []={brackets}")

    if errors:
        print(f"FAIL {path}")
        for e in errors:
            print("   ", e)
        return 1
    print(f"OK   {path}（注释/字符串/括号全部配平）")
    return 0


if __name__ == "__main__":
    rc = 0
    for p in sys.argv[1:]:
        rc |= check(p)
    sys.exit(rc)
