package org.simplemodeling.textus.blog

import java.nio.file.{Files, Path, Paths}
import cats.syntax.all.*
import org.goldenport.*
import org.goldenport.bag.Bag
import org.goldenport.cncf.action.ActionCall
import org.goldenport.cncf.blob.*
import org.goldenport.cncf.component.Component
import org.goldenport.cncf.component.ComponentCreate
import org.goldenport.cncf.unitofwork.ExecUowM
import org.goldenport.datatype.ContentType
import org.goldenport.protocol.operation.OperationResponse
import org.goldenport.record.Record
import org.simplemodeling.model.datatype.{EntityCollectionId, EntityId}
import org.simplemodeling.textus.blog.entity.{BlogInlineImage, BlogPost, EntityImageBinding, ImageAsset}
import org.simplemodeling.textus.blog.entity.create.{
  BlogInlineImage as BlogInlineImageCreate,
  BlogPost as BlogPostCreate,
  EntityImageBinding as EntityImageBindingCreate,
  ImageAsset as ImageAssetCreate
}
import org.simplemodeling.textus.blog.value.{BlogEntityImageSpec, BlogInlineImageSpec}
import org.goldenport.cncf.blob.BlobRepository.given

/*
 * @since   Apr. 29, 2026
 * @version Apr. 29, 2026
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
  override val Blog: BlogComponentComponent.BlogServiceFactory =
    BlogServiceFactoryImpl()

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
      entityImages <- _create_entity_images(created.id, entityImageSpecs, treeRoot)
      inlineImages <- _create_inline_images(created.id, inlineImageSpecs, treeRoot)
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
          case (spec, index) if spec.existingImageId.isEmpty && spec.existingBlobId.isEmpty =>
            Consequence.argumentInvalid(s"registerPost entityImages[$index] requires existingImageId or existingBlobId; local path payloads must be imported through importPostTree")
        }.orElse {
          inlineImages.zipWithIndex.collectFirst {
            case (spec, index) if spec.existingImageId.isEmpty && spec.existingBlobId.isEmpty =>
              Consequence.argumentInvalid(s"registerPost inlineImages[$index] requires existingImageId or existingBlobId; local path payloads must be imported through importPostTree")
          }
        }.getOrElse(Consequence.unit)
    }

  private def _create_entity_images(
    postId: EntityId,
    specs: Vector[BlogEntityImageSpec],
    treeRoot: Option[Path]
  ): ExecUowM[Vector[EntityImageBindingCreate]] =
    specs.zipWithIndex.foldLeft(exec_pure(Vector.empty[EntityImageBindingCreate])) {
      case (z, (spec, index)) =>
        z.flatMap { bindings =>
          for {
            imageId <- _ensure_image_asset(spec, treeRoot)
            binding <- exec_from(_entity_image_binding(postId, imageId, spec, index))
            _ <- entity_create(binding)
          } yield bindings :+ binding
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
            imageId <- _ensure_inline_image_asset(spec, treeRoot, index)
            inline <- exec_from(_blog_inline_image(postId, imageId, spec, index))
            _ <- entity_create(inline)
          } yield images :+ inline
        }
    }

  private def _ensure_image_asset(
    spec: BlogEntityImageSpec,
    treeRoot: Option[Path]
  ): ExecUowM[EntityId] =
    spec.existingImageId match {
      case Some(id) => exec_pure(id)
      case None if treeRoot.isEmpty && spec.existingBlobId.isEmpty =>
        exec_from(Consequence.argumentInvalid("registerPost entityImages require existingImageId or existingBlobId; local path payloads must be imported through importPostTree"))
      case None =>
        val objectKey = spec.existingBlobId.map(_.value).orElse(spec.path).getOrElse(spec.role)
        val imageId = _image_id(objectKey)
        val blobId = spec.existingBlobId.getOrElse(_blob_id(objectKey))
        for {
          _ <- _create_blob_if_local(blobId, spec.path, spec.contentType, treeRoot)
          image <- exec_from(_image_asset(imageId, objectKey, spec.contentType, None, None, spec.checksum))
          _ <- entity_create(image)
        } yield imageId
    }

  private def _ensure_inline_image_asset(
    spec: BlogInlineImageSpec,
    treeRoot: Option[Path],
    index: Int
  ): ExecUowM[Option[EntityId]] =
    spec.existingImageId match {
      case Some(id) => exec_pure(Some(id))
      case None if treeRoot.isEmpty && spec.existingBlobId.isEmpty =>
        exec_from(Consequence.argumentInvalid("registerPost inlineImages require existingImageId or existingBlobId; local path payloads must be imported through importPostTree"))
      case None =>
        val objectKey = spec.existingBlobId.map(_.value).orElse(spec.path).orElse(spec.clientId).getOrElse(s"inline-$index")
        val imageId = _image_id(objectKey)
        val blobId = spec.existingBlobId.getOrElse(_blob_id(objectKey))
        for {
          _ <- _create_blob_if_local(blobId, spec.path, spec.contentType, treeRoot)
          image <- exec_from(_image_asset(imageId, objectKey, spec.contentType, spec.altText, spec.titleText, spec.checksum))
          _ <- entity_create(image)
        } yield Some(imageId)
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
          "id" -> _post_id(s),
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

  private def _entity_image_binding(
    postId: EntityId,
    imageId: EntityId,
    spec: BlogEntityImageSpec,
    index: Int
  ): Consequence[EntityImageBindingCreate] =
    EntityImageBindingCreate.createC(
      Record.dataAuto(
        "id" -> _id(EntityImageBinding.collectionId, s"${postId.value}-${spec.role}-$index"),
        "entityName" -> "blog_post",
        "entityId" -> postId,
        "imageId" -> imageId,
        "role" -> spec.role,
        "sortOrder" -> spec.sortOrder,
        "caption" -> spec.caption
      )
    )

  private def _blog_inline_image(
    postId: EntityId,
    imageId: Option[EntityId],
    spec: BlogInlineImageSpec,
    index: Int
  ): Consequence[BlogInlineImageCreate] =
    BlogInlineImageCreate.createC(
      Record.dataAuto(
        "id" -> _id(BlogInlineImage.collectionId, s"${postId.value}-inline-$index"),
        "blogPostId" -> postId,
        "imageId" -> imageId,
        "sourceUrl" -> spec.path.orElse(spec.clientId).orElse(imageId.map(_.value)).getOrElse(s"inline-$index"),
        "altText" -> spec.altText,
        "titleText" -> spec.titleText,
        "sortOrder" -> spec.sortOrder.getOrElse(index),
        "synchronized" -> imageId.isDefined
      )
    )

  private def _image_asset(
    id: EntityId,
    objectKey: String,
    contentType: Option[String],
    altText: Option[String],
    titleText: Option[String],
    checksum: Option[String]
  ): Consequence[ImageAssetCreate] =
    ImageAssetCreate.createC(
      Record.dataAuto(
        "id" -> id,
        "title" -> titleText.getOrElse(objectKey),
        "objectKey" -> objectKey,
        "contentType" -> contentType.getOrElse(_content_type(objectKey)),
        "altText" -> altText,
        "checksum" -> checksum
      )
    )

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
      case (z, item) => z.flatMap(xs => BlogEntityImageSpec.createC(item).map(xs :+ _))
    }

  private def _inline_image_specs(record: Record): Consequence[Vector[BlogInlineImageSpec]] =
    _record_vector(record, "inlineImages", "inline_images").foldLeft(Consequence.success(Vector.empty[BlogInlineImageSpec])) {
      case (z, item) => z.flatMap(xs => BlogInlineImageSpec.createC(item).map(xs :+ _))
    }

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

  private def _post_id(slug: String): EntityId =
    _id(BlogPost.collectionId, slug)

  private def _image_id(key: String): EntityId =
    _id(ImageAsset.collectionId, key)

  protected final def _blob_id(key: String): EntityId =
    _id(BlobRepository.CollectionId, key)

  private def _id(collection: EntityCollectionId, key: String): EntityId =
    EntityId(collection.major, _safe_id(key), collection)

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
}
