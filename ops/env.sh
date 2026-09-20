# Sourceable environment for this project: `source ops/env.sh` (bash or zsh, from any directory).
# Finds a JDK 21 and Maven wherever this machine keeps them, then exports JAVA_HOME, PATH, PY (the research
# venv interpreter) and CP (the runtime classpath). ops/make.sh, ops/bench.sh and ops/bench_jfr.sh source it,
# so the toolchain paths live in exactly one place instead of being hard-coded in every script.
# Windows (Git bash) is still supported: the classpath separator and the venv layout are chosen per platform.
# Directory searches go through `find` with quoted patterns, because an unmatched glob is a fatal error in zsh.

LOB_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]:-${(%):-%x}}")/.." && pwd)"; export LOB_ROOT

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) LOB_WIN=1; CPSEP=';' ;;
  *)                    LOB_WIN=0; CPSEP=':' ;;
esac
export CPSEP

# --- JDK 21 -----------------------------------------------------------------------------------------------
# An existing JAVA_HOME wins if it is a 21; otherwise the first 21 found under ~/tools, /usr/lib/jvm or
# Program Files. JDK 17 cannot compile this project (records + pattern switch), so the version is checked.
lob_is_jdk21() { [ -x "$1/bin/java" ] && "$1/bin/java" -version 2>&1 | head -1 | grep -q '"21\.'; }
if ! { [ -n "${JAVA_HOME:-}" ] && lob_is_jdk21 "$JAVA_HOME"; }; then
  JAVA_HOME=""
  while IFS= read -r cand; do
    [ -n "$cand" ] || continue
    if lob_is_jdk21 "$cand"; then JAVA_HOME="$cand"; break; fi
  done <<CANDIDATES
$(find "$HOME/tools" /usr/lib/jvm "/c/Program Files/Eclipse Adoptium" -maxdepth 1 -name 'jdk-21*' 2>/dev/null | sort)
CANDIDATES
fi
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/java" ]; then
  echo "ops/env.sh: no JDK 21 found. Install one (see docs/setup.md §1) or export JAVA_HOME first." >&2
else
  export JAVA_HOME
  export PATH="$JAVA_HOME/bin:$PATH"
fi

# --- Maven ------------------------------------------------------------------------------------------------
if ! command -v mvn >/dev/null 2>&1; then
  mvn_bin="$(find "$HOME/tools" -maxdepth 2 -type d -name bin -path '*apache-maven*' 2>/dev/null | sort | tail -1)"
  [ -x "${mvn_bin:-}/mvn" ] && export PATH="$mvn_bin:$PATH"
  unset mvn_bin
fi

# --- Python research layer --------------------------------------------------------------------------------
if [ "$LOB_WIN" = 1 ]; then PY="$LOB_ROOT/research/.venv/Scripts/python.exe"; else PY="$LOB_ROOT/research/.venv/bin/python"; fi
export PY

# --- Runtime classpath ------------------------------------------------------------------------------------
# cp.txt holds the external dependencies only (git-ignored, regenerated here if absent); the two target/classes
# directories are prepended, so a plain `mvn package` is enough to run anything.
if [ ! -f "$LOB_ROOT/cp.txt" ] && command -v mvn >/dev/null 2>&1; then
  ( cd "$LOB_ROOT" && mvn -q -B install -DskipTests >/dev/null 2>&1 \
    && mvn -q -B dependency:build-classpath -pl replay -Dmdep.outputFile="$LOB_ROOT/cp.txt" >/dev/null 2>&1 )
fi
if [ -f "$LOB_ROOT/cp.txt" ]; then
  CP="$LOB_ROOT/replay/target/classes${CPSEP}$LOB_ROOT/core/target/classes${CPSEP}$(tr -d '\r\n' < "$LOB_ROOT/cp.txt")"
  export CP
fi

[ -n "${LOB_QUIET:-}" ] || {
  echo "java   $([ -n "${JAVA_HOME:-}" ] && "$JAVA_HOME/bin/java" -version 2>&1 | head -1 || echo 'not found')"
  echo "mvn    $(command -v mvn >/dev/null 2>&1 && mvn -v 2>/dev/null | head -1 || echo 'not found')"
  echo "PY     $([ -x "$PY" ] && "$PY" -V 2>&1 || echo 'research/.venv missing — see docs/setup.md §3')"
  echo "CP     $([ -n "${CP:-}" ] && echo "set (${CPSEP}-separated, $(echo "$CP" | tr "$CPSEP" '\n' | wc -l) entries)" || echo 'unset — run: mvn -B install -DskipTests')"
}
