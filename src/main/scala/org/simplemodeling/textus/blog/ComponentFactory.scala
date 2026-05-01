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
import org.goldenport.cncf.directive.{Query, SearchResult}
import org.goldenport.cncf.entity.{EntityPersistent, EntityQuery, EntitySearchScope, EntityStore}
import org.goldenport.cncf.feed.{AtomFeedProjection, AtomFeedRenderer}
import org.goldenport.cncf.operation.{CmlOperationAssociationBinding, CmlOperationImageBinding}
import org.goldenport.cncf.security.SecuritySubject
import org.goldenport.cncf.unitofwork.{ExecUowM, UnitOfWorkOp}
import org.goldenport.datatype.{ContentType, FileBundle}
import org.goldenport.http.{HttpResponse, HttpStatus}
import org.goldenport.id.UniversalId
import org.goldenport.protocol.operation.OperationResponse
import org.goldenport.record.Record
import org.simplemodeling.model.datatype.{EntityCollectionId, EntityId}
import org.simplemodeling.textus.blog.entity.{BlogInlineImage, BlogPost}
import org.simplemodeling.textus.blog.entity.create.{
  BlogInlineImage as BlogInlineImageCreate,
  BlogPost as BlogPostCreate
}
import org.simplemodeling.textus.blog.value.{BlogEntityImageSpec, BlogInlineImageSpec}
import org.goldenport.cncf.association.AssociationRepository.given
import org.goldenport.cncf.blob.BlobRepository.given

/*
 * @since   Apr. 29, 2026
 *  version Apr. 30, 2026
 * @version May.  1, 2026
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
    override def operationDefinitions: Vector[org.goldenport.cncf.operation.CmlOperationDefinition] =
      super.operationDefinitions.map {
        case definition if definition.name == "importPostTree" =>
          definition.copy(imageBinding = Some(CmlOperationImageBinding(
            acceptsArchiveBlobId = true,
            createsAttachment = true,
            roles = BlogComponentRuntimeFactory.ImageRoles,
            parameters = Vector("fileBundle", "archiveBlobId", "entityImages", "inlineImages"),
            sourceEntityIdMode = CmlOperationAssociationBinding.SourceEntityIdModeEntityCreateResult
          )))
        case definition if definition.name == "registerPost" =>
          definition.copy(imageBinding = Some(CmlOperationImageBinding(
            acceptsExistingBlobId = true,
            createsAttachment = true,
            roles = BlogComponentRuntimeFactory.ImageRoles,
            parameters = Vector("entityImages.existingBlobId", "inlineImages.existingBlobId"),
            sourceEntityIdMode = CmlOperationAssociationBinding.SourceEntityIdModeEntityCreateResult,
            targetIdParameters = Vector("entityImages.existingBlobId", "inlineImages.existingBlobId")
          )))
        case definition =>
          definition
      }
  }

  final class BlogServiceFactoryImpl extends BlogComponentComponent.BlogServiceFactory {
    import BlogComponentComponent.BlogService.*

    override def createImportPostTreeActionCall(
      core: ActionCall.Core,
      action: ImportPostTreeCommand
    ): ImportPostTreeActionCall =
      ImportPostTreeActionCallImpl(core, action)

    override def createRegisterPostActionCall(
      core: ActionCall.Core,
      action: RegisterPostCommand
    ): RegisterPostActionCall =
      RegisterPostActionCallImpl(core, action)

    override def createSaveEditorPostActionCall(
      core: ActionCall.Core,
      action: SaveEditorPostCommand
    ): SaveEditorPostActionCall =
      SaveEditorPostActionCallImpl(core, action)

    override def createGetPostActionCall(
      core: ActionCall.Core,
      action: GetPostQuery
    ): GetPostActionCall =
      GetPostActionCallImpl(core, action)

    override def createSearchPostsActionCall(
      core: ActionCall.Core,
      action: SearchPostsQuery
    ): SearchPostsActionCall =
      SearchPostsActionCallImpl(core, action)

    override def createAtomFeedActionCall(
      core: ActionCall.Core,
      action: AtomFeedQuery
    ): AtomFeedActionCall =
      AtomFeedActionCallImpl(core, action)

    override def createListImageBlobsActionCall(
      core: ActionCall.Core,
      action: ListImageBlobsQuery
    ): ListImageBlobsActionCall =
      ListImageBlobsActionCallImpl(core, action)

    override def createPublishPostActionCall(
      core: ActionCall.Core,
      action: PublishPostCommand
    ): PublishPostActionCall =
      PublishPostActionCallImpl(core, action)

    override def createDeactivatePostActionCall(
      core: ActionCall.Core,
      action: DeactivatePostCommand
    ): DeactivatePostActionCall =
      DeactivatePostActionCallImpl(core, action)
  }

  object BlogServiceFactoryImpl {
    def apply(): BlogServiceFactoryImpl = new BlogServiceFactoryImpl()
  }

  import BlogComponentComponent.BlogService.*

  private final case class ImportPostTreeActionCallImpl(
    core: ActionCall.Core,
    override val action: ImportPostTreeCommand
  ) extends ImportPostTreeActionCall with BlogRegistrationActionSupport {
    protected def build_Program: ExecUowM[OperationResponse] =
      for {
        sourceRecord <- exec_from(_with_current_author_if_missing(action.record))
        source <- _import_tree_source(action.record)
        response <- {
          for {
            draft <- exec_from(BlogFileTreeImportSupport.normalizeTreeWithInlineImageUrls(source.root) { img =>
              Some(BlobUrl.cncfRoute(_blob_id(img.treePath.getOrElse(img.sourcePath))).displayUrl)
            })
            register = _register_record(sourceRecord, draft)
            response <- _register(register, Some(source.root))
          } yield response
        }.guarantee(exec_from(_cleanup_import_tree_source(source)))
      } yield response
  }

  private final case class RegisterPostActionCallImpl(
    core: ActionCall.Core,
    override val action: RegisterPostCommand
  ) extends RegisterPostActionCall with BlogRegistrationActionSupport {
    protected def build_Program: ExecUowM[OperationResponse] =
      _register(action.record, None)
  }

  private final case class SaveEditorPostActionCallImpl(
    core: ActionCall.Core,
    override val action: SaveEditorPostCommand
  ) extends SaveEditorPostActionCall with BlogRegistrationActionSupport {
    protected def build_Program: ExecUowM[OperationResponse] =
      _save_editor_post(action.record)
  }

  private final case class GetPostActionCallImpl(
    core: ActionCall.Core,
    override val action: GetPostQuery
  ) extends GetPostActionCall with BlogReadActionSupport {
    protected def build_Program: ExecUowM[OperationResponse] =
      for {
        id <- exec_from(_entity_id(action.record, "id"))
        post <- _load_blog_post(id)
        _ <- exec_from(_public_visible(post))
        record <- exec_from(_public_post_record(post))
      } yield OperationResponse(record)
  }

  private final case class SearchPostsActionCallImpl(
    core: ActionCall.Core,
    override val action: SearchPostsQuery
  ) extends SearchPostsActionCall with BlogReadActionSupport {
    protected def build_Program: ExecUowM[OperationResponse] = {
      val storeQuery = Query.plan(Record.empty)
      val responseQuery = Query.plan(action.record, limit = _int(action.record, "limit"), offset = _int(action.record, "offset"))
      for {
        result <- _search_blog_posts(storeQuery)
        visible = _filter_public_search(result.data, action.record)
        page = _page(visible, action.record)
        records <- exec_from(_sequence(page.map(_public_post_record)))
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
    override val action: AtomFeedQuery
  ) extends AtomFeedActionCall with BlogReadActionSupport {
    protected def build_Program: ExecUowM[OperationResponse] = {
      val storeQuery = Query.plan(Record.empty)
      for {
        result <- _search_blog_posts(storeQuery)
        visible = _filter_public_search(result.data, action.record)
        page = _page_with_default(_sort_feed_posts(visible), action.record, 20)
        records <- exec_from(_sequence(page.map(_public_post_record)))
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
    override val action: ListImageBlobsQuery
  ) extends ListImageBlobsActionCall with BlogRegistrationActionSupport {
    protected def build_Program: ExecUowM[OperationResponse] =
      for {
        _ <- exec_from(_require_authenticated())
        response <- exec_from(_list_image_blobs(action.record))
      } yield OperationResponse(response)
  }

  private final case class PublishPostActionCallImpl(
    core: ActionCall.Core,
    override val action: PublishPostCommand
  ) extends PublishPostActionCall with BlogLifecycleActionSupport {
    protected def build_Program: ExecUowM[OperationResponse] =
      for {
        id <- exec_from(_entity_id(action.record, "id"))
        post <- entity_load[BlogPost](id)
        updated = post.copy(id = id, draftStatus = "published")
        _ <- entity_save(updated)
      } yield OperationResponse(updated.toRecord())
  }

  private final case class DeactivatePostActionCallImpl(
    core: ActionCall.Core,
    override val action: DeactivatePostCommand
  ) extends DeactivatePostActionCall with BlogLifecycleActionSupport {
    protected def build_Program: ExecUowM[OperationResponse] =
      for {
        id <- exec_from(_entity_id(action.record, "id"))
        post <- entity_load[BlogPost](id)
        updated = post.copy(id = id, activeStatus = "inactive")
        _ <- entity_save(updated)
      } yield OperationResponse(updated.toRecord())
  }

  private trait BlogRegistrationActionSupport { self: ActionCall =>
  protected final case class BlogImportTreeSource(root: Path, temporary: Boolean)

  protected final def _save_editor_post(record: Record): ExecUowM[OperationResponse] =
    for {
      author <- exec_from(_current_author_account_id())
      id = _optional_entity_id(record, BlogPost.collectionId, "id")
      slug <- exec_from(_required_string(record, "slug"))
      title <- exec_from(_required_string(record, "title"))
      content <- exec_from(_required_string(record, "content"))
      inlineSpecs <- exec_from(_inline_image_specs_from_editor_content(content))
      _ <- exec_from(_validate_editor_inline_specs(inlineSpecs))
      publish = _boolean(record, "publish").getOrElse(false)
      saved <- id match {
        case Some(postId) =>
          for {
            post <- entity_load[BlogPost](postId)
            _ <- exec_from(_assert_editor_author(post, author))
            updated = post.copy(
              id = postId,
              nameAttributes = org.simplemodeling.model.value.NameAttributes.Builder(post.nameAttributes).withTitle(title).build(),
              descriptiveAttributes = org.simplemodeling.model.value.DescriptiveAttributes.Builder(post.descriptiveAttributes).withContent(content).build(),
              slug = slug,
              draftStatus = (if (publish) "published" else "draft"),
              activeStatus = "active"
            )
            _ <- _save_blog_post_direct(updated)
          } yield (updated.id, updated.toRecord())
        case None =>
          for {
            post <- exec_from(BlogPostCreate.createC(
              Record.dataAuto(
                "id" -> _id(BlogPost.collectionId, slug),
                "slug" -> slug,
                "title" -> title,
                "content" -> content,
                "authorAccountId" -> author,
                "draftStatus" -> (if (publish) "published" else "draft"),
                "activeStatus" -> "active"
              )
            ))
            created <- entity_create(post)
          } yield (created.id, created.toRecord)
      }
      inlineCount <- _sync_editor_inline_images(saved._1, inlineSpecs)
    } yield OperationResponse(
      _post_response_record(saved._2, saved._1) ++ Record.dataAuto(
        "inlineImageCount" -> inlineCount
      )
    )

  protected final def _register(
    record: Record,
    treeRoot: Option[Path]
  ): ExecUowM[OperationResponse] =
    for {
      entityImageSpecs <- exec_from(_entity_image_specs(record))
      inlineImageSpecs <- exec_from(_inline_image_specs(record))
      _ <- exec_from(_validate_registration_image_specs(entityImageSpecs, inlineImageSpecs, treeRoot))
      post <- exec_from(_blog_post(record))
      created <- entity_create(post)
      postId = created.id
      entityImages <- _create_entity_images(postId, entityImageSpecs, treeRoot)
      inlineImages <- _create_inline_images(postId, inlineImageSpecs, treeRoot)
    } yield {
      OperationResponse(
        _post_response_record(created.toRecord, created.id) ++ Record.dataAuto(
          "entityImageCount" -> entityImages.size,
          "inlineImageCount" -> inlineImages.size
        )
      )
    }

  private def _validate_registration_image_specs(
    entityImages: Vector[BlogEntityImageSpec],
    inlineImages: Vector[BlogInlineImageSpec],
    treeRoot: Option[Path]
  ): Consequence[Unit] =
    treeRoot match {
      case Some(_) =>
        Consequence.unit
      case None =>
        entityImages.zipWithIndex.collectFirst {
          case (spec, index) if spec.existingBlobId.isEmpty =>
            Consequence.argumentInvalid(s"registerPost entityImages[$index] requires existingBlobId; local path payloads must be imported through importPostTree")
        }.orElse {
          inlineImages.zipWithIndex.collectFirst {
            case (spec, index) if spec.existingBlobId.isEmpty =>
              Consequence.argumentInvalid(s"registerPost inlineImages[$index] requires existingBlobId; local path payloads must be imported through importPostTree")
          }
        }.getOrElse(Consequence.unit)
    }

  private def _post_response_record(record: Record, id: EntityId): Record =
    Record.dataAuto(
      "id" -> id,
      "entity_id" -> id.value,
      "draft_status" -> _string(record, "draftStatus", "draft_status").orNull,
      "active_status" -> _string(record, "activeStatus", "active_status").orNull
    )

  private def _save_blog_post_direct(post: BlogPost): ExecUowM[Unit] =
    exec_from {
      given org.goldenport.cncf.context.ExecutionContext = executionContext
      EntityStore.standard().save(post)
    }

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

  private def _create_inline_images(
    postId: EntityId,
    specs: Vector[BlogInlineImageSpec],
    treeRoot: Option[Path]
  ): ExecUowM[Vector[BlogInlineImageCreate]] =
    specs.zipWithIndex.foldLeft(exec_pure(Vector.empty[BlogInlineImageCreate])) {
      case (z, (spec, index)) =>
        z.flatMap { images =>
          for {
            blobId <- _ensure_inline_blob(spec, treeRoot, index)
            association <- exec_from(_blob_association(postId, blobId, "inline", spec.sortOrder.orElse(Some(index)), _inline_image_attributes(spec), Some(index.toString)))
            _ <- entity_create(association)
            inline <- exec_from(_blog_inline_image(postId, Some(blobId), spec, index))
            _ <- entity_create(inline)
          } yield images :+ inline
        }
    }

  private def _sync_editor_inline_images(
    postId: EntityId,
    specs: Vector[BlogInlineImageSpec]
  ): ExecUowM[Int] =
    for {
      _ <- _delete_existing_inline_images(postId)
      _ <- exec_from(_delete_existing_inline_associations(postId))
      images <- _create_inline_images(postId, specs, None)
    } yield images.size

  private def _validate_editor_inline_specs(
    specs: Vector[BlogInlineImageSpec]
  ): Consequence[Unit] =
    specs.foldLeft(Consequence.unit) {
      case (z, spec) =>
        z.flatMap { _ =>
          spec.existingBlobId
            .map(_verify_blob)
            .getOrElse(Consequence.argumentInvalid("editor inline images require existing Blob ids"))
        }
    }

  private def _assert_editor_author(
    post: BlogPost,
    author: EntityId
  ): Consequence[Unit] =
    if (post.authorAccountId.value == author.value)
      Consequence.unit
    else
      Consequence.operationInvalid("Blog editor update requires the post author")

  private def _delete_existing_inline_images(postId: EntityId): ExecUowM[Unit] =
    for {
      values <- exec_from(_find_inline_images(postId))
      _ <- values.foldLeft(exec_pure(())) { case (z, image) =>
        z.flatMap(_ => entity_delete_hard(image.id))
      }
    } yield ()

  private def _find_inline_images(postId: EntityId): Consequence[Vector[BlogInlineImage]] = {
    given org.goldenport.cncf.context.ExecutionContext = executionContext
    EntityStore.standard()
      .search[BlogInlineImage](EntityQuery(BlogInlineImage.collectionId, Query.plan(Record.empty), EntitySearchScope.Store))
      .map(_.data.map(_normalize_inline_image).filter(_.blogPostId == postId))
  }

  private def _normalize_inline_image(image: BlogInlineImage): BlogInlineImage =
    image.copy(
      id = _with_collection(image.id, BlogInlineImage.collectionId),
      blogPostId = _with_collection(image.blogPostId, BlogPost.collectionId),
      blobId = image.blobId.map(_with_collection(_, BlobRepository.CollectionId))
    )

  private def _delete_existing_inline_associations(postId: EntityId): Consequence[Unit] = {
    given org.goldenport.cncf.context.ExecutionContext = executionContext
    val repository = AssociationRepository.entityStore(AssociationStoragePolicy.blobAttachmentDefault)
    repository.list(AssociationFilter(
      domain = AssociationDomain.BlobAttachment,
      sourceEntityId = Some(_association_source_id(postId)),
      targetKind = Some("blob"),
      role = Some("inline")
    )).flatMap { associations =>
      associations.foldLeft(Consequence.unit) {
        case (z, association) =>
          val normalized = association.copy(id = _with_collection(association.id, AssociationStoragePolicy.BlobAttachmentCollection))
          z.flatMap(_ => repository.delete(normalized))
      }
    }
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

  private def _ensure_inline_blob(
    spec: BlogInlineImageSpec,
    treeRoot: Option[Path],
    index: Int
  ): ExecUowM[EntityId] =
    spec.existingBlobId match {
      case Some(id) =>
        for {
          _ <- exec_from(_verify_blob(id))
        } yield id
      case None if treeRoot.isEmpty =>
        exec_from(Consequence.argumentInvalid("registerPost inlineImages require existingBlobId; local path payloads must be imported through importPostTree"))
      case None =>
        val objectKey = spec.path.orElse(spec.clientId).getOrElse(s"inline-$index")
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

  private def _blog_post(record: Record): Consequence[BlogPostCreate] = {
    val slug = _required_string(record, "slug")
    val content = _required_string(record, "content")
    val publish = _boolean(record, "publish").getOrElse(false)
    for {
      s <- slug
      t <- _string(record, "title").map(Consequence.success).getOrElse(Consequence.success(s))
      c <- content
      author <- _entity_id(record, "authorAccountId", "author_account_id")
      post <- BlogPostCreate.createC(
        record ++ Record.dataAuto(
          "id" -> record.getAny("id").getOrElse(_id(BlogPost.collectionId, s)),
          "slug" -> s,
          "title" -> t,
          "content" -> c,
          "authorAccountId" -> author,
          "draftStatus" -> (if (publish) "published" else "draft"),
          "activeStatus" -> "active"
        )
      )
    } yield post
  }

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
        id = Some(_id(AssociationStoragePolicy.BlobAttachmentCollection, Vector(Some(postId.display), Some(role), discriminator, Some(blobId.display)).flatten.mkString("-"))),
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
    postId.display

  private def _blog_inline_image(
    postId: EntityId,
    blobId: Option[EntityId],
    spec: BlogInlineImageSpec,
    index: Int
  ): Consequence[BlogInlineImageCreate] =
    BlogInlineImageCreate.createC(
      Record.dataAuto(
        "id" -> _id(BlogInlineImage.collectionId, s"${postId.display}-inline-$index"),
        "blogPostId" -> postId,
        "sourceUrl" -> spec.path.orElse(spec.clientId).orElse(blobId.map(_.value)).getOrElse(s"inline-$index"),
        "altText" -> spec.altText,
        "titleText" -> spec.titleText,
        "sortOrder" -> spec.sortOrder.getOrElse(index),
        "synchronized" -> blobId.isDefined
      )
    )

  private def _entity_image_attributes(spec: BlogEntityImageSpec): Map[String, String] =
    Vector(
      spec.caption.map("caption" -> _),
      spec.checksum.map("checksum" -> _),
      spec.path.map("sourcePath" -> _)
    ).flatten.toMap

  private def _inline_image_attributes(spec: BlogInlineImageSpec): Map[String, String] =
    Vector(
      spec.clientId.map("clientId" -> _),
      spec.path.map("sourcePath" -> _),
      spec.altText.map("altText" -> _),
      spec.titleText.map("titleText" -> _),
      spec.checksum.map("checksum" -> _)
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

  protected final def _register_record(source: Record, draft: BlogRegistrationDraft): Record =
    Record.dataAuto(
      "slug" -> draft.slug.getOrElse(_slug_from_title(draft.title)),
      "title" -> draft.title,
      "content" -> draft.content,
      "authorAccountId" -> source.getAny("authorAccountId").orElse(source.getAny("author_account_id")).orNull,
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
      },
      "inlineImages" -> draft.inlineImages.map { image =>
        Record.dataAuto(
          "path" -> image.treePath.getOrElse(image.sourcePath),
          "altText" -> image.altText,
          "titleText" -> image.titleText,
          "sortOrder" -> image.index
        )
      }
    )

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

  private def _inline_image_specs(record: Record): Consequence[Vector[BlogInlineImageSpec]] =
    _record_vector(record, "inlineImages", "inline_images").foldLeft(Consequence.success(Vector.empty[BlogInlineImageSpec])) {
      case (z, item) =>
        z.flatMap { xs =>
          if (_has_any(item, "existingImageId", "existing_image_id"))
            Consequence.argumentInvalid("existingImageId is no longer supported; use existingBlobId")
          else
            BlogInlineImageSpec.createC(item).map(xs :+ _)
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
        case value => EntityId.parse(value.toString.trim).toOption
      }.orElse {
        record.getAsC[EntityId](name).toOption.flatten
      }
    }.nextOption()

  private def _with_collection(id: EntityId, collection: EntityCollectionId): EntityId =
    if (id.collection == collection)
      id
    else
      EntityId(id.major, id.minor, collection, id.timestamp, id.entropy)

  protected final def _with_current_author_if_missing(record: Record): Consequence[Record] =
    if (_has_any(record, "authorAccountId", "author_account_id"))
      Consequence.success(record)
    else
      _current_author_account_id().map { author =>
        record ++ Record.dataAuto("authorAccountId" -> author)
      }

  protected final def _current_author_account_id(): Consequence[EntityId] = {
    val subject = SecuritySubject.current(using executionContext)
    if (!subject.isAuthenticated) {
      Consequence.operationInvalid("Blog authoring requires an authenticated session")
    } else {
      val candidates = Vector(
        subject.attributes.get("authorAccountId"),
        subject.attributes.get("author_account_id"),
        subject.attributes.get("userAccountId"),
        subject.attributes.get("user_account_id"),
        subject.attributes.get("accountId"),
        subject.attributes.get("account_id"),
        Some(subject.subjectId)
      ).flatten.map(_.trim).filter(_.nonEmpty)
      candidates.flatMap(x => EntityId.parse(x).toOption).headOption match {
        case Some(id) => Consequence.success(id)
        case None =>
          val collection = EntityCollectionId("textus", "account", "account")
          Consequence.success(EntityId("textus", _safe_id(subject.subjectId, collection), collection))
      }
    }
  }

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

  private def _entity_id(record: Record, names: String*): Consequence[EntityId] =
    _optional_entity_id(record, names*) match {
      case Some(id) => Consequence.success(id)
      case None => Consequence.argumentMissing(names.head)
    }

  private def _required_string(record: Record, names: String*): Consequence[String] =
    _string(record, names*).map(Consequence.success).getOrElse(Consequence.argumentMissing(names.head))

  private def _string(record: Record, names: String*): Option[String] =
    names.iterator.flatMap(record.getAny).map(_.toString.trim).find(_.nonEmpty)

  private def _boolean(record: Record, names: String*): Option[Boolean] =
    names.iterator.flatMap(record.getAny).flatMap {
      case b: java.lang.Boolean => Some(b.booleanValue)
      case s: String => scala.util.Try(s.trim.toBoolean).toOption
      case other => scala.util.Try(other.toString.trim.toBoolean).toOption
    }.nextOption()

  private def _int(record: Record, names: String*): Option[Int] =
    names.iterator.flatMap(record.getAny).flatMap {
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
    _safe_id(title, BlogPost.collectionId)

  private def _inline_image_specs_from_editor_content(content: String): Consequence[Vector[BlogInlineImageSpec]] = {
    val records = BlogComponentRuntimeFactory.extractBlobContentImageIds(content).zipWithIndex.map { case (blobId, index) =>
      Record.dataAuto(
        "existingBlobId" -> blobId,
        "clientId" -> blobId.display,
        "sortOrder" -> index
      )
    }
    _inline_image_specs(Record.dataAuto("inlineImages" -> records))
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
    protected final def _entity_id(record: Record, names: String*): Consequence[EntityId] =
      _optional_entity_id(record, BlogPost.collectionId, names*) match {
        case Some(id) => Consequence.success(id)
        case None => Consequence.argumentMissing(names.head)
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

    protected final def _public_visible(post: BlogPost): Consequence[Unit] =
      if (_is_public(post))
        Consequence.unit
      else
        Consequence.entityNotFound(s"public blog post not found: ${post.id.value}")

    protected final def _load_blog_post(id: EntityId): ExecUowM[BlogPost] =
      ConsequenceT
        .liftF(Free.liftF[UnitOfWorkOp, Option[BlogPost]](UnitOfWorkOp.EntityStoreLoadDirect(
          id,
          summon[EntityPersistent[BlogPost]]
        )))
        .flatMap { value =>
          exec_from(value.map(Consequence.success).getOrElse(Consequence.entityNotFound(s"blog post not found: ${id.value}")))
        }

    protected final def _search_blog_posts(
      query: Query[?]
    ): ExecUowM[SearchResult[BlogPost]] =
      ConsequenceT.liftF(Free.liftF(UnitOfWorkOp.EntityStoreSearchDirect(
        EntityQuery(BlogPost.collectionId, query, EntitySearchScope.Store),
        summon[EntityPersistent[BlogPost]],
        None
      )))

    protected final def _filter_public_search(
      posts: Vector[BlogPost],
      record: Record
    ): Vector[BlogPost] = {
      val publicPosts = (_string(record, "draftStatus", "draft_status"), _string(record, "activeStatus", "active_status")) match {
        case (Some(x), _) if x != "published" => Vector.empty
        case (_, Some(x)) if x != "active" => Vector.empty
        case _ => posts.filter(_is_public)
      }
      _string(record, "text").map(_normalize_text).fold(publicPosts) { needle =>
        publicPosts.filter(post => _search_text(post).contains(needle))
      }
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

    protected final def _public_post_record(post: BlogPost): Consequence[Record] =
      _image_projection(post.id).map { projection =>
        val representative = projection.representativeImage
        post.toRecord() ++ Record.dataAuto(
          "representativeBlobId" -> representative.map(_.metadata.id),
          "representativeBlobUrl" -> representative.map(_.metadata.accessUrl.displayUrl),
          "imageRoles" -> projection.images.map(_image_role_record)
        )
      }

    private def _image_projection(postId: EntityId): Consequence[BlobProjectionResult] = {
      given org.goldenport.cncf.context.ExecutionContext = executionContext
      val associations = AssociationRepository.entityStore(AssociationStoragePolicy.blobAttachmentDefault)
      val blobs = BlobRepository.entityStore()
      BlobProjection.entityImageProjection(postId.display)(
        BlobProjection.Loaders(
          listAssociations = filter => associations.list(filter),
          loadBlob = id => blobs.get(id)
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

    private def _is_public(post: BlogPost): Boolean =
      post.draftStatus == "published" && post.activeStatus == "active"

    private def _search_text(post: BlogPost): String = {
      val record = post.toRecord()
      Vector("slug", "title", "headline", "summary", "description", "content")
        .flatMap(record.getAny)
        .map(_.toString)
        .map(_normalize_text)
        .mkString("\n")
    }

    private def _normalize_text(value: String): String =
      value.toLowerCase(java.util.Locale.ROOT)

    protected final def _string(record: Record, names: String*): Option[String] =
      names.iterator.flatMap(record.getAny).map(_.toString.trim).find(_.nonEmpty)

    protected final def _int(record: Record, names: String*): Option[Int] =
      names.iterator.flatMap(record.getAny).flatMap {
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
  val ImageRoles: Vector[String] =
    Vector("primary", "cover", "thumbnail", "gallery", "inline")

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
}
