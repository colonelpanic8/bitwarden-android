{
  description = "Personal Bitwarden Android fork with YubiKey passkey login and PRF unlock";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = {
    self,
    nixpkgs,
    flake-utils,
  }:
    flake-utils.lib.eachDefaultSystem (system: let
      pkgs = import nixpkgs {
        inherit system;
        config = {
          allowUnfree = true;
          android_sdk.accept_license = true;
        };
      };
      buildToolsVersion = "37.0.0";
      androidComposition = pkgs.androidenv.composeAndroidPackages {
        platformToolsVersion = "37.0.1";
        buildToolsVersions = [buildToolsVersion "36.0.0"];
        platformVersions = ["37"];
        includeEmulator = false;
        includeSources = false;
        includeSystemImages = false;
        includeNDK = false;
        useGoogleAPIs = false;
      };
      androidHome = "${androidComposition.androidsdk}/libexec/android-sdk";
      jdk = pkgs.jdk21;
      androidShell = pkgs.mkShell {
        packages = with pkgs; [
          alejandra
          androidComposition.androidsdk
          gh
          git
          jdk
          just
        ];
        ANDROID_HOME = androidHome;
        ANDROID_SDK_ROOT = androidHome;
        JAVA_HOME = jdk.home;
        GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${androidHome}/build-tools/${buildToolsVersion}/aapt2";
        shellHook = ''
          export PATH=${androidHome}/build-tools/${buildToolsVersion}:${androidHome}/platform-tools:$PATH
        '';
      };
    in {
      packages.default = pkgs.writeShellApplication {
        name = "bitwarden-android-build";
        runtimeInputs = [jdk];
        text = ''
          export ANDROID_HOME=${androidHome}
          exec ./gradlew :app:assembleFdroidDebug "$@"
        '';
      };
      devShells = {
        default = androidShell;
        android = androidShell;
      };
      formatter = pkgs.alejandra;
    });
}
