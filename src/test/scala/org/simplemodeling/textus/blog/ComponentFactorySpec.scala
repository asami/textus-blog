package org.simplemodeling.textus.blog

import java.io.ByteArrayOutputStream
import java.util.zip.{ZipEntry, ZipOutputStream}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.goldenport.Consequence
import org.goldenport.cncf.action.{ActionCall, CommandExecutionMode}
import org.goldenport.bag.Bag
import org.goldenport.cncf.association.{AssociationDomain, AssociationFilter, AssociationRepository, AssociationStoragePolicy}
import org.goldenport.cncf.blob.*
import org.goldenport.cncf.component.{ComponentCreate, ComponentOrigin}
import org.goldenport.cncf.context.{ExecutionContext, SecurityContext}
import org.goldenport.cncf.entity.EntityStore
import org.goldenport.cncf.operation.{CmlEntityRelationshipDefinition, CmlOperationAssociationBinding}
import org.goldenport.cncf.subsystem.DefaultSubsystemFactory
import org.goldenport.datatype.ContentType
import org.goldenport.protocol.Request
import org.goldenport.protocol.operation.OperationResponse
import org.goldenport.record.Record
import org.simplemodeling.model.datatype.{EntityCollectionId, EntityId}
import org.simplemodeling.textus.blog.entity.BlogPost

/*
 * @since   Apr. 29, 2026
 * @version Apr. 30, 2026
 * @author  ASAMI, Tomoharu
 */
final class ComponentFactorySpec extends AnyWordSpec with Matchers {
  import BlogComponentComponent.BlogService.*

  "ComponentFactory" should {
    "wire executable bulk blog operations instead of generated placeholders" in {
      val factory = new ComponentFactory
      val importAction = ImportPostTreeCommand(null, Record.empty)
      val registerAction = RegisterPostCommand(null, Record.empty)
      val importCore = ActionCall.Core(importAction, null.asInstanceOf[ExecutionContext], None, None)
      val registerCore = ActionCall.Core(registerAction, null.asInstanceOf[ExecutionContext], None, None)

      factory.Blog.createImportPostTreeActionCall(importCore, importAction).getClass.getName should include ("ImportPostTreeActionCallImpl")
      factory.Blog.createRegisterPostActionCall(registerCore, registerAction).getClass.getName should include ("RegisterPostActionCallImpl")
    }

    "publish image binding metadata for blog import and registration operations" in {
      val subsystem = DefaultSubsystemFactory.default(Some("command"))
      val component = (new ComponentFactory)
        .create(ComponentCreate(subsystem, ComponentOrigin.Repository("test")))
        .primary
      val definitions = component.operationDefinitions.map(x => x.name -> x).toMap
      val importBinding = definitions("importPostTree").imageBinding.getOrElse(fail("importPostTree imageBinding is missing"))
      val registerBinding = definitions("registerPost").imageBinding.getOrElse(fail("registerPost imageBinding is missing"))

      importBinding.acceptsArchiveBlobId shouldBe true
      importBinding.createsAttachment shouldBe true
      importBinding.roles should contain allOf ("primary", "cover", "thumbnail", "gallery", "inline")
      importBinding.parameters should contain ("archiveBlobId")
      registerBinding.acceptsExistingBlobId shouldBe true
      registerBinding.createsAttachment shouldBe true
      registerBinding.roles should contain allOf ("primary", "cover", "thumbnail", "gallery", "inline")
      registerBinding.parameters should contain ("entityImages.existingBlobId")
      registerBinding.parameters should contain ("inlineImages.existingBlobId")
      registerBinding.sourceEntityIdMode shouldBe CmlOperationAssociationBinding.SourceEntityIdModeEntityCreateResult
      registerBinding.toAssociationBinding.domain shouldBe "blob_attachment"
      registerBinding.toAssociationBinding.targetKind shouldBe "blob"
    }

    "publish Blog image relationship metadata from CML" in {
      val subsystem = DefaultSubsystemFactory.default(Some("command"))
      val component = (new ComponentFactory)
        .create(ComponentCreate(subsystem, ComponentOrigin.Repository("test")))
        .primary
      val relationships = component.relationshipDefinitions.map(x => x.name -> x).toMap
      val images = relationships.getOrElse("BlogPost.images", fail("BlogPost.images relationship is missing"))
      val inline = relationships.getOrElse("BlogPost.inlineImages", fail("BlogPost.inlineImages relationship is missing"))

      images.kind shouldBe CmlEntityRelationshipDefinition.KindAssociation
      images.sourceEntityName shouldBe "BlogPost"
      images.targetEntityName shouldBe "Blob"
      images.storageMode shouldBe CmlEntityRelationshipDefinition.StorageAssociationRecord
      images.associationDomain shouldBe Some("blob_attachment")
      images.targetKind shouldBe Some("blob")
      images.multiplicity shouldBe Some("one-to-many")
      images.lifecyclePolicy shouldBe Some(CmlEntityRelationshipDefinition.LifecycleIndependent)

      inline.kind shouldBe CmlEntityRelationshipDefinition.KindComposition
      inline.sourceEntityName shouldBe "BlogPost"
      inline.targetEntityName shouldBe "BlogInlineImage"
      inline.storageMode shouldBe CmlEntityRelationshipDefinition.StorageChildParentIdField
      inline.parentIdField shouldBe Some("blogPostId")
      inline.sortOrderField shouldBe Some("sortOrder")
      inline.multiplicity shouldBe Some("one-to-many")
      inline.lifecyclePolicy shouldBe Some(CmlEntityRelationshipDefinition.LifecycleDependent)
    }

    "publish the BundleFactory service entry for runtime discovery" in {
      val resourceName = "META-INF/services/org.goldenport.cncf.component.Component$BundleFactory"
      val stream = Option(getClass.getClassLoader.getResourceAsStream(resourceName))

      stream.isDefined shouldBe true
      val content =
        try scala.io.Source.fromInputStream(stream.get, "UTF-8").mkString
        finally stream.foreach(_.close())

      content should include ("org.simplemodeling.textus.blog.ComponentFactory")
    }

    "import a production Blog ZIP archive Blob and register image payloads" in {
      val subsystem = DefaultSubsystemFactory.default(Some("command"))
      val component = (new ComponentFactory)
        .create(ComponentCreate(subsystem, ComponentOrigin.Repository("test")))
        .primary
      given ExecutionContext = ExecutionContext.withFrameworkCommandExecutionMode(
        component.logic.executionContext(),
        CommandExecutionMode.SyncJob
      )
      val archiveId = EntityId("cncf", "blog_archive_1", BlobRepository.CollectionId)
      val archive = _zip(Vector(
        "META-INF/blog.yaml" ->
          """slug: archive-post
            |entryHtmlPath: posts/index.html
            |title: Archive META title
            |entityImages:
            |  - path: assets/hero.jpg
            |    role: thumbnail
            |""".stripMargin,
        "posts/index.html" ->
          """<html><head><title>HTML title</title></head>
            |<body><article><p>Archive body</p><img src="images/inline.png" alt="Inline"></article></body></html>""".stripMargin,
        "posts/images/inline.png" -> "inline-bytes",
        "assets/hero.jpg" -> "hero-bytes"
      ))
      _success(BlobPayloadSupport.putManagedPayload(
        component = component,
        id = archiveId,
        kind = BlobKind.Attachment,
        filename = Some("blog.zip"),
        contentType = ContentType.APPLICATION_OCTET_STREAM,
        payload = Bag.binary(archive)
      ))
      val authorId = EntityId("textus", "author_1", EntityCollectionId("textus", "account", "account"))
      val request = Request.of("blog", "blog", "importPostTree")
      val action = ImportPostTreeCommand(request, Record.dataAuto(
        "archiveBlobId" -> archiveId,
        "authorAccountId" -> authorId,
        "publish" -> true
      ))

      val response = _record(_success(component.logic.executeAction(action, summon[ExecutionContext])))

      response.getInt("entityImageCount") shouldBe Some(1)
      response.getInt("inlineImageCount") shouldBe Some(1)

      val postId = response.getAsC[EntityId]("id").toOption.flatten.getOrElse(fail("response id missing"))
      val associations = _associations(postId)
      associations.map(_.role).toSet should contain allOf ("thumbnail", "inline")

      val inlineBlobMetadata = _success(BlobRepository.entityStore().list())
        .find(_.attributes.get("sourcePath").exists(_.endsWith("posts/images/inline.png")))
        .getOrElse(fail("inline Blob metadata not created"))
      val inlineBlob = _success(BlobPayloadSupport.service(component).flatMap { service =>
        service.blobStore.get(inlineBlobMetadata.storageRef.getOrElse(fail("inline Blob has no storageRef")))
      })
      inlineBlob.payload.openInputStream().readAllBytes().toVector shouldBe "inline-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8).toVector
    }

    "register existing Blob images through BlobAttachment Association" in {
      val subsystem = DefaultSubsystemFactory.default(Some("command"))
      val component = (new ComponentFactory)
        .create(ComponentCreate(subsystem, ComponentOrigin.Repository("test")))
        .primary
      given ExecutionContext = ExecutionContext.withFrameworkCommandExecutionMode(
        component.logic.executionContext(),
        CommandExecutionMode.SyncJob
      )
      val authorId = EntityId("textus", "author_assoc", EntityCollectionId("textus", "account", "account"))
      val blobId = EntityId("cncf", "assoc_primary_blob", BlobRepository.CollectionId)
      _success(BlobPayloadSupport.putManagedPayload(
        component = component,
        id = blobId,
        kind = BlobKind.Image,
        filename = Some("primary.jpg"),
        contentType = ContentType.parse("image/jpeg"),
        payload = Bag.binary("primary".getBytes(java.nio.charset.StandardCharsets.UTF_8))
      ))
      val action = RegisterPostCommand(
        Request.of("blog", "blog", "registerPost"),
        Record.dataAuto(
          "slug" -> "association-primary",
          "title" -> "Association Primary",
          "content" -> "<article><p>Body</p></article>",
          "authorAccountId" -> authorId,
          "publish" -> true,
          "entityImages" -> Vector(Record.dataAuto(
            "existingBlobId" -> blobId,
            "role" -> "primary",
            "sortOrder" -> 0
          ))
        )
      )

      val response = _record(_success(component.logic.executeAction(action, summon[ExecutionContext])))

      response.getAny("primaryImageId") shouldBe None
      response.getString("entity_id") should not be empty
      val postId = response.getAsC[EntityId]("id").toOption.flatten.getOrElse(fail("response id missing"))
      response.getString("entity_id") shouldBe Some(postId.value)
      val associations = _associations(postId)
      associations.map(x => x.role -> x.targetEntityId) should contain ("primary" -> blobId.value)
    }

    "reject direct registerPost existingImageId specs" in {
      val subsystem = DefaultSubsystemFactory.default(Some("command"))
      val component = (new ComponentFactory)
        .create(ComponentCreate(subsystem, ComponentOrigin.Repository("test")))
        .primary
      given ExecutionContext = ExecutionContext.withFrameworkCommandExecutionMode(
        component.logic.executionContext(),
        CommandExecutionMode.SyncJob
      )
      val action = RegisterPostCommand(
        Request.of("blog", "blog", "registerPost"),
        Record.dataAuto(
          "slug" -> "existing-image-id",
          "title" -> "Existing Image ID",
          "content" -> "<article><p>Body</p></article>",
          "authorAccountId" -> EntityId("textus", "author_existing_image", EntityCollectionId("textus", "account", "account")),
          "entityImages" -> Vector(Record.dataAuto(
            "existingImageId" -> EntityId("textus_blog", "legacy_image", EntityCollectionId("textus_blog", "blog_component", "image_asset")),
            "role" -> "primary"
          ))
        )
      )

      val result = component.logic.executeAction(action, summon[ExecutionContext])

      result shouldBe a[Consequence.Failure[_]]
      val postId = EntityId(BlogPost.collectionId.major, "existing_image_id", BlogPost.collectionId)
      _success(EntityStore.standard().load[BlogPost](postId)) shouldBe None
    }

    "reject direct registerPost path-only image specs" in {
      val subsystem = DefaultSubsystemFactory.default(Some("command"))
      val component = (new ComponentFactory)
        .create(ComponentCreate(subsystem, ComponentOrigin.Repository("test")))
        .primary
      given ExecutionContext = ExecutionContext.withFrameworkCommandExecutionMode(
        component.logic.executionContext(),
        CommandExecutionMode.SyncJob
      )
      val request = Request.of("blog", "blog", "registerPost")
      val action = RegisterPostCommand(request, Record.dataAuto(
        "slug" -> "path-only",
        "title" -> "Path Only",
        "content" -> "<article><img src=\"images/inline.png\"></article>",
        "authorAccountId" -> EntityId("textus", "author_2", EntityCollectionId("textus", "account", "account")),
        "inlineImages" -> Vector(Record.dataAuto(
          "path" -> "images/inline.png",
          "sourcePath" -> "images/inline.png"
        ))
      ))

      val result = component.logic.executeAction(action, summon[ExecutionContext])

      result shouldBe a[Consequence.Failure[_]]
      val postId = EntityId(BlogPost.collectionId.major, "path_only", BlogPost.collectionId)
      _success(EntityStore.standard().load[BlogPost](postId)) shouldBe None
    }

    "derive representative images from Associations and enforce public lifecycle visibility" in {
      val subsystem = DefaultSubsystemFactory.default(Some("command"))
      val component = (new ComponentFactory)
        .create(ComponentCreate(subsystem, ComponentOrigin.Repository("test")))
        .primary
      given ExecutionContext = ExecutionContext.withFrameworkCommandExecutionMode(
        component.logic.executionContext(),
        CommandExecutionMode.SyncJob
      )
      val authorId = EntityId("textus", "author_lifecycle", EntityCollectionId("textus", "account", "account"))
      val coverBlobId = EntityId("cncf", "lifecycle_cover_blob", BlobRepository.CollectionId)
      val galleryBlobId = EntityId("cncf", "lifecycle_gallery_blob", BlobRepository.CollectionId)
      Vector(coverBlobId -> "cover", galleryBlobId -> "gallery").foreach { case (id, body) =>
        _success(BlobPayloadSupport.putManagedPayload(
          component = component,
          id = id,
          kind = BlobKind.Image,
          filename = Some(s"${body}.jpg"),
          contentType = ContentType.parse("image/jpeg"),
          payload = Bag.binary(body.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        ))
      }
      val register = RegisterPostCommand(
        Request.of("blog", "blog", "registerPost"),
        Record.dataAuto(
          "slug" -> "lifecycle-association",
          "title" -> "Lifecycle Association",
          "content" -> "<article><p>Body</p></article>",
          "authorAccountId" -> authorId,
          "publish" -> false,
          "entityImages" -> Vector(
            Record.dataAuto("existingBlobId" -> galleryBlobId, "role" -> "gallery", "sortOrder" -> 0),
            Record.dataAuto("existingBlobId" -> coverBlobId, "role" -> "cover", "sortOrder" -> 1)
          )
        )
      )
      val registered = _record(_success(component.logic.executeAction(register, summon[ExecutionContext])))
      val postId = registered.getAsC[EntityId]("id").toOption.flatten.getOrElse(fail("response id missing"))
      val get = GetPostQuery(Request.of("blog", "blog", "getPost"), Record.dataAuto("id" -> postId))

      component.logic.executeAction(get, summon[ExecutionContext]) shouldBe a[Consequence.Failure[_]]

      val managerContext = ExecutionContext.withFrameworkCommandExecutionMode(
        _with_privilege(summon[ExecutionContext], SecurityContext.Privilege.ApplicationContentManager),
        CommandExecutionMode.SyncJob
      )
      val publish = PublishPostCommand(Request.of("blog", "blog", "publishPost"), Record.dataAuto(
        "id" -> postId,
        "publisherAccountId" -> authorId
      ))
      _success(component.logic.executeAction(publish, managerContext))

      val published = _record(_success(component.logic.executeAction(get, summon[ExecutionContext])))
      published.getAsC[EntityId]("representativeBlobId").toOption.flatten shouldBe Some(coverBlobId)
      published.getAny("primaryImageId") shouldBe None
      val search = SearchPostsQuery(Request.of("blog", "blog", "searchPosts"), Record.dataAuto("draftStatus" -> "draft"))
      val hiddenSearch = _record(_success(component.logic.executeAction(search, summon[ExecutionContext])))
      hiddenSearch.getInt("fetchedCount") shouldBe Some(0)

      val deactivate = DeactivatePostCommand(Request.of("blog", "blog", "deactivatePost"), Record.dataAuto(
        "id" -> postId,
        "operatorAccountId" -> authorId
      ))
      _success(component.logic.executeAction(deactivate, managerContext))

      component.logic.executeAction(get, summon[ExecutionContext]) shouldBe a[Consequence.Failure[_]]
    }

    "derive representative image from first inline Association fallback" in {
      val subsystem = DefaultSubsystemFactory.default(Some("command"))
      val component = (new ComponentFactory)
        .create(ComponentCreate(subsystem, ComponentOrigin.Repository("test")))
        .primary
      given ExecutionContext = ExecutionContext.withFrameworkCommandExecutionMode(
        component.logic.executionContext(),
        CommandExecutionMode.SyncJob
      )
      val authorId = EntityId("textus", "author_inline_representative", EntityCollectionId("textus", "account", "account"))
      val inline1 = EntityId("cncf", "inline_representative_1", BlobRepository.CollectionId)
      val inline2 = EntityId("cncf", "inline_representative_2", BlobRepository.CollectionId)
      Vector(inline1 -> "inline-1", inline2 -> "inline-2").foreach { case (id, body) =>
        _success(BlobPayloadSupport.putManagedPayload(
          component = component,
          id = id,
          kind = BlobKind.Image,
          filename = Some(s"${body}.png"),
          contentType = ContentType.IMAGE_PNG,
          payload = Bag.binary(body.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        ))
      }
      val register = RegisterPostCommand(
        Request.of("blog", "blog", "registerPost"),
        Record.dataAuto(
          "slug" -> "inline-representative",
          "title" -> "Inline Representative",
          "content" -> "<article><p>Body</p><img src=\"/web/blob/content/inline-1\"><img src=\"/web/blob/content/inline-2\"></article>",
          "authorAccountId" -> authorId,
          "publish" -> true,
          "inlineImages" -> Vector(
            Record.dataAuto("existingBlobId" -> inline2, "sourcePath" -> "inline-2.png", "sortOrder" -> 2),
            Record.dataAuto("existingBlobId" -> inline1, "sourcePath" -> "inline-1.png", "sortOrder" -> 1)
          )
        )
      )
      val registered = _record(_success(component.logic.executeAction(register, summon[ExecutionContext])))
      val postId = registered.getAsC[EntityId]("id").toOption.flatten.getOrElse(fail("response id missing"))
      val get = GetPostQuery(Request.of("blog", "blog", "getPost"), Record.dataAuto("id" -> postId))

      val published = _record(_success(component.logic.executeAction(get, summon[ExecutionContext])))

      published.getAsC[EntityId]("representativeBlobId").toOption.flatten shouldBe Some(inline1)
      published.getString("representativeBlobUrl") shouldBe Some(s"/web/blob/content/${inline1.value}")
    }

    "derive representative image URL from Blob metadata accessUrl" in {
      val subsystem = DefaultSubsystemFactory.default(Some("command"))
      val component = (new ComponentFactory)
        .create(ComponentCreate(subsystem, ComponentOrigin.Repository("test")))
        .primary
      given ExecutionContext = ExecutionContext.withFrameworkCommandExecutionMode(
        component.logic.executionContext(),
        CommandExecutionMode.SyncJob
      )
      val authorId = EntityId("textus", "author_external_representative", EntityCollectionId("textus", "account", "account"))
      val externalBlobId = EntityId("cncf", "external_representative_cover", BlobRepository.CollectionId)
      val externalUrl = "https://example.test/blog/external-cover.png"
      _success(BlobRepository.entityStore().create(BlobCreate(
        id = externalBlobId,
        kind = BlobKind.Image,
        sourceMode = BlobSourceMode.ExternalUrl,
        filename = Some("external-cover.png"),
        contentType = Some(ContentType.IMAGE_PNG),
        byteSize = None,
        digest = None,
        storageRef = None,
        externalUrl = Some(externalUrl),
        accessUrl = BlobAccessUrl(
          displayUrl = externalUrl,
          downloadUrl = externalUrl,
          urlSource = BlobAccessUrlSource.Backend
        )
      )))
      val register = RegisterPostCommand(
        Request.of("blog", "blog", "registerPost"),
        Record.dataAuto(
          "slug" -> "external-representative",
          "title" -> "External Representative",
          "content" -> "<article><p>Body</p></article>",
          "authorAccountId" -> authorId,
          "publish" -> true,
          "entityImages" -> Vector(
            Record.dataAuto("existingBlobId" -> externalBlobId, "role" -> "cover", "sortOrder" -> 1)
          )
        )
      )
      val registered = _record(_success(component.logic.executeAction(register, summon[ExecutionContext])))
      val postId = registered.getAsC[EntityId]("id").toOption.flatten.getOrElse(fail("response id missing"))
      val get = GetPostQuery(Request.of("blog", "blog", "getPost"), Record.dataAuto("id" -> postId))

      val published = _record(_success(component.logic.executeAction(get, summon[ExecutionContext])))

      published.getAsC[EntityId]("representativeBlobId").toOption.flatten shouldBe Some(externalBlobId)
      published.getString("representativeBlobUrl") shouldBe Some(externalUrl)
    }

    "filter public search by text before applying pagination" in {
      val subsystem = DefaultSubsystemFactory.default(Some("command"))
      val component = (new ComponentFactory)
        .create(ComponentCreate(subsystem, ComponentOrigin.Repository("test")))
        .primary
      given ExecutionContext = ExecutionContext.withFrameworkCommandExecutionMode(
        component.logic.executionContext(),
        CommandExecutionMode.SyncJob
      )
      val authorId = EntityId("textus", "author_search", EntityCollectionId("textus", "account", "account"))
      val needle = "BI02 Search Needle"
      val draft = RegisterPostCommand(
        Request.of("blog", "blog", "registerPost"),
        Record.dataAuto(
          "slug" -> "bi02-search-draft",
          "title" -> s"$needle Draft",
          "content" -> "<article><p>Draft body</p></article>",
          "authorAccountId" -> authorId,
          "publish" -> false
        )
      )
      val published = RegisterPostCommand(
        Request.of("blog", "blog", "registerPost"),
        Record.dataAuto(
          "slug" -> "bi02-search-published",
          "title" -> s"$needle Published",
          "content" -> "<article><p>Published body</p></article>",
          "authorAccountId" -> authorId,
          "publish" -> true
        )
      )
      _success(component.logic.executeAction(draft, summon[ExecutionContext]))
      _success(component.logic.executeAction(published, summon[ExecutionContext]))
      val search = SearchPostsQuery(
        Request.of("blog", "blog", "searchPosts"),
        Record.dataAuto(
          "text" -> needle,
          "limit" -> 1,
          "offset" -> 0
        )
      )

      val result = _record(_success(component.logic.executeAction(search, summon[ExecutionContext])))

      result.getInt("totalCount") shouldBe Some(1)
      result.getInt("fetchedCount") shouldBe Some(1)
      val records = _records(result, "data")
      records.map(_.getString("title")) shouldBe Vector(Some(s"$needle Published"))
    }
  }

  private def _associations(postId: EntityId)(using ExecutionContext) =
    _success(
      AssociationRepository.entityStore(AssociationStoragePolicy.blobAttachmentDefault).list(
        AssociationFilter(
          domain = AssociationDomain.BlobAttachment,
          sourceEntityId = Some(postId.value),
          targetKind = Some("blob")
        )
      )
    )

  private def _record(response: OperationResponse): Record =
    response match {
      case OperationResponse.RecordResponse(record) => record
      case other => fail(s"expected record response but got $other")
    }

  private def _records(record: Record, key: String): Vector[Record] =
    record.getVector(key).getOrElse(Vector.empty).collect {
      case r: Record => r
    }

  private def _with_privilege(
    context: ExecutionContext,
    privilege: SecurityContext.Privilege
  ): ExecutionContext = {
    lazy val secured = ExecutionContext.withSecurityContext(
      context,
      SecurityContext(
        principal = new org.goldenport.cncf.context.Principal {
          def id: org.goldenport.cncf.context.PrincipalId = privilege.principalId
          def attributes: Map[String, String] = privilege.attributes
        },
        capabilities = privilege.capabilities,
        level = privilege.level,
        subjectKind = privilege.subjectKind
      )
    )
    lazy val rebound: ExecutionContext =
      ExecutionContext.withRuntimeContext(secured, runtime)
    lazy val runtime =
      secured.runtime.withUnitOfWorkContext(rebound, "component-factory-spec")
    rebound
  }

  private def _success[A](value: Consequence[A]): A =
    value match {
      case Consequence.Success(v) => v
      case Consequence.Failure(c) => fail(s"unexpected failure: $c")
    }

  private def _zip(entries: Vector[(String, String)]): Array[Byte] = {
    val out = new ByteArrayOutputStream()
    val zip = new ZipOutputStream(out)
    try {
      entries.foreach { case (name, body) =>
        zip.putNextEntry(new ZipEntry(name))
        zip.write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        zip.closeEntry()
      }
    } finally {
      zip.close()
    }
    out.toByteArray
  }
}
