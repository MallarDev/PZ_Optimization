#!/usr/bin/env bash
# PZ Optimization: removes it from this game folder:  bash "<game folder>/uninstall-pz-optimization.bash"
# The game must be closed (the script waits for it). Runs the installer the release left in pzopt/uninstall/ with
# --uninstall, else the latest one from GitHub.
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [[ -f "$here/pzopt/uninstall/install.bash" ]]; then
  tmp=$(mktemp "${TMPDIR:-/tmp}/pzopt-uninstall.XXXXXX")
  cp "$here/pzopt/uninstall/install.bash" "$tmp"
  bash "$tmp" --uninstall --dir "$here"
  rc=$?
  rm -f "$tmp"
  exit $rc
fi
curl -fsSL https://github.com/xD3I/PZ_Optimization/releases/latest/download/install.sh | bash -s -- --uninstall --dir "$here"
