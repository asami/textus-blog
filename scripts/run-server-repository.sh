#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=cncf-common.sh
source "$SCRIPT_DIR/cncf-common.sh"

TEXTUS_BLOG_COMPONENT="${TEXTUS_BLOG_COMPONENT:-textus-blog}"
TEXTUS_BLOG_COMPONENT_VERSION="${TEXTUS_BLOG_COMPONENT_VERSION:-}"

runtime_classpath="$(
  "$CNCF_LAUNCHER" \
    --dependency "org.goldenport:goldenport-cncf_3:$CNCF_VERSION" \
    --main-class "$CNCF_MAIN_CLASS" \
    --repository "$SIMPLEMODELING_REPOSITORY" \
    --cache "$CNCF_LAUNCHER_CACHE" \
    --resolve-only
)"

cncf_args=("--textus.component=$TEXTUS_BLOG_COMPONENT")
if [[ -n "$TEXTUS_BLOG_COMPONENT_VERSION" ]]; then
  cncf_args+=("--textus.component.version=$TEXTUS_BLOG_COMPONENT_VERSION")
fi
cncf_args+=(server)

exec java \
  -Dcncf.server.port="$CNCF_SERVER_PORT" \
  -Dcncf.http.baseurl="$CNCF_HTTP_BASEURL" \
  -cp "$runtime_classpath" \
  "$CNCF_MAIN_CLASS" \
  "${cncf_args[@]}"
