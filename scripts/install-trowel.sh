#!/usr/bin/env bash
# Downloads the latest (or a pinned) trowel release, verifies its checksum,
# and installs it to ~/.local/bin.
set -euo pipefail

REPO="orchard-cde/orchard"
VERSION="${1:-latest}"
INSTALL_DIR="${TROWEL_INSTALL_DIR:-$HOME/.local/bin}"

os="$(uname -s)"
arch="$(uname -m)"

case "$os" in
  Linux) platform_os="linux" ;;
  Darwin) platform_os="macos" ;;
  *) echo "error: unsupported OS: $os" >&2; exit 1 ;;
esac

case "$arch" in
  x86_64|amd64) platform_arch="amd64" ;;
  aarch64|arm64) platform_arch="arm64" ;;
  *) echo "error: unsupported architecture: $arch" >&2; exit 1 ;;
esac

asset="trowel-${platform_os}-${platform_arch}.tar.gz"

if [ "$VERSION" = "latest" ]; then
  base_url="https://github.com/${REPO}/releases/latest/download"
else
  base_url="https://github.com/${REPO}/releases/download/${VERSION}"
fi

tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

echo "Downloading ${asset} (${VERSION})..."
curl -fsSL "${base_url}/${asset}" -o "${tmp_dir}/${asset}"
curl -fsSL "${base_url}/checksums-sha256.txt" -o "${tmp_dir}/checksums-sha256.txt"

echo "Verifying checksum..."
if command -v sha256sum &>/dev/null; then
  ( cd "$tmp_dir" && sha256sum --check checksums-sha256.txt --ignore-missing )
else
  ( cd "$tmp_dir" && shasum -a 256 --check checksums-sha256.txt --ignore-missing )
fi

tar -xzf "${tmp_dir}/${asset}" -C "$tmp_dir"

mkdir -p "$INSTALL_DIR"
install -m 755 "${tmp_dir}/trowel" "${INSTALL_DIR}/trowel"

if [ "$platform_os" = "macos" ]; then
  xattr -d com.apple.quarantine "${INSTALL_DIR}/trowel" 2>/dev/null || true
fi

echo "Installed trowel to ${INSTALL_DIR}/trowel"
"${INSTALL_DIR}/trowel" --version

case ":${PATH}:" in
  *":${INSTALL_DIR}:"*)
    ;;
  *)
    echo
    echo "${INSTALL_DIR} is not on your PATH. Add it by appending this to your shell rc file (~/.bashrc, ~/.zshrc, etc.):"
    echo
    echo "  export PATH=\"${INSTALL_DIR}:\$PATH\""
    echo
    ;;
esac
