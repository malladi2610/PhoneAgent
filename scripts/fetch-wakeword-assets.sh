#!/usr/bin/env bash
# Fetches the sherpa-onnx runtime and the wake-phrase models used by :pa-wakeword.
#
#   ./scripts/fetch-wakeword-assets.sh
#
# Produces (all gitignored):
#   Android/src/pa-wakeword/localrepo/        Maven repo with the sherpa-onnx 2.0.1 static-link AAR,
#                                             trimmed to arm64-v8a (see settings.gradle.kts)
#   Android/src/pa-wakeword/src/main/assets/wakeword/
#                                             KWS encoder/decoder/joiner, tokens.txt, en.phone,
#                                             keywords.txt (generated), CAM++ speaker model
#
# Every download is pinned by size and sha256. The script is idempotent: when everything is in
# place it only verifies checksums and prints "up to date".
#
# Optional: PA_WAKEWORD_SEED_DIR=<dir> makes the script look for already-downloaded copies of the
# three downloads (any file name, up to 3 levels deep, matched by size + sha256) before using the
# network.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MODULE="$ROOT/Android/src/pa-wakeword"
ASSETS="$MODULE/src/main/assets/wakeword"
REPO="$MODULE/localrepo"
SEED_DIR="${PA_WAKEWORD_SEED_DIR:-}"

SHERPA_VERSION="2.0.1"
GROUP_PATH="com/k2fsa/sherpa/onnx"
ARTIFACT="sherpa-onnx-static-arm64"
AAR_DIR="$REPO/$GROUP_PATH/$ARTIFACT/$SHERPA_VERSION"
AAR_OUT="$AAR_DIR/$ARTIFACT-$SHERPA_VERSION.aar"
POM_OUT="$AAR_DIR/$ARTIFACT-$SHERPA_VERSION.pom"
AAR_STAMP="$AAR_DIR/.source-sha256"

# --- Pinned downloads -----------------------------------------------------------------------------
AAR_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v${SHERPA_VERSION}/sherpa-onnx-static-link-onnxruntime-${SHERPA_VERSION}.aar"
AAR_SIZE=38577050
AAR_SHA=07e042d97f3a6bd5b7fe74d4eef0513dc01a714b16c15a903250095aba43a575
JNI_SHA=491b1b6e287fdfe5e0e1ffa918f5e5556299cb14bea7b7b8a0c19468f4156193   # jni/arm64-v8a/libsherpa-onnx-jni.so
CLASSES_SHA=0ee4991c56c19ef5a8a573b01891193d7298ffe7ebcc68f42b944361d8869d93 # classes.jar

KWS_NAME="sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20"
KWS_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/${KWS_NAME}.tar.bz2"
KWS_SIZE=32885699
KWS_SHA=68447f4fbc67e70eee3a93961f36e81e98f47aef73ce7e7ca00885c6cd3616a6

CAMPP_NAME="3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx"
CAMPP_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/${CAMPP_NAME}"
CAMPP_SIZE=29596978
CAMPP_SHA=357a834f702b80161e5b981182c038e18553c1f2ca752ed6cec2052365d4129b

# Files taken from the KWS archive: name sha256
KWS_FILES=(
  "encoder-epoch-13-avg-2-chunk-16-left-64.int8.onnx 408bbd740838c42d5bf6d1c5b80b3c88b616c7860b92d980328b5b068c76ae48"
  "decoder-epoch-13-avg-2-chunk-16-left-64.onnx 63a22dd60f40fff082ac3e09afa507f6787da36df76ded2fbe145fa233e22c21"
  "joiner-epoch-13-avg-2-chunk-16-left-64.int8.onnx 190d4067b4cc20b72a42a1916e69d92052000fb7051a427ebb1bc72a69207dc1"
  "tokens.txt 2d3f32311f9b692b964da3c90e830258d3e78e013cb0c992dbfb15cd5a1a71b0"
  "en.phone f7000ec3a90544c0c7c16090d8951779c2b322e14dad5006290f498567d439ea"
)

# The KeywordSpotter constructor needs a valid keywords file; the user's phrase is passed at runtime
# through createStream(keywords).
KEYWORDS_TXT="HH EY1 JH AA1 R V AH0 S :1.5 #0.25 @HEY_JARVIS"

# --- Helpers --------------------------------------------------------------------------------------
sha256() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

has_sha() { [[ -f "$1" ]] && [[ "$(sha256 "$1")" == "$2" ]]; }

die() { echo "fetch-wakeword-assets: $*" >&2; exit 1; }

TMP=""
cleanup() { if [[ -n "$TMP" ]]; then rm -rf "$TMP"; fi; }
trap cleanup EXIT
# Sets the global TMP (must not run in a subshell, or cleanup would miss it).
ensure_tmp() {
  if [[ -z "$TMP" ]]; then TMP="$(mktemp -d "${TMPDIR:-/tmp}/pa-wakeword.XXXXXX")"; fi
}

# fetch <label> <url> <size> <sha256> <dest>
fetch() {
  local label="$1" url="$2" size="$3" sha="$4" dest="$5" f
  if [[ -n "$SEED_DIR" && -d "$SEED_DIR" ]]; then
    while IFS= read -r -d '' f; do
      if has_sha "$f" "$sha"; then
        cp "$f" "$dest"
        echo "  $label: copied from $f"
        return 0
      fi
    done < <(find "$SEED_DIR" -maxdepth 3 -type f -size "${size}c" -print0)
  fi
  echo "  $label: downloading $url"
  curl -fL --retry 3 --silent --show-error -o "$dest" "$url"
  has_sha "$dest" "$sha" || die "$label: sha256 mismatch for $url (expected $sha, got $(sha256 "$dest"))"
}

# --- Checks ---------------------------------------------------------------------------------------
aar_ok() {
  [[ -f "$AAR_OUT" && -f "$POM_OUT" && -f "$AAR_STAMP" ]] || return 1
  [[ "$(cat "$AAR_STAMP")" == "$AAR_SHA $(sha256 "$AAR_OUT")" ]]
}

kws_ok() {
  local entry name sha
  for entry in "${KWS_FILES[@]}"; do
    name="${entry%% *}"; sha="${entry##* }"
    has_sha "$ASSETS/$name" "$sha" || return 1
  done
}

campp_ok() { has_sha "$ASSETS/$CAMPP_NAME" "$CAMPP_SHA"; }

keywords_ok() { [[ -f "$ASSETS/keywords.txt" && "$(cat "$ASSETS/keywords.txt")" == "$KEYWORDS_TXT" ]]; }

# --- Install steps --------------------------------------------------------------------------------
install_aar() {
  local t; ensure_tmp; t="$TMP"
  fetch "sherpa-onnx AAR" "$AAR_URL" "$AAR_SIZE" "$AAR_SHA" "$t/sherpa.aar"
  rm -rf "$t/aar" && mkdir -p "$t/aar"
  (cd "$t/aar" && unzip -q "$t/sherpa.aar")
  # Keep arm64-v8a only (the app ships to arm64 phones; the other ABIs add ~75 MB).
  find "$t/aar/jni" -mindepth 1 -maxdepth 1 -type d ! -name arm64-v8a -exec rm -rf {} +
  has_sha "$t/aar/jni/arm64-v8a/libsherpa-onnx-jni.so" "$JNI_SHA" || die "unexpected libsherpa-onnx-jni.so"
  has_sha "$t/aar/classes.jar" "$CLASSES_SHA" || die "unexpected classes.jar"
  mkdir -p "$AAR_DIR"
  rm -f "$AAR_OUT"
  (cd "$t/aar" && zip -q -r -X "$AAR_OUT" .)
  cat >"$POM_OUT" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.k2fsa.sherpa.onnx</groupId>
  <artifactId>$ARTIFACT</artifactId>
  <version>$SHERPA_VERSION</version>
  <packaging>aar</packaging>
  <description>sherpa-onnx $SHERPA_VERSION static-link-onnxruntime AAR, arm64-v8a only (repackaged by scripts/fetch-wakeword-assets.sh)</description>
  <licenses><license><name>Apache-2.0</name></license></licenses>
</project>
EOF
  echo "$AAR_SHA $(sha256 "$AAR_OUT")" >"$AAR_STAMP"
  echo "  sherpa-onnx AAR: installed $AAR_OUT"
}

install_kws() {
  local t entry name sha; ensure_tmp; t="$TMP"
  fetch "KWS model" "$KWS_URL" "$KWS_SIZE" "$KWS_SHA" "$t/kws.tar.bz2"
  local members=()
  for entry in "${KWS_FILES[@]}"; do members+=("$KWS_NAME/${entry%% *}"); done
  tar -xjf "$t/kws.tar.bz2" -C "$t" "${members[@]}"
  mkdir -p "$ASSETS"
  for entry in "${KWS_FILES[@]}"; do
    name="${entry%% *}"; sha="${entry##* }"
    has_sha "$t/$KWS_NAME/$name" "$sha" || die "KWS model: unexpected $name"
    mv -f "$t/$KWS_NAME/$name" "$ASSETS/$name"
  done
  rm -f "$t/kws.tar.bz2"
  echo "  KWS model: installed into $ASSETS"
}

install_campp() {
  local t; ensure_tmp; t="$TMP"
  fetch "CAM++ speaker model" "$CAMPP_URL" "$CAMPP_SIZE" "$CAMPP_SHA" "$t/$CAMPP_NAME"
  mkdir -p "$ASSETS"
  mv -f "$t/$CAMPP_NAME" "$ASSETS/$CAMPP_NAME"
  echo "  CAM++ speaker model: installed into $ASSETS"
}

install_keywords() {
  mkdir -p "$ASSETS"
  printf '%s\n' "$KEYWORDS_TXT" >"$ASSETS/keywords.txt"
  echo "  keywords.txt: written"
}

# --- Main -----------------------------------------------------------------------------------------
changed=0
aar_ok || { install_aar; changed=1; }
kws_ok || { install_kws; changed=1; }
campp_ok || { install_campp; changed=1; }
keywords_ok || { install_keywords; changed=1; }

# Re-verify everything after installing.
aar_ok && kws_ok && campp_ok && keywords_ok || die "verification failed after install"

if [[ "$changed" == 0 ]]; then
  echo "wakeword assets: up to date ($MODULE)"
else
  echo "wakeword assets: installed ($MODULE)"
fi
