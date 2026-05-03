# Blog Web Inline Image Sync Handoff

Date: 2026-05-02
Status: journal

## Context

This entry records the interrupted `textus-blog` Blog Web app work after the
CNCF core commit `bd168e9` (`Advance entity access scope and static form web`).
The current `textus-blog` worktree intentionally remains dirty and partially
staged. Do not treat the current `ComponentFactory.scala` state as a final
design.

The active failing area is editor inline image synchronization in
`src/main/scala/org/simplemodeling/textus/blog/ComponentFactory.scala`.

## Current Worktree Shape

The broader staged work contains the Phase 19 Blog Web app split:

- flat Static Form Web App pages under `src/main/web`;
- CAR web metadata in `src/main/car/web/web.yaml`;
- Blog CML operation additions in `src/main/cozy/blog.cml`;
- Blog runtime changes in `ComponentFactory.scala`;
- focused specs in `ComponentFactorySpec.scala`.

`ComponentFactory.scala` has both staged and unstaged edits. The unstaged part
currently contains a stopgap direct `EntityStore.standard().deleteHard` route
for missing-tolerant cleanup. That route was used only to prove the failing spec
can pass and should not be committed as-is.

## Observed Failure

Focused command:

```console
sbt --batch 'testOnly org.simplemodeling.textus.blog.ComponentFactorySpec -- -z resynchronize'
```

Before the stopgap, the failing spec was:

```text
should save editor posts from authenticated users and resynchronize inline images on update
```

Failure symptom:

```text
Vector("cncf-editor_inline_first-entity-blob", "cncf-editor_inline_second-entity-blob")
was not equal to
Vector("cncf-editor_inline_second-entity-blob")
```

Meaning: updating an editor post from one inline Blob to another creates the new
`BlogInlineImage`, but the old inline occurrence remains visible in the test
helper.

## Facts Learned

- Association cleanup works separately through BlobAttachment association delete.
- The remaining stale data is `BlogInlineImage` occurrence data, not the
  BlobAttachment association.
- `_find_inline_images(postId)` using normal `entity_search[BlogInlineImage]`
  returned no rows inside the action during cleanup, even though the spec helper
  can see rows after the operation.
- Creating `BlogInlineImage` with the generated static collection caused
  collection/runtime-id mismatch risk.
- Adding a runtime `EntityPersistentCreate[BlogInlineImageCreate]` is the right
  direction: `BlogInlineImage` create should go through UoW `entity_create`, but
  target the runtime collection.
- Making editor inline occurrence IDs deterministic from
  `postId.parts.entropy + "_inline_" + index` made the duplicate visible when
  cleanup did not run:

```text
DataStore.Duplicate[id:sys-sys-entity-blog_inline_image-..._inline_0]
```

This is useful evidence: deterministic IDs reveal stale occurrence cleanup
failure instead of silently accumulating duplicate occurrences.

## Bad Route To Avoid

Do not solve this by using raw/direct entity I/O from Blog application logic:

```scala
EntityStore.standard().deleteHard(id)
```

That route contradicts the CNCF direction captured in the newly committed notes:

- application logic uses internal DSL;
- internal DSL emits UnitOfWork intents;
- entity I/O should keep lifecycle, logical delete, access scope, metrics, and
  EntitySpace consistency on the UoW path.

The stopgap passed the focused spec only because it bypassed the missing-row
behavior and ignored missing deletes. It is not the desired design.

## Preferred Fix Direction

Keep Blog code on the UoW path and add the missing primitive at CNCF level if
needed.

Recommended next slice:

1. Add a CNCF internal DSL helper such as `entity_delete_hard_if_present(id)` or
   a UnitOfWork op/option with explicit missing-tolerant semantics.
2. Implement it in terms of UoW, not direct application-side EntityStore calls.
3. Use that helper from Blog editor inline cleanup.
4. Keep deterministic editor inline occurrence IDs for replace-by-index cleanup.
5. Keep `BlogInlineImageCreate` using runtime collection metadata through a
   local `EntityPersistentCreate` wrapper, unless CNCF/Cozy generation is fixed
   to make runtime collection creation automatic for this case.

The cleanup can then be:

```scala
_editor_inline_image_id_candidates(postId).foldLeft(exec_pure(())) {
  case (z, id) => z.flatMap(_ => entity_delete_hard_if_present(id))
}
```

This avoids broad internal search and avoids application-side raw datastore
access.

## Follow-Up Checks

After replacing the stopgap with the UoW helper:

```console
sbt --batch 'testOnly org.simplemodeling.textus.blog.ComponentFactorySpec -- -z resynchronize'
sbt --batch 'testOnly org.simplemodeling.textus.blog.ComponentFactorySpec'
sbt --batch Test/compile
git diff --check
```

Also re-check that:

- no `System.err.println("DEBUG ...")` remains;
- Blog application code does not use `entity_search_internal`;
- Blog application code does not use raw `DataStore`;
- any direct `EntityStore.standard()` use is intentional and not part of normal
  application entity I/O.

## Related CNCF State

CNCF commit `bd168e9` contains:

- `IdGenerationContext`;
- `EntityAccessScopePolicy`;
- `EntityIdentityScope`;
- `EntityLifecycleRecordPolicy`;
- EntityStore logical delete / access-scope work;
- Static Form Web App flat routing/result template work;
- notes for application/internal DSL/UnitOfWork guidelines.

Those changes compiled with:

```console
sbt --batch Test/compile
```

and an earlier full CNCF test run had passed before the final commit.
