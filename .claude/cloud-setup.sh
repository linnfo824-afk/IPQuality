#!/bin/bash
# 云端环境 Setup script：安装 REA + Ghidra，并把 REA 注册为 Claude Code 的 MCP 服务器。
# 用法：把本文件内容粘贴到云端环境设置的 "Setup script" 中。
# 它在 Claude Code 启动前运行，因此 REA 的 MCP 工具在会话一开始就可用。
# 可重复执行：已安装的部分会被跳过。
set -euo pipefail

GHIDRA_VERSION="12.1.4"
GHIDRA_BUILD_DATE="20260921"
# 首次下载该发布包时记录的 SHA-256，用于发现损坏或被替换的下载
GHIDRA_SHA256="ddac49f903da9d5bac833e5cc79395098b9c33cfd3279be5f31bd00387d2d4db"
GHIDRA_PARENT="${GHIDRA_PARENT:-/opt}"
GHIDRA_DIR="$GHIDRA_PARENT/ghidra_${GHIDRA_VERSION}_PUBLIC"
GHIDRA_URL="https://github.com/NationalSecurityAgency/ghidra/releases/download/Ghidra_${GHIDRA_VERSION}_build/ghidra_${GHIDRA_VERSION}_PUBLIC_${GHIDRA_BUILD_DATE}.zip"

# Ghidra 需要 64 位完整 JDK 21+
if ! command -v javac >/dev/null 2>&1; then
  echo "cloud-setup: 未找到 JDK (javac)，Ghidra 需要 JDK 21+" >&2
  exit 1
fi

# 安装 Ghidra（先解压到临时目录再移动，避免留下半装的目录）
if [ ! -x "$GHIDRA_DIR/support/analyzeHeadless" ]; then
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  curl -fsSL --retry 4 --retry-delay 2 -o "$tmp/ghidra.zip" "$GHIDRA_URL"
  echo "$GHIDRA_SHA256  $tmp/ghidra.zip" | sha256sum -c --quiet -
  unzip -q "$tmp/ghidra.zip" -d "$tmp/extract"
  rm -f "$tmp/ghidra.zip"
  rm -rf "$GHIDRA_DIR"
  mv "$tmp/extract/ghidra_${GHIDRA_VERSION}_PUBLIC" "$GHIDRA_DIR"
fi
export GHIDRA_INSTALL_DIR="$GHIDRA_DIR"

# 让交互 shell 中的 rea CLI 也能找到 Ghidra
if ! grep -qs "GHIDRA_INSTALL_DIR=$GHIDRA_DIR" ~/.bashrc; then
  sed -i '/^export GHIDRA_INSTALL_DIR=/d' ~/.bashrc 2>/dev/null || true
  echo "export GHIDRA_INSTALL_DIR=$GHIDRA_DIR" >> ~/.bashrc
fi

# 安装 rea CLI（已安装则跳过）
if ! command -v rea >/dev/null 2>&1; then
  npm install --global rea-agents
fi

# 注册 REA MCP 服务器；GHIDRA_INSTALL_DIR 和 JAVA_HOME 会写入注册的 env
rea setup --client claude_code --yes >/dev/null
