#!/usr/bin/env bash

set -euo pipefail

fail() {
    printf 'ERROR: %s\n' "$*" >&2
    exit 1
}

if [[ $# -ne 2 ]]; then
    fail "Usage: $0 <release.apk|release.aab> <certificate-sha256-allowlist>"
fi

artifact=$1
allowlist_file=$2

[[ -f "$artifact" && -r "$artifact" ]] || fail "Artifact is not a readable regular file: $artifact"
[[ -f "$allowlist_file" && -r "$allowlist_file" ]] || fail "Allowlist is not a readable regular file: $allowlist_file"

allowed_digests=()
placeholder_found=false
while IFS= read -r line || [[ -n "$line" ]]; do
    line=${line%%#*}
    entry=$(printf '%s' "$line" | sed 's/^[[:space:]]*//; s/[[:space:]]*$//')
    [[ -z "$entry" ]] && continue

    case "$entry" in
        *REPLACE_WITH*|*PLACEHOLDER*)
            placeholder_found=true
            continue
            ;;
    esac

    if [[ "$entry" =~ ^[0-9A-Fa-f]{64}$ ]]; then
        normalized=$(printf '%s' "$entry" | tr '[:upper:]' '[:lower:]')
    elif [[ "$entry" =~ ^([0-9A-Fa-f]{2}:){31}[0-9A-Fa-f]{2}$ ]]; then
        normalized=$(printf '%s' "$entry" | tr -d ':' | tr '[:upper:]' '[:lower:]')
    else
        fail "Allowlist contains a non-SHA-256 entry."
    fi
    allowed_digests+=("$normalized")
done < "$allowlist_file"

[[ "$placeholder_found" == false ]] || fail "Release certificate allowlist still contains a placeholder."
[[ ${#allowed_digests[@]} -gt 0 ]] || fail "Release certificate allowlist contains no approved digest."

signer_dn=
signer_digest=

case "$artifact" in
    *.apk)
        apksigner_command=${APKSIGNER:-}
        if [[ -z "$apksigner_command" ]]; then
            apksigner_command=$(command -v apksigner || true)
        fi
        if [[ -z "$apksigner_command" ]]; then
            android_sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
            if [[ -n "$android_sdk" && -d "$android_sdk/build-tools" ]]; then
                while IFS= read -r candidate; do
                    [[ -x "$candidate" ]] && apksigner_command=$candidate
                done < <(find "$android_sdk/build-tools" -type f -name apksigner | sort)
            fi
        fi
        [[ -n "$apksigner_command" && -x "$apksigner_command" ]] || fail "apksigner was not found; set APKSIGNER or ANDROID_HOME."

        verification_output=$(
            "$apksigner_command" verify --verbose --print-certs "$artifact"
        ) || fail "APK signature verification failed."

        signer_dns=()
        while IFS= read -r value; do
            signer_dns+=("$value")
        done < <(printf '%s\n' "$verification_output" | sed -n 's/^Signer #[0-9][0-9]* certificate DN: //p')
        signer_digests=()
        while IFS= read -r value; do
            signer_digests+=("$value")
        done < <(printf '%s\n' "$verification_output" | sed -n 's/^Signer #[0-9][0-9]* certificate SHA-256 digest: //p')

        [[ ${#signer_dns[@]} -eq 1 && ${#signer_digests[@]} -eq 1 ]] || fail "APK must have exactly one current signer."
        signer_dn=${signer_dns[0]}
        signer_digest=${signer_digests[0]}
        ;;
    *.aab)
        command -v jarsigner >/dev/null 2>&1 || fail "jarsigner was not found."
        command -v keytool >/dev/null 2>&1 || fail "keytool was not found."

        set +e
        jarsigner_output=$(LC_ALL=C jarsigner -verify -strict -verbose -certs "$artifact" 2>&1)
        jarsigner_status=$?
        set -e
        if (( (jarsigner_status & 16) != 0 )); then
            fail "AAB contains unsigned entries."
        fi
        (( (jarsigner_status & ~4) == 0 )) || fail "AAB JAR signature verification failed."
        grep -Fq 'jar verified' <<< "$jarsigner_output" || fail "AAB JAR signature verification did not report success."
        if grep -Fq 'This jar contains unsigned entries which have not been integrity-checked.' <<< "$jarsigner_output"; then
            fail "AAB contains unsigned entries."
        fi

        certificate_output=$(LC_ALL=C keytool -printcert -jarfile "$artifact" 2>&1) || fail "AAB signer certificate could not be read."
        signer_count=$(printf '%s\n' "$certificate_output" | grep -Ec '^Signer #[0-9]+:' || true)
        [[ "$signer_count" -eq 1 ]] || fail "AAB must have exactly one signer."
        signer_dn=$(awk 'sub(/^Owner: /, "") { print; exit }' <<< "$certificate_output")
        signer_digest=$(awk 'sub(/^[[:space:]]*SHA256: /, "") { print; exit }' <<< "$certificate_output")
        [[ -n "$signer_dn" && -n "$signer_digest" ]] || fail "AAB signer DN or SHA-256 digest could not be parsed."
        ;;
    *)
        fail "Unsupported artifact type; expected .apk or .aab."
        ;;
esac

normalized_signer_digest=$(printf '%s' "$signer_digest" | tr -d '[:space:]:' | tr '[:upper:]' '[:lower:]')
[[ "$normalized_signer_digest" =~ ^[0-9a-f]{64}$ ]] || fail "Signer SHA-256 digest is malformed."

normalized_signer_dn=$(printf '%s' "$signer_dn" | tr '[:upper:]' '[:lower:]')
[[ "$normalized_signer_dn" != *'cn=android debug'* ]] || fail "Android Debug certificate is forbidden for Release artifacts."

allowlisted=false
for allowed_digest in "${allowed_digests[@]}"; do
    if [[ "$normalized_signer_digest" == "$allowed_digest" ]]; then
        allowlisted=true
        break
    fi
done
[[ "$allowlisted" == true ]] || fail "Signer SHA-256 digest is not in the approved Release allowlist."

printf 'Artifact: %s\n' "$artifact"
printf 'Signer DN: %s\n' "$signer_dn"
printf 'Signer certificate SHA-256: %s\n' "$normalized_signer_digest"
printf 'Allowlist result: PASS\n'
