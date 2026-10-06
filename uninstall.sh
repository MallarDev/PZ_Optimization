#!/usr/bin/env bash
# PZ Optimization uninstaller (Linux, macOS), a release asset beside install.sh:
#   curl -fsSL https://github.com/xD3I/PZ_Optimization/releases/latest/download/uninstall.sh | bash
# Runs the latest install.sh with --uninstall (extra arguments pass through, e.g. --dir <game folder>). The game folder
# also has uninstall-pz-optimization.bash.
curl -fsSL https://github.com/xD3I/PZ_Optimization/releases/latest/download/install.sh | bash -s -- --uninstall "$@"
