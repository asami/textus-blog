package org.simplemodeling.textus.blog

import java.nio.file.{Files, Path, Paths}
import java.util.UUID
import cats.free.Free
import cats.syntax.all.*
import org.goldenport.*
import org.goldenport.bag.Bag
import org.goldenport.cncf.action.ActionCall
import org.goldenport.cncf.association.{AssociationCreate, AssociationDomain, AssociationRepository, AssociationStoragePolicy}
import org.goldenport.cncf.blob.*
import org.goldenport.cncf.component.{Component, ComponentCreate}
import org.goldenport.cncf.directive.{Query, SearchResult}
import org.goldenport.cncf.entity.{EntityPersistent, EntityQuery, EntitySearchScope}
import org.goldenport.cncf.operation.CmlOperationImageBinding
import org.goldenport.cncf.unitofwork.{ExecUowM, UnitOfWorkOp}
import org.goldenport.datatype.ContentType
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
 * @version Apr. 30, 2026
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
            parameters = Vector("archiveBlobId", "entityImages", "inlineImages")
          )))
        case definition if definition.name == "registerPost" =>
          definition.copy(imageBinding = Some(CmlOperationImageBinding(
            acceptsExistingBlobId = true,
            createsAttachment = true,
            roles = BlogComponentRuntimeFactory.ImageRoles,
            parameters = Vector("entityImages.existingBlobId", "inlineImages.existingBlobId")
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
        source <- _import_tree_source(action.record)
        response <- {
          for {
            draft <- exec_from(BlogFileTreeImportSupport.normalizeTreeWithInlineImageUrls(source.root) { img =>
              Some(BlobUrl.cncfRoute(_blob_id(img.treePath.getOrElse(img.sourcePath))).displayUrl)
            })
            register = _register_record(action.record, draft)
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

  private final case class PublishPostActionCallImpl(
    core: ActionCall.Core,
    override val action: PublishPostCommand
  ) extends PublishPostActionCall with BlogLifecycleActionSupport {
    protected def build_Program: ExecUowM[OperationResponse] =
      for {
        id <- exec_from(_entity_id(action.record, "id"))
        post <- entity_load[BlogPost](id)
        updated = post.copy(draftStatus = "published")
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
        updated = post.copy(activeStatus = "inactive")
        _ <- entity_save(updated)
      } yield OperationResponse(updated.toRecord())
  }

  private trait BlogRegistrationActionSupport { self: ActionCall =>
  protected final case class BlogImportTreeSource(root: Path, temporary: Boolean)

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
        created.toRecord ++ Record.dataAuto(
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
            association <- exec_from(_blob_association(postId, blobId, "inline", spec.sortOrder.orElse(Some(index)), _inline_image_attributes(spec)))
            _ <- entity_create(association)
            inline <- exec_from(_blog_inline_image(postId, Some(blobId), spec, index))
            _ <- entity_create(inline)
          } yield images :+ inline
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
    attributes: Map[String, String]
  ): Consequence[AssociationCreate] =
    Consequence.success(
      AssociationCreate(
        id = None,
        associationId = UUID.randomUUID().toString,
        sourceEntityId = postId.value,
        targetEntityId = blobId.value,
        targetKind = Some("blob"),
        role = role,
        associationDomain = AssociationDomain.BlobAttachment,
        sortOrder = sortOrder,
        attributes = attributes,
        collectionId = AssociationStoragePolicy.BlobAttachmentCollection
      )
    )

  private def _blog_inline_image(
    postId: EntityId,
    blobId: Option[EntityId],
    spec: BlogInlineImageSpec,
    index: Int
  ): Consequence[BlogInlineImageCreate] =
    BlogInlineImageCreate.createC(
      Record.dataAuto(
        "id" -> _id(BlogInlineImage.collectionId, s"${postId.value}-inline-$index"),
        "blogPostId" -> postId,
        "blobId" -> blobId,
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
    _string(record, "treeRootPath", "tree_root_path")
      .map { _ =>
        exec_from(_tree_root(record).map(BlogImportTreeSource(_, temporary = false)))
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
      .getOrElse(Consequence.operationInvalid("importPostTree requires treeRootPath or archiveBlobId"))

  private def _archive_blob_id(record: Record): Consequence[EntityId] =
    _optional_entity_id(record, "archiveBlobId", "archive_blob_id")
      .map(Consequence.success)
      .getOrElse(Consequence.argumentMissing("archiveBlobId"))

  private def _optional_entity_id(record: Record, names: String*): Option[EntityId] =
    names.iterator.flatMap { name =>
      record.getAsC[EntityId](name).toOption.flatten.orElse {
        record.getAny(name).flatMap {
          case id: EntityId => Some(id)
          case value => EntityId.parse(value.toString.trim).toOption
        }
      }
    }.nextOption()

  private def _component: Consequence[Component] =
    component match {
      case Some(value) => Consequence.success(value)
      case None => Consequence.serviceUnavailable("BlogComponent is not attached to a subsystem component")
    }

  private def _entity_id(record: Record, names: String*): Consequence[EntityId] =
    names.iterator.flatMap(name => record.getAsC[EntityId](name).toOption.flatten).nextOption() match {
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

  protected final def _blob_id(key: String): EntityId =
    _id(BlobRepository.CollectionId, key)

  private def _id(collection: EntityCollectionId, key: String): EntityId =
    EntityId(
      collection.major,
      _safe_id(key),
      collection,
      Some(UniversalId.StableTimestamp),
      Some(UniversalId.StableEntropy)
    )

  private def _safe_id(value: String): String =
    BlobStoreSupport.safeSegment(value).replace("-", "_").replace(".", "_") match {
      case s if s.headOption.exists(_.isLetter) => s
      case s => s"id_${s}"
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
    _safe_id(title)
  }

  private trait BlogReadActionSupport { self: ActionCall =>
    protected final def _entity_id(record: Record, names: String*): Consequence[EntityId] =
      names.iterator.flatMap(name => record.getAsC[EntityId](name).toOption.flatten).nextOption() match {
        case Some(id) => Consequence.success(id)
        case None => Consequence.argumentMissing(names.head)
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
      BlobProjection.entityImageProjection(postId.value)(
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
}
