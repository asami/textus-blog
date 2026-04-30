# Blog File Tree Import

BlogComponent accepts a local-previewable article file tree through
`importPostTree`. During Phase 19 the executable development-driver input is
`treeRootPath`, which points at an already expanded local tree. `archiveBlobId`
remains the stable operation contract for the archived tree transport. The
operation is the user-facing bulk registration boundary; it normalizes the tree
and delegates to `registerPost`, which accepts an already normalized HTML
article fragment plus image specifications.

## Tree Layout

The archive contains one or more full HTML files and their image files. Image
paths inside article HTML are relative to the HTML file location so the article
can be opened and previewed locally before registration.

Metadata lives under `META-INF/`. Blog metadata is read from
`META-INF/blog.yaml`; metadata in that file is authoritative, and missing fields
fall back to the HTML document head.

```text
post-tree/
  index.html
  images/
    hero.jpg
    inline-a.png
  META-INF/
    blog.yaml
```

## Metadata

`META-INF/blog.yaml` defines the post identity and images bound to the
`BlogPost` entity.

```yaml
slug: phase-19-html-tree
entryHtmlPath: index.html
title: Phase 19 HTML Tree
description: Blog metadata wins over HTML head metadata.
canonicalPath: /blog/phase-19-html-tree
entityImages:
  - path: images/hero.jpg
    role: thumbnail
    sortOrder: 0
    caption: Hero image
```

Metadata precedence:

- `META-INF/blog.yaml` is authoritative.
- `entryHtmlPath` selects the HTML file to import; when omitted, the first
  deterministic non-`META-INF` `.html` file is used.
- Missing `title` falls back to `<head><title>`.
- Missing `description` falls back to `<meta name="description">`.
- Missing `canonicalPath` falls back to `<link rel="canonical">`.

## HTML Extraction

Input HTML may be a full HTML document. BlogComponent parses it with CNCF
standard HTML tree values, extracts only the article body, and stores
`BlogPost.content` as the rendered HTML fragment string.

Article extraction order:

- `article[data-blog-content]`
- first `<article>`
- `main article`
- otherwise validation error

The surrounding site layout remains outside `BlogPost.content`. CSS,
JavaScript, header, footer, and page shell are supplied by the Blog site or by
the external Web application that embeds the fragment.

## Image Handling

Entity images are declared in `META-INF/blog.yaml`. During import, each image
file is registered as a managed CNCF Blob with payload and metadata, then linked
to the `BlogPost` by a `BlobAttachment` Association whose role is `primary`,
`cover`, `thumbnail`, `gallery`, or an application-specific value.

Inline images are discovered from `<img src>` in the extracted article
fragment. Relative paths are resolved against the source HTML file, registered
as managed Blobs, attached to the `BlogPost` with `role = inline`, and the
stored HTML fragment rewrites each `src` to the registered public image URL.
`BlogInlineImage` records track the HTML occurrence and synchronization state;
the image payload and entity-to-image link remain Blob + BlobAttachment data.

Individual image registration, binding, and inline-image sync operations remain
available for admin repair and maintenance; normal authoring uses the bulk
`importPostTree` flow.

## Atom Feed

BlogComponent exposes public posts through `Blog.atomFeed`, available through
the REST operation URL `/rest/v1/blog/blog/atomFeed`. The operation uses the
CNCF AtomFeed helper and returns Atom 1.0 XML with content type
`application/atom+xml; charset=utf-8`.

Only published and active posts appear in the feed. Draft and inactive posts
remain private to author/admin workflows. The feed accepts the same public text
filter semantics as `searchPosts`; when `limit` is omitted it returns up to 20
entries.

Entry URLs are generated from the configured site base URL and the post slug:

```text
{siteBaseUrl}/blog/{slug}
```

Set either `textus.site.base-url` or `cncf.site.base-url` before invoking the
feed. `BlogPost.content` is emitted as Atom `content type="html"` using the
stored HTML fragment.
