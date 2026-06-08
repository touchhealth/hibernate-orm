#!/bin/bash

set -euo pipefail

tag_file="release-in-progress"

create_commit() {
    sed -i -E "s/^hibernateVersion=.+$/hibernateVersion=$1/" gradle/version.properties
    git add gradle/version.properties
    git commit -q -m "$1"
}

prepare() {
    local version="$(sed -n -E 's/^hibernateVersion=(.+)-SNAPSHOT$/\1/p' gradle/version.properties)"
    local v=( ${version//./ } )
    local release_version="${v[0]}.${v[1]}.${v[2]}.touch"
    local snapshot_version="${v[0]}.${v[1]}.$(( ${v[2]} + 1 ))-SNAPSHOT"
    local tag="$release_version"

    create_commit "$release_version"
    git tag "$tag"
    create_commit "$snapshot_version"
    echo "$tag" > "$tag_file"

    echo "Done!"
}

deploy() {
    local tag="$(cat "$tag_file")"

    git checkout "$tag"
    ./gradlew clean
    ./gradlew publishAllPublicationsToMavenRepository
    rm "$tag_file"

    echo
    echo "Done!"
}

case "${1:-}" in
    prepare)
        prepare
        ;;
    deploy)
        deploy
        ;;
    *)
        echo "usage: ./release [prepare|deploy]"
        exit 1
        ;;
esac
