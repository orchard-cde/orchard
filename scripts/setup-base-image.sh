#!/bin/bash
set -e

# =============================================================================
# Orchard CDE - Base Image Setup Script
#
# Downloads and prepares an Ubuntu 22.04 cloud image for use as the base
# seedling (VM) image in the Orchard platform.
# =============================================================================

ARCH="$(uname -m)"
if [[ "$ARCH" == "arm64" || "$ARCH" == "aarch64" ]]; then
    UBUNTU_IMAGE_URL="https://cloud-images.ubuntu.com/jammy/current/jammy-server-cloudimg-arm64.img"
    DOWNLOAD_FILENAME="jammy-server-cloudimg-arm64.img"
else
    UBUNTU_IMAGE_URL="https://cloud-images.ubuntu.com/jammy/current/jammy-server-cloudimg-amd64.img"
    DOWNLOAD_FILENAME="jammy-server-cloudimg-amd64.img"
fi
IMAGE_FILENAME="ubuntu-22.04-base.qcow2"
RESIZE_TARGET="20G"
SSH_KEY_PATH="$HOME/.ssh/orchard_ed25519"

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

info()  { echo "[INFO]  $*"; }
warn()  { echo "[WARN]  $*"; }
error() { echo "[ERROR] $*" >&2; }
die()   { error "$*"; exit 1; }

usage() {
    cat <<EOF
Usage: $(basename "$0") [OPTIONS]

Prepare an Ubuntu 22.04 base image for Orchard seedlings.

Options:
  --image-path <dir>   Override the default image directory
  -h, --help           Show this help message

Default image directories:
  macOS:  /tmp/orchard/images
  Linux:  /var/lib/orchard/images
EOF
    exit 0
}

# ---------------------------------------------------------------------------
# Argument parsing
# ---------------------------------------------------------------------------

CUSTOM_IMAGE_PATH=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --image-path)
            [[ -z "${2:-}" ]] && die "--image-path requires a directory argument"
            CUSTOM_IMAGE_PATH="$2"
            shift 2
            ;;
        -h|--help)
            usage
            ;;
        *)
            die "Unknown option: $1. Use --help for usage."
            ;;
    esac
done

# ---------------------------------------------------------------------------
# 1. Detect OS
# ---------------------------------------------------------------------------

OS="$(uname -s)"
case "$OS" in
    Darwin)
        info "Detected macOS"
        DEFAULT_IMAGE_DIR="/tmp/orchard/images"
        ;;
    Linux)
        info "Detected Linux"
        DEFAULT_IMAGE_DIR="/var/lib/orchard/images"
        ;;
    *)
        die "Unsupported operating system: $OS"
        ;;
esac

IMAGE_DIR="${CUSTOM_IMAGE_PATH:-$DEFAULT_IMAGE_DIR}"
info "Image directory: $IMAGE_DIR"

# ---------------------------------------------------------------------------
# 2. Check required tools
# ---------------------------------------------------------------------------

check_command() {
    if ! command -v "$1" &>/dev/null; then
        return 1
    fi
    return 0
}

info "Checking required tools..."

if ! check_command qemu-img; then
    die "qemu-img is not installed. Install QEMU first (e.g. 'brew install qemu' on macOS, 'apt install qemu-utils' on Linux)."
fi
info "  qemu-img ... found"

DOWNLOADER=""
if check_command wget; then
    DOWNLOADER="wget"
    info "  wget     ... found"
elif check_command curl; then
    DOWNLOADER="curl"
    info "  curl     ... found"
else
    die "Neither wget nor curl is installed. Please install one of them."
fi

# ---------------------------------------------------------------------------
# 3. Create images directory
# ---------------------------------------------------------------------------

if [[ ! -d "$IMAGE_DIR" ]]; then
    info "Creating image directory: $IMAGE_DIR"
    mkdir -p "$IMAGE_DIR"
else
    info "Image directory already exists: $IMAGE_DIR"
fi

# ---------------------------------------------------------------------------
# 4. Download Ubuntu 22.04 cloud image (idempotent)
# ---------------------------------------------------------------------------

DOWNLOAD_PATH="$IMAGE_DIR/$DOWNLOAD_FILENAME"
FINAL_IMAGE_PATH="$IMAGE_DIR/$IMAGE_FILENAME"

if [[ -f "$FINAL_IMAGE_PATH" ]]; then
    info "Base image already exists at $FINAL_IMAGE_PATH -- skipping download and conversion."
else
    if [[ -f "$DOWNLOAD_PATH" ]]; then
        info "Downloaded image already present at $DOWNLOAD_PATH -- skipping download."
    else
        info "Downloading Ubuntu 22.04 cloud image..."
        info "  URL: $UBUNTU_IMAGE_URL"
        info "  Destination: $DOWNLOAD_PATH"

        if [[ "$DOWNLOADER" == "wget" ]]; then
            wget -q --show-progress -O "$DOWNLOAD_PATH" "$UBUNTU_IMAGE_URL"
        else
            curl -L --progress-bar -o "$DOWNLOAD_PATH" "$UBUNTU_IMAGE_URL"
        fi

        info "Download complete."
    fi

    # -----------------------------------------------------------------------
    # 5. Convert to qcow2 format
    # -----------------------------------------------------------------------

    info "Converting image to qcow2 format..."
    qemu-img convert -f qcow2 -O qcow2 "$DOWNLOAD_PATH" "$FINAL_IMAGE_PATH"
    info "Conversion complete: $FINAL_IMAGE_PATH"

    # -----------------------------------------------------------------------
    # 6. Resize to 20G
    # -----------------------------------------------------------------------

    info "Resizing image to $RESIZE_TARGET..."
    qemu-img resize "$FINAL_IMAGE_PATH" "$RESIZE_TARGET"
    info "Resize complete."

    # Clean up the raw download to save space
    if [[ "$DOWNLOAD_PATH" != "$FINAL_IMAGE_PATH" ]]; then
        info "Removing original download to save space..."
        rm -f "$DOWNLOAD_PATH"
    fi
fi

# ---------------------------------------------------------------------------
# 7. Generate SSH key pair for Orchard (if it doesn't already exist)
# ---------------------------------------------------------------------------

if [[ -f "$SSH_KEY_PATH" ]]; then
    info "Orchard SSH key already exists at $SSH_KEY_PATH -- skipping generation."
else
    info "Generating Orchard SSH key pair at $SSH_KEY_PATH..."
    ssh-keygen -t ed25519 -f "$SSH_KEY_PATH" -N "" -C "orchard-cde"
    info "SSH key pair generated."
fi

# ---------------------------------------------------------------------------
# 8. Print instructions for ORCHARD_SSH_PUBLIC_KEY env var
# ---------------------------------------------------------------------------

echo ""
echo "============================================================================="
echo " Orchard Base Image Setup Complete"
echo "============================================================================="
echo ""
echo " Base image:  $FINAL_IMAGE_PATH"
echo " SSH key:     $SSH_KEY_PATH"
echo ""
echo " To configure the Orchard SSH public key, add the following to your"
echo " shell profile (~/.bashrc, ~/.zshrc, etc.):"
echo ""
echo "   export ORCHARD_SSH_PUBLIC_KEY=\"\$(cat ${SSH_KEY_PATH}.pub)\""
echo ""
echo " Or set it directly in your current session:"
echo ""
echo "   export ORCHARD_SSH_PUBLIC_KEY=\"\$(cat ${SSH_KEY_PATH}.pub)\""
echo ""
echo " You can verify the key is set with:"
echo ""
echo "   echo \"\$ORCHARD_SSH_PUBLIC_KEY\""
echo ""

# ---------------------------------------------------------------------------
# 9. Check for genisoimage/mkisofs (needed for cloud-init ISO creation)
# ---------------------------------------------------------------------------

ISOMAKER=""
if check_command genisoimage; then
    ISOMAKER="genisoimage"
elif check_command mkisofs; then
    ISOMAKER="mkisofs"
fi

if [[ -n "$ISOMAKER" ]]; then
    info "ISO creation tool found: $ISOMAKER"
else
    echo "============================================================================="
    echo " WARNING: Neither genisoimage nor mkisofs was found."
    echo ""
    echo " One of these tools is required to generate cloud-init ISO images for"
    echo " seedling (VM) provisioning. Without it, Orchard cannot inject SSH keys"
    echo " and configuration into new VMs."
    echo ""
    echo " Install with:"
    echo "   macOS:  brew install cdrtools        (provides mkisofs)"
    echo "   Linux:  apt install genisoimage"
    echo "           or: apt install mkisofs"
    echo "============================================================================="
fi

echo ""
info "Setup finished successfully."
