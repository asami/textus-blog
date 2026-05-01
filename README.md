# textus-blog

## Development Component Resolution

`textus-blog` uses `textus-user-account` as the authentication/account component.
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
textus.repository.component.dev.dir = "../textus-user-account"
textus.datastore.sqlite.path = "target/textus-blog-dev.sqlite"
```

`.textus.conf` is intentionally not committed. The SQLite setting keeps local
user accounts and Blog records across runtime restarts. The first startup after
adding this setting uses a new persistent database, so register once more; later
restarts reuse the same local database file.

With this file in place, starting from the `textus-blog` project root can use the
component development directory directly:

```bash
sbt --batch 'runMain org.goldenport.cncf.CncfMain --component-dev-dir . server'
```

The runtime treats the configured user-account development directory as a component
repository by reading the latest CAR from its `target/` directory. Rebuild the
user-account CAR after changing that project:

```bash
cd ../textus-user-account
sbt --batch cozyBuildCAR
```

Alternatively, when testing a packaged user-account CAR rather than the sibling
development directory, place or symlink CAR files under `repository.d/` and pass
that repository explicitly. `repository.d/` is a local deployment area and is not
committed.
