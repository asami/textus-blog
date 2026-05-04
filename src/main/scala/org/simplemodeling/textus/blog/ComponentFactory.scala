package org.simplemodeling.textus.blog

import java.nio.file.{Files, Path, Paths}
import java.nio.charset.StandardCharsets
import java.util.UUID
import scala.util.Using
import scala.util.matching.Regex
import cats.free.Free
import cats.syntax.all.*
import org.goldenport.*
import org.goldenport.bag.Bag
import org.goldenport.cncf.action.ActionCall
import org.goldenport.cncf.association.{AssociationCreate, AssociationDomain, AssociationFilter, AssociationRepository, AssociationStoragePolicy}
import org.goldenport.cncf.blob.*
import org.goldenport.cncf.component.{Component, ComponentCreate}
import org.goldenport.cncf.context.{ExecutionContext, SecurityContext}
import org.goldenport.cncf.directive.{Query, SearchResult}
import org.goldenport.cncf.entity.{EntityIdentityScope, EntityPersistent, EntityPersistentCreate, EntityQuery, EntitySearchScope, EntityStore, EntityVisibilityScope, SimpleEntityStorageShapePolicy}
import org.goldenport.cncf.entity.view.{Browser, ContextualBrowserQuery, ViewBuilder, ViewCollection}
import org.goldenport.cncf.feed.{AtomFeedProjection, AtomFeedRenderer}
import org.goldenport.cncf.id.TextusUrn
import org.goldenport.cncf.operation.{CmlOperationAssociationBinding, CmlOperationImageBinding}
import org.goldenport.cncf.security.SecuritySubject
import org.goldenport.cncf.unitofwork.{ExecUowM, UnitOfWorkOp}
import org.goldenport.datatype.{ContentType, FileBundle, MimeType}
import org.goldenport.datatype.ObjectId
import org.goldenport.datatype.{I18nText, Name as GpName}
import org.goldenport.http.{HttpResponse, HttpStatus}
import org.goldenport.id.UniversalId
import org.goldenport.protocol.operation.OperationResponse
import org.goldenport.record.Record
import org.simplemodeling.model.datatype.{EntityCollectionId, EntityId}
import org.simplemodeling.model.statemachine.{Aliveness, PostStatus}
import org.simplemodeling.model.value.{NameAttributes, DescriptiveAttributes, ContentAttributes, ContentMarkup, ContentReferenceOccurrence, LifecycleAttributes, PublicationAttributes, ResourceAttributes, AuditAttributes, MediaAttributes, ContextualAttributes, SecurityAttributes}
import org.simplemodeling.textus.blog.entity.BlogPost
import org.simplemodeling.textus.blog.entity.create.{
  BlogPost as BlogPostCreate
}
import org.simplemodeling.textus.blog.value.BlogEntityImageSpec
import org.goldenport.cncf.association.AssociationRepository.given
import org.goldenport.cncf.blob.BlobRepository.given

private[blog] final case class BlogProjectionRow(
  entityId: EntityId,
  slug: String,
  shortid: String,
  title: String,
  summary: Option[String],
  authorId: String,
  postStatus: String,
  aliveness: String,
  createdAt: java.time.Instant,
  updatedAt: java.time.Instant,
  representativeBlobId: Option[EntityId],
  representativeBlobUrl: Option[String],
  imageRoles: Vector[Record],
  searchText: String,
  renderedContent: Option[String]
) {
  def matches(value: String): Boolean =
    entityId.value == value || entityId.print == value || slug == value || shortid == value

  def toListRecord: Record =
    _base_record

  def toFeedRecord: Record =
    _base_record ++ renderedContent.map("content" -> _).map(Record.dataAuto(_)).getOrElse(Record.empty)

  private def _base_record: Record =
    Record.dataAuto(
      "id" -> entityId,
      "entity_id" -> entityId.value,
      "shortid" -> shortid,
      "slug" -> slug,
      "name" -> slug,
      "title" -> title,
      "summary" -> summary,
      "authorId" -> authorId,
      "post_status" -> postStatus,
      "postStatus" -> postStatus,
      "aliveness" -> aliveness,
      "createdAt" -> createdAt,
      "updatedAt" -> updatedAt,
      "representativeBlobId" -> representativeBlobId,
      "representativeBlobUrl" -> representativeBlobUrl,
      "imageRoles" -> imageRoles
    )
}

/*
 * @since   Apr. 29, 2026
 *  version Apr. 30, 2026
 * @version May.  4, 2026
 * @author  ASAMI, Tomoharu
 */
final class ComponentFactory
  extends BlogComponentRuntimeFactory
  with Component.BundleFactory
  with Component.PrimaryComponentFactory {
  override def primaryFactory: Component.PrimaryComponentFactory = this
  override def componentletFactories: Vector[Component.ComponentletFactory] = Vector.empty
}

class BlogComponentRuntimeFactory extends BlogComponentComponent.Factory {
  override protected def create_Component(params: ComponentCreate): Component =
    BlogRuntimeComponent()

  override val Blog: BlogComponentComponent.BlogServiceFactory =
    BlogServiceFactoryImpl()

  private final class BlogRuntimeComponent extends BlogComponentComponent {
    BlogComponentRuntimeFactory.installBlogViews(this)

    override def componentDescriptors: Vector[org.goldenport.cncf.component.ComponentDescriptor] =
      super.componentDescriptors.map { descriptor =>
        descriptor.copy(entityRuntimeDescriptors = descriptor.entityRuntimeDescriptors.map {
          case runtime if runtime.entityName == "BlogPost" =>
            runtime.copy(
              usageKind = org.goldenport.cncf.security.EntityUsageKind.PublicContent,
              applicationDomain = org.goldenport.cncf.security.EntityApplicationDomain.Cms
            )
          case other => other
        })
      }

    override def operationDefinitions: Vector[org.goldenport.cncf.operation.CmlOperationDefinition] =
      super.operationDefinitions.map {
        case definition if definition.name == "importPostTree" =>
          definition.copy(imageBinding = Some(CmlOperationImageBinding(
            acceptsArchiveBlobId = true,
            createsAttachment = true,
            roles = BlogComponentRuntimeFactory.ImageRoles,
            parameters = Vector("fileBundle", "archiveBlobId", "entityImages"),
            sourceEntityIdMode = CmlOperationAssociationBinding.SourceEntityIdModeEntityCreateResult
          )))
        case definition if definition.name == "registerPost" =>
          definition.copy(imageBinding = Some(CmlOperationImageBinding(
            acceptsExistingBlobId = true,
            createsAttachment = true,
            roles = BlogComponentRuntimeFactory.ImageRoles,
            parameters = Vector("entityImages.existingBlobId"),
            sourceEntityIdMode = CmlOperationAssociationBinding.SourceEntityIdModeEntityCreateResult,
            targetIdParameters = Vector("entityImages.existingBlobId")
          )))
        case definition =>
          definition
      }
  }

  final class BlogServiceFactoryImpl extends BlogComponentComponent.BlogServiceFactory {
    import BlogComponentComponent.BlogService.*

    override def createImportPostTreeActionCall(
      core: ActionCall.Core,
      action: ImportPostTree
    ): ImportPostTreeActionCall =
      ImportPostTreeActionCallImpl(core, action)

    override def createRegisterPostActionCall(
      core: ActionCall.Core,
      action: RegisterBlogPost
    ): RegisterPostActionCall =
      RegisterPostActionCallImpl(core, action)

    override def createSaveEditorPostActionCall(
      core: ActionCall.Core,
      action: SaveEditorBlogPost
    ): SaveEditorPostActionCall =
      SaveEditorPostActionCallImpl(core, action)

    override def createGetPostActionCall(
      core: ActionCall.Core,
      action: GetBlogPost
    ): GetPostActionCall =
      GetPostActionCallImpl(core, action)

    override def createGetMyPostActionCall(
      core: ActionCall.Core,
      action: GetMyBlogPost
    ): GetMyPostActionCall =
      GetMyPostActionCallImpl(core, action)

    override def createSearchPostsActionCall(
      core: ActionCall.Core,
      action: SearchBlogPosts
    ): SearchPostsActionCall =
      SearchPostsActionCallImpl(core, action)

    override def createSearchMyPostsActionCall(
      core: ActionCall.Core,
      action: SearchMyBlogPosts
    ): SearchMyPostsActionCall =
      SearchMyPostsActionCallImpl(core, action)

    override def createAtomFeedActionCall(
      core: ActionCall.Core,
      action: AtomFeedBlogPosts
    ): AtomFeedActionCall =
      AtomFeedActionCallImpl(core, action)

    override def createListImageBlobsActionCall(
      core: ActionCall.Core,
      action: ListBlogImageBlobs
    ): ListImageBlobsActionCall =
      ListImageBlobsActionCallImpl(core, action)

    override def createPublishPostActionCall(
      core: ActionCall.Core,
      action: PublishBlogPost
    ): PublishPostActionCall =
      PublishPostActionCallImpl(core, action)

    override def createDeactivatePostActionCall(
      core: ActionCall.Core,
      action: DeactivateBlogPost
    ): DeactivatePostActionCall =
      DeactivatePostActionCallImpl(core, action)
  }

  object BlogServiceFactoryImpl {
    def apply(): BlogServiceFactoryImpl = new BlogServiceFactoryImpl()
  }

  import BlogComponentComponent.BlogService.*

  private final case class ImportPostTreeActionCallImpl(
    core: ActionCall.Core,
    override val action: ImportPostTree
  ) extends ImportPostTreeActionCall with BlogRegistrationActionSupport {
    protected def build_Program: ExecUowM[OperationResponse] =
      for {
        sourceRecord <- exec_from(_with_current_author_if_missing(action.record))
        source <- _import_tree_source(action.record)
        response <- {
          for {
            draft <- exec_from(BlogFileTreeImportSupport.normalizeTreeWithInlineImageUrls(source.root) { img =>
              Some(img.treePath.getOrElse(img.sourcePath))
            })
            normalized <- content_normalize_references(
              ContentReferenceContent(
                InlineImageMarkup.HtmlFragment,
                draft.content,
                Some(FileBundle.Directory(source.root))
              )
            )
            register = _register_record(sourceRecord, draft, Some(normalized))
            response <- _register(register, Some(source.root))
          } yield response
        }.guarantee(exec_from(_cleanup_import_tree_source(source)))
      } yield response
  }

  private final case class RegisterPostActionCallImpl(
    core: ActionCall.Core,
    override val action: RegisterBlogPost
  ) extends RegisterPostActionCall with BlogRegistrationActionSupport {
    protected def build_Program: ExecUowM[OperationResponse] =
      _register(action.record, None)
  }

  private final case class SaveEditorPostActionCallImpl(
    core: ActionCall.Core,
    override val action: SaveEditorBlogPost
  ) extends SaveEditorPostActionCall with BlogRegistrationActionSupport {
    protected def build_Program: ExecUowM[OperationResponse] =
      _save_editor_post(action.record)
  }

  private final case class GetPostActionCallImpl(
    core: ActionCall.Core,
    override val action: GetBlogPost
  ) extends GetPostActionCall with BlogReadActionSupport {
    protected def build_Program: ExecUowM[OperationResponse] =
      for {
        post <- _load_public_blog_post(action.record, "id")
        record <- exec_from(_public_post_record(post))
      } yield OperationResponse(record)
  }

  private final case class GetMyPostActionCallImpl(
    core: ActionCall.Core,
    override val action: GetMyBlogPost
  ) extends GetMyPostActionCall with BlogReadActionSupport {
    protected def build_Program: ExecUowM[OperationResponse] =
      for {
        post <- _load_blog_post(action.record, "id")
        record <- exec_from(_public_post_record(post))
      } yield OperationResponse(record)
  }

  private final case class SearchPostsActionCallImpl(
    core: ActionCall.Core,
    override val action: SearchBlogPosts
  ) extends SearchPostsActionCall with BlogReadActionSupport {
    protected def build_Program: ExecUowM[OperationResponse] = {
      val storeQuery = Query.plan(Record.empty)
      val responseQuery = Query.plan(action.record, limit = _int(action.record, "limit"), offset = _int(action.record, "offset"))
      for {
        rows <- _published_blog_view_rows(storeQuery)
        visible = _filter_projection_search_text(rows, action.record)
        page = _page(visible, action.record)
        records = page.map(_.toListRecord)
      } yield OperationResponse(
        SearchResult(
          query = responseQuery,
          data = records,
          totalCount = Some(visible.size),
          offset = _int(action.record, "offset"),
          limit = _int(action.record, "limit"),
          fetchedCount = records.size
        ).toRecord()
      )
    }
  }

  private final case class SearchMyPostsActionCallImpl(
    core: ActionCall.Core,
    override val action: SearchMyBlogPosts
  ) extends SearchMyPostsActionCall with BlogReadActionSupport {
    protected def build_Program: ExecUowM[OperationResponse] = {
      val storeQuery = Query.plan(Record.empty)
      val responseQuery = Query.plan(action.record, limit = _int(action.record, "limit"), offset = _int(action.record, "offset"))
      for {
        rows <- _author_blog_view_rows(storeQuery)
        visible = _filter_projection_search_text(_sort_projection_rows(rows), action.record)
        page = _page(visible, action.record)
        records = page.map(_.toListRecord)
      } yield OperationResponse(
        SearchResult(
          query = responseQuery,
          data = records,
          totalCount = Some(visible.size),
          offset = _int(action.record, "offset"),
          limit = _int(action.record, "limit"),
          fetchedCount = records.size
        ).toRecord()
      )
    }
  }

  private final case class AtomFeedActionCallImpl(
    core: ActionCall.Core,
    override val action: AtomFeedBlogPosts
  ) extends AtomFeedActionCall with BlogReadActionSupport {
    protected def build_Program: ExecUowM[OperationResponse] = {
      val storeQuery = Query.plan(Record.empty)
      for {
        rows <- _blog_feed_view_rows(storeQuery)
        visible = _filter_projection_search_text(rows, action.record)
        page = _page_with_default(_sort_projection_rows(visible), action.record, 20)
        records = page.map(_.toFeedRecord)
        baseUrl <- exec_from(AtomFeedProjection.resolveSiteBaseUrl(request, executionContext.runtime.resolvedParameters))
        feed <- exec_from(AtomFeedProjection.project(
          AtomFeedProjection.Config(
            title = "Blog",
            baseUrl = baseUrl,
            selfPath = "/rest/v1/blog/blog/atomFeed",
            entryPathPrefix = "/blog"
          ),
          records
        ))
      } yield OperationResponse.Http(
        HttpResponse.Text(
          HttpStatus.Ok,
          ContentType.parse("application/atom+xml; charset=utf-8"),
          Bag.text(AtomFeedRenderer.render(feed), StandardCharsets.UTF_8)
        )
      )
    }
  }

  private final case class ListImageBlobsActionCallImpl(
    core: ActionCall.Core,
    override val action: ListBlogImageBlobs
  ) extends ListImageBlobsActionCall with BlogRegistrationActionSupport {
    protected def build_Program: ExecUowM[OperationResponse] =
      for {
        _ <- exec_from(_require_authenticated())
        response <- exec_from(_list_image_blobs(action.record))
      } yield OperationResponse(response)
  }

  private final case class PublishPostActionCallImpl(
    core: ActionCall.Core,
    override val action: PublishBlogPost
  ) extends PublishPostActionCall with BlogLifecycleActionSupport {
    protected def build_Program: ExecUowM[OperationResponse] =
      for {
        post <- _load_blog_post(action.record, "id")
        updated <- exec_from(_with_blog_visibility(post, publish = true, active = true))
        _ <- entity_save(updated)
        _ <- exec_from(_invalidate_blog_views())
      } yield OperationResponse(updated.toRecord())
  }

  private final case class DeactivatePostActionCallImpl(
    core: ActionCall.Core,
    override val action: DeactivateBlogPost
  ) extends DeactivatePostActionCall with BlogLifecycleActionSupport {
    protected def build_Program: ExecUowM[OperationResponse] =
      for {
        post <- _load_blog_post(action.record, "id")
        updated <- exec_from(_with_blog_visibility(post, post.lifecycleAttributes.postStatus, Aliveness.Dead))
        _ <- entity_save(updated)
        _ <- exec_from(_invalidate_blog_views())
      } yield OperationResponse(updated.toRecord())
  }

  private def _with_blog_visibility(
    post: BlogPost,
    publish: Boolean,
    active: Boolean
  ): Consequence[BlogPost] =
    _with_blog_visibility(
      post,
      if (publish) PostStatus.Published else PostStatus.Draft,
      if (active) Aliveness.Alive else Aliveness.Dead
    )

  private def _with_blog_visibility(
    post: BlogPost,
    postStatus: PostStatus,
    aliveness: Aliveness
  ): Consequence[BlogPost] =
    Consequence.success(
      post.copy(lifecycleAttributes = post.lifecycleAttributes.copy(
        postStatus = postStatus,
        aliveness = aliveness
      ))
    )

  private trait BlogRegistrationActionSupport { self: ActionCall =>
  protected final case class BlogImportTreeSource(root: Path, temporary: Boolean)

  private def _create_blog_post(
    post: BlogPostCreate
  ): ExecUowM[org.goldenport.cncf.entity.CreateResult[BlogPostCreate]] = {
    val persistent = new EntityPersistentCreate[BlogPostCreate] {
      def id(e: BlogPostCreate): Option[EntityId] =
        e.id.map(_blog_post_id)

      def collection(e: BlogPostCreate): EntityCollectionId =
        _runtime_collection_id(BlogPost.collectionId)

      def toRecord(e: BlogPostCreate): Record =
        e.toRecord()

      override def toStoreRecord(e: BlogPostCreate): Record =
        e.toDataStore()
    }
    entity_create(post)(using persistent)
  }

  protected final def _save_editor_post(record: Record): ExecUowM[OperationResponse] =
    for {
      _ <- exec_from(_reject_external_content_references(record))
      owner <- exec_from(_current_owner_id())
      target <- _optional_blog_post(record, "id")
      title <- exec_from(_required_string(record, "title"))
      rawContent <- exec_from(_required_string(record, "content"))
      normalized <- content_normalize_references(
        ContentReferenceContent(
          InlineImageMarkup.HtmlFragment,
          rawContent,
          None
        )
      )
      content = normalized.normalizedText
      explicitSlug = _string(record, "slug")
      contentReferences = normalized.references
      _ <- content_validate_references(contentReferences)
      publish = _boolean(record, "publish").getOrElse(false)
      saved <- target match {
        case Some(post) =>
          for {
            slug <- explicitSlug.map(_unique_blog_slug(_, Some(post.id))).getOrElse(exec_pure(_post_slug(post)))
            updated0 = post.copy(
              nameAttributes = org.simplemodeling.model.value.NameAttributes.Builder(post.nameAttributes).withName(slug).withTitle(title).build(),
              contentAttributes = _html_content_attributes(
                org.simplemodeling.model.value.ContentAttributes.Builder(post.contentAttributes),
                content,
                contentReferences
              ),
              securityAttributes = _blog_security(owner, publish)
            )
            updated <- exec_from(_with_blog_visibility(updated0, publish, active = true))
            _ <- entity_save(updated)
            inlineCount <- _recover_with(_sync_content_references(updated.id, contentReferences)) { conclusion =>
              entity_save(post).flatMap { _ =>
                _ignore_failure(_sync_content_references(post.id, post.contentAttributes.references)).flatMap { _ =>
                  exec_from(Consequence.Failure[Int](conclusion))
                }
              }
            }
          } yield (updated.id, updated.toRecord(), inlineCount)
        case None =>
          for {
            identity <- _unique_blog_identity(explicitSlug.getOrElse(title), None)
            post: BlogPostCreate = BlogPostCreate(
              id = None,
              nameAttributes = NameAttributes.Builder().withName(identity.slug).withTitle(title).build(),
              descriptiveAttributes = DescriptiveAttributes.empty,
              contentAttributes = _html_content_attributes(
                ContentAttributes.Builder(),
                content,
                contentReferences
              ),
              lifecycleAttributes = LifecycleAttributes(java.time.Instant.EPOCH, java.time.Instant.EPOCH, org.goldenport.datatype.Identifier("system"), org.goldenport.datatype.Identifier("system"), if (publish) PostStatus.Published else PostStatus.Draft, Aliveness.Alive),
              publicationAttributes = PublicationAttributes(None, None, None, None, None),
              securityAttributes = _blog_security(owner, publish),
              resourceAttributes = ResourceAttributes(),
              auditAttributes = AuditAttributes(),
              mediaAttributes = MediaAttributes(None, Vector.empty, Vector.empty, Vector.empty, Vector.empty),
              contextualAttribute = ContextualAttributes()
            )
            created <- _create_blog_post(post)
            inlineCount <- _cleanup_created_blog_post_on_failure(created.id) {
              _sync_content_references(created.id, contentReferences)
            }
          } yield (created.id, created.record.getOrElse(post.toRecord()), inlineCount)
      }
      _ <- exec_from(_invalidate_blog_views())
    } yield OperationResponse(
      _post_response_record(saved._2, saved._1) ++ Record.dataAuto(
        "inlineImageCount" -> saved._3
      )
    )

  private final case class BlogPostIdentity(slug: String, shortid: String)

  private def _unique_blog_identity(
    value: String,
    excludeId: Option[EntityId]
  ): ExecUowM[BlogPostIdentity] =
    for {
      slug <- _unique_blog_slug_value(_safe_slug(value), excludeId)
      shortid <- _unique_blog_shortid_value(_safe_shortid(slug), excludeId)
    } yield BlogPostIdentity(slug, shortid)

  private def _unique_blog_slug(
    value: String,
    excludeId: Option[EntityId]
  ): ExecUowM[String] =
    _unique_blog_slug_value(_safe_slug(value), excludeId)

  private def _unique_blog_slug_value(
    base: String,
    excludeId: Option[EntityId],
    index: Int = 1
  ): ExecUowM[String] = {
    val candidate = if (index <= 1) base else _slug_with_suffix(base, index)
    entity_unique_value_exists[BlogPost](
      _runtime_collection_id(BlogPost.collectionId),
      "name",
      candidate,
      excludeId,
      EntityIdentityScope.CurrentContext
    ).flatMap {
      case true => _unique_blog_slug_value(base, excludeId, index + 1)
      case false => exec_pure(candidate)
    }
  }

  private def _unique_blog_shortid_value(
    base: String,
    excludeId: Option[EntityId],
    index: Int = 1
  ): ExecUowM[String] = {
    val candidate = if (index <= 1) base else _shortid_with_suffix(base, index)
    entity_unique_value_exists[BlogPost](
      _runtime_collection_id(BlogPost.collectionId),
      "shortid",
      candidate,
      excludeId,
      EntityIdentityScope.CurrentContext,
      includeEntityIdEntropy = true
    ).flatMap {
      case true => _unique_blog_shortid_value(base, excludeId, index + 1)
      case false => exec_pure(candidate)
    }
  }

  private def _optional_blog_post(record: Record, names: String*): ExecUowM[Option[BlogPost]] =
    _record_value(record, names*) match {
      case Some(value) =>
        _resolve_blog_post(value).flatMap {
          case Some(post) => exec_pure(Some(post))
          case None => exec_from(Consequence.entityNotFound(s"blog post not found: ${_record_value_text(value)}"))
        }
      case None => exec_pure(None)
    }

  private def _resolve_blog_post(value: Any): ExecUowM[Option[BlogPost]] =
    for {
      owner <- exec_from(_current_owner_id())
      id <- _resolve_blog_post_id(value)
      post <- id match {
        case Some(entityId) =>
          entity_load_option[BlogPost](_blog_post_id(entityId)).map(_.map(_normalize_blog_post))
        case None =>
          exec_pure(None)
      }
    } yield post.filter(_post_owner_id(_) == owner)

  private def _resolve_blog_post_id(value: Any): ExecUowM[Option[EntityId]] =
    value match {
      case id: EntityId =>
        exec_pure(Some(_blog_post_id(id)))
      case other =>
        val text = _record_value_text(other)
        if (text.isEmpty)
          exec_pure(None)
        else {
          EntityId.parse(text).toOption match {
            case Some(id) =>
              exec_pure(Some(_blog_post_id(id)))
            case None =>
              entity_resolve_identity[BlogPost](
                _runtime_collection_id(BlogPost.collectionId),
                text,
                Vector("shortid", "name"),
                includeEntityIdEntropy = true,
                EntityIdentityScope.CurrentContext
              ).map(_.map(_blog_post_id))
          }
        }
    }

  protected final def _register(
    record: Record,
    treeRoot: Option[Path]
  ): ExecUowM[OperationResponse] =
    for {
      _ <- exec_from(_reject_external_content_references(record))
      entityImageSpecs <- exec_from(_entity_image_specs(record))
      _ <- exec_from(_validate_registration_image_specs(entityImageSpecs, treeRoot))
      rawContent <- exec_from(_required_string(record, "content"))
      normalized <- content_normalize_references(
        ContentReferenceContent(
          InlineImageMarkup.HtmlFragment,
          rawContent,
          treeRoot.map(FileBundle.Directory(_))
        )
      )
      post <- _blog_post(record, normalized.normalizedText, normalized.references)
      _ <- content_validate_references(normalized.references)
      created <- _create_blog_post(post)
      postId = created.id
      result <- _cleanup_created_blog_post_on_failure(postId) {
        for {
          inline <- _sync_content_references(postId, normalized.references)
          entityImages <- _create_entity_images(postId, entityImageSpecs, treeRoot)
        } yield (entityImages, inline)
      }
      _ <- exec_from(_invalidate_blog_views())
    } yield {
      OperationResponse(
        _post_response_record(created.toRecord, created.id) ++ Record.dataAuto(
          "entityImageCount" -> result._1.size,
          "inlineImageCount" -> result._2
        )
      )
    }

  private def _validate_registration_image_specs(
    entityImages: Vector[BlogEntityImageSpec],
    treeRoot: Option[Path]
  ): Consequence[Unit] =
    treeRoot match {
      case Some(_) =>
        Consequence.unit
      case None =>
        entityImages.zipWithIndex.collectFirst {
          case (spec, index) if spec.existingBlobId.isEmpty =>
            Consequence.argumentInvalid(s"registerPost entityImages[$index] requires existingBlobId; local path payloads must be imported through importPostTree")
        }.getOrElse(Consequence.unit)
    }

  private def _post_response_record(record: Record, id: EntityId): Record =
    Record.dataAuto(
      "id" -> id,
      "entity_id" -> id.value,
      "shortid" -> _post_shortid(id, record),
      "slug" -> _post_slug(id, record),
      "post_status" -> _string(record, "postStatus", "post_status").getOrElse(""),
      "aliveness" -> _string(record, "aliveness").getOrElse("")
    )

  private def _save_blog_post_direct(post: BlogPost): ExecUowM[Unit] =
    {
      val normalized = _normalize_blog_post(post)
      for {
      _ <- ConsequenceT.liftF(Free.liftF[UnitOfWorkOp, Unit](UnitOfWorkOp.EntityStoreSave(
        normalized,
        summon[EntityPersistent[BlogPost]],
        None
      )))
      comp <- exec_from(_component)
      _ <- exec_from {
        comp.entitySpace.entityOption(BlogPost.collectionId).map(_.asInstanceOf[org.goldenport.cncf.entity.runtime.EntityCollection[BlogPost]]) match {
          case Some(collection) =>
            Consequence(collection.put(normalized))
          case None =>
            Consequence.unit
        }
      }
    } yield ()
    }

  private def _normalize_blog_post(post: BlogPost): BlogPost =
    post.copy(id = _blog_post_id(post.id))

  private def _load_blog_post_direct(id: EntityId): ExecUowM[BlogPost] =
    for {
      post <- ConsequenceT
      .liftF(Free.liftF[UnitOfWorkOp, Option[BlogPost]](UnitOfWorkOp.EntityStoreLoadDirect(
        id,
        summon[EntityPersistent[BlogPost]]
      )))
      .flatMap { value =>
        exec_from(value.map(Consequence.success).getOrElse(Consequence.entityNotFound(s"blog post not found: ${id.value}")))
      }
    } yield _normalize_blog_post(post)

  private def _create_entity_images(
    postId: EntityId,
    specs: Vector[BlogEntityImageSpec],
    treeRoot: Option[Path]
  ): ExecUowM[Vector[AssociationCreate]] =
    specs.zipWithIndex.foldLeft(exec_pure(Vector.empty[AssociationCreate])) {
      case (z, (spec, index)) =>
        z.flatMap { associations =>
          for {
            blobId <- _ensure_entity_blob(spec, treeRoot)
            association <- exec_from(_blob_association(postId, blobId, spec.role, spec.sortOrder, _entity_image_attributes(spec)))
            _ <- entity_create(association)
          } yield associations :+ association
        }
    }

  private def _sync_content_references(
    postId: EntityId,
    references: Vector[ContentReferenceOccurrence]
  ): ExecUowM[Int] =
    for {
      _ <- content_sync_inline_references(_association_source_id(postId), references)
    } yield _inline_image_count(references)

  private def _inline_image_count(
    references: Vector[ContentReferenceOccurrence]
  ): Int =
    references.count(x => x.elementKind.contains("img") && x.attributeName.contains("src"))

  private def _cleanup_created_blog_post_on_failure[A](
    postId: EntityId
  )(
    program: ExecUowM[A]
  ): ExecUowM[A] =
    _recover_with(program) { conclusion =>
      _ignore_failure(_delete_existing_blob_associations(postId)).flatMap { _ =>
        _ignore_failure(entity_delete(postId)).flatMap { _ =>
          exec_from(Consequence.Failure[A](conclusion))
        }
      }
    }

  private def _recover_with[A](
    program: ExecUowM[A]
  )(f: Conclusion => ExecUowM[A]): ExecUowM[A] =
    ConsequenceT(program.value.flatMap {
      case Consequence.Success(value) =>
        Free.pure(Consequence.Success(value))
      case Consequence.Failure(conclusion) =>
        f(conclusion).value
    })

  private def _ignore_failure[A](
    program: ExecUowM[A]
  ): ExecUowM[Unit] =
    ConsequenceT(program.value.map {
      case Consequence.Success(_) => Consequence.unit
      case Consequence.Failure(_) => Consequence.unit
    })

  private def _delete_existing_inline_associations(postId: EntityId): ExecUowM[Unit] = {
    given org.goldenport.cncf.context.ExecutionContext = executionContext
    val repository = AssociationRepository.entityStore(AssociationStoragePolicy.mediaAttachmentDefault)
    exec_from(repository.list(AssociationFilter(
      domain = AssociationDomain.MediaAttachment,
      sourceEntityId = Some(_association_source_id(postId)),
      targetKind = Some("image"),
      role = Some("inline")
    ))).flatMap { associations =>
      associations.foldLeft(exec_pure(())) {
        case (z, association) =>
          z.flatMap(_ => exec_from(_delete_association_if_present(repository, association)))
      }
    }
  }

  private def _delete_existing_blob_associations(postId: EntityId): ExecUowM[Unit] = {
    given org.goldenport.cncf.context.ExecutionContext = executionContext
    val blobRepository = AssociationRepository.entityStore(AssociationStoragePolicy.blobAttachmentDefault)
    val mediaRepository = AssociationRepository.entityStore(AssociationStoragePolicy.mediaAttachmentDefault)
    val blobAssociations = blobRepository.list(AssociationFilter(
      domain = AssociationDomain.BlobAttachment,
      sourceEntityId = Some(_association_source_id(postId)),
      targetKind = Some("blob")
    ))
    val mediaAssociations = mediaRepository.list(AssociationFilter(
      domain = AssociationDomain.MediaAttachment,
      sourceEntityId = Some(_association_source_id(postId)),
      targetKind = Some("image")
    ))
    exec_from(blobAssociations).flatMap { blobValues =>
      exec_from(mediaAssociations).flatMap { mediaValues =>
        val associations = blobValues.map(blobRepository -> _) ++ mediaValues.map(mediaRepository -> _)
        associations.foldLeft(exec_pure(())) {
          case (z, (repository, association)) =>
            z.flatMap(_ => exec_from(_delete_association_if_present(repository, association)))
        }
      }
    }
  }

  private def _delete_association_if_present(
    repository: AssociationRepository,
    association: org.goldenport.cncf.association.Association
  )(using org.goldenport.cncf.context.ExecutionContext): Consequence[Unit] =
    repository.delete(association).recoverWith {
      case conclusion if _is_not_found(conclusion) => Consequence.unit
      case conclusion => Consequence.Failure(conclusion)
    }

  private def _is_not_found(conclusion: org.goldenport.Conclusion): Boolean = {
    val text = conclusion.show.toLowerCase(java.util.Locale.ROOT)
    text.contains("notfound") || text.contains("not found") || text.contains("not-found")
  }

  private def _ensure_entity_blob(
    spec: BlogEntityImageSpec,
    treeRoot: Option[Path]
  ): ExecUowM[EntityId] =
    spec.existingBlobId match {
      case Some(id) =>
        for {
          _ <- exec_from(_verify_blob(id))
        } yield id
      case None if treeRoot.isEmpty =>
        exec_from(Consequence.argumentInvalid("registerPost entityImages require existingBlobId; local path payloads must be imported through importPostTree"))
      case None =>
        val objectKey = spec.path.getOrElse(spec.role)
        val blobId = _blob_id(objectKey)
        for {
          _ <- _create_blob_if_local(blobId, spec.path, spec.contentType, treeRoot)
        } yield blobId
    }

  private def _create_blob_if_local(
    id: EntityId,
    path: Option[String],
    contentType: Option[String],
    treeRoot: Option[Path]
  ): ExecUowM[Unit] =
    (treeRoot, path) match {
      case (Some(root), Some(value)) =>
        val file = root.resolve(value).normalize()
        if (file.startsWith(root.normalize()) && Files.isRegularFile(file)) {
          for {
            _ <- exec_from(_put_blob_payload(id, file, contentType))
          } yield ()
        } else {
          exec_from(Consequence.operationInvalid(s"Blog image file is missing: ${value}"))
        }
      case _ => exec_pure(())
    }

  private def _verify_blob(id: EntityId): Consequence[Unit] = {
    given org.goldenport.cncf.context.ExecutionContext = executionContext
    BlobRepository.entityStore().get(id).map(_ => ())
  }

  private def _blog_post(
    record: Record,
    normalizedContent: String,
    contentReferences: Vector[ContentReferenceOccurrence]
  ): ExecUowM[BlogPostCreate] = {
    val slug = _required_string(record, "slug")
    val publish = _boolean(record, "publish").getOrElse(false)
    for {
      s <- exec_from(slug)
      identity <- _unique_blog_identity(s, None)
      t <- exec_from(_string(record, "title").map(Consequence.success).getOrElse(Consequence.success(identity.slug)))
      owner <- exec_from(_current_owner_id())
      post = BlogPostCreate(
        id = None,
        nameAttributes = NameAttributes.Builder().withName(identity.slug).withTitle(t).build(),
        descriptiveAttributes = DescriptiveAttributes.empty,
        contentAttributes = _html_content_attributes(
          ContentAttributes.Builder(),
          normalizedContent,
          contentReferences
        ),
        lifecycleAttributes = LifecycleAttributes(java.time.Instant.EPOCH, java.time.Instant.EPOCH, org.goldenport.datatype.Identifier("system"), org.goldenport.datatype.Identifier("system"), if (publish) PostStatus.Published else PostStatus.Draft, Aliveness.Alive),
        publicationAttributes = PublicationAttributes(None, None, None, None, None),
        securityAttributes = _blog_security(owner, publish),
        resourceAttributes = ResourceAttributes(),
        auditAttributes = AuditAttributes(),
        mediaAttributes = MediaAttributes(None, Vector.empty, Vector.empty, Vector.empty, Vector.empty),
        contextualAttribute = ContextualAttributes()
      )
    } yield post
  }

  private def _html_content_attributes(
    builder: ContentAttributes.Builder,
    content: String,
    references: Vector[ContentReferenceOccurrence]
  ): ContentAttributes =
    builder
      .withContent(content)
      .withMimeType(MimeType.TEXT_HTML)
      .withCharset(StandardCharsets.UTF_8)
      .withMarkup(ContentMarkup.HtmlFragment)
      .withReferences(references)
      .build()

  private def _blob_association(
    postId: EntityId,
    blobId: EntityId,
    role: String,
    sortOrder: Option[Int],
    attributes: Map[String, String],
    discriminator: Option[String] = None
  ): Consequence[AssociationCreate] =
    Consequence.success(
      AssociationCreate(
        id = Some(_id(AssociationStoragePolicy.BlobAttachmentCollection, Vector(Some(postId.value), Some(role), discriminator, Some(blobId.value)).flatten.mkString("-"))),
        associationId = UUID.randomUUID().toString,
        sourceEntityId = _association_source_id(postId),
        targetEntityId = blobId.value,
        targetKind = Some("blob"),
        role = role,
        associationDomain = AssociationDomain.BlobAttachment,
        sortOrder = sortOrder,
        attributes = attributes,
        collectionId = AssociationStoragePolicy.BlobAttachmentCollection
      )
    )

  private def _association_source_id(postId: EntityId): String =
    postId.value

  private def _entity_image_attributes(spec: BlogEntityImageSpec): Map[String, String] =
    Vector(
      spec.caption.map("caption" -> _),
      spec.checksum.map("checksum" -> _),
      spec.path.map("sourcePath" -> _)
    ).flatten.toMap

  private def _put_blob_payload(
    id: EntityId,
    file: Path,
    contentType: Option[String]
  ): Consequence[Unit] =
    try {
      val ct = ContentType.parse(contentType.getOrElse(_content_type(file.getFileName.toString)))
      val payload = Bag.binary(Files.readAllBytes(file))
      _component.flatMap { comp =>
        given org.goldenport.cncf.context.ExecutionContext = executionContext
        BlobPayloadSupport.putManagedPayload(
          component = comp,
          id = id,
          kind = BlobKind.Image,
          filename = Some(file.getFileName.toString),
          contentType = ct,
          payload = payload,
          attributes = Map("sourcePath" -> file.toString)
        ).map(_ => ())
      }
    } catch {
      case e: Exception =>
        Consequence.operationInvalid(s"failed to read image file for blob registration: ${file}: ${e.getMessage}")
    }

  protected final def _register_record(
    source: Record,
    draft: BlogRegistrationDraft,
    normalizedReferences: Option[ContentReferenceNormalizeResult] = None
  ): Record =
    Record.dataAuto(
      "slug" -> draft.slug.getOrElse(_slug_from_title(draft.title)),
      "title" -> draft.title,
      "content" -> normalizedReferences.map(_.normalizedText).getOrElse(draft.content),
      "publish" -> _boolean(source, "publish").getOrElse(false),
      "description" -> draft.description,
      "canonicalPath" -> draft.canonicalPath,
      "entityImages" -> draft.entityImages.map { image =>
        Record.dataAuto(
          "path" -> image.path,
          "role" -> image.role,
          "sortOrder" -> image.sortOrder,
          "caption" -> image.caption
        )
      }
    )

  private def _reject_external_content_references(record: Record): Consequence[Unit] =
    if (_has_any(record, "contentReferences", "content_references"))
      Consequence.argumentInvalid("contentReferences is server-derived metadata and cannot be supplied as operation input")
    else
      Consequence.success(())

  private def _entity_image_specs(record: Record): Consequence[Vector[BlogEntityImageSpec]] =
    _record_vector(record, "entityImages", "entity_images").foldLeft(Consequence.success(Vector.empty[BlogEntityImageSpec])) {
      case (z, item) =>
        z.flatMap { xs =>
          if (_has_any(item, "existingImageId", "existing_image_id"))
            Consequence.argumentInvalid("existingImageId is no longer supported; use existingBlobId")
          else
            BlogEntityImageSpec.createC(item).map(xs :+ _)
        }
    }

  private def _has_any(record: Record, names: String*): Boolean =
    names.exists(record.getAny(_).isDefined)

  private def _record_vector(record: Record, names: String*): Vector[Record] =
    names.iterator.flatMap(record.getAny).collectFirst {
      case xs: Seq[?] => xs.collect { case r: Record => r }.toVector
      case xs: Array[?] => xs.toVector.collect { case r: Record => r }
      case r: Record => Vector(r)
    }.getOrElse(Vector.empty)

  protected final def _import_tree_source(record: Record): ExecUowM[BlogImportTreeSource] =
    _file_bundle_value(record)
      .map { value =>
        exec_from(_file_bundle_tree(value).map(BlogImportTreeSource(_, temporary = true)))
      }
      .orElse {
        _string(record, "treeRootPath", "tree_root_path")
          .map { _ =>
            exec_from(_tree_root(record).map(BlogImportTreeSource(_, temporary = false)))
          }
      }
      .getOrElse {
        for {
          id <- exec_from(_archive_blob_id(record))
          comp <- exec_from(_component)
          read <- {
            given org.goldenport.cncf.context.ExecutionContext = executionContext
            exec_from(BlobPayloadSupport.readManagedPayload(comp, id))
          }
          bytes <- exec_from(BlobStoreSupport.readAllBytes(read.payload))
          root <- exec_from(BlogFileTreeImportSupport.extractZipTree(bytes))
        } yield BlogImportTreeSource(root, temporary = true)
      }

  private def _file_bundle_value(record: Record): Option[Any] =
    record.getAny("fileBundle").orElse(record.getAny("file_bundle"))

  private def _file_bundle_tree(value: Any): Consequence[Path] =
    FileBundle.create("fileBundle", value).flatMap { bundle =>
      bundle.toMimeBody.flatMap { body =>
        try {
          val bytes = Using.resource(body.value.openInputStream())(_.readAllBytes())
          BlogFileTreeImportSupport.extractZipTree(bytes)
        } catch {
          case e: Exception =>
            Consequence.operationInvalid(s"failed to read fileBundle payload: ${e.getMessage}")
        }
      }
    }

  protected final def _cleanup_import_tree_source(source: BlogImportTreeSource): Consequence[Unit] =
    if (source.temporary)
      BlogFileTreeImportSupport.cleanupTree(source.root)
    else
      Consequence.unit

  private def _tree_root(record: Record): Consequence[Path] =
    _string(record, "treeRootPath", "tree_root_path")
      .map(x => Paths.get(x).toAbsolutePath.normalize())
      .map { path =>
        if (Files.isDirectory(path))
          Consequence.success(path)
        else
          Consequence.operationInvalid(s"Blog file tree root is not a directory: ${path}")
      }
      .getOrElse(Consequence.operationInvalid("importPostTree requires fileBundle, treeRootPath, or archiveBlobId"))

  private def _archive_blob_id(record: Record): Consequence[EntityId] =
    _optional_entity_id(record, BlobRepository.CollectionId, "archiveBlobId", "archive_blob_id")
      .map(Consequence.success)
      .getOrElse(Consequence.argumentMissing("archiveBlobId"))

  private def _optional_entity_id(
    record: Record,
    collection: EntityCollectionId,
    names: String*
  ): Option[EntityId] =
    _optional_entity_id(record, names*).map(_with_collection(_, collection))

  private def _optional_entity_id(record: Record, names: String*): Option[EntityId] =
    names.iterator.flatMap { name =>
      record.getAny(name).flatMap {
        case id: EntityId => Some(id)
        case value if java.util.Objects.isNull(value) => None
        case value => EntityId.parse(value.toString.trim).toOption
      }.orElse {
        record.getAsC[EntityId](name).toOption.flatten
      }
    }.nextOption()

  protected final def _with_current_author_if_missing(record: Record): Consequence[Record] =
    _current_owner_id().map(owner => record ++ Record.dataAuto("ownerId" -> owner))

  protected final def _current_owner_id(): Consequence[String] = {
    val subject = SecuritySubject.current(using executionContext)
    if (!subject.isAuthenticated) {
      Consequence.operationInvalid("Blog authoring requires an authenticated session")
    } else {
      Consequence.success(BlogComponentRuntimeFactory.ownerIdText(subject.subjectId))
    }
  }

  protected final def _blog_security(
    owner: String,
    publish: Boolean
  ): SecurityAttributes =
    if (publish)
      SecurityAttributes.publicOwnedBy(owner)
    else
      SecurityAttributes.privateOwnedBy(owner)

  protected final def _post_owner_id(post: BlogPost): String =
    post.toDataStore().getString("owner_id")
      .orElse(post.toRecord().getString("author_id"))
      .getOrElse(post.securityAttributes.ownerId.id.value)

  protected final def _require_authenticated(): Consequence[Unit] =
    if (SecuritySubject.current(using executionContext).isAuthenticated)
      Consequence.unit
    else
      Consequence.operationInvalid("Blog operation requires an authenticated session")

  private def _component: Consequence[Component] =
    component match {
      case Some(value) => Consequence.success(value)
      case None => Consequence.serviceUnavailable("BlogComponent is not attached to a subsystem component")
    }

  private def _invalidate_blog_views(): Consequence[Unit] =
    _component.map { component =>
      BlogComponentRuntimeFactory.invalidateBlogViews(component)
    }

  private def _entity_id(record: Record, names: String*): Consequence[EntityId] =
    _optional_entity_id(record, names*) match {
      case Some(id) => Consequence.success(id)
      case None => Consequence.argumentMissing(names.head)
    }

  private def _required_string(record: Record, names: String*): Consequence[String] =
    _string(record, names*).map(Consequence.success).getOrElse(Consequence.argumentMissing(names.head))

  private def _string(record: Record, names: String*): Option[String] =
    names.iterator.flatMap(record.getAny).flatMap {
      case value if java.util.Objects.isNull(value) => None
      case value => Some(value.toString.trim)
    }.find(_.nonEmpty)

  private def _boolean(record: Record, names: String*): Option[Boolean] =
    names.iterator.flatMap(record.getAny).flatMap {
      case value if java.util.Objects.isNull(value) => None
      case b: java.lang.Boolean => Some(b.booleanValue)
      case s: String => _boolean_string(s)
      case other => _boolean_string(other.toString)
    }.nextOption()

  private def _boolean_string(value: String): Option[Boolean] =
    value.trim.toLowerCase(java.util.Locale.ROOT) match {
      case "true" | "on" | "yes" | "1" => Some(true)
      case "false" | "off" | "no" | "0" => Some(false)
      case "published" => Some(true)
      case "draft" => Some(false)
      case other => scala.util.Try(other.toBoolean).toOption
    }

  private def _int(record: Record, names: String*): Option[Int] =
    names.iterator.flatMap(record.getAny).flatMap {
      case value if java.util.Objects.isNull(value) => None
      case i: java.lang.Integer => Some(i.intValue)
      case i: Int => Some(i)
      case s: String => scala.util.Try(s.trim.toInt).toOption
      case other => scala.util.Try(other.toString.trim.toInt).toOption
    }.nextOption()

  protected final def _blob_id(key: String): EntityId =
    _id(BlobRepository.CollectionId, key)

  private def _id(collection: EntityCollectionId, key: String): EntityId =
    EntityId(
      collection.major,
      _safe_id(key, collection),
      collection,
      Some(UniversalId.StableTimestamp),
      Some(UniversalId.StableEntropy)
    )

  private def _runtime_collection_id(collection: EntityCollectionId): EntityCollectionId =
    EntityCollectionId(executionContext.major, executionContext.minor, collection.name)

  private def _blog_post_id(id: EntityId): EntityId =
    _with_collection(id, _runtime_collection_id(BlogPost.collectionId))

  private def _with_collection(id: EntityId, collection: EntityCollectionId): EntityId =
    if (id.collection == collection)
      id
    else
      EntityId(id.major, id.minor, collection, id.timestamp, id.entropy)

  private def _safe_id(value: String, collection: EntityCollectionId): String =
    _bounded_id(BlobStoreSupport.safeSegment(value).replace("-", "_").replace(".", "_"), collection) match {
      case s if s.headOption.exists(_.isLetter) => s
      case s => s"id_${s}"
    }

  private def _bounded_id(value: String, collection: EntityCollectionId): String = {
    val max = _entity_minor_max(collection)
    if (value.length <= max)
      value
    else {
      val suffix = java.lang.Integer.toHexString(value.hashCode)
      val prefix = (max - suffix.length - 1).max(1)
      s"${value.take(prefix)}_${suffix}".take(max)
    }
  }

  private def _entity_minor_max(collection: EntityCollectionId): Int = {
    val maxEntityIdLength = 60
    val fixed = collection.major.length + "-entity-".length + collection.name.length + "-0-stable".length + 1
    (maxEntityIdLength - fixed).max(8)
  }

  private def _content_type(path: String): String =
    path.toLowerCase(java.util.Locale.ROOT) match {
      case x if x.endsWith(".png") => "image/png"
      case x if x.endsWith(".gif") => "image/gif"
      case x if x.endsWith(".webp") => "image/webp"
      case x if x.endsWith(".svg") => "image/svg+xml"
      case _ => "image/jpeg"
    }

  private def _slug_from_title(title: String): String =
    _safe_slug(title)

  private def _unique_slug_value(base: String, existing: Set[String]): String =
    if (!existing.contains(base))
      base
    else
      Iterator.from(2).map(_slug_with_suffix(base, _)).find(x => !existing.contains(x)).get

  private def _slug_with_suffix(base: String, index: Int): String = {
    val suffix = s"-$index"
    val prefix = base.take((64 - suffix.length).max(1)).stripSuffix("-")
    s"$prefix$suffix"
  }

  private def _unique_shortid_value(base: String, existing: Set[String]): String =
    if (!existing.contains(base))
      base
    else
      Iterator.from(2).map(_shortid_with_suffix(base, _)).find(x => !existing.contains(x)).get

  private def _shortid_with_suffix(base: String, index: Int): String = {
    val suffix = s"_$index"
    val prefix = base.take((64 - suffix.length).max(1)).stripSuffix("_")
    s"$prefix$suffix"
  }

  private def _safe_slug(value: String): String = {
    val raw = BlobStoreSupport.safeSegment(value)
      .toLowerCase(java.util.Locale.ROOT)
      .replace("_", "-")
      .replace(".", "-")
      .replaceAll("-+", "-")
      .stripPrefix("-")
      .stripSuffix("-")
    val bounded = (if (raw.nonEmpty) raw else "post").take(64).stripSuffix("-")
    if (bounded.headOption.exists(_.isLetterOrDigit))
      bounded
    else
      s"post-$bounded".take(64).stripSuffix("-")
  }

  private def _safe_shortid(value: String): String = {
    val raw = BlobStoreSupport.safeSegment(value)
      .toLowerCase(java.util.Locale.ROOT)
      .map {
        case c if c.isLetterOrDigit || c == '_' => c
        case _ => '_'
      }
      .mkString
      .replaceAll("_+", "_")
      .stripPrefix("_")
      .stripSuffix("_")
    val bounded = (if (raw.nonEmpty) raw else "post").take(64).stripSuffix("_")
    if (bounded.nonEmpty) bounded else "post"
  }

  private def _owner_id_text(text: String): String = {
    BlogComponentRuntimeFactory.ownerIdText(text)
  }

  private def _post_shortid(post: BlogPost): String =
    _post_shortid(post.id, post.toRecord())

  private def _post_shortid(id: EntityId, record: Record): String =
    _string(record, "shortid", "shortId", "short_id").getOrElse(id.parts.entropy)

  private def _post_slug(post: BlogPost): String =
    _post_slug(post.id, post.toRecord())

  private def _post_slug(id: EntityId, record: Record): String =
    _string(record, "slug", "name").getOrElse(id.parts.entropy)

  private def _matches_blog_reference(post: BlogPost, value: String): Boolean =
    post.id.value == value || _post_shortid(post) == value || _post_slug(post) == value

  private def _record_value(record: Record, names: String*): Option[Any] =
    names.iterator.flatMap { name =>
      record.getAny(name).filter(value => _record_value_text(value).nonEmpty).orElse {
        record.getAsC[EntityId](name).toOption.flatten
      }
    }.nextOption()

  private def _record_value_text(value: Any): String =
    value match {
      case id: EntityId => id.value
      case other => Option(other).map(_.toString.trim).getOrElse("")
    }

  protected final def _list_image_blobs(record: Record): Consequence[Record] = {
    given org.goldenport.cncf.context.ExecutionContext = executionContext
    val text = _string(record, "text").map(_.toLowerCase(java.util.Locale.ROOT))
    val offset = _int(record, "offset").getOrElse(0).max(0)
    val limit = _int(record, "limit").filter(_ >= 0)
    BlobRepository.entityStore().list().map { values =>
      val filtered = values
        .filter(_.kind == BlobKind.Image)
        .filter { blob =>
          text.forall { needle =>
            Vector(
              blob.id.value,
              blob.filename.getOrElse(""),
              blob.contentType.map(_.header).getOrElse("")
            ).exists(_.toLowerCase(java.util.Locale.ROOT).contains(needle))
          }
        }
        .sortBy(blob => (-blob.updatedAt.toEpochMilli, blob.id.value))
      val page = limit.map(l => filtered.drop(offset).take(l)).getOrElse(filtered.drop(offset))
      SearchResult(
        query = Query.plan(record, offset = Some(offset), limit = limit),
        data = page.map(_image_blob_record),
        totalCount = Some(filtered.size),
        offset = Some(offset),
        limit = limit,
        fetchedCount = page.size
      ).toRecord()
    }
  }

  private def _image_blob_record(blob: Blob): Record =
    Record.dataAuto(
      "id" -> blob.id,
      "filename" -> blob.filename,
      "contentType" -> blob.contentType.map(_.header),
      "url" -> blob.accessUrl.displayUrl,
      "created" -> blob.createdAt.toString,
      "updated" -> blob.updatedAt.toString
    )
  }

  private trait BlogReadActionSupport { self: ActionCall =>
    private def _component: Consequence[Component] =
      component match {
        case Some(value) => Consequence.success(value)
        case None => Consequence.serviceUnavailable("BlogComponent is not attached to a subsystem component")
      }

    protected final def _current_owner_id(): Consequence[String] = {
      val subject = SecuritySubject.current(using executionContext)
      if (!subject.isAuthenticated) {
        Consequence.operationInvalid("Blog authoring requires an authenticated session")
      } else {
        Consequence.success(BlogComponentRuntimeFactory.ownerIdText(subject.subjectId))
      }
    }

    protected final def _entity_id(record: Record, names: String*): ExecUowM[EntityId] =
      _load_blog_post(record, names*).map(_.id)

    protected final def _load_blog_post(record: Record, names: String*): ExecUowM[BlogPost] =
      _record_value(record, names*) match {
        case Some(value) =>
          _resolve_my_blog_post(value).flatMap {
            case Some(post) => exec_pure(post)
            case None => exec_from(Consequence.entityNotFound(s"blog post not found: ${_record_value_text(value)}"))
          }
        case None => exec_from(Consequence.argumentMissing(names.head))
      }

    protected final def _load_public_blog_post(record: Record, names: String*): ExecUowM[BlogPost] =
      _record_value(record, names*) match {
        case Some(value) =>
          _resolve_public_blog_post(value).flatMap {
            case Some(post) => exec_pure(post)
            case None => exec_from(Consequence.entityNotFound(s"blog post not found: ${_record_value_text(value)}"))
          }
        case None => exec_from(Consequence.argumentMissing(names.head))
      }

    private def _optional_entity_id(
      record: Record,
      collection: EntityCollectionId,
      names: String*
    ): Option[EntityId] =
      names.iterator.flatMap { name =>
        record.getAny(name).flatMap {
          case id: EntityId => Some(id)
          case value => EntityId.parse(value.toString.trim).toOption
        }.orElse {
          record.getAsC[EntityId](name).toOption.flatten
        }
      }.nextOption().map { id =>
        if (id.collection == collection)
          id
        else
          EntityId(id.major, id.minor, collection, id.timestamp, id.entropy)
      }

    private def _with_collection(id: EntityId, collection: EntityCollectionId): EntityId =
      if (id.collection == collection)
        id
      else
        EntityId(id.major, id.minor, collection, id.timestamp, id.entropy)

    private def _runtime_collection_id(collection: EntityCollectionId): EntityCollectionId =
      EntityCollectionId(executionContext.major, executionContext.minor, collection.name)

    private def _blog_post_id(id: EntityId): EntityId =
      _with_collection(id, _runtime_collection_id(BlogPost.collectionId))

    private def _resolve_public_blog_post(value: Any): ExecUowM[Option[BlogPost]] =
      _resolve_public_blog_post_id(value).flatMap {
        case Some(id) => _load_public_blog_post_from_store(id)
        case None => exec_pure(None)
      }

    private def _load_public_blog_post_from_store(id: EntityId): ExecUowM[Option[BlogPost]] =
      exec_from {
        given ExecutionContext = executionContext
        EntityStore.standard().load[BlogPost](_blog_post_id(id)).map {
          _.map(_normalize_blog_post).filter(BlogComponentRuntimeFactory.isPublicPost)
        }
      }

    private def _resolve_my_blog_post(value: Any): ExecUowM[Option[BlogPost]] =
      for {
        id <- _resolve_author_blog_post_id(value)
        post <- id match {
          case Some(entityId) =>
            entity_load_option[BlogPost](entityId).map(_.map(_normalize_blog_post))
          case None =>
            exec_pure(None)
        }
      } yield post

    private def _resolve_public_blog_post_id(value: Any): ExecUowM[Option[EntityId]] = {
      val text = _record_value_text(value)
      if (text.isEmpty)
        exec_pure(None)
      else {
        val entityId = value match {
          case id: EntityId => Some(id)
          case _ => EntityId.parse(text).toOption
        }
        entityId match {
          case Some(id) =>
            _load_public_blog_post_from_store(id).flatMap {
              case Some(post) => exec_pure(Some(post.id))
              case None => _resolve_public_blog_post_id_from_projection_or_store(text)
            }
          case None =>
            _resolve_public_blog_post_id_from_projection_or_store(text)
        }
      }
    }

    private def _resolve_public_blog_post_id_from_projection_or_store(text: String): ExecUowM[Option[EntityId]] =
      _blog_slug_index_rows(Query.plan(Record.empty)).flatMap { rows =>
        rows.find(_.matches(text)).map(x => exec_pure(Some(x.entityId))).getOrElse {
          entity_resolve_identity[BlogPost](
            _runtime_collection_id(BlogPost.collectionId),
            text,
            Vector("shortid", "name"),
            includeEntityIdEntropy = true,
            EntityIdentityScope.CurrentContext
          ).flatMap {
            case Some(id) =>
              _load_public_blog_post_from_store(id).map {
                _.map(_.id)
              }
            case None =>
              _search_blog_posts(Query.plan(Record.empty)).map { result =>
                result.data.find(x => BlogComponentRuntimeFactory.isPublicPost(x) && _matches_blog_reference(x, text)).map(_.id)
              }
          }
        }
      }

    private def _resolve_author_blog_post_id(value: Any): ExecUowM[Option[EntityId]] = {
      val text = _record_value_text(value)
      if (text.isEmpty)
        exec_pure(None)
      else {
        val direct = value match {
          case id: EntityId => Some(id)
          case _ => EntityId.parse(text).toOption
        }
        if (_is_content_manager)
          direct.map(id => exec_pure(Some(_blog_post_id(id)))).getOrElse(_resolve_blog_post_id(text))
        else
          _author_blog_view_rows(Query.plan(Record.empty)).map(_.find(_.matches(text)).map(_.entityId))
      }
    }

    private def _is_content_manager: Boolean =
      SecuritySubject.current(using executionContext).hasPrivilege(SecurityContext.Privilege.ApplicationContentManager.name)

    private def _resolve_blog_post_id(value: Any): ExecUowM[Option[EntityId]] =
      value match {
        case id: EntityId =>
          exec_pure(Some(_blog_post_id(id)))
        case other =>
          val text = _record_value_text(other)
          if (text.isEmpty)
            exec_pure(None)
          else {
            EntityId.parse(text).toOption match {
            case Some(id) =>
                exec_pure(Some(_blog_post_id(id)))
              case None =>
                entity_resolve_identity[BlogPost](
                  _runtime_collection_id(BlogPost.collectionId),
                  text,
                  Vector("shortid", "name"),
                  includeEntityIdEntropy = true,
                  EntityIdentityScope.CurrentContext
                ).map(_.map(_blog_post_id))
            }
          }
      }

    private def _matches_blog_reference(post: BlogPost, value: String): Boolean =
      post.id.value == value || post.id.print == value || _post_shortid(post) == value || _post_slug(post) == value

    private def _post_shortid(post: BlogPost): String =
      post.toRecord().getString("shortid").getOrElse(post.id.parts.entropy)

    private def _post_slug(post: BlogPost): String =
      post.toRecord().getString("name").getOrElse(post.id.parts.entropy)

    private def _record_value(record: Record, names: String*): Option[Any] =
      names.iterator.flatMap { name =>
        record.getAny(name).filter(value => _record_value_text(value).nonEmpty).orElse {
          record.getAsC[EntityId](name).toOption.flatten
        }
      }.nextOption()

    private def _record_value_text(value: Any): String =
      value match {
        case id: EntityId => id.value
        case other => Option(other).map(_.toString.trim).getOrElse("")
      }

    protected final def _load_blog_post(id: EntityId): ExecUowM[BlogPost] =
      entity_load[BlogPost](id).map(_normalize_blog_post)

    private def _load_blog_post_direct(id: EntityId): ExecUowM[BlogPost] =
      entity_load_internal[BlogPost](id).map(_normalize_blog_post)

    protected final def _search_blog_posts(
      query: Query[?]
    ): ExecUowM[SearchResult[BlogPost]] =
      entity_search[BlogPost](EntityQuery(_runtime_collection_id(BlogPost.collectionId), query, EntitySearchScope.Store, visibilityScope = Some(EntityVisibilityScope.Public))).map { result =>
        result.copy(data = result.data.map(_normalize_blog_post))
      }

    protected final def _search_my_blog_posts(
      query: Query[?]
    ): ExecUowM[SearchResult[BlogPost]] =
      entity_search[BlogPost](EntityQuery(_runtime_collection_id(BlogPost.collectionId), query, EntitySearchScope.Store, visibilityScope = Some(EntityVisibilityScope.Owner))).map { result =>
        result.copy(data = result.data.map(_normalize_blog_post))
      }

    protected final def _published_blog_view_rows(
      query: Query[?]
    ): ExecUowM[Vector[BlogProjectionRow]] =
      _blog_view_rows(BlogComponentRuntimeFactory.PublishedBlogViewName, query)

    protected final def _blog_slug_index_rows(
      query: Query[?]
    ): ExecUowM[Vector[BlogProjectionRow]] =
      _blog_view_rows(BlogComponentRuntimeFactory.BlogSlugIndexName, query)

    protected final def _blog_feed_view_rows(
      query: Query[?]
    ): ExecUowM[Vector[BlogProjectionRow]] =
      _blog_view_rows(BlogComponentRuntimeFactory.BlogFeedProjectionName, query)

    protected final def _author_blog_view_rows(
      query: Query[?]
    ): ExecUowM[Vector[BlogProjectionRow]] =
      _blog_view_rows(BlogComponentRuntimeFactory.BlogAuthorPostViewName, query)

    private def _blog_view_rows(
      viewName: String,
      query: Query[?]
    ): ExecUowM[Vector[BlogProjectionRow]] =
      exec_from {
        given ExecutionContext = executionContext
        _component.flatMap(_.viewSpace.queryWithContext[BlogProjectionRow](viewName, query))
      }

    private def _normalize_blog_post(post: BlogPost): BlogPost =
      post.copy(id = _blog_post_id(post.id))

    protected final def _filter_search_text(
      posts: Vector[BlogPost],
      record: Record
    ): Vector[BlogPost] =
      _string(record, "text").map(_normalize_text).fold(posts) { needle =>
        posts.filter(post => _search_text(post).contains(needle))
      }

    protected final def _page[A](
      values: Vector[A],
      record: Record
    ): Vector[A] = {
      val offset = _int(record, "offset").getOrElse(0).max(0)
      _int(record, "limit").filter(_ >= 0) match {
        case Some(limit) => values.drop(offset).take(limit)
        case None => values.drop(offset)
      }
    }

    protected final def _page_with_default[A](
      values: Vector[A],
      record: Record,
      defaultLimit: Int
    ): Vector[A] = {
      val offset = _int(record, "offset").getOrElse(0).max(0)
      val limit = _int(record, "limit").filter(_ >= 0).getOrElse(defaultLimit)
      values.drop(offset).take(limit)
    }

    protected final def _sort_feed_posts(posts: Vector[BlogPost]): Vector[BlogPost] =
      posts.sortBy { post =>
        (-post.lifecycleAttributes.updatedAt.toEpochMilli, -post.lifecycleAttributes.createdAt.toEpochMilli, post.id.value)
      }

    protected final def _sort_projection_rows(rows: Vector[BlogProjectionRow]): Vector[BlogProjectionRow] =
      rows.sortBy(row => (-row.updatedAt.toEpochMilli, -row.createdAt.toEpochMilli, row.entityId.value))

    protected final def _filter_projection_search_text(
      rows: Vector[BlogProjectionRow],
      record: Record
    ): Vector[BlogProjectionRow] =
      _string(record, "text").map(_normalize_text).fold(rows) { needle =>
        rows.filter(_.searchText.contains(needle))
      }

    protected final def _public_post_record(post: BlogPost): Consequence[Record] =
      for {
        projection <- _image_projection(post.id)
        base = post.toRecord()
        rendered <- _render_public_content(post.contentAttributes)
      } yield {
        val representative = projection.representativeImage
        base ++ rendered.map("content" -> _).map(Record.dataAuto(_)).getOrElse(Record.empty) ++ Record.dataAuto(
          "entity_id" -> post.id.value,
          "shortid" -> _post_shortid(post),
          "slug" -> _post_slug(post),
          "authorId" -> post.securityAttributes.ownerId.id.value,
          "representativeBlobId" -> representative.map(_.metadata.id),
          "representativeBlobUrl" -> representative.map(_.metadata.accessUrl.displayUrl),
          "imageRoles" -> projection.images.map(_image_role_record)
        )
      }

    private def _render_public_content(content: ContentAttributes): Consequence[Option[String]] =
      content.content match {
        case Some(_) =>
          given org.goldenport.cncf.context.ExecutionContext = executionContext
          component
            .map(Consequence.success)
            .getOrElse(Consequence.serviceUnavailable("BlogComponent is not attached to a subsystem component"))
            .flatMap { component =>
              ContentRenderWorkflow(component).renderHtml(content).map(result => Some(result.html))
            }
        case None =>
          Consequence.success(None)
      }

    private def _image_projection(postId: EntityId): Consequence[BlobProjectionResult] = {
      given org.goldenport.cncf.context.ExecutionContext = executionContext
      val blobAssociations = AssociationRepository.entityStore(AssociationStoragePolicy.blobAttachmentDefault)
      val mediaAssociations = AssociationRepository.entityStore(AssociationStoragePolicy.mediaAttachmentDefault)
      val blobs = BlobRepository.entityStore()
      val media = MediaRepository.entityStore()
      BlobProjection.entityImageProjection(postId.value)(
        BlobProjection.Loaders(
          listAssociations = filter =>
            filter.domain match {
              case AssociationDomain.MediaAttachment => mediaAssociations.list(filter)
              case _ => blobAssociations.list(filter)
            },
          loadBlob = id => blobs.get(id),
          loadMedia = id => media.get(MediaKind.Image, id)
        )
      )
    }

    private def _image_role_record(row: BlobProjectionRow): Record =
      Record.dataAuto(
        "role" -> row.association.role,
        "blobId" -> row.metadata.id,
        "url" -> row.metadata.accessUrl.displayUrl,
        "sortOrder" -> row.association.sortOrder
      )

    private def _search_text(post: BlogPost): String = {
      val record = post.toRecord()
      Vector("name", "title", "headline", "summary", "description", "content")
        .flatMap(record.getAny)
        .flatMap {
          case value if java.util.Objects.isNull(value) => None
          case value => Some(value.toString)
        }
        .map(_normalize_text)
        .mkString("\n")
    }

    private def _normalize_text(value: String): String =
      value.toLowerCase(java.util.Locale.ROOT)

    protected final def _invalidate_blog_views(): Consequence[Unit] =
      _component.map { component =>
        BlogComponentRuntimeFactory.invalidateBlogViews(component)
      }

    protected final def _string(record: Record, names: String*): Option[String] =
      names.iterator.flatMap(record.getAny).flatMap {
        case value if java.util.Objects.isNull(value) => None
        case value => Some(value.toString.trim)
      }.find(_.nonEmpty)

    protected final def _int(record: Record, names: String*): Option[Int] =
      names.iterator.flatMap(record.getAny).flatMap {
        case value if java.util.Objects.isNull(value) => None
        case i: java.lang.Integer => Some(i.intValue)
        case i: Int => Some(i)
        case s: String => scala.util.Try(s.trim.toInt).toOption
        case other => scala.util.Try(other.toString.trim.toInt).toOption
      }.nextOption()

    protected final def _sequence[A](xs: Vector[Consequence[A]]): Consequence[Vector[A]] =
      xs.foldLeft(Consequence.success(Vector.empty[A])) {
        case (z, x) => z.flatMap(values => x.map(values :+ _))
      }
  }

  private trait BlogLifecycleActionSupport extends BlogReadActionSupport { self: ActionCall =>
  }
}

object BlogComponentRuntimeFactory {
  val PublishedBlogViewName: String = "PublishedBlogView"
  val BlogSlugIndexName: String = "BlogSlugIndex"
  val BlogFeedProjectionName: String = "BlogFeedProjection"
  val BlogAuthorPostViewName: String = "BlogAuthorPostView"

  val ImageRoles: Vector[String] =
    Vector("primary", "cover", "thumbnail", "gallery", "inline")

  def installBlogViews(component: Component): Unit = {
    _register_blog_view(
      component,
      PublishedBlogViewName,
      EntityVisibilityScope.Public,
      includeRenderedContent = false
    )
    _register_blog_view(
      component,
      BlogSlugIndexName,
      EntityVisibilityScope.Public,
      includeRenderedContent = false
    )
    _register_blog_view(
      component,
      BlogFeedProjectionName,
      EntityVisibilityScope.Public,
      includeRenderedContent = true
    )
    _register_blog_view(
      component,
      BlogAuthorPostViewName,
      EntityVisibilityScope.Owner,
      includeRenderedContent = false
    )
  }

  def invalidateBlogViews(component: Component): Unit =
    Vector(PublishedBlogViewName, BlogSlugIndexName, BlogFeedProjectionName, BlogAuthorPostViewName)
      .foreach(component.viewSpace.invalidate)

  private def _register_blog_view(
    component: Component,
    name: String,
    visibilityScope: EntityVisibilityScope,
    includeRenderedContent: Boolean
  ): Unit =
    if (component.viewSpace.collectionOption[BlogProjectionRow](name).isEmpty) {
      val collection = new ViewCollection[BlogProjectionRow](
        new ViewBuilder[BlogProjectionRow] {
          def build(id: EntityId): Consequence[BlogProjectionRow] =
            Consequence.operationInvalid(s"$name does not support direct materialization by id")
        },
        maxQueries = if (visibilityScope == EntityVisibilityScope.Owner) 0 else 512,
        metricsName = name
      )
      val query = new ContextualBrowserQuery[BlogProjectionRow] {
        def query_with_context(q: Query[_])(using ctx: ExecutionContext): Consequence[Vector[BlogProjectionRow]] =
          _blog_projection_rows(component, visibilityScope, includeRenderedContent, q)
      }
      component.viewSpace.register(name, collection, Browser.from(collection, query))
    }

  private def _blog_projection_rows(
    component: Component,
    visibilityScope: EntityVisibilityScope,
    includeRenderedContent: Boolean,
    query: Query[_]
  )(using ctx: ExecutionContext): Consequence[Vector[BlogProjectionRow]] = {
    val cid = EntityCollectionId(ctx.major, ctx.minor, BlogPost.collectionId.name)
    val entityquery = EntityQuery[BlogPost](
      cid,
      query,
      EntitySearchScope.Store,
      visibilityScope = Some(visibilityScope)
    )
    EntityStore.standard().search[BlogPost](entityquery).flatMap { result =>
      val posts = result.data.map(x => x.copy(id = _blog_post_id(ctx, x.id)))
      val scoped = visibilityScope match {
        case EntityVisibilityScope.Public => posts.filter(_is_public_post)
        case _ => posts
      }
      scoped.foldLeft(Consequence.success(Vector.empty[BlogProjectionRow])) { (z, post) =>
        z.flatMap(rows => _blog_projection_row(component, post, includeRenderedContent).map(rows :+ _))
      }
    }
  }

  private def _blog_projection_row(
    component: Component,
    post: BlogPost,
    includeRenderedContent: Boolean
  )(using ctx: ExecutionContext): Consequence[BlogProjectionRow] = {
    val record = post.toRecord()
    for {
      projection <- _image_projection(post.id)
      rendered <- if (includeRenderedContent) _render_public_content(component, post.contentAttributes) else Consequence.success(None)
    } yield {
      val representative = projection.representativeImage
      BlogProjectionRow(
        entityId = post.id,
        slug = _record_string(record, "slug", "name").getOrElse(post.id.parts.entropy),
        shortid = _record_string(record, "shortid").getOrElse(post.id.parts.entropy),
        title = _record_string(record, "title").getOrElse(""),
        summary = _record_string(record, "summary"),
        authorId = post.securityAttributes.ownerId.id.value,
        postStatus = _record_string(record, "postStatus", "post_status").getOrElse(""),
        aliveness = _record_string(record, "aliveness").getOrElse(""),
        createdAt = post.lifecycleAttributes.createdAt,
        updatedAt = post.lifecycleAttributes.updatedAt,
        representativeBlobId = representative.map(_.metadata.id),
        representativeBlobUrl = representative.map(_.metadata.accessUrl.displayUrl),
        imageRoles = projection.images.map(_image_role_record),
        searchText = _search_text(record),
        renderedContent = rendered
      )
    }
  }

  private def _is_public_post(post: BlogPost): Boolean =
    post.lifecycleAttributes.postStatus == PostStatus.Published &&
      post.lifecycleAttributes.aliveness == Aliveness.Alive

  def isPublicPost(post: BlogPost): Boolean = _is_public_post(post)

  private def _blog_post_id(ctx: ExecutionContext, id: EntityId): EntityId = {
    val cid = EntityCollectionId(ctx.major, ctx.minor, BlogPost.collectionId.name)
    if (id.collection == cid)
      id
    else
      EntityId(id.major, id.minor, cid, id.timestamp, id.entropy)
  }

  private def _render_public_content(
    component: Component,
    content: ContentAttributes
  )(using ctx: ExecutionContext): Consequence[Option[String]] =
    content.content match {
      case Some(_) =>
        ContentRenderWorkflow(component).renderHtml(content).map(result => Some(result.html))
      case None =>
        Consequence.success(None)
    }

  private def _image_projection(
    postId: EntityId
  )(using ctx: ExecutionContext): Consequence[BlobProjectionResult] = {
    val blobAssociations = AssociationRepository.entityStore(AssociationStoragePolicy.blobAttachmentDefault)
    val mediaAssociations = AssociationRepository.entityStore(AssociationStoragePolicy.mediaAttachmentDefault)
    val blobs = BlobRepository.entityStore()
    val media = MediaRepository.entityStore()
    BlobProjection.entityImageProjection(postId.value)(
      BlobProjection.Loaders(
        listAssociations = filter =>
          filter.domain match {
            case AssociationDomain.MediaAttachment => mediaAssociations.list(filter)
            case _ => blobAssociations.list(filter)
          },
        loadBlob = id => blobs.get(id),
        loadMedia = id => media.get(MediaKind.Image, id)
      )
    )
  }

  private def _image_role_record(row: BlobProjectionRow): Record =
    Record.dataAuto(
      "role" -> row.association.role,
      "blobId" -> row.metadata.id,
      "url" -> row.metadata.accessUrl.displayUrl,
      "sortOrder" -> row.association.sortOrder
    )

  private def _search_text(record: Record): String =
    Vector("name", "title", "headline", "summary", "description", "content")
      .flatMap(record.getAny)
      .flatMap {
        case value if java.util.Objects.isNull(value) => None
        case value => Some(value.toString)
      }
      .map(_.toLowerCase(java.util.Locale.ROOT))
      .mkString("\n")

  private def _record_string(record: Record, names: String*): Option[String] =
    names.iterator.flatMap(record.getString).nextOption()

  private val BlobContentImagePattern: Regex =
    """/web/blob/content/([^"'?\s<>#]+)""".r

  def extractBlobContentImageIds(content: String): Vector[EntityId] =
    BlobContentImagePattern.findAllMatchIn(content).map { m =>
      _blob_entity_id(m.group(1))
    }.toVector

  private def _blob_entity_id(value: String): EntityId =
    EntityId.parse(value).toOption match {
      case Some(id) =>
        EntityId(id.major, id.minor, BlobRepository.CollectionId, id.timestamp, id.entropy)
      case None =>
        EntityId("cncf", _safe_id(value), BlobRepository.CollectionId)
    }

  private def _safe_id(value: String): String = {
    val raw = BlobStoreSupport.safeSegment(value).replace("-", "_").replace(".", "_")
    val bounded =
      if (raw.length <= 32)
        raw
      else
        s"${raw.take(23)}_${java.lang.Integer.toHexString(raw.hashCode)}"
    if (bounded.headOption.exists(_.isLetter))
      bounded
    else
      s"id_${bounded}"
  }

  def ownerIdText(text: String): String = {
    val sanitized = text.trim.map {
      case c if c.isLetterOrDigit || c == '_' => c
      case _ => '_'
    }.mkString
    val nonempty = if (sanitized.isEmpty) "unknown" else sanitized
    if (nonempty.headOption.exists(_.isLetter)) nonempty else s"id_$nonempty"
  }
}
