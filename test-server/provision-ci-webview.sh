#!/usr/bin/env bash
set -euo pipefail

api=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
abi=$(adb shell getprop ro.product.cpu.abilist | tr -d '\r')
case "$api:$abi" in
  26:x86*)
    kind=chrome; arch=x86; package=com.android.chrome
    digest=48c8996ecafbd9a39ea6fbab88fc88b0a1d70efc410dbd459fc614308a25d3d8
    signer=f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83 ;;
  26:arm64*)
    kind=chrome; arch=arm32+64; package=com.android.chrome
    digest=be5c2110c7e0e9f850c929d9c6d0c7e653986e182c6d699199c9c8976db93b01
    signer=f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83 ;;
  29:x86|29:x86,*)
    kind=google; arch=x86; package=com.google.android.webview
    digest=1737fe3008b66e9f85aea42aae250f354d263c1b77b6989b746325103141784e
    signer=6faf3c4140407473400934d117815a21af1cfefc5c0bee61c858bc3d72ba6fe5 ;;
  29:arm64*)
    kind=google; arch=arm32+64; package=com.google.android.webview
    digest=8298e6d4c17d59859d53c22c1ad83e711f4a45643a4abe2ad8a53430b4b82385
    signer=6faf3c4140407473400934d117815a21af1cfefc5c0bee61c858bc3d72ba6fe5 ;;
  26:*|29:*) echo "Unsupported WebView fixture ABI: $abi" >&2; exit 1 ;;
  *) exit 0 ;;
esac

build_tools=$(find "${ANDROID_HOME:?ANDROID_HOME is required}/build-tools" -name apksigner -type f | sort | tail -n 1)
test -n "$build_tools"
tools_dir=$(dirname "$build_tools")
work=$(mktemp -d "${TMPDIR:-/tmp}/rownd-webview.XXXXXXXX")
apk="$work/provider.apk"
trap 'rm -f "$apk"; rmdir "$work"' EXIT
url="https://github.com/JonaNorman/WebViewPackage/releases/download/$kind/122.0.6261.64_min26_$arch.apk"
# The mirror is untrusted: pin the bytes and verify Google's signing certificate before installation.
curl --fail --location --silent --show-error --retry 3 "$url" -o "$apk"
echo "$digest  $apk" | shasum -a 256 --check --status
certificates=$("$build_tools" verify --print-certs "$apk" | sed -n 's/^Signer #[0-9]* certificate SHA-256 digest: //p')
test "$certificates" = "$signer"
"$tools_dir/aapt" dump badging "$apk" | grep -F "package: name='$package'" >/dev/null
adb install -r "$apk"
adb shell cmd webviewupdate set-webview-implementation "$package"
adb shell dumpsys webviewupdate
