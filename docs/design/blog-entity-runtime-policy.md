# Blog Entity Runtime Policy

Date: 2026-05-04

This design note records the Textus Blog application policy for Entity kind,
Working Set, and view/cache usage. The general CNCF policy lives in
`cloud-native-component-framework/docs/notes/entity-kind-and-working-set-policy.md`.

## BlogPost Classification

`BlogPost` is a CMS public-content document.

Runtime classification:

| Parameter | Value |
|-----------|-------|
| `entityKind` | `document` |
| `applicationDomain` | `cms` |
| `usageKind` | `public-content` |
| legacy `operationKind` | `resource` |
| `workingSetPolicy` | `disabled` |

`BlogPost` is the canonical store-backed Entity for article content. It owns
the slug, title, content body, content references, lifecycle state, publication
state, owner/security attributes, and media attachment links.

Public read/search should use the store-backed canonical path and expose only
published active posts. Author/admin surfaces own draft, update, publish, and
deactivate behavior.

## Working Set Boundary

`BlogPost` should not be kept resident by default. Blog articles can be large,
long-lived, and numerous. Keeping full article records resident would make the
Working Set behave as a CMS cache instead of an execution-oriented domain
runtime.

Draft and edit state do not make `BlogPost` a task. They are lifecycle states
of a document.

If review or publication approval requires explicit process state, model that
process as a separate workflow Entity, for example `BlogPublicationWorkflow`,
and keep `BlogPost` as the document.

## View and Cache Candidates

Read optimization should use derived views, indexes, or projections rather than
resident `BlogPost` instances.

Recommended candidates:

- `PublishedBlogView` for public list/search rows.
- `BlogSlugIndex` for slug-to-`BlogPost` resolution.
- `BlogFeedProjection` for Atom/feed output.
- `BlogAuthorPostView` for author management lists.

These views should contain only the metadata needed by their surfaces. Full
content remains on `BlogPost`.
