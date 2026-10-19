#!/usr/bin/env bash
#
# Build the Lincoln release and publish it to the fork so the head unit can update itself
# from Settings -> About -> Check for updates.
#
# The in-app updater only accepts a release that carries BOTH an .apk asset and a
# SHA256SUMS.txt, and it compares the tag against the installed versionName by parsed
# x.y.z numbers. The tag therefore has to look like v0.2.21, and a new version must
# increment versionName (versionCode too, by Android convention) before publishing.
#
# Usage:
#   scripts/release-lincoln.sh                 # uses the version already in build.gradle.kts
#   scripts/release-lincoln.sh --bump          # increments the last version number first
#   scripts/release-lincoln.sh --bump --dry-run  # build and verify, publish nothing
#
# Environment (defaults match this machine; override to move the build elsewhere):
#   LINCOLN_KEYSTORE          release keystore path
#   LINCOLN_KEYSTORE_PASSWORD keystore password
#   LINCOLN_KEY_ALIAS         signing key alias
#   LINCOLN_KEY_PASSWORD      signing key password
#   DIPLAY_AUTH_ASSETS_DIR    runtime authentication assets directory
#   CORSAIRPLAY_MIRROR_HOST   SSH host for the fallback download mirror (oracle-sg1)

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

WORKSPACE="$(cd "$REPO_ROOT/.." && pwd)"
GRADLE_FILE="mobile/build.gradle.kts"
APK_SOURCE="mobile/build/outputs/apk/release/mobile-release.apk"
FORK_REMOTE="${LINCOLN_FORK_REMOTE:-fork}"

: "${LINCOLN_KEYSTORE:=$WORKSPACE/lincoln-release.jks}"
: "${DIPLAY_AUTH_ASSETS_DIR:=$WORKSPACE/runtime-assets}"
: "${LINCOLN_KEYSTORE_PASSWORD:=lincolnpay}"
: "${LINCOLN_KEY_ALIAS:=lincolnpay}"
: "${LINCOLN_KEY_PASSWORD:=$LINCOLN_KEYSTORE_PASSWORD}"
: "${CORSAIRPLAY_MIRROR_HOST:=oracle-sg1}"

BUMP=0
DRY_RUN=0
for arg in "$@"; do
  case "$arg" in
    --bump) BUMP=1 ;;
    --dry-run) DRY_RUN=1 ;;
    -h|--help) sed -n '2,30p' "${BASH_SOURCE[0]}" | sed 's/^# \?//'; exit 0 ;;
    *) echo "Unknown option: $arg" >&2; exit 2 ;;
  esac
done

die() { echo "error: $*" >&2; exit 1; }
step() { printf '\n== %s ==\n' "$*"; }

# --- 0. Preconditions -------------------------------------------------------------------

step "Checking prerequisites"
command -v git >/dev/null || die "git is not installed"
command -v gh  >/dev/null || die "the GitHub CLI (gh) is not installed"
gh auth status >/dev/null 2>&1 || die "gh is not logged in; run: gh auth login"

[ -f "$LINCOLN_KEYSTORE" ] || die "release keystore not found: $LINCOLN_KEYSTORE"
[ -f "$DIPLAY_AUTH_ASSETS_DIR/offline-mfi/identity.pk8" ] || die "missing $DIPLAY_AUTH_ASSETS_DIR/offline-mfi/identity.pk8"
[ -f "$DIPLAY_AUTH_ASSETS_DIR/offline-mfi/certificate.p7b" ] || die "missing $DIPLAY_AUTH_ASSETS_DIR/offline-mfi/certificate.p7b"

# A release must be cut from a clean tree, or the published APK would not match any commit.
# Build output is ignored, so only real source changes count.
if [ "$DRY_RUN" -eq 0 ]; then
  dirty="$(git status --porcelain | grep -v -E '(^|/)build/' || true)"
  if [ -n "$dirty" ]; then
    echo "$dirty" | head -20 >&2
    die "the working tree has uncommitted changes; commit them before releasing"
  fi
fi

# The fork owner/repo drives the release call, so it is read from the remote rather than hardcoded.
FORK_SLUG="$(git remote get-url "$FORK_REMOTE" 2>/dev/null \
  | sed -E 's#^git@[^:]+:##; s#^https?://[^/]+/##; s#\.git$##')"
case "$FORK_SLUG" in
  */*) : ;;
  *) die "could not read the fork repository from remote '$FORK_REMOTE'; set LINCOLN_FORK_REMOTE" ;;
esac
echo "fork: $FORK_SLUG (remote $FORK_REMOTE)"

# --- 1. Version ------------------------------------------------------------------------

current_version="$(sed -n 's/^ *versionName = "\(.*\)"/\1/p' "$GRADLE_FILE" | head -1)"
[ -n "$current_version" ] || die "could not read versionName from $GRADLE_FILE"

next_version="$current_version"
if [ "$BUMP" -eq 1 ]; then
  # Increment the last numeric component: 0.2.20 -> 0.2.21
  next_version="$(printf '%s' "$current_version" | awk -F. '{
      if (NF < 2) { print $0 ".1"; next }
      last = $NF + 1
      $NF = ""
      printf "%s%s\n", $0, last
    }' OFS=.)"
  [ "$next_version" != "$current_version" ] || die "could not bump version $current_version"

  current_code="$(sed -n 's/^ *versionCode = \([0-9]*\)/\1/p' "$GRADLE_FILE" | head -1)"
  [ -n "$current_code" ] || die "could not read versionCode from $GRADLE_FILE"
  next_code="$((current_code + 1))"

  echo "version: $current_version -> $next_version (versionCode $current_code -> $next_code)"
  sed -i '' \
    -e "s/^\( *\)versionCode = $current_code/\1versionCode = $next_code/" \
    -e "s/^\( *\)versionName = \"$current_version\"/\1versionName = \"$next_version\"/" \
    "$GRADLE_FILE"
else
  echo "version: $current_version (unchanged; pass --bump to increment)"
fi

TAG="v$next_version"
APK_NAME="CorsairPlay-$next_version.apk"

if [ "$DRY_RUN" -eq 0 ] && git rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
  die "tag $TAG already exists locally; bump the version or delete the tag"
fi

# --- 2. Tests and build ----------------------------------------------------------------

step "Running unit tests"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
if [ -z "${JAVA_HOME:-}" ] && [ -d /opt/homebrew/opt/openjdk@25 ]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@25
fi
export PATH="$ANDROID_HOME/platform-tools:$PATH"
export DIPLAY_AUTH_ASSETS_DIR
export ANDROID_KEYSTORE_PATH="$LINCOLN_KEYSTORE"
export ANDROID_KEYSTORE_PASSWORD="$LINCOLN_KEYSTORE_PASSWORD"
export ANDROID_KEY_ALIAS="$LINCOLN_KEY_ALIAS"
export ANDROID_KEY_PASSWORD="$LINCOLN_KEY_PASSWORD"

./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest --console=plain

step "Building the signed release APK"
./gradlew :mobile:lintRelease :mobile:assembleRelease --console=plain

[ -f "$APK_SOURCE" ] || die "release APK was not produced at $APK_SOURCE"

# --- 3. Verify the artifact ------------------------------------------------------------

step "Verifying the APK"
BUILD_TOOLS="${ANDROID_HOME}/build-tools"
if [ -d "$BUILD_TOOLS" ]; then
  AAPT2="$(find "$BUILD_TOOLS" -maxdepth 2 -name aapt2 2>/dev/null | sort -V | tail -1)"
  if [ -n "$AAPT2" ]; then
    badging="$("$AAPT2" dump badging "$APK_SOURCE" 2>/dev/null || true)"
    echo "$badging" | grep -E "^package:" || true
    echo "$badging" | grep -E "^application-label:" || true
    echo "$badging" | grep -E "^minSdkVersion" || true
    echo "$badging" | grep "^package:" | grep -q "versionName='$next_version'" \
      || die "APK versionName does not match $next_version"
    echo "$badging" | grep "^package:" | grep -q "name='com.corsairplay'" \
      || die "APK package name is not com.corsairplay"
  fi
  APKSIGNER="$(find "$BUILD_TOOLS" -maxdepth 2 -name apksigner 2>/dev/null | sort -V | tail -1)"
  if [ -n "$APKSIGNER" ]; then
    "$APKSIGNER" verify --print-certs "$APK_SOURCE" | head -4
  fi
fi

# The updater refuses a release without both assets, and a mismatched SHA-256 aborts the install.
RELEASE_DIR="build/lincoln-release-$next_version"
mkdir -p "$RELEASE_DIR"
cp "$APK_SOURCE" "$RELEASE_DIR/$APK_NAME"
(
  cd "$RELEASE_DIR"
  shasum -a 256 "$APK_NAME" > SHA256SUMS.txt
)
echo
echo "assets staged in $RELEASE_DIR:"
ls -lh "$RELEASE_DIR"
cat "$RELEASE_DIR/SHA256SUMS.txt"

if [ "$DRY_RUN" -eq 1 ]; then
  step "Dry run complete"
  echo "Nothing was committed or published."
  if [ "$BUMP" -eq 1 ]; then
    echo "The version bump in $GRADLE_FILE is left in the working tree for you to review."
  fi
  exit 0
fi

# --- 4. Commit, push and publish -------------------------------------------------------

step "Committing the version bump"
git add "$GRADLE_FILE"
git commit -m "Lincoln $next_version"

step "Pushing to $FORK_REMOTE"
BRANCH="$(git rev-parse --abbrev-ref HEAD)"
[ "$BRANCH" != "HEAD" ] || die "detached HEAD; check out a branch before releasing"
git push "$FORK_REMOTE" "HEAD:refs/heads/$BRANCH"

step "Publishing release $TAG"
# The fork's default branch is not where this work lives, so the release is pinned to the commit
# that was just pushed. Without --target gh would tag the fork's main instead.
TARGET_SHA="$(git rev-parse HEAD)"
NOTES_FILE="docs/RELEASE-NOTES-$next_version.md"
notes_args=(--notes "Lincoln/Ford build $next_version.")
if [ -f "$NOTES_FILE" ]; then
  notes_args=(--notes-file "$NOTES_FILE")
fi
gh release create "$TAG" \
  "$RELEASE_DIR/$APK_NAME" "$RELEASE_DIR/SHA256SUMS.txt" \
  --repo "$FORK_SLUG" --target "$TARGET_SHA" \
  --title "Lincoln $next_version" "${notes_args[@]}"

step "Refreshing the fallback download mirror"
if ssh -o BatchMode=yes "$CORSAIRPLAY_MIRROR_HOST" \
    'sudo -n /usr/local/sbin/corsairplay-mirror-sync'; then
  echo "Mirror updated on $CORSAIRPLAY_MIRROR_HOST"
else
  echo "warning: GitHub release succeeded, but the fallback mirror was not refreshed" >&2
fi

step "Done"
cat <<EOF
Published $TAG with $APK_NAME and SHA256SUMS.txt.

On the head unit: Settings -> About -> Check for updates, then Download and Install.
The install keeps every setting because the package name and signing key are unchanged.
EOF
