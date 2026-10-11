#!/bin/bash
# Install REA (rea-agents) and every analysis engine it can drive on Linux so
# the REA MCP server (.mcp.json) exposes its full tool set in Claude Code cloud
# sessions. Each step is skipped when its engine is already installed, and one
# failed step does not stop the others.
set -euo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

export DEBIAN_FRONTEND=noninteractive
export DOTNET_CLI_TELEMETRY_OPTOUT=1 DOTNET_NOLOGO=1 DOTNET_SKIP_FIRST_TIME_EXPERIENCE=1
export PATH="${HOME}/.cargo/bin:${PATH}"

REA_VERSION="6.4.0"
TOOLS="/opt/rea-tools"
# .mcp.json sources this file before it starts the REA MCP server.
ENV_FILE="${TOOLS}/env.sh"
LOG="${TOOLS}/install.log"

# Engine versions and digests are the ones rea-agents 6.4.0 documents as verified.
GHIDRA_VERSION="12.1.4"
GHIDRA_BUILD_DATE="20260921"
GHIDRA_SHA256="ddac49f903da9d5bac833e5cc79395098b9c33cfd3279be5f31bd00387d2d4db"
GHIDRA_INSTALL_DIR="/opt/ghidra/ghidra_${GHIDRA_VERSION}_PUBLIC"
JADX_VERSION="0.7.1"
JADX_SHA256="6e5eacf500b64292bfb73c49797c1958f6ee44646e43e868039ae7feb573ff75"
JADX_JAR="${TOOLS}/jadx/jadx-headless-mcp-${JADX_VERSION}-all.jar"
WAKARU_VERSION="1.14.0"
WAKARU_SHA256="664fa06782a61eda7ed15885134b52525fd565dd77607e642062ceb6cd927c38"
BINWALK_VERSION="3.1.0"
UNBLOB_VERSION="26.6.4"
SASQUATCH_RELEASE="sasquatch-v4.5.1-6"
SASQUATCH_SHA256="efbc92bd27594e01211e1b582f105a463a0017360a0746a93de3f283906ade95"
MITMPROXY_VERSION="12.2.3"
PWNTOOLS_VERSION="4.15.0"
PYELFTOOLS_VERSION="0.33"
UNICORN_VERSION="2.1.2"
PWNDBG_VERSION="2026.09.15"
PWNDBG_COMMIT="55596aa676a2756efb8d67034f31675170eabe48"
# ilspycmd 10+ targets .NET 10; 9.1 is the newest release that runs on .NET 8.
ILSPY_VERSION="9.1.0.7988"

APT_PACKAGES=(
  adb apktool dotnet-sdk-8.0 gdb python3.12-venv xvfb libxtst6
  # Unblob extractors and the Binwalk build dependencies.
  p7zip-full lz4 lziprecover lzop zstd unar android-sdk-libsparse-utils
  partclone erofs-utils e2fsprogs libfontconfig1-dev liblzma-dev
)

mkdir -p "$TOOLS"
: > "$LOG"
WORK="$(mktemp -d "${TOOLS}/.work.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

download() {
  local url="$1" dest="$2" sha256="$3"
  curl -fsSL --retry 4 --retry-delay 2 -o "$dest" "$url"
  echo "${sha256}  ${dest}" | sha256sum --check --quiet -
}

has_sha256() {
  [ -f "$1" ] && echo "$2  $1" | sha256sum --check --quiet - > /dev/null 2>&1
}

ghidra_installed() {
  [ -x "${GHIDRA_INSTALL_DIR}/support/analyzeHeadless" ] &&
    grep -qx "application.version=${GHIDRA_VERSION}" \
      "${GHIDRA_INSTALL_DIR}/Ghidra/application.properties"
}

install_apt_packages() {
  local missing=() package
  for package in "${APT_PACKAGES[@]}"; do
    dpkg-query -W -f='${Status}' "$package" 2> /dev/null | grep "ok installed" > /dev/null ||
      missing+=("$package")
  done
  if [ "${#missing[@]}" -gt 0 ]; then
    apt-get update -qq
    apt-get install -y -qq --no-install-recommends "${missing[@]}"
  fi
  if ! command -v sasquatch > /dev/null; then
    download "https://github.com/onekey-sec/sasquatch/releases/download/${SASQUATCH_RELEASE}/sasquatch_1.0_amd64.deb" \
      "${WORK}/sasquatch.deb" "$SASQUATCH_SHA256"
    dpkg -i "${WORK}/sasquatch.deb"
  fi
}

install_ghidra() {
  ghidra_installed && return
  local archive="ghidra_${GHIDRA_VERSION}_PUBLIC_${GHIDRA_BUILD_DATE}.zip"
  download "https://github.com/NationalSecurityAgency/ghidra/releases/download/Ghidra_${GHIDRA_VERSION}_build/${archive}" \
    "${WORK}/${archive}" "$GHIDRA_SHA256"
  unzip -q "${WORK}/${archive}" -d "${WORK}/ghidra"
  mkdir -p "$(dirname "$GHIDRA_INSTALL_DIR")"
  rm -rf "$GHIDRA_INSTALL_DIR"
  mv "${WORK}/ghidra/ghidra_${GHIDRA_VERSION}_PUBLIC" "$GHIDRA_INSTALL_DIR"
  ghidra_installed
}

install_rea() {
  [ "$(rea --version 2> /dev/null)" = "$REA_VERSION" ] ||
    npm install -g --no-fund --no-audit "rea-agents@${REA_VERSION}"
  # Warm the npx cache that .mcp.json starts the MCP server from.
  npx -y "rea-agents@${REA_VERSION}" --version > /dev/null
}

install_jadx() {
  has_sha256 "$JADX_JAR" "$JADX_SHA256" && return
  download "https://github.com/1013503897/jadx-headless-mcp/releases/download/v${JADX_VERSION}/jadx-headless-mcp-${JADX_VERSION}-all.jar" \
    "${WORK}/jadx.jar" "$JADX_SHA256"
  install -D -m 0644 "${WORK}/jadx.jar" "$JADX_JAR"
}

install_wakaru() {
  has_sha256 "${TOOLS}/wakaru/wakaru" "$WAKARU_SHA256" && return
  curl -fsSL --retry 4 --retry-delay 2 -o "${WORK}/wakaru.tar.gz" \
    "https://github.com/pionxzh/wakaru/releases/download/v${WAKARU_VERSION}/wakaru-linux-x64.tar.gz"
  tar -xzf "${WORK}/wakaru.tar.gz" -C "$WORK" wakaru
  echo "${WAKARU_SHA256}  ${WORK}/wakaru" | sha256sum --check --quiet -
  install -D -m 0755 "${WORK}/wakaru" "${TOOLS}/wakaru/wakaru"
}

install_venv() {
  local venv="${TOOLS}/$1-venv"
  shift
  [ -x "${venv}/bin/python" ] || /usr/bin/python3.12 -m venv "$venv"
  "${venv}/bin/pip" install -q --disable-pip-version-check "$@"
}

install_pwntools() {
  local python="${TOOLS}/pwntools-venv/bin/python"
  [ -x "$python" ] && "$python" -I -c "
import elftools, pwnlib, unicorn
assert (pwnlib.__version__, elftools.__version__, unicorn.__version__) == \
    ('${PWNTOOLS_VERSION}', '${PYELFTOOLS_VERSION}', '${UNICORN_VERSION}')
" 2> /dev/null && return
  install_venv pwntools "pwntools==${PWNTOOLS_VERSION}" \
    "pyelftools==${PYELFTOOLS_VERSION}" "unicorn==${UNICORN_VERSION}"
}

install_unblob() {
  local bin="${TOOLS}/unblob-venv/bin" tool
  [ "$("${bin}/unblob" --version 2> /dev/null)" = "$UNBLOB_VERSION" ] ||
    install_venv unblob "unblob==${UNBLOB_VERSION}"
  # Unblob looks these extractors up on PATH; its venv bin is not on PATH.
  for tool in jefferson ubireader_extract_files ubireader_extract_images; do
    ln -sfn "${bin}/${tool}" "/usr/local/bin/${tool}"
  done
}

install_mitmproxy() {
  "${TOOLS}/mitmproxy-venv/bin/mitmdump" --version 2> /dev/null |
    grep -x "Mitmproxy: ${MITMPROXY_VERSION}" > /dev/null && return
  install_venv mitmproxy "mitmproxy==${MITMPROXY_VERSION}"
}

install_binwalk() {
  [ "$("${TOOLS}/binwalk/bin/binwalk" --version 2> /dev/null)" = "binwalk ${BINWALK_VERSION}" ] && return
  cargo install binwalk --version "$BINWALK_VERSION" --locked --root "${TOOLS}/binwalk"
}

install_pwndbg() {
  local dir="${TOOLS}/pwndbg" python_version
  [ -f "${dir}/.rea-installed" ] &&
    [ "$(git -C "$dir" rev-parse HEAD 2> /dev/null)" = "$PWNDBG_COMMIT" ] && return
  rm -rf "$dir"
  git -c advice.detachedHead=false clone -q --depth 1 --branch "$PWNDBG_VERSION" \
    https://github.com/pwndbg/pwndbg "$dir"
  [ "$(git -C "$dir" rev-parse HEAD)" = "$PWNDBG_COMMIT" ]
  # pwndbg's virtualenv must use the Python that GDB embeds.
  python_version="$(gdb -batch -q --nx -ex 'python import sys; print("%d.%d" % sys.version_info[:2])')"
  "/usr/bin/python${python_version}" -m venv "${dir}/.venv"
  "${dir}/.venv/bin/pip" install -q --disable-pip-version-check uv
  (
    cd "$dir"
    export VIRTUAL_ENV="${dir}/.venv" UV_PROJECT_ENVIRONMENT="${dir}/.venv"
    "${dir}/.venv/bin/uv" sync --frozen --no-install-project --group build -q
    "${dir}/.venv/bin/uv" sync --frozen --no-build-isolation-package pwndbg -q
  )
  touch "${dir}/.rea-installed"
}

install_ilspy() {
  "${TOOLS}/ilspy/ilspycmd" --version 2> /dev/null |
    grep -x "ilspycmd: ${ILSPY_VERSION}" > /dev/null && return
  rm -rf "${TOOLS}/ilspy"
  dotnet tool install ilspycmd --version "$ILSPY_VERSION" --tool-path "${TOOLS}/ilspy"
}

install_nativeaot() {
  local manifest="${TOOLS}/nativeaot/build.json"
  [ -f "${TOOLS}/nativeaot/rea-ghidra-nativeaot.jar" ] &&
    grep -q "\"ghidra_version\": \"${GHIDRA_VERSION}\"" "$manifest" 2> /dev/null && return
  ghidra_installed
  # REA builds this Ghidra extension from source at its pinned upstream commit.
  git -c advice.detachedHead=false clone -q --depth 1 --branch "rea-agents-${REA_VERSION}" \
    https://github.com/morluto/rea "${WORK}/rea"
  git -C "${WORK}/rea" submodule update -q --init --depth 1 third_party/ghidra-nativeaot
  GHIDRA_INSTALL_DIR="$GHIDRA_INSTALL_DIR" REA_NATIVEAOT_BUILD_ROOT="${TOOLS}/nativeaot" \
    node "${WORK}/rea/scripts/build-ghidra-nativeaot.mjs"
}

install_hopper() {
  [ -x /opt/hopper/bin/Hopper ] && return
  if ! curl -fsS -o /dev/null --max-time 15 https://www.hopperapp.com/; then
    echo "www.hopperapp.com is not reachable from this environment"
    return 3
  fi
  # Without --client, setup installs only Hopper and leaves agent configuration alone.
  rea setup --yes --install-hopper --json
}

write_env() {
  local browser
  browser="$(find /opt/pw-browsers -path '*/chrome-linux*/chrome' -type f 2> /dev/null | sort -V | tail -n 1 || true)"
  {
    env_line GHIDRA_INSTALL_DIR "$GHIDRA_INSTALL_DIR" -d
    env_line REA_GHIDRA_NATIVEAOT_JAR "${TOOLS}/nativeaot/rea-ghidra-nativeaot.jar" -f
    env_line REA_JADX_MCP_JAR "$JADX_JAR" -f
    env_line REA_APKTOOL_COMMAND /usr/bin/apktool -x
    env_line REA_ADB_PATH /usr/bin/adb -x
    env_line REA_BINWALK_COMMAND "${TOOLS}/binwalk/bin/binwalk" -x
    env_line REA_UNBLOB_COMMAND "${TOOLS}/unblob-venv/bin/unblob" -x
    env_line REA_PWNTOOLS_PYTHON "${TOOLS}/pwntools-venv/bin/python" -x
    if [ -f "${TOOLS}/pwndbg/.rea-installed" ]; then
      env_line REA_PWNDBG_GDB /usr/bin/gdb -x
      env_line REA_PWNDBG_GDBINIT "${TOOLS}/pwndbg/gdbinit.py" -f
      env_line REA_PWNDBG_VENV_PATH "${TOOLS}/pwndbg/.venv" -d
    fi
    env_line REA_WAKARU_COMMAND "${TOOLS}/wakaru/wakaru" -x
    env_line REA_MITMDUMP_COMMAND "${TOOLS}/mitmproxy-venv/bin/mitmdump" -x
    env_line REA_ILSPY_CMD_PATH "${TOOLS}/ilspy/ilspycmd" -x
    if [ -n "$browser" ]; then
      env_line REA_BROWSER_EXECUTABLE "$browser" -x
      # Chromium refuses to start its sandbox as root.
      [ "$(id -u)" != 0 ] || echo 'export REA_BROWSER_NO_SANDBOX="true"'
    fi
  } > "${ENV_FILE}.tmp"
  mv "${ENV_FILE}.tmp" "$ENV_FILE"
}

env_line() {
  local name="$1" path="$2" kind="$3" present=false
  case "$kind" in
    -d) [ ! -d "$path" ] || present=true ;;
    -f) [ ! -f "$path" ] || present=true ;;
    -x) [ ! -x "$path" ] || present=true ;;
  esac
  if [ "$present" = true ]; then
    printf 'export %s="%s"\n' "$name" "$path"
  fi
}

failed=()
skipped=()
run_step() {
  local name="$1" status
  shift
  echo "=== ${name}" >> "$LOG"
  set +e
  (
    set -e
    "$@"
  ) >> "$LOG" 2>&1
  status=$?
  set -e
  case "$status" in
    0) ;;
    3) skipped+=("$name") ;;
    *) failed+=("$name") ;;
  esac
}

run_step "apt packages" install_apt_packages
run_step "Ghidra ${GHIDRA_VERSION}" install_ghidra
run_step "rea-agents ${REA_VERSION}" install_rea
run_step "JADX ${JADX_VERSION}" install_jadx
run_step "Wakaru ${WAKARU_VERSION}" install_wakaru
run_step "pwntools ${PWNTOOLS_VERSION}" install_pwntools
run_step "Unblob ${UNBLOB_VERSION}" install_unblob
run_step "mitmproxy ${MITMPROXY_VERSION}" install_mitmproxy
run_step "Binwalk ${BINWALK_VERSION}" install_binwalk
run_step "pwndbg ${PWNDBG_VERSION}" install_pwndbg
run_step "ILSpy ${ILSPY_VERSION}" install_ilspy
run_step "Ghidra NativeAOT extension" install_nativeaot
run_step "Hopper" install_hopper
write_env

if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
  cat "$ENV_FILE" >> "$CLAUDE_ENV_FILE"
fi

echo "REA ${REA_VERSION} engines are configured in ${ENV_FILE} for the REA MCP server."
if [ "${#skipped[@]}" -gt 0 ]; then
  echo "Skipped: ${skipped[*]} (www.hopperapp.com is blocked by this environment's network policy)."
fi
if [ "${#failed[@]}" -gt 0 ]; then
  echo "Failed: ${failed[*]}; see ${LOG}."
fi
