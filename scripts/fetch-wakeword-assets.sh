#!/usr/bin/env bash
# Fetches the sherpa-onnx runtime and the wake-phrase models used by :pa-wakeword.
#
#   ./scripts/fetch-wakeword-assets.sh
#
# Produces (all gitignored):
#   Android/src/pa-wakeword/localrepo/        Maven repo with the sherpa-onnx 2.0.1 static-link AAR,
#                                             trimmed to arm64-v8a (see settings.gradle.kts); used for
#                                             the CAM++ speaker embedding (voice match)
#   Android/src/pa-wakeword/src/main/assets/wakeword/
#                                             openWakeWord feature models melspectrogram.tflite and
#                                             embedding_model.tflite (Apache-2.0), CAM++ speaker model
#   Android/src/pa-wakeword/testmodels/       the ONNX copies of the two feature models, for the JVM
#                                             unit tests only (never packaged)
#
# Files of the old keyword-spotter design (KWS model, en.phone, keywords.txt) are deleted from the
# assets directory so they are not packaged any more.
#
# Every download is pinned by size and sha256. The script is idempotent: when everything is in
# place it only verifies checksums and prints "up to date".
#
# Optional: PA_WAKEWORD_SEED_DIR=<dir> makes the script look for already-downloaded copies of the
# downloads (any file name, up to 3 levels deep, matched by size + sha256) before using the network.
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

# openWakeWord v0.5.1 release: only the two shared feature models (Apache-2.0; NOT the pretrained
# wake-word models, which are CC BY-NC-SA). name size sha256 destination
OWW_URL="https://github.com/dscripka/openWakeWord/releases/download/v0.5.1"
TESTMODELS="$MODULE/testmodels"
OWW_FILES=(
  "melspectrogram.tflite 1092516 96fa0adccb6e8cf95cb14465409a1a2898ee4a96a85bb9ed3c7eb0e68bf163e8 $ASSETS"
  "embedding_model.tflite 1330312 c0aea21eb84a4ce90a08c870da41b7a7173b45269e6a3207c71d67c40f3a59d8 $ASSETS"
  "melspectrogram.onnx 1087958 ba2b0e0f8b7b875369a2c89cb13360ff53bac436f2895cced9f479fa65eb176f $TESTMODELS"
  "embedding_model.onnx 1326578 70d164290c1d095d1d4ee149bc5e00543250a7316b59f31d056cff7bd3075c1f $TESTMODELS"
)

CAMPP_NAME="3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx"
CAMPP_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/${CAMPP_NAME}"
CAMPP_SIZE=29596978
CAMPP_SHA=357a834f702b80161e5b981182c038e18553c1f2ca752ed6cec2052365d4129b

# Left over from the keyword-spotter design; removed if present.
STALE_FILES=(
  encoder-epoch-13-avg-2-chunk-16-left-64.int8.onnx
  decoder-epoch-13-avg-2-chunk-16-left-64.onnx
  joiner-epoch-13-avg-2-chunk-16-left-64.int8.onnx
  tokens.txt
  en.phone
  keywords.txt
)

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

oww_ok() {
  local entry name size sha dir
  for entry in "${OWW_FILES[@]}"; do
    read -r name size sha dir <<<"$entry"
    has_sha "$dir/$name" "$sha" || return 1
  done
}

campp_ok() { has_sha "$ASSETS/$CAMPP_NAME" "$CAMPP_SHA"; }

stale_ok() {
  local name
  for name in "${STALE_FILES[@]}"; do [[ ! -e "$ASSETS/$name" ]] || return 1; done
}

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

install_oww() {
  local t entry name size sha dir; ensure_tmp; t="$TMP"
  for entry in "${OWW_FILES[@]}"; do
    read -r name size sha dir <<<"$entry"
    has_sha "$dir/$name" "$sha" && continue
    fetch "openWakeWord $name" "$OWW_URL/$name" "$size" "$sha" "$t/$name"
    mkdir -p "$dir"
    mv -f "$t/$name" "$dir/$name"
    echo "  openWakeWord $name: installed into $dir"
  done
}

install_campp() {
  local t; ensure_tmp; t="$TMP"
  fetch "CAM++ speaker model" "$CAMPP_URL" "$CAMPP_SIZE" "$CAMPP_SHA" "$t/$CAMPP_NAME"
  mkdir -p "$ASSETS"
  mv -f "$t/$CAMPP_NAME" "$ASSETS/$CAMPP_NAME"
  echo "  CAM++ speaker model: installed into $ASSETS"
}

remove_stale() {
  local name
  for name in "${STALE_FILES[@]}"; do
    if [[ -e "$ASSETS/$name" ]]; then rm -f "$ASSETS/$name"; echo "  removed old $name"; fi
  done
}

# --- Main -----------------------------------------------------------------------------------------
changed=0
aar_ok || { install_aar; changed=1; }
oww_ok || { install_oww; changed=1; }
campp_ok || { install_campp; changed=1; }
stale_ok || { remove_stale; changed=1; }

# Re-verify everything after installing.
aar_ok && oww_ok && campp_ok && stale_ok || die "verification failed after install"

if [[ "$changed" == 0 ]]; then
  echo "wakeword assets: up to date ($MODULE)"
else
  echo "wakeword assets: installed ($MODULE)"
fi
