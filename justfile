set shell := ["bash", "-euo", "pipefail", "-c"]

# Local builds need a GitHub token with read:packages for the Bitwarden SDK
# (GITHUB_TOKEN or gitHubToken in user.properties).

default:
    @just --list

fmt:
    alejandra flake.nix

fmt-check:
    alejandra --check flake.nix

lint:
    ./gradlew detekt

# Run unit tests for the app module, optionally filtered, e.g. `just test '*WebAuthnPrf*'`.
test filter="":
    ./gradlew :app:testFdroidDebugUnitTest {{ if filter != "" { "--tests '" + filter + "'" } else { "" } }}

check: fmt-check lint

build:
    ./gradlew :app:assembleFdroidDebug

release:
    ./gradlew :app:assembleFdroidRelease

install-debug:
    adb install -r app/build/outputs/apk/fdroid/debug/com.colonelpanic.wardenkey.dev-fdroid.apk
