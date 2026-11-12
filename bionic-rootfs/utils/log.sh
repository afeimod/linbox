#!/bin/bash
# 日志输出函数 —— tag 加颜色，消息保持默认
# 用法: info "消息" / warn "消息" / err "消息" / ok "消息"

_LOG_RED='\033[0;31m'
_LOG_YELLOW='\033[0;33m'
_LOG_BLUE='\033[0;34m'
_LOG_GREEN='\033[0;32m'
_LOG_RESET='\033[0m'

info() { echo -e "${_LOG_BLUE}[INFO]${_LOG_RESET} $*"; }
warn() { echo -e "${_LOG_YELLOW}[WARN]${_LOG_RESET} $*"; }
err()  { echo -e "${_LOG_RED}[ERR]${_LOG_RESET} $*"; }
ok()   { echo -e "${_LOG_GREEN}[OK]${_LOG_RESET} $*"; }
