#!/usr/bin/env bash
#
# Re-stamp the three bundled schemas' content-hash pins, bottom-up, plus every copy of them this
# repo carries. Run it after any edit to spec/m/{meta-kernel,meta,core}.tn: a digest is over the
# document's own bytes, so an edit to meta.tn invalidates meta.tn's own !!id pin, core.tn's !!meta
# pin, core.tn's own !!id pin (its bytes now differ), TsonBundledSchemas' held constants, and the
# getting-started example's pins in README.md and InitCommand.java.
#
#   scripts/restamp-bundled-schemas.sh            re-stamp in place
#   scripts/restamp-bundled-schemas.sh --check    report what is stale, write nothing (exit 1 if any)
#
# The library verifies the packaged bytes against the held digest on every load, so a stale pin is
# not a test-only failure -- it fails Tson.builder().build(). Restamping is what keeps the checks on
# while a schema is edited across several commits.
#
# [TSON-DATA] §2.2.1's content hash is sha256 over every byte past the !!id line, so the id line can
# carry the digest of the document it identifies. That is `tail -n +2 | shasum -a 256`, and using it
# rather than `tson hash` keeps this script runnable when the tree does not compile.

set -euo pipefail

cd "$(dirname "$0")/.."

CHECK=0
if [[ ${1-} == --check ]]; then
    CHECK=1
elif [[ $# -gt 0 ]]; then
    echo "usage: $0 [--check]" >&2
    exit 2
fi

STALE=0

CARRIERS=(README.md tson-cli/src/main/java/io/ltr8/tson/cli/InitCommand.java)

# The base URL a document identifies itself by: its own !!id with any query stripped. Read rather than
# hardcoded, so a spec-revision bump moves the identities and this script follows them.
identity() {
    perl -ne 'if (/^!!id:"([^?"]*)/) { print "$1\n"; exit }' "$1"
}

# The content hash of $1: sha256 over every byte past the !!id line.
digest() {
    local file=$1
    if [[ $(head -c 4 "$file") != '!!id' ]]; then
        echo "$file: the first line must be an !!id directive to content-hash it" >&2
        exit 2
    fi
    tail -n +2 "$file" | shasum -a 256 | cut -d' ' -f1
}

# Re-stamp every *already pinned* reference to base URL $2 in file $1 to digest $3. Only a reference
# that already carries ?sha256= is touched: pinning is optional, and an unpinned mention in prose is
# not a pin that has gone stale.
restamp() {
    local file=$1 base=$2 want=$3
    local found
    found=$(BASE="$base" WANT="$want" perl -ne '
        print "$1\n" while /\Q$ENV{BASE}\E\?sha256=([0-9a-f]{64})/g' "$file" | sort -u)
    [[ -z $found ]] && return 0
    if [[ $found == "$want" ]]; then
        return 0
    fi
    STALE=1
    if (( CHECK )); then
        echo "stale: $file -> $base"
        echo "       has $(echo "$found" | tr '\n' ' ')"
        echo "       want $want"
        return 0
    fi
    BASE="$base" WANT="$want" perl -i -pe '
        s/\Q$ENV{BASE}\E\?sha256=[0-9a-f]{64}/$ENV{BASE}?sha256=$ENV{WANT}/g' "$file"
    echo "restamped: $file -> $base?sha256=$want"
}

# Re-stamp the java constant $2 in TsonBundledSchemas to $3.
restamp_constant() {
    local file=$1 name=$2 want=$3
    local found
    found=$(perl -ne "print \"\$1\\n\" if /\\b$name = \"([0-9a-f]{64})\"/" "$file")
    if [[ -z $found ]]; then
        echo "$file: no $name constant found" >&2
        exit 2
    fi
    [[ $found == "$want" ]] && return 0
    STALE=1
    if (( CHECK )); then
        echo "stale: $file -> $name"
        echo "       has $found"
        echo "       want $want"
        return 0
    fi
    NAME="$name" WANT="$want" perl -i -pe '
        s/\b\Q$ENV{NAME}\E = "[0-9a-f]{64}"/$ENV{NAME} = "$ENV{WANT}"/' "$file"
    echo "restamped: $file -> $name = $want"
}

# The getting-started example schema, extracted from the README's fenced block. README.md and
# InitCommand.java carry byte-identical copies (bar the text block's indent), so one is hashed and
# both are stamped; a divergence between them is caught here rather than shipping two examples.
example_digest() {
    local readme init
    readme=$(awk '/^!!id:"https:\/\/example\.com\/.*person\.tn/,/^```$/' README.md | sed '$d')
    init=$(sed -n '/private static final String SCHEMA = """/,/^            """;$/p' \
        tson-cli/src/main/java/io/ltr8/tson/cli/InitCommand.java | sed '1d;$d' | sed 's/^            //')
    if [[ $readme != "$init" ]]; then
        echo "README.md and InitCommand.java carry different example schemas -- reconcile them first" >&2
        exit 2
    fi
    printf '%s\n' "$readme" | tail -n +2 | shasum -a 256 | cut -d' ' -f1
}

# --- bottom-up: each layer's digest is stamped into the layer above before that layer is hashed ---

KERNEL_ID=$(identity spec/m/meta-kernel.tn)
META_ID=$(identity spec/m/meta.tn)
CORE_ID=$(identity spec/m/core.tn)

KERNEL=$(digest spec/m/meta-kernel.tn)
restamp spec/m/meta-kernel.tn "$KERNEL_ID" "$KERNEL"

restamp spec/m/meta.tn "$KERNEL_ID" "$KERNEL"
META=$(digest spec/m/meta.tn)
restamp spec/m/meta.tn "$META_ID" "$META"

restamp spec/m/core.tn "$META_ID" "$META"
CORE=$(digest spec/m/core.tn)
restamp spec/m/core.tn "$CORE_ID" "$CORE"

BUNDLED=tson-schema/src/main/java/io/ltr8/tson/schema/TsonBundledSchemas.java
restamp_constant "$BUNDLED" META_KERNEL_SHA256 "$KERNEL"
restamp_constant "$BUNDLED" META_SHA256 "$META"
restamp_constant "$BUNDLED" CORE_SHA256 "$CORE"

# The example pins meta.tn and core.tn, so its own digest is over bytes this pass has just changed:
# stamp those two first, then hash it, then stamp its own !!id.
for carrier in "${CARRIERS[@]}"; do
    restamp "$carrier" "$META_ID" "$META"
    restamp "$carrier" "$CORE_ID" "$CORE"
done
EXAMPLE_ID=$(awk '/^!!id:"https:\/\/[^"]*person\.tn/ { sub(/^!!id:"/, ""); sub(/[?"].*$/, ""); print; exit }' README.md)
EXAMPLE=$(example_digest)
for carrier in "${CARRIERS[@]}"; do
    restamp "$carrier" "$EXAMPLE_ID" "$EXAMPLE"
done

if (( CHECK )); then
    (( STALE )) && exit 1
    echo "every bundled-schema pin is current"
    exit 0
fi
(( STALE )) || echo "every bundled-schema pin was already current"
