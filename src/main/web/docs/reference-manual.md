# Textus Blog Reference Manual

This reference summarizes the component-owned Web surfaces.

- Public posts: `/web/blog/publicblogs`
- Author posts: `/web/blog/userblogs`
- New post editor: `/web/blog/new`
- Application jobs: `/web/blog/jobs`
- Atom feed: `/rest/v1/blog/blog/atomFeed`

Blog content can be saved as `html-fragment`, `markdown-gfm`, or `smartdox`.
The public renderer wraps content in a Textus article unless the supplied HTML
already owns a top-level `article` element.
