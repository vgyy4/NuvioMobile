#!/usr/bin/env bash
# Lists the system symbols the built app binary binds that the iOS SDK headers mark as introduced
# after iOS 12. Such a symbol is looked up when the app launches, so iOS 12 would refuse to start
# the app. Best effort: it reads the availability annotation next to each declaration.
set -uo pipefail
binary="$1"
sdk="$(xcrun --sdk iphoneos --show-sdk-path)"
report="${2:-ios12-newer-symbols.txt}"
: > "$report"
nm -u -arch arm64 "$binary" | awk '{print $2}' | sed 's/^_//' | grep -E '^(UI|NS|AV|CA|CF|CM|CV|MP|CX|UN|CL|LA|Sec|kCF|kUT|kSec|OS_|os_)' | sort -u > "${report}.symbols"
count=0
while read -r symbol; do
    [[ -z "$symbol" ]] && continue
    # Find the declaration line (word match) in framework headers and read its availability.
    line="$(grep -rhw --include='*.h' -m1 -- "$symbol" "$sdk/System/Library/Frameworks/UIKit.framework/Headers" \
        "$sdk/System/Library/Frameworks/Foundation.framework/Headers" \
        "$sdk/System/Library/Frameworks/AVFoundation.framework/Headers" \
        "$sdk/System/Library/Frameworks/CoreMedia.framework/Headers" \
        "$sdk/System/Library/Frameworks/MediaPlayer.framework/Headers" \
        "$sdk/System/Library/Frameworks/CoreGraphics.framework/Headers" 2>/dev/null | grep -E 'API_AVAILABLE|NS_AVAILABLE_IOS|API_UNAVAILABLE' | head -1)"
    version="$(printf '%s' "$line" | grep -oE 'ios\(([0-9]+)' | head -1 | grep -oE '[0-9]+')"
    if [[ -n "$version" && "$version" -gt 12 ]]; then
        echo "iOS ${version}: ${symbol}" >> "$report"
        count=$((count + 1))
    fi
done < "${report}.symbols"
echo "Symbols introduced after iOS 12: ${count}"
cat "$report"
