#!/usr/bin/env python3
"""Kotlin nested-comment / brace-balance scanner.

Kotlin block comments NEST (unlike Java): a literal '/*' sequence inside a
comment opens a nested comment that must be closed by an extra '*/'.
This scanner lexes .kt files and reports:
  - any '/*' seen while inside a comment (potential unclosed-comment bug)
  - comments still open at EOF
  - brace/paren/bracket balance of code-only text (comments & strings stripped)

Approximate string handling: "..." with escapes, \"\"\" raw strings, '...' char
literals, ${...} templates count braces. Good enough for this codebase.
"""
import sys
from pathlib import Path

def scan(path: Path):
    issues = []
    text = path.read_text(encoding="utf-8", errors="replace")
    n = len(text)
    i = 0
    line = 1
    # stack of (kind, line_started) for block comments
    comment_stack = []
    brace = paren = bracket = 0
    state = "code"  # code | line_comment | block_comment | string | raw_string | char
    tpl_depth = 0   # ${ ... } nesting inside strings

    def nl(ch):
        nonlocal line
        if ch == "\n":
            line += 1

    while i < n:
        c = text[i]
        c2 = text[i:i+2]
        if state == "code":
            if c2 == "//":
                state = "line_comment"
                i += 2; continue
            if c2 == "/*":
                comment_stack.append(line)
                state = "block_comment"
                i += 2; continue
            if text[i:i+3] == '"""':
                state = "raw_string"
                i += 3; continue
            if c == '"':
                state = "string"
                i += 1; continue
            if c == "'":
                state = "char"
                i += 1; continue
            if c == "{": brace += 1
            elif c == "}": brace -= 1
            elif c == "(": paren += 1
            elif c == ")": paren -= 1
            elif c == "[": bracket += 1
            elif c == "]": bracket -= 1
        elif state == "line_comment":
            if c == "\n":
                state = "code"
        elif state == "block_comment":
            if c2 == "/*":
                # THE bug pattern: nested comment open inside a comment
                issues.append(f"line {line}: nested '/*' inside comment (depth {len(comment_stack)})")
                comment_stack.append(line)
                i += 2; continue
            if c2 == "*/":
                comment_stack.pop()
                if not comment_stack:
                    state = "code"
                i += 2; continue
        elif state == "string":
            if c == "\\":
                # any escaped char (\", \\, \n, \t, \$ ...)
                i += 2; continue
            if tpl_depth == 0 and c == "\n":
                # unterminated single-line string (newlines OK inside ${...} templates)
                issues.append(f"line {line}: newline in non-raw string")
                state = "code"
            elif tpl_depth == 0 and c2 == "${":
                tpl_depth = 1
                brace += 1
                i += 2; continue
            elif tpl_depth > 0:
                # inside template expression: track braces, allow nested strings minimal
                if c == "{": tpl_depth += 1; brace += 1
                elif c == "}": tpl_depth -= 1; brace -= 1
                elif c == '"':
                    # nested string inside template (rare) - consume till "
                    j = i + 1
                    while j < n and text[j] != '"':
                        if text[j] == "\\": j += 1
                        j += 1
                    i = j
            if c == '"':
                # " ends string when not inside a template expression
                if tpl_depth == 0:
                    state = "code"
        elif state == "raw_string":
            if text[i:i+3] == '"""':
                state = "code"
                i += 3; continue
        elif state == "char":
            if c2[0] == "\\" and len(c2) == 2 and c2 != "\n":
                # any backslash escape inside char literal (', \, n, t, uXXXX...)
                if c2 == "\\u":
                    # unicode escape: \uXXXX
                    i += 6; continue
                i += 2; continue
            if c == "\n":
                issues.append(f"line {line}: newline in char literal")
                state = "code"
            elif c == "'":
                state = "code"
                i += 1; continue
        nl(c)
        i += 1

    if comment_stack:
        issues.append(f"EOF: unclosed comment (outermost opened at line {comment_stack[0]}, depth {len(comment_stack)})")
    for name, v in (("brace", brace), ("paren", paren), ("bracket", bracket)):
        if v != 0:
            issues.append(f"EOF: unbalanced {name} count {v:+d}")
    return issues

def main():
    roots = [Path(p) for p in sys.argv[1:]] or [Path(".")]
    files = []
    for r in roots:
        if r.is_file():
            files.append(r)
        else:
            files += sorted(r.rglob("*.kt")) + sorted(r.rglob("*.kts"))
    bad = 0
    for f in files:
        try:
            issues = scan(f)
        except Exception as e:
            issues = [f"scanner error: {e}"]
        if issues:
            bad += 1
            print(f"[FAIL] {f}")
            for it in issues[:12]:
                print(f"    - {it}")
    print(f"\n{len(files)} files scanned, {bad} with issues")
    sys.exit(1 if bad else 0)

if __name__ == "__main__":
    main()
