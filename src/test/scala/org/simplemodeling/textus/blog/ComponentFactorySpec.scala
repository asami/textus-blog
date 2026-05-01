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
import org.goldenport.datatype.{ContentType, FileBundle, MimeBody}
import org.goldenport.protocol.{Property, Request}
import org.goldenport.protocol.operation.OperationResponse
import org.goldenport.record.Record
import org.simplemodeling.model.datatype.{EntityCollectionId, EntityId}
import org.simplemodeling.textus.blog.entity.{BlogInlineImage, BlogPost}

/*
 * @since   Apr. 29, 2026
 *  version Apr. 30, 2026
 * @version May.  1, 2026
 * @author  ASAMI, Tomoharu
 */
final class ComponentFactorySpec extends AnyWordSpec with Matchers {
  import BlogComponentComponent.BlogService.*

  "ComponentFactory" should {
    "wire executable bulk blog operations instead of generated placeholders" in {
      val factory = new ComponentFactory
      val importAction = ImportPostTree.unsafeForTest(null, Record.empty)
      val registerAction = RegisterBlogPost.unsafeForTest(null, Record.empty)
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
      importBinding.parameters should contain ("fileBundle")
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

    "publish atomFeed operation metadata from CML" in {
      val subsystem = DefaultSubsystemFactory.default(Some("command"))
      val component = (new ComponentFactory)
        .create(ComponentCreate(subsystem, ComponentOrigin.Repository("test")))
        .primary
      val definitions = component.operationDefinitions.map(x => x.name -> x).toMap

      definitions should contain key "atomFeed"
      definitions("atomFeed").inputType shouldBe "AtomFeedBlogPosts"
      definitions("atomFeed").outputType shouldBe "AtomFeedBlogPostsResult"
    }

    "publish Blog Web app operation metadata from CML" in {
      val subsystem = DefaultSubsystemFactory.default(Some("command"))
      val component = (new ComponentFactory)
        .create(ComponentCreate(subsystem, ComponentOrigin.Repository("test")))
        .primary
      val definitions = component.operationDefinitions.map(x => x.name -> x).toMap

      definitions should contain key "saveEditorPost"
      definitions should contain key "listImageBlobs"
      definitions("saveEditorPost").inputType shouldBe "SaveEditorBlogPost"
      definitions("listImageBlobs").inputType shouldBe "ListBlogImageBlobs"
      definitions("importPostTree").parameters.find(_.name == "fileBundle").map(_.datatype) shouldBe Some("filebundle")
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
      val action = ImportPostTree.unsafeForTest(request, Record.dataAuto(
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

    "import a Blog filebundle and derive author from authenticated principal" in {
      val subsystem = DefaultSubsystemFactory.default(Some("command"))
      val component = (new ComponentFactory)
        .create(ComponentCreate(subsystem, ComponentOrigin.Repository("test")))
        .primary
      val baseContext = ExecutionContext.withFrameworkCommandExecutionMode(
        component.logic.executionContext(),
        CommandExecutionMode.SyncJob
      )
      val authorId = EntityId("textus", "author_filebundle", EntityCollectionId("textus", "account", "account"))
      given ExecutionContext = _with_authenticated_principal(baseContext, "filebundle-author", Map("authorAccountId" -> authorId.value))
      val archive = _zip(Vector(
        "META-INF/blog.yaml" ->
          """slug: filebundle-post
            |entryHtmlPath: index.html
            |title: FileBundle title
            |""".stripMargin,
        "index.html" ->
          """<html><head><title>HTML title</title></head>
            |<body><article><p>FileBundle body</p><img src="images/inline.png" alt="Inline"></article></body></html>""".stripMargin,
        "images/inline.png" -> "inline-filebundle-bytes"
      ))
      val fileBundle = FileBundle(MimeBody(ContentType.APPLICATION_ZIP, Bag.binary(archive)))
      val action = ImportPostTree.unsafeForTest(Request.of("blog", "blog", "importPostTree"), Record.dataAuto(
        "fileBundle" -> fileBundle,
        "publish" -> true
      ))

      val response = _record(_success(component.logic.executeAction(action, summon[ExecutionContext])))

      response.getInt("inlineImageCount") shouldBe Some(1)
      val postId = response.getAsC[EntityId]("id").toOption.flatten.getOrElse(fail("response id missing"))
      val stored = _success(EntityStore.standard().load[BlogPost](postId)).getOrElse(fail("post missing"))
      stored.authorAccountId.value shouldBe authorId.value
      _associations(postId).map(_.role) should contain ("inline")
    }

    "save editor posts from authenticated users and resynchronize inline images on update" in {
      val subsystem = DefaultSubsystemFactory.default(Some("command"))
      val component = (new ComponentFactory)
        .create(ComponentCreate(subsystem, ComponentOrigin.Repository("test")))
        .primary
      val baseContext = ExecutionContext.withFrameworkCommandExecutionMode(
        component.logic.executionContext(),
        CommandExecutionMode.SyncJob
      )
      val authorId = EntityId("textus", "author_editor", EntityCollectionId("textus", "account", "account"))
      given ExecutionContext = _with_authenticated_principal(baseContext, "editor-author", Map("authorAccountId" -> authorId.value))
      val firstBlob = EntityId("cncf", "editor_inline_first", BlobRepository.CollectionId)
      val secondBlob = EntityId("cncf", "editor_inline_second", BlobRepository.CollectionId)
      Vector(firstBlob -> "first", secondBlob -> "second").foreach { case (id, body) =>
        _success(BlobPayloadSupport.putManagedPayload(
          component = component,
          id = id,
          kind = BlobKind.Image,
          filename = Some(s"${body}.png"),
          contentType = ContentType.IMAGE_PNG,
          payload = Bag.binary(body.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        ))
      }
      val create = SaveEditorBlogPost.unsafeForTest(Request.of("blog", "blog", "saveEditorPost"), Record.dataAuto(
        "slug" -> "editor-post",
        "title" -> "Editor Post",
        "content" -> s"""<article><p>Body</p><img src="/web/blob/content/${firstBlob.value}" alt=""></article>"""
      ))

      val created = _record(_success(component.logic.executeAction(create, summon[ExecutionContext])))
      val postId = created.getAsC[EntityId]("id").toOption.flatten.getOrElse(fail("response id missing"))

      created.getString("entity_id") shouldBe Some(postId.value)
      created.getInt("inlineImageCount") shouldBe Some(1)
      _associations(postId).map(_.targetEntityId) should contain (firstBlob.value)

      val update = SaveEditorBlogPost.unsafeForTest(Request.of("blog", "blog", "saveEditorPost"), Record.dataAuto(
        "id" -> postId,
        "slug" -> "editor-post",
        "title" -> "Editor Post Updated",
        "content" -> s"""<article><p>Body</p><img src="/web/blob/content/${secondBlob.value}" alt=""></article>""",
        "publish" -> true
      ))
      val updated = _record(_success(component.logic.executeAction(update, summon[ExecutionContext])))

      updated.getInt("inlineImageCount") shouldBe Some(1)
      val updatedStored = _success(EntityStore.standard().load[BlogPost](postId)).getOrElse(fail("updated post missing"))
      updatedStored.draftStatus shouldBe "published"
      val associations = _associations(postId)
      associations.map(_.targetEntityId) should contain (secondBlob.value)
      associations.map(_.targetEntityId) should not contain firstBlob.value
      _inline_images(postId).map(_.sourceUrl) shouldBe Vector(secondBlob.display)
      val visible = _record(_success(component.logic.executeAction(
        GetBlogPost.unsafeForTest(Request.of("blog", "blog", "getPost"), Record.dataAuto("id" -> postId)),
        summon[ExecutionContext]
      )))
      visible.getString("title") shouldBe Some("Editor Post Updated")
    }

    "reject editor updates from a different authenticated author" in {
      val subsystem = DefaultSubsystemFactory.default(Some("command"))
      val component = (new ComponentFactory)
        .create(ComponentCreate(subsystem, ComponentOrigin.Repository("test")))
        .primary
      val baseContext = ExecutionContext.withFrameworkCommandExecutionMode(
        component.logic.executionContext(),
        CommandExecutionMode.SyncJob
      )
      val authorId = EntityId("textus", "author_editor_owner", EntityCollectionId("textus", "account", "account"))
      val otherId = EntityId("textus", "author_editor_other", EntityCollectionId("textus", "account", "account"))
      given ExecutionContext = _with_authenticated_principal(baseContext, "editor-owner", Map("authorAccountId" -> authorId.value))
      val create = SaveEditorBlogPost.unsafeForTest(Request.of("blog", "blog", "saveEditorPost"), Record.dataAuto(
        "slug" -> "owned-editor-post",
        "title" -> "Owned Editor Post",
        "content" -> "<article><p>Original</p></article>"
      ))
      val created = _record(_success(component.logic.executeAction(create, summon[ExecutionContext])))
      val postId = created.getAsC[EntityId]("id").toOption.flatten.getOrElse(fail("response id missing"))
      val otherContext = _with_authenticated_principal(baseContext, "editor-other", Map("authorAccountId" -> otherId.value))
      val update = SaveEditorBlogPost.unsafeForTest(Request.of("blog", "blog", "saveEditorPost"), Record.dataAuto(
        "id" -> postId,
        "slug" -> "owned-editor-post",
        "title" -> "Hijacked",
        "content" -> "<article><p>Changed</p></article>",
        "publish" -> true
      ))

      component.logic.executeAction(update, otherContext) shouldBe a[Consequence.Failure[_]]

      val stored = _success(EntityStore.standard().load[BlogPost](postId)).getOrElse(fail("post missing"))
      stored.toRecord().getString("title") shouldBe Some("Owned Editor Post")
      stored.draftStatus shouldBe "draft"
    }

    "validate editor inline Blob refs before saving or deleting old inline bindings" in {
      val subsystem = DefaultSubsystemFactory.default(Some("command"))
      val component = (new ComponentFactory)
        .create(ComponentCreate(subsystem, ComponentOrigin.Repository("test")))
        .primary
      val baseContext = ExecutionContext.withFrameworkCommandExecutionMode(
        component.logic.executionContext(),
        CommandExecutionMode.SyncJob
      )
      val authorId = EntityId("textus", "author_editor_invalid_blob", EntityCollectionId("textus", "account", "account"))
      given ExecutionContext = _with_authenticated_principal(baseContext, "editor-invalid-blob", Map("authorAccountId" -> authorId.value))
      val validBlob = EntityId("cncf", "editor_valid_inline", BlobRepository.CollectionId)
      val missingBlob = EntityId("cncf", "editor_missing_inline", BlobRepository.CollectionId)
      _success(BlobPayloadSupport.putManagedPayload(
        component = component,
        id = validBlob,
        kind = BlobKind.Image,
        filename = Some("valid.png"),
        contentType = ContentType.IMAGE_PNG,
        payload = Bag.binary("valid".getBytes(java.nio.charset.StandardCharsets.UTF_8))
      ))
      val create = SaveEditorBlogPost.unsafeForTest(Request.of("blog", "blog", "saveEditorPost"), Record.dataAuto(
        "slug" -> "editor-invalid-blob",
        "title" -> "Editor Invalid Blob",
        "content" -> s"""<article><p>Original</p><img src="/web/blob/content/${validBlob.value}" alt=""></article>"""
      ))
      val created = _record(_success(component.logic.executeAction(create, summon[ExecutionContext])))
      val postId = created.getAsC[EntityId]("id").toOption.flatten.getOrElse(fail("response id missing"))
      val update = SaveEditorBlogPost.unsafeForTest(Request.of("blog", "blog", "saveEditorPost"), Record.dataAuto(
        "id" -> postId,
        "slug" -> "editor-invalid-blob",
        "title" -> "Should Not Save",
        "content" -> s"""<article><p>Broken</p><img src="/web/blob/content/${missingBlob.value}" alt=""></article>""",
        "publish" -> true
      ))

      component.logic.executeAction(update, summon[ExecutionContext]) shouldBe a[Consequence.Failure[_]]

      val stored = _success(EntityStore.standard().load[BlogPost](postId)).getOrElse(fail("post missing"))
      stored.toRecord().getString("title") shouldBe Some("Editor Invalid Blob")
      stored.draftStatus shouldBe "draft"
      _associations(postId).map(_.targetEntityId) should contain (validBlob.value)
      _inline_images(postId).map(_.sourceUrl) shouldBe Vector(validBlob.display)
    }

    "allow the same Blob image to appear in multiple inline occurrences" in {
      val subsystem = DefaultSubsystemFactory.default(Some("command"))
      val component = (new ComponentFactory)
        .create(ComponentCreate(subsystem, ComponentOrigin.Repository("test")))
        .primary
      val baseContext = ExecutionContext.withFrameworkCommandExecutionMode(
        component.logic.executionContext(),
        CommandExecutionMode.SyncJob
      )
      val authorId = EntityId("textus", "author_editor_duplicate_inline", EntityCollectionId("textus", "account", "account"))
      given ExecutionContext = _with_authenticated_principal(baseContext, "editor-duplicate-inline", Map("authorAccountId" -> authorId.value))
      val blobId = EntityId("cncf", "editor_duplicate_inline_blob", BlobRepository.CollectionId)
      _success(BlobPayloadSupport.putManagedPayload(
        component = component,
        id = blobId,
        kind = BlobKind.Image,
        filename = Some("duplicate.png"),
        contentType = ContentType.IMAGE_PNG,
        payload = Bag.binary("duplicate".getBytes(java.nio.charset.StandardCharsets.UTF_8))
      ))
      val create = SaveEditorBlogPost.unsafeForTest(Request.of("blog", "blog", "saveEditorPost"), Record.dataAuto(
        "slug" -> "editor-duplicate-inline",
        "title" -> "Editor Duplicate Inline",
        "content" -> s"""<article><img src="/web/blob/content/${blobId.value}" alt=""><p>Again</p><img src="/web/blob/content/${blobId.value}" alt=""></article>"""
      ))

      val created = _record(_success(component.logic.executeAction(create, summon[ExecutionContext])))

      created.getInt("inlineImageCount") shouldBe Some(2)
      val postId = created.getAsC[EntityId]("id").toOption.flatten.getOrElse(fail("response id missing"))
      _inline_images(postId).map(_.sourceUrl) shouldBe Vector(blobId.display, blobId.display)
      _associations(postId).filter(_.targetEntityId == blobId.value).map(_.role) shouldBe Vector("inline", "inline")
    }

    "reject anonymous editor save and image listing" in {
      val subsystem = DefaultSubsystemFactory.default(Some("command"))
      val component = (new ComponentFactory)
        .create(ComponentCreate(subsystem, ComponentOrigin.Repository("test")))
        .primary
      given ExecutionContext = ExecutionContext.withFrameworkCommandExecutionMode(
        component.logic.executionContext(),
        CommandExecutionMode.SyncJob
      )
      val save = SaveEditorBlogPost.unsafeForTest(Request.of("blog", "blog", "saveEditorPost"), Record.dataAuto(
        "slug" -> "anonymous-editor",
        "title" -> "Anonymous",
        "content" -> "<article><p>Body</p></article>"
      ))
      val list = ListBlogImageBlobs.unsafeForTest(Request.of("blog", "blog", "listImageBlobs"), Record.empty)

      component.logic.executeAction(save, summon[ExecutionContext]) shouldBe a[Consequence.Failure[_]]
      component.logic.executeAction(list, summon[ExecutionContext]) shouldBe a[Consequence.Failure[_]]
    }

    "list only image Blob metadata for the editor picker" in {
      val subsystem = DefaultSubsystemFactory.default(Some("command"))
      val component = (new ComponentFactory)
        .create(ComponentCreate(subsystem, ComponentOrigin.Repository("test")))
        .primary
      val baseContext = ExecutionContext.withFrameworkCommandExecutionMode(
        component.logic.executionContext(),
        CommandExecutionMode.SyncJob
      )
      given ExecutionContext = _with_authenticated_principal(baseContext, "editor-picker-user")
      val imageBlob = EntityId("cncf", "picker_image_blob", BlobRepository.CollectionId)
      val attachmentBlob = EntityId("cncf", "picker_attachment_blob", BlobRepository.CollectionId)
      _success(BlobPayloadSupport.putManagedPayload(
        component = component,
        id = imageBlob,
        kind = BlobKind.Image,
        filename = Some("picker.png"),
        contentType = ContentType.IMAGE_PNG,
        payload = Bag.binary("image".getBytes(java.nio.charset.StandardCharsets.UTF_8))
      ))
      _success(BlobPayloadSupport.putManagedPayload(
        component = component,
        id = attachmentBlob,
        kind = BlobKind.Attachment,
        filename = Some("archive.zip"),
        contentType = ContentType.APPLICATION_ZIP,
        payload = Bag.binary(_zip(Vector("index.html" -> "<html></html>")))
      ))
      val action = ListBlogImageBlobs.unsafeForTest(Request.of("blog", "blog", "listImageBlobs"), Record.dataAuto(
        "text" -> "picker",
        "limit" -> 10
      ))

      val response = _record(_success(component.logic.executeAction(action, summon[ExecutionContext])))

      val rows = _records(response, "data")
      rows.map(_.getAsC[EntityId]("id").toOption.flatten) should contain (Some(imageBlob))
      rows.map(_.getAsC[EntityId]("id").toOption.flatten) should not contain Some(attachmentBlob)
      rows.head.getString("url") shouldBe Some(s"/web/blob/content/${imageBlob.value}")
    }

    "place the Blog Web app in CAR metadata and Web app resource roots" in {
      val root = java.nio.file.Paths.get(".").toAbsolutePath.normalize()

      java.nio.file.Files.exists(root.resolve("src/main/car/web/web.yaml")) shouldBe true
      java.nio.file.Files.exists(root.resolve("src/main/web/web.yaml")) shouldBe false
      java.nio.file.Files.exists(root.resolve("src/main/web/blog/index.html")) shouldBe true
      java.nio.file.Files.exists(root.resolve("src/main/web/blog/assets/blog.css")) shouldBe true
      java.nio.file.Files.exists(root.resolve("src/main/web/blog/assets/blog.js")) shouldBe true
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
      val action = RegisterBlogPost.unsafeForTest(
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
      val action = RegisterBlogPost.unsafeForTest(
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
      val action = RegisterBlogPost.unsafeForTest(request, Record.dataAuto(
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
      val register = RegisterBlogPost.unsafeForTest(
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
      val get = GetBlogPost.unsafeForTest(Request.of("blog", "blog", "getPost"), Record.dataAuto("id" -> postId))

      component.logic.executeAction(get, summon[ExecutionContext]) shouldBe a[Consequence.Failure[_]]

      val managerContext = ExecutionContext.withFrameworkCommandExecutionMode(
        _with_privilege(summon[ExecutionContext], SecurityContext.Privilege.ApplicationContentManager),
        CommandExecutionMode.SyncJob
      )
      val publish = PublishBlogPost.unsafeForTest(Request.of("blog", "blog", "publishPost"), Record.dataAuto(
        "id" -> postId,
        "publisherAccountId" -> authorId
      ))
      _success(component.logic.executeAction(publish, managerContext))

      val published = _record(_success(component.logic.executeAction(get, summon[ExecutionContext])))
      published.getAsC[EntityId]("representativeBlobId").toOption.flatten shouldBe Some(coverBlobId)
      published.getAny("primaryImageId") shouldBe None
      val search = SearchBlogPosts.unsafeForTest(Request.of("blog", "blog", "searchPosts"), Record.dataAuto("draftStatus" -> "draft"))
      val hiddenSearch = _record(_success(component.logic.executeAction(search, summon[ExecutionContext])))
      hiddenSearch.getInt("fetchedCount") shouldBe Some(0)

      val deactivate = DeactivateBlogPost.unsafeForTest(Request.of("blog", "blog", "deactivatePost"), Record.dataAuto(
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
      val register = RegisterBlogPost.unsafeForTest(
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
      val get = GetBlogPost.unsafeForTest(Request.of("blog", "blog", "getPost"), Record.dataAuto("id" -> postId))

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
      val register = RegisterBlogPost.unsafeForTest(
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
      val get = GetBlogPost.unsafeForTest(Request.of("blog", "blog", "getPost"), Record.dataAuto("id" -> postId))

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
      val draft = RegisterBlogPost.unsafeForTest(
        Request.of("blog", "blog", "registerPost"),
        Record.dataAuto(
          "slug" -> "bi02-search-draft",
          "title" -> s"$needle Draft",
          "content" -> "<article><p>Draft body</p></article>",
          "authorAccountId" -> authorId,
          "publish" -> false
        )
      )
      val published = RegisterBlogPost.unsafeForTest(
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
      val search = SearchBlogPosts.unsafeForTest(
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

    "render Atom feed XML for published active posts" in {
      val subsystem = DefaultSubsystemFactory.default(Some("command"))
      val component = (new ComponentFactory)
        .create(ComponentCreate(subsystem, ComponentOrigin.Repository("test")))
        .primary
      given ExecutionContext = ExecutionContext.withFrameworkCommandExecutionMode(
        component.logic.executionContext(),
        CommandExecutionMode.SyncJob
      )
      val authorId = EntityId("textus", "author_atom_feed", EntityCollectionId("textus", "account", "account"))
      val marker = "Atom Feed Needle"
      val draft = RegisterBlogPost.unsafeForTest(
        Request.of("blog", "blog", "registerPost"),
        Record.dataAuto(
          "slug" -> "atom-feed-draft",
          "title" -> s"$marker Draft",
          "content" -> "<article><p>Draft body</p></article>",
          "authorAccountId" -> authorId,
          "publish" -> false
        )
      )
      val published = RegisterBlogPost.unsafeForTest(
        Request.of("blog", "blog", "registerPost"),
        Record.dataAuto(
          "slug" -> "atom-feed-published",
          "title" -> s"$marker Published",
          "content" -> s"<article><p>$marker Published & Body</p></article>",
          "authorAccountId" -> authorId,
          "publish" -> true
        )
      )
      _success(component.logic.executeAction(draft, summon[ExecutionContext]))
      _success(component.logic.executeAction(published, summon[ExecutionContext]))
      val request = Request.of("blog", "blog", "atomFeed").copy(
        properties = List(Property("cncf.site.base-url", "https://blog.example.test", None))
      )
      val action = AtomFeedBlogPosts.unsafeForTest(
        request,
        Record.dataAuto(
          "text" -> marker,
          "limit" -> 10
        )
      )

      val response = _success(component.logic.executeAction(action, summon[ExecutionContext]))

      response match {
        case OperationResponse.Http(http) =>
          http.contentType.header shouldBe "application/atom+xml; charset=UTF-8"
          val xml = http.getString.getOrElse(fail("Atom response body missing"))
          xml should include ("""xmlns="http://www.w3.org/2005/Atom"""")
          xml should include ("""href="https://blog.example.test/rest/v1/blog/blog/atomFeed"""")
          xml should include ("https://blog.example.test/blog/atom-feed-published")
          xml should include (s"$marker Published")
          xml should include ("&lt;article&gt;&lt;p&gt;Atom Feed Needle Published &amp; Body&lt;/p&gt;&lt;/article&gt;")
          xml should not include "atom-feed-draft"
        case other =>
          fail(s"expected HTTP Atom response but got $other")
      }
    }
  }

  private def _associations(postId: EntityId)(using ExecutionContext) =
    _success(
      AssociationRepository.entityStore(AssociationStoragePolicy.blobAttachmentDefault).list(
        AssociationFilter(
          domain = AssociationDomain.BlobAttachment,
          sourceEntityId = Some(postId.display),
          targetKind = Some("blob")
        )
      )
    )

  private def _inline_images(postId: EntityId)(using ExecutionContext): Vector[BlogInlineImage] =
    _success(
      EntityStore.standard()
        .search[BlogInlineImage](org.goldenport.cncf.entity.EntityQuery(
          BlogInlineImage.collectionId,
          org.goldenport.cncf.directive.Query.plan(Record.empty),
          org.goldenport.cncf.entity.EntitySearchScope.Store
        ))
        .map(_.data.filter(_.blogPostId == postId).sortBy(_.sortOrder))
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

  private def _with_authenticated_principal(
    context: ExecutionContext,
    principalId: String,
    attrs: Map[String, String] = Map.empty
  ): ExecutionContext = {
    lazy val secured = ExecutionContext.withSecurityContext(
      context,
      SecurityContext(
        principal = new org.goldenport.cncf.context.Principal {
          def id: org.goldenport.cncf.context.PrincipalId =
            org.goldenport.cncf.context.PrincipalId(principalId)
          def attributes: Map[String, String] =
            attrs + ("authenticated" -> "true")
        },
        capabilities = Set.empty,
        level = org.goldenport.cncf.context.SecurityLevel("user"),
        subjectKind = org.goldenport.cncf.context.SubjectKind.User
      )
    )
    lazy val rebound: ExecutionContext =
      ExecutionContext.withRuntimeContext(secured, runtime)
    lazy val runtime =
      secured.runtime.withUnitOfWorkContext(rebound, "component-factory-spec-authenticated")
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
