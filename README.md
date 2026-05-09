# textus-blog

## Development Component Resolution

`textus-blog` uses `textus-user-account` as the authentication/account component
and `textus-user-notification` as the in-app notification component for the
header badge and Job notification forwarding driver.
Use the same component resolution policy as CNCF development:

1. Published standard components are resolved from the `simplemodeling.org`
   component repository and need no project-local configuration.
2. If a component CAR has been obtained locally, place or symlink it under
   `repository.d/`. This is local packaged staging and is not committed.
3. If a component is being developed at the same time, keep the developer-local
   path in `.textus.conf`.

For the common Blog + UserAccount edit/run workflow, point the runtime at the
sibling user-account project with a local `.textus.conf` file:

```conf
textus.repository.component.dev.dir = "../textus-user-account,../textus-user-notification"
textus.datastore.sqlite.path = "target/textus-blog-dev.sqlite"
```

`.textus.conf` is intentionally not committed. The SQLite setting keeps local
user accounts and Blog records across runtime restarts. The first startup after
adding this setting uses a new persistent database, so register once more; later
restarts reuse the same local database file.

With this file in place, starting from the `textus-blog` project root uses the
component development directories directly:

```bash
sbt --batch 'runMain org.goldenport.cncf.CncfMain --component-dev-dir . server'
```

The runtime does not read the dependent component CARs while
`textus.repository.component.dev.dir` is configured. Each dependency must have
`target/cncf.d/runtime-classpath.txt` once, then normal development only needs
`sbt compile` and a Blog server restart for Scala/CML implementation changes:

```bash
cd ../textus-user-account
scripts/update-runtime-classpath.sh
sbt --batch compile
cd ../textus-user-notification
scripts/update-runtime-classpath.sh
sbt --batch compile
```

Run the `update-runtime-classpath.sh` scripts again only after dependency or
classpath changes. Web resources are read from each dependency's `src/main/web`,
so HTML/CSS/JS changes can be checked with a browser reload and no CAR build.
Use `cozyBuildCAR` for release/package validation, not for the normal edit/run
loop.

If `textus-user-notification` is not available, the Blog pages still run; the
header notification badge stays hidden. When the component is present, the badge
loads `getNotificationSummary` for the authenticated user and displays only
unconfirmed notifications.

Alternatively, when testing a packaged user-account CAR rather than the sibling
development directory, place or symlink CAR files under `repository.d/` and pass
that repository explicitly. `repository.d/` is a local deployment area and is not
committed.
