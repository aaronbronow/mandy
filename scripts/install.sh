#!/usr/bin/env bash

# 🌿 Mandy (Manual Discovery) Installer
# This script downloads and installs the latest Mandy binaries.

set -e

# --- Configuration ---
OWNER="aaronbronow"
REPO="mandy"
INSTALL_PREFIX="${PREFIX:-/usr/local}"
BINARY_DIR="$INSTALL_PREFIX/bin"
VERSION="${MANDY_VERSION:-latest}"

# Colors
GREEN='\033[0;32m'
BLUE='\033[0;34m'
RED='\033[0;31m'
NC='\033[0m' # No Color

echo -e "${BLUE}🌿 Installing Mandy (Manual Discovery)...${NC}"

# --- Dependency Check ---
echo -n "Checking for YAMLScript (ys)... "
if command -v ys >/dev/null 2>&1; then
    echo -e "${GREEN}Found${NC}"
else
    echo -e "${RED}Not Found${NC}"
    echo -e "  Note: Mandy works best with YAMLScript. Install it via: curl -sSL https://yamlscript.org/install | bash"
fi

# --- Platform Detection ---
OS="$(uname -s | tr '[:upper:]' '[:lower:]')"
ARCH="$(uname -m)"

case "$ARCH" in
    x86_64) ARCH="x64" ;;
    arm64|aarch64) ARCH="arm64" ;;
    *) echo -e "${RED}Unsupported architecture: $ARCH${NC}"; exit 1 ;;
esac

TARGET="${OS}-${ARCH}"
echo -e "Detected platform: ${BLUE}${TARGET}${NC}"

# --- Download & Install ---
TEMP_DIR=$(mktemp -d)
TARBALL="mandy-v0.1.0-${TARGET}.tar.gz" # Note: Beta 0.1 hardcoded for now, should be dynamic for production

if [ "$VERSION" = "latest" ]; then
    URL="https://github.com/${OWNER}/${REPO}/releases/latest/download/${TARBALL}"
else
    URL="https://github.com/${OWNER}/${REPO}/releases/download/${VERSION}/${TARBALL}"
fi

echo -e "Downloading from ${BLUE}${URL}${NC}..."

if ! curl -L --fail -o "${TEMP_DIR}/${TARBALL}" "$URL"; then
    echo -e "${RED}Failed to download tarball. The version or platform might not be available yet.${NC}"
    exit 1
fi

echo "Extracting..."
tar -xzf "${TEMP_DIR}/${TARBALL}" -C "$TEMP_DIR"

echo -e "Installing to ${GREEN}${BINARY_DIR}${NC}..."
if [ ! -w "$BINARY_DIR" ]; then
    echo "Requesting sudo permissions to install to $BINARY_DIR"
    sudo mkdir -p "$BINARY_DIR"
    sudo mv "$TEMP_DIR/bin/mandy" "$TEMP_DIR/bin/mandy-ui" "$BINARY_DIR/"
else
    mkdir -p "$BINARY_DIR"
    mv "$TEMP_DIR/bin/mandy" "$TEMP_DIR/bin/mandy-ui" "$BINARY_DIR/"
fi

# --- Cleanup ---
rm -rf "$TEMP_DIR"

echo -e "\n${GREEN}Successfully installed Mandy Beta 0.1!${NC}"
echo -e "Run ${BLUE}mandy --help${NC} to get started."
