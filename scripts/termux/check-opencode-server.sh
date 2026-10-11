#!/data/data/com.termux/files/usr/bin/bash
set -eu
BASE="http://127.0.0.1:4096"
printf '%s\n' '== OpenCode version =='
opencode --version
printf '%s\n' '== API health =='
curl -fsS "$BASE/global/health"
printf '\n%s\n' '== Current working directory =='
curl -fsS "$BASE/path"
printf '\n%s\n' '== Providers =='
curl -fsS "$BASE/config/providers"
printf '\n%s\n' '== Sessions =='
curl -fsS "$BASE/session"
printf '\n'
