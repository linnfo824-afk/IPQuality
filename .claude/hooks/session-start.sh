#!/bin/bash
# 云端会话启动时安装 REA (https://github.com/morluto/rea) 并接入 Claude Code
set -euo pipefail

# 只在 Claude Code 云端会话中运行
if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

# 安装 rea CLI（已安装则跳过）
if ! command -v rea >/dev/null 2>&1; then
  npm install --global rea-agents
fi

# 注册 REA MCP 服务器并安装 skill（可重复执行）
rea setup --client claude_code --yes >/dev/null
