#!/usr/bin/env bash
# Publish a release of this repository: one commit per release, and nothing
# else.
#
# The public repository has a history of its own. Each run adds a single commit
# whose tree is this repository's tracked files, minus the internal working
# material listed below, and whose message is the release heading and notes at
# the top of CHANGELOG.md. No private commit is copied: the public history
# carries no private hash, no private commit subject, and no count of how many
# commits it took to get here.
#
# The exclusion list is the ONLY place the public/private split is written down,
# so a new internal path is published unless it is added here.
#
# Publishing is deliberate. A run refuses while the top of CHANGELOG.md still
# says Unreleased or carries SNAPSHOT, and refuses to publish a tree that
# changed under a version already released. Cut the release in CHANGELOG.md
# first, then run this.
#
# Usage: ./tools/export-public.sh <dest-dir> [<remote-url> [--push]]
set -euo pipefail

usage() {
  cat >&2 <<'EOF'
Usage: ./tools/export-public.sh <dest-dir> [<remote-url> [--push]]

Publishes this repository's HEAD to <dest-dir> as one commit named after the
top section of CHANGELOG.md, leaving out the internal working material listed
in this script. <dest-dir> is created as a repository, or reused if it already
holds an earlier release. With <remote-url> the clone's `origin` is set to it;
with --push the new commit is pushed to it.

Nothing is removed from this repository. Commit first: only HEAD is exported,
not the working tree.
EOF
}

if (( $# < 1 || $# > 3 )); then
  usage
  exit 2
fi

dest=$1
remote=${2:-}
push=false

case ${3:-} in
  "") ;;
  --push) push=true ;;
  *) usage; exit 2 ;;
esac

exclude=(
  .claude
  .roo
  .zcode
)

# A repository this script made says so in its own config, where the flag is
# invisible in the published tree. Without it the script cannot tell an earlier
# release from a history it did not write, and replacing either would lose work.
flag=export-public.export

src=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)

# Untracked files are never exported, so they are not reported: the warning
# exists for work that exists only in the working tree.
if [ -n "$(git -C "$src" status --porcelain --untracked-files=no)" ]; then
  echo "export-public: uncommitted changes in $src are not exported; only HEAD is" >&2
fi

# The top `## ` heading of CHANGELOG.md names the release; the lines under it,
# up to the next heading, are the message. One pass keeps the newest section
# from running into the one below it.
changelog="$src/CHANGELOG.md"
if [ ! -f "$changelog" ]; then
  echo "export-public: $changelog is missing; a release is named by its top section" >&2
  exit 2
fi

section=$(awk '/^## / { if (found) exit; found = 1; next } found' "$changelog")
heading=$(awk '/^## / { sub(/^## /, ""); print; exit }' "$changelog")
if [ -z "$heading" ]; then
  echo "export-public: $changelog has no '## ' section; a release is named by its top section" >&2
  exit 2
fi

release=${heading%"${heading##*[![:space:]]}"}

case $release in
  *[Uu]nreleased* | *SNAPSHOT*)
    echo "export-public: the top of CHANGELOG.md is '${release}', which is not a release" >&2
    echo "export-public: cut the release in CHANGELOG.md first, then run this" >&2
    exit 2
    ;;
esac

notes=$(printf '%s\n' "$section" | sed -e :a -e '/^[[:space:]]*$/{$d;N;ba' -e '}')

subject="Release ${release}"

# The destination holds either an earlier release or nothing. A foreign history
# is refused rather than replaced: it may hold work this repository does not.
if [ -d "$dest/.git" ]; then
  if git -C "$dest" rev-parse --verify -q HEAD >/dev/null; then
    if [ "$(git -C "$dest" config --get "$flag" || true)" != true ]; then
      echo "export-public: $dest has history this script did not write (no $flag)" >&2
      echo "export-public: use a fresh directory, or delete $dest to start the public history again" >&2
      exit 2
    fi
  fi
  git -C "$dest" config "$flag" true
elif [ -e "$dest" ]; then
  echo "export-public: $dest exists and is not a git repository" >&2
  exit 2
else
  git init -q "$dest"
  git -C "$dest" config "$flag" true
fi

if [ -n "$remote" ]; then
  if git -C "$dest" remote get-url origin >/dev/null 2>&1; then
    git -C "$dest" remote set-url origin "$remote"
  else
    git -C "$dest" remote add origin "$remote"
  fi
elif $push && ! git -C "$dest" remote get-url origin >/dev/null 2>&1; then
  echo "export-public: --push needs a <remote-url>, or an origin already set in $dest" >&2
  exit 2
fi

# `git archive` yields tracked files only, so build output such as target/,
# generated/ and .bloop/ cannot reach the public tree even if it is unignored.
# The exclusions are removed as well: the split must not depend on what git
# happens to ignore at the moment of the export.
tree=$(mktemp -d "${TMPDIR:-/tmp}/scalahdl-export-XXXXXX")
trap 'rm -rf "$tree"' EXIT
git -C "$src" archive HEAD | tar -x -C "$tree"
for path in "${exclude[@]}"; do
  rm -rf "${tree:?}/$path"
done

# The snapshot is authoritative: a file deleted in this repository disappears
# from the public tree, and one edited there directly is overwritten.
find "$dest" -mindepth 1 -maxdepth 1 ! -name .git -exec rm -rf {} +
cp -a "$tree/." "$dest/"

git -C "$dest" add -A
staged=$(git -C "$dest" status --porcelain)
previous=$(git -C "$dest" log -1 --format=%s 2>/dev/null || true)

if [ -z "$staged" ] && [ "$previous" = "$subject" ]; then
  echo "export-public: $dest already holds ${subject}; no commit added"
  if $push; then
    git -C "$dest" push -q -u origin HEAD
  fi
  exit 0
fi

# A tree that moved under a version already released is a release without a
# version. Refusing keeps every public commit a statement the CHANGELOG agrees
# with, and keeps the version a reader checks against the tree truthful.
if [ -n "$staged" ] && [ "$previous" = "$subject" ]; then
  echo "export-public: the tree changed but CHANGELOG.md still names ${release}" >&2
  echo "export-public: give this release a new section in CHANGELOG.md first" >&2
  exit 2
fi

message="$subject"
[ -n "$notes" ] && message="$message"$'\n\n'"$notes"
git -C "$dest" commit -q --allow-empty -F - <<<"$message"

if $push; then
  git -C "$dest" push -q -u origin HEAD
fi

echo "export-public: $dest now at ${release} ($(git -C "$dest" rev-parse --short HEAD); excluded: ${exclude[*]})"
