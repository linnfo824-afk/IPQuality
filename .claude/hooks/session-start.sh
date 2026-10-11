#!/bin/bash
# Install Ghidra in Claude Code cloud sessions so the REA MCP server
# (.mcp.json) can decompile native binaries with its Ghidra provider.
set -euo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

GHIDRA_VERSION="12.1.4"
GHIDRA_BUILD_DATE="20260921"
GHIDRA_SHA256="ddac49f903da9d5bac833e5cc79395098b9c33cfd3279be5f31bd00387d2d4db"
GHIDRA_ROOT="/opt/ghidra"
# Keep in sync with the GHIDRA_INSTALL_DIR default in .mcp.json.
GHIDRA_INSTALL_DIR="${GHIDRA_ROOT}/ghidra_${GHIDRA_VERSION}_PUBLIC"

installed() {
  [ -x "${GHIDRA_INSTALL_DIR}/support/analyzeHeadless" ] &&
    grep -qx "application.version=${GHIDRA_VERSION}" \
      "${GHIDRA_INSTALL_DIR}/Ghidra/application.properties"
}

if ! installed; then
  archive="ghidra_${GHIDRA_VERSION}_PUBLIC_${GHIDRA_BUILD_DATE}.zip"
  url="https://github.com/NationalSecurityAgency/ghidra/releases/download/Ghidra_${GHIDRA_VERSION}_build/${archive}"

  mkdir -p "$GHIDRA_ROOT"
  work="$(mktemp -d "${GHIDRA_ROOT}/.install.XXXXXX")"
  trap 'rm -rf "$work"' EXIT

  curl -fsSL --retry 4 --retry-delay 2 -o "${work}/${archive}" "$url"
  echo "${GHIDRA_SHA256}  ${work}/${archive}" | sha256sum --check --quiet -
  unzip -q "${work}/${archive}" -d "${work}/extract"

  rm -rf "$GHIDRA_INSTALL_DIR"
  mv "${work}/extract/ghidra_${GHIDRA_VERSION}_PUBLIC" "$GHIDRA_INSTALL_DIR"
  installed
fi

if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
  echo "export GHIDRA_INSTALL_DIR=\"${GHIDRA_INSTALL_DIR}\"" >> "$CLAUDE_ENV_FILE"
fi

echo "Ghidra ${GHIDRA_VERSION} is installed at ${GHIDRA_INSTALL_DIR} for the REA MCP server."
