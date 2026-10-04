#!/usr/bin/env bash
set -euo pipefail
engine=$(realpath "$1")
tests=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
fixture=$(mktemp -d)
trap 'rm -rf -- "$fixture"' EXIT
mkdir -p "$fixture/root/tmp" "$fixture/store"
bindings=()
for directory in /usr /lib /lib64 /etc /proc; do
  [[ ! -e "$directory" ]] || bindings+=(-b "$directory:$directory")
done
# Two different tracer processes exercise the persistent external store.
for phase in create verify; do
  PROOT_L2S_DIR="$fixture/store" "$engine" -l -0 -r "$fixture/root" \
    "${bindings[@]}" -b "$tests:/verification" -w / \
    /usr/bin/python3 /verification/snapshot-files.py "$phase" /tmp/snapshot-files
done
