# textus-blog

## Development Startup

`textus-blog` uses `textus-user-account` as the authentication/account component
and `textus-user-notification` as the in-app notification component for the
header badge and Job notification forwarding driver.

During development, use the development-directory startup script:

```bash
scripts/run-server.sh
```

`scripts/run-server.sh` starts CNCF with:

```bash
--component-dev-dir <textus-blog project root>
```

This keeps the normal edit/run loop fast. The script expects
`target/cncf.d/runtime-classpath.txt`; refresh it when dependency or classpath
settings change:

```bash
scripts/update-runtime-classpath.sh
```

For local persistent data, keep datastore settings in an uncommitted
`.textus.conf`:

```conf
textus.datastore.sqlite.path = "target/textus-blog-dev.sqlite"
```

The SQLite setting keeps local user accounts and Blog records across runtime
restarts. The first startup after adding this setting uses a new persistent
database, so register once more; later restarts reuse the same local database
file.

## Component Repository Startup

For packaged verification, use the component repository startup script:

```bash
scripts/run-server-repository.sh
```

That script starts CNCF with only the Blog component name:

```bash
--textus.component=textus-blog
```

When no component version is specified, CNCF uses the latest available
`textus-blog` version from the standard component repository. To pin a
specific Blog CAR version, set:

```bash
TEXTUS_BLOG_COMPONENT_VERSION=0.0.2 scripts/run-server-repository.sh
```

which adds:

```bash
--textus.component.version=0.0.2
```

CNCF fetches `textus-blog` from the standard component repository. The Blog
assembly descriptor names `textus-user-account` and
`textus-user-notification` with their required versions, and CNCF fetches those
dependency CARs from the repository as well. No local Blog CAR path or local
dependency CAR path is passed to CNCF in this mode.

Use `sbt --batch cozyBuildCAR` when preparing a new Blog CAR for publication to
the component repository. Once published, the server startup should continue to
use `scripts/run-server-repository.sh` instead of a local CAR path.

`repository.d/` is a local development/debug staging area only. The packaged
Blog startup path should not depend on it.

If `textus-user-notification` is not available, the Blog pages still run; the
header notification badge stays hidden. When the component is present, the badge
loads `getNotificationSummary` for the authenticated user and displays only
unconfirmed notifications.

## Publication Metadata

Git に登録するコンポーネント情報は `project.yaml` で管理する。
`.cozy/config.yaml` は個人環境のローカルパスだけを置く。

```yaml
project:
  name: textus-blog
  title: Textus Blog Component
  kind: car
  path: components/textus/blog

publication:
  source_manifest:
    enabled: false

packaging:
  kind: car
  car:
    source_dir: src/main/car

warehouse:
  repository_artifacts:
    include:
      - car
    modules:
      - textus-blog
```

ローカル出力先は Git 管理外の `.cozy/config.yaml` に置く。

```yaml
publication:
  output: /Users/asami/src/dev2025/simplemodeling-org/src/main/publication

warehouse:
  repository: /Users/asami/src/maven-repository
```
