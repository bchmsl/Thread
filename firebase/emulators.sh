#!/bin/sh
# Runs the Firebase Auth and Firestore emulators for the demo-thread project (adr/ADR-005-auth-and-security-rules.md).
#
# A "demo-" project ID has no cloud project behind it, so nothing here can reach a real Firebase project.
# No `firebase login` is needed or used. firebase-tools is pinned and runs through npx; nothing is installed
# globally.
#
#   firebase/emulators.sh start             Run the emulators until Ctrl+C.
#   firebase/emulators.sh exec "<command>"  Start them, run the command, stop them. Example:
#   firebase/emulators.sh exec "./gradlew :shared:data:jsBrowserTest -Pthread.emulatorTests"
set -eu

cd "$(dirname "$0")/.."
tools="firebase-tools@15.36.0"
project="demo-thread"

case "${1:-}" in
    start)
        exec npx --yes "$tools" emulators:start --only auth,firestore --project "$project"
        ;;
    exec)
        shift
        exec npx --yes "$tools" emulators:exec --only auth,firestore --project "$project" "$@"
        ;;
    *)
        echo "Usage: $0 start | exec \"<command>\"" >&2
        exit 2
        ;;
esac
