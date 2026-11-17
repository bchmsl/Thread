#!/usr/bin/env bash
# Builds, signs and (optionally) installs Thread Calendar, the menu bar app that uploads one calendar's meetings
# to Firestore (adr/ADR-020-calendar-meetings-sync.md).
#
# Usage:
#   macCompanion/scripts/build-app.sh [--install] [--emulator]
#
#   --install    Quit the running copy, copy the app to ~/Applications, and reopen it only if it was running.
#                A first install is not opened: the owner opens it once and signs in.
#   --emulator   Build "Thread Calendar Emulator" (its own bundle id) for the local emulators. The config files are not read.
#
# Config, read at build time and never printed:
#   firebase/web-config.properties          apiKey, projectId          (git-ignored)
#   macCompanion/oauth-desktop.properties   clientId, clientSecret     (git-ignored)
# All four set: cloud mode. None set: the emulator variant, with a warning. Anything in between stops the build.
# The values go only into Config.plist inside the app bundle in ~/Library/Caches/ThreadCalendar-build.
# Errors name the key, never the value.
#
# Signing: the valid "Apple Development" identity in the login keychain, found by hash. Its name and team are never
# printed. The TCC calendar grant and the Keychain item follow the signature, so a stable identity keeps both.
set -euo pipefail
set +x

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO="$(cd "$ROOT/.." && pwd)"
BUILD_DIR="$HOME/Library/Caches/ThreadCalendar-build"
SCRATCH_PATH="$BUILD_DIR/release"
INSTALL_DIR="$HOME/Applications"
MIN_MACOS="14.0"
PRODUCT="ThreadCalendar"
WEB_CONFIG="$REPO/firebase/web-config.properties"
OAUTH_CONFIG="$ROOT/oauth-desktop.properties"

install=false
emulator=false
while [[ $# -gt 0 ]]; do
    case "$1" in
        --install) install=true; shift ;;
        --emulator) emulator=true; shift ;;
        -h | --help) sed -n '2,20p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "Unknown option: $1" >&2; exit 64 ;;
    esac
done

# Prints one value of a key=value file to stdout, for capture into a variable only. Empty when the file or key is missing.
property() {
    local file="$1" key="$2" line
    [[ -f "$file" ]] || return 0
    line="$(grep -m1 -E "^[[:space:]]*${key}[[:space:]]*=" "$file" || true)"
    line="${line#*=}"
    line="${line//$'\r'/}"
    line="${line#"${line%%[![:space:]]*}"}"
    line="${line%"${line##*[![:space:]]}"}"
    printf '%s' "$line"
}

api_key=""
project_id=""
client_id=""
client_secret=""
if [[ "$emulator" == false ]]; then
    api_key="$(property "$WEB_CONFIG" apiKey)"
    project_id="$(property "$WEB_CONFIG" projectId)"
    client_id="$(property "$OAUTH_CONFIG" clientId)"
    client_secret="$(property "$OAUTH_CONFIG" clientSecret)"
    set_count=0
    missing=()
    for key in apiKey projectId clientId clientSecret; do
        case "$key" in
            apiKey) value="$api_key" ;;
            projectId) value="$project_id" ;;
            clientId) value="$client_id" ;;
            clientSecret) value="$client_secret" ;;
        esac
        if [[ -n "$value" ]]; then
            set_count=$((set_count + 1))
            # The same charset Config.swift accepts. It also keeps the values safe to write into XML.
            if [[ ! "$value" =~ ^[A-Za-z0-9._:-]+$ ]]; then
                echo "error: $key has characters outside A-Z a-z 0-9 . _ : -" >&2
                exit 1
            fi
        else
            missing+=("$key")
        fi
    done
    value=""
    if [[ "$set_count" -eq 0 ]]; then
        echo "warning: no config values found; building the emulator variant instead" >&2
        emulator=true
    elif [[ "$set_count" -lt 4 ]]; then
        echo "error: config is incomplete; missing: ${missing[*]}" >&2
        echo "       apiKey and projectId come from firebase/web-config.properties," >&2
        echo "       clientId and clientSecret from macCompanion/oauth-desktop.properties." >&2
        exit 1
    fi
fi

if [[ "$emulator" == true ]]; then
    MODE="emulator"
    BUNDLE_ID="com.bachana.ThreadCalendar.emulator"
    APP_NAME="Thread Calendar Emulator"
else
    MODE="cloud"
    BUNDLE_ID="com.bachana.ThreadCalendar"
    APP_NAME="Thread Calendar"
fi
APP="$BUILD_DIR/apps/$APP_NAME.app"
INSTALLED_APP="$INSTALL_DIR/$APP_NAME.app"

# Stale private PackageDescription interfaces in some Command Line Tools installs break manifest builds (as in Awake).
prepare_swiftpm() {
    local pm_dir module clean
    pm_dir="$(cd "$(dirname "$(xcrun --find swift)")/../lib/swift/pm" && pwd)"
    module="$pm_dir/ManifestAPI/PackageDescription.swiftmodule"
    if compgen -G "$module/*.private.swiftinterface" >/dev/null &&
        compgen -G "$module/*-apple-macos.swiftinterface" >/dev/null; then
        clean="$BUILD_DIR/pm-libs"
        rm -rf "$clean"
        mkdir -p "$clean"
        rsync -a --exclude '*.private.swiftinterface' "$pm_dir/ManifestAPI" "$pm_dir/PluginAPI" "$clean/"
        export SWIFTPM_CUSTOM_LIBS_DIR="$clean"
    fi
}

echo "==> EventKit read-only check"
# Only CalendarService may create an EKEventStore, and no file that imports EventKit may call a write API.
# A plain loop, because the repo path has spaces.
eventkit_files=0
for file in "$ROOT"/Sources/*/*.swift; do
    grep -qE '^(@preconcurrency )?import EventKit' "$file" || continue
    eventkit_files=$((eventkit_files + 1))
    if grep -nE '\.(save|remove)\(|saveCalendar|removeCalendar|\.commit\(\)|\.reset\(\)' "$file"; then
        echo "error: an EventKit write call appeared in ${file#"$ROOT"/} (Thread Calendar is read-only)" >&2
        exit 1
    fi
done
if [[ "$eventkit_files" -eq 0 ]]; then
    echo "error: no file imports EventKit; the read-only check found nothing to check" >&2
    exit 1
fi
echo "    $eventkit_files files import EventKit; no write calls"
if grep -nE 'EKEventStore\(' "$ROOT"/Sources/*/*.swift | grep -v '/CalendarService.swift:'; then
    echo "error: EKEventStore created outside CalendarService.swift" >&2
    exit 1
fi

prepare_swiftpm
SDK_VERSION="$(xcrun --show-sdk-version)"
LINK_FLAGS=(-Xlinker -platform_version -Xlinker macos -Xlinker "$MIN_MACOS" -Xlinker "$SDK_VERSION")

echo "==> Building $APP_NAME (release, $MODE mode, macOS $MIN_MACOS+, SDK $SDK_VERSION)"
swift build -c release "${LINK_FLAGS[@]}" --package-path "$ROOT" --scratch-path "$SCRATCH_PATH" --product "$PRODUCT"
BIN_DIR="$(swift build -c release "${LINK_FLAGS[@]}" --package-path "$ROOT" --scratch-path "$SCRATCH_PATH" --show-bin-path)"
RECORDED_SDK="$(vtool -show-build "$BIN_DIR/$PRODUCT" | awk '$1 == "sdk" { print $2 }')"
if [[ "$RECORDED_SDK" != "$SDK_VERSION" ]]; then
    echo "error: binary records SDK $RECORDED_SDK, expected $SDK_VERSION" >&2
    exit 1
fi

echo "==> Assembling the app bundle"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$BIN_DIR/$PRODUCT" "$APP/Contents/MacOS/$PRODUCT"
PLIST="$APP/Contents/Info.plist"
USAGE="Thread Calendar reads one calendar you pick and shows its meetings in Thread. It never changes your calendar."
/usr/libexec/PlistBuddy -c "Add :CFBundleIdentifier string $BUNDLE_ID" \
    -c "Add :CFBundleName string $APP_NAME" \
    -c "Add :CFBundleDisplayName string $APP_NAME" \
    -c "Add :CFBundleExecutable string $PRODUCT" \
    -c "Add :CFBundlePackageType string APPL" \
    -c "Add :CFBundleShortVersionString string 1.0" \
    -c "Add :CFBundleVersion string $(date +%Y%m%d%H%M%S)" \
    -c "Add :LSMinimumSystemVersion string $MIN_MACOS" \
    -c "Add :LSUIElement bool true" \
    -c "Add :NSCalendarsFullAccessUsageDescription string $USAGE" \
    -c "Add :NSCalendarsUsageDescription string $USAGE" \
    "$PLIST" >/dev/null
plutil -lint "$PLIST" >/dev/null

# Config.plist: written with the printf builtin, so the values never reach a command line, a log or the terminal.
CONFIG="$APP/Contents/Resources/Config.plist"
(
    umask 077
    {
        printf '<?xml version="1.0" encoding="UTF-8"?>\n'
        printf '<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">\n'
        printf '<plist version="1.0">\n<dict>\n'
        printf '    <key>mode</key><string>%s</string>\n' "$MODE"
        if [[ "$MODE" == "cloud" ]]; then
            printf '    <key>apiKey</key><string>%s</string>\n' "$api_key"
            printf '    <key>projectId</key><string>%s</string>\n' "$project_id"
            printf '    <key>clientId</key><string>%s</string>\n' "$client_id"
            printf '    <key>clientSecret</key><string>%s</string>\n' "$client_secret"
        fi
        printf '</dict>\n</plist>\n'
    } >"$CONFIG"
)
api_key=""
project_id=""
client_id=""
client_secret=""
plutil -lint "$CONFIG" >/dev/null

identities="$(security find-identity -v -p codesigning 2>/dev/null | awk '/"Apple Development/ { print $2 }')"
IDENTITY="$(printf '%s\n' "$identities" | awk 'NF { print; exit }')"
count="$(printf '%s\n' "$identities" | awk 'NF' | wc -l | tr -d ' ')"
if [[ -n "$IDENTITY" ]]; then
    [[ "$count" -gt 1 ]] && echo "warning: $count Apple Development identities; using the first" >&2
    echo "==> Signing with the Apple Development identity"
    SIGN="$IDENTITY"
elif [[ "$MODE" == "emulator" ]]; then
    echo "warning: no Apple Development identity; signing ad-hoc (calendar access will be asked again after each build)" >&2
    SIGN="-"
else
    echo "error: no valid Apple Development identity in the login keychain (see macCompanion/README.md, Signing)" >&2
    exit 1
fi
xattr -cr "$APP"
# No hardened runtime: it would need the calendars entitlement, and the spike proved TCC and Keychain without it (U2, U11).
codesign --force --sign "$SIGN" "$APP" 2>&1 | grep -v "replacing existing signature" || true
codesign --verify --strict "$APP"
if [[ "$SIGN" != "-" ]]; then
    # Prints only whether a team is present, never the team or the name.
    codesign -dv "$APP" 2>&1 | awk -F= '/^TeamIdentifier=/ { print "    TeamIdentifier=" (length($2) > 0 && $2 != "not set" ? "present" : "none") }'
fi

if [[ "$install" == true ]]; then
    EXECUTABLE="$INSTALLED_APP/Contents/MacOS/$PRODUCT"
    was_running=false
    if pgrep -f "$EXECUTABLE" >/dev/null; then
        was_running=true
        pkill -f "$EXECUTABLE" 2>/dev/null || true
        for _ in {1..50}; do
            pgrep -f "$EXECUTABLE" >/dev/null || break
            sleep 0.1
        done
    fi
    mkdir -p "$INSTALL_DIR"
    rm -rf "$INSTALLED_APP"
    ditto "$APP" "$INSTALLED_APP"
    if [[ "$was_running" == true ]]; then
        open "$INSTALLED_APP"
        echo "==> Installed and reopened: ~/Applications/$APP_NAME.app"
    else
        echo "==> Installed: ~/Applications/$APP_NAME.app (not opened)"
    fi
else
    echo "==> Built: $APP"
fi
