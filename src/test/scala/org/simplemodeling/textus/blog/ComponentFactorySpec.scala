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
import org.goldenport.cncf.context.{ExecutionContext, IdGenerationContext, SecurityContext}
import org.goldenport.cncf.entity.EntityStore
import org.goldenport.cncf.operation.{CmlEntityRelationshipDefinition, CmlOperationAssociationBinding}
import org.goldenport.cncf.tag.{TagCreate, TagRepository, TagSpace}
import org.goldenport.cncf.subsystem.DefaultSubsystemFactory
import org.goldenport.datatype.{ContentType, FileBundle, MimeBody}
import org.goldenport.protocol.{Property, Request}
import org.goldenport.protocol.operation.OperationResponse
import org.goldenport.record.Record
import org.simplemodeling.model.datatype.{EntityCollectionId, EntityId}
import org.simplemodeling.model.statemachine.PostStatus
import org.simplemodeling.model.value.ContentMarkup
import org.simplemodeling.textus.blog.entity.BlogPost

/*
 * @since   Apr. 29, 2026
 *  version Apr. 30, 2026
 * @version May.  7, 2026
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
      registerBinding.parameters should not contain ("inlineImages.existingBlobId")
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
      definitions should contain key "getMyPost"
      definitions should contain key "searchMyPosts"
      definitions("saveEditorPost").inputType shouldBe "SaveEditorBlogPost"
      definitions("listImageBlobs").inputType shouldBe "ListBlogImageBlobs"
      definitions("getMyPost").inputType shouldBe "GetMyBlogPost"
      definitions("searchMyPosts").inputType shouldBe "SearchMyBlogPosts"
      definitions("getPost").visibility shouldBe Some("public")
      definitions("searchPosts").visibility shouldBe Some("public")
      definitions("getMyPost").visibility shouldBe Some("owner")
      definitions("searchMyPosts").visibility shouldBe Some("owner")
      definitions("saveEditorPost").parameters.find(_.name == "contentMarkup").map(_.datatype) shouldBe Some("string")
      definitions("importPostTree").parameters.find(_.name == "fileBundle").map(_.datatype) shouldBe Some("filebundle")
    }

    "publish Blog image relationship metadata from CML" in {
      val subsystem = DefaultSubsystemFactory.default(Some("command"))
      val component = (new ComponentFactory)
        .create(ComponentCreate(subsystem, ComponentOrigin.Repository("test")))
        .primary
      val relationships = component.relationshipDefinitions.map(x => x.name -> x).toMap
      val images = relationships.getOrElse("BlogPost.images", fail("BlogPost.images relationship is missing"))

      images.kind shouldBe CmlEntityRelationshipDefinition.KindAssociation
      images.sourceEntityName shouldBe "BlogPost"
      images.targetEntityName shouldBe "Blob"
      images.storageMode shouldBe CmlEntityRelationshipDefinition.StorageAssociationRecord
      images.associationDomain shouldBe Some("blob_attachment")
      images.targetKind shouldBe Some("blob")
      images.multiplicity shouldBe Some("one-to-many")
      images.lifecyclePolicy shouldBe Some(CmlEntityRelationshipDefinition.LifecycleIndependent)
      relationships should not contain key ("BlogPost.inlineImages")
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
      given ExecutionContext = _with_authenticated_principal(
        ExecutionContext.withFrameworkCommandExecutionMode(component.logic.executionContext(), CommandExecutionMode.SyncJob),
        "archive_author"
      )
      val archiveId = _blob_id("blog_archive_1")
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
      val request = Request.of("blog", "blog", "importPostTree")
      val action = ImportPostTree.unsafeForTest(request, Record.dataAuto(
        "archiveBlobId" -> archiveId,
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
      given ExecutionContext = _with_authenticated_principal(baseContext, "filebundle_author", Map("authorAccountId" -> authorId.value))
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
      stored.securityAttributes.ownerId.id.value shouldBe "filebundle_author"
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
      given ExecutionContext = _with_authenticated_principal(baseContext, "editor_author", Map("authorAccountId" -> authorId.value))
      val firstBlob = _blob_id("editor_inline_first")
      val secondBlob = _blob_id("editor_inline_second")
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
      val createdStored = _success(EntityStore.standard().load[BlogPost](postId)).getOrElse(fail("created post missing"))
      val firstImageId = createdStored.contentAttributes.references.head.targetEntityId.getOrElse(fail("created image reference missing"))
      _associations(postId).map(_.targetEntityId) should contain (firstImageId)

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
      updatedStored.lifecycleAttributes.postStatus shouldBe PostStatus.Published
      val associations = _associations(postId)
      val secondImageId = updatedStored.contentAttributes.references.head.targetEntityId.getOrElse(fail("updated image reference missing"))
      associations.map(_.targetEntityId) should contain (secondImageId)
      associations.map(_.targetEntityId) should not contain firstImageId
      updatedStored.contentAttributes.references.map(_.targetEntityId).flatten shouldBe Vector(secondImageId)
      updatedStored.contentAttributes.references.map(_.originalRef).flatten shouldBe Vector(s"/web/blob/content/${secondBlob.value}")
      val visible = _record(_success(component.logic.executeAction(
        GetBlogPost.unsafeForTest(Request.of("blog", "blog", "getPost"), Record.dataAuto("id" -> postId)),
        summon[ExecutionContext]
      )))
      visible.getString("title") shouldBe Some("Editor Post Updated")
      visible.getString("content").getOrElse("") should not include ("<article class=\"textus-content\"><article")
      visible.getString("contentSource").getOrElse("") should include ("urn:textus:image:")
    }

    "save and register selected content markup and render public HTML" in {
      val subsystem = DefaultSubsystemFactory.default(Some("command"))
      val component = (new ComponentFactory)
        .create(ComponentCreate(subsystem, ComponentOrigin.Repository("test")))
        .primary
      given ExecutionContext = _with_authenticated_principal(
        ExecutionContext.withFrameworkCommandExecutionMode(component.logic.executionContext(), CommandExecutionMode.SyncJob),
        "markup_author"
      )
      val markdown = SaveEditorBlogPost.unsafeForTest(Request.of("blog", "blog", "saveEditorPost"), Record.dataAuto(
        "slug" -> "markdown-editor-post",
        "title" -> "Markdown Editor Post",
        "contentMarkup" -> "markdown-gfm",
        "content" -> "| A | B |\n|---|---|\n| 1 | 2 |",
        "publish" -> true
      ))

      val markdownCreated = _record(_success(component.logic.executeAction(markdown, summon[ExecutionContext])))
      val markdownId = markdownCreated.getAsC[EntityId]("id").toOption.flatten.getOrElse(fail("markdown response id missing"))
      val markdownStored = _success(EntityStore.standard().load[BlogPost](markdownId)).getOrElse(fail("markdown post missing"))
      markdownStored.contentAttributes.markup shouldBe Some(ContentMarkup.MarkdownGfm)
      markdownStored.contentAttributes.contentText shouldBe Some("| A | B |\n|---|---|\n| 1 | 2 |")

      val markdownPublic = _record(_success(component.logic.executeAction(
        GetBlogPost.unsafeForTest(Request.of("blog", "blog", "getPost"), Record.dataAuto("id" -> markdownId)),
        summon[ExecutionContext]
      )))
      markdownPublic.getString("contentMarkup") shouldBe Some("markdown-gfm")
      markdownPublic.getString("contentSource") shouldBe markdownStored.contentAttributes.contentText
      markdownPublic.fields.count(_.key == "content") shouldBe 1
      markdownPublic.getString("content").getOrElse("") should include ("""<article class="textus-content">""")
      markdownPublic.getString("content").getOrElse("") should include ("<table>")

      val smartdox = RegisterBlogPost.unsafeForTest(Request.of("blog", "blog", "registerPost"), Record.dataAuto(
        "slug" -> "smartdox-register-post",
        "title" -> "SmartDox Register Post",
        "contentMarkup" -> "smartdox",
        "content" -> "# SmartDox Title\n\nSmartDox *bold* text.",
        "publish" -> true
      ))
      val smartdoxCreated = _record(_success(component.logic.executeAction(smartdox, summon[ExecutionContext])))
      val smartdoxId = smartdoxCreated.getAsC[EntityId]("id").toOption.flatten.getOrElse(fail("smartdox response id missing"))
      val smartdoxStored = _success(EntityStore.standard().load[BlogPost](smartdoxId)).getOrElse(fail("smartdox post missing"))
      smartdoxStored.contentAttributes.markup shouldBe Some(ContentMarkup.SmartDox)

      val smartdoxPublic = _record(_success(component.logic.executeAction(
        GetBlogPost.unsafeForTest(Request.of("blog", "blog", "getPost"), Record.dataAuto("id" -> smartdoxId)),
        summon[ExecutionContext]
      )))
      smartdoxPublic.getString("contentMarkup") shouldBe Some("smartdox")
      smartdoxPublic.getString("contentSource") shouldBe smartdoxStored.contentAttributes.contentText
      smartdoxPublic.fields.count(_.key == "content") shouldBe 1
      smartdoxPublic.getString("content").getOrElse("") should include ("""<article class="textus-content">""")
      smartdoxPublic.getString("content").getOrElse("") should include ("<h1>SmartDox Title</h1>")
      smartdoxPublic.getString("content").getOrElse("") should include ("<strong>bold</strong>")
    }

    "generate unique editor slugs from titles when slug is omitted" in {
      val subsystem = DefaultSubsystemFactory.default(Some("command"))
      val component = (new ComponentFactory)
        .create(ComponentCreate(subsystem, ComponentOrigin.Repository("test")))
        .primary
      val baseContext = _with_deterministic_id_generation(ExecutionContext.withFrameworkCommandExecutionMode(
        component.logic.executionContext(),
        CommandExecutionMode.SyncJob
      ))
      val authorId = EntityId("textus", "author_slug_editor", EntityCollectionId("textus", "account", "account"))
      given ExecutionContext = _with_authenticated_principal(baseContext, "slug_author", Map("authorAccountId" -> authorId.value))

      val first = SaveEditorBlogPost.unsafeForTest(Request.of("blog", "blog", "saveEditorPost"), Record.dataAuto(
        "title" -> "SEO Slug Title",
        "content" -> "<article><p>First</p></article>"
      ))
      val second = SaveEditorBlogPost.unsafeForTest(Request.of("blog", "blog", "saveEditorPost"), Record.dataAuto(
        "title" -> "SEO Slug Title",
        "content" -> "<article><p>Second</p></article>"
      ))

      val firstRecord = _record(_success(component.logic.executeAction(first, summon[ExecutionContext])))
      val secondRecord = _record(_success(component.logic.executeAction(second, summon[ExecutionContext])))
      val firstId = firstRecord.getAsC[EntityId]("id").toOption.flatten.getOrElse(fail("first id missing"))
      val secondId = secondRecord.getAsC[EntityId]("id").toOption.flatten.getOrElse(fail("second id missing"))
      val firstPost = _success(EntityStore.standard().load[BlogPost](firstId)).getOrElse(fail("first post missing"))
      val secondPost = _success(EntityStore.standard().load[BlogPost](secondId)).getOrElse(fail("second post missing"))

      firstId.major shouldBe summon[ExecutionContext].major
      firstId.minor shouldBe summon[ExecutionContext].minor
      firstPost.toRecord().getString("name") shouldBe Some("seo-slug-title")
      secondPost.toRecord().getString("name") shouldBe Some("seo-slug-title-2")
      firstRecord.getString("shortid") shouldBe Some(firstId.parts.entropy)
      secondRecord.getString("shortid") shouldBe Some(secondId.parts.entropy)
      firstId.parts.entropy shouldBe "blogslug_000001"
      secondId.parts.entropy shouldBe "blogslug_000002"

      val update = SaveEditorBlogPost.unsafeForTest(Request.of("blog", "blog", "saveEditorPost"), Record.dataAuto(
        "id" -> firstId.parts.entropy,
        "title" -> "Changed Title",
        "content" -> "<article><p>Updated</p></article>"
      ))
      _success(component.logic.executeAction(update, summon[ExecutionContext]))
      val updated = _success(EntityStore.standard().load[BlogPost](firstId)).getOrElse(fail("updated post missing"))
      updated.toRecord().getString("name") shouldBe Some("seo-slug-title")

      val visibleByShortid = _record(_success(component.logic.executeAction(
        GetMyBlogPost.unsafeForTest(Request.of("blog", "blog", "getMyPost"), Record.dataAuto("id" -> firstId.parts.entropy)),
        summon[ExecutionContext]
      )))
      visibleByShortid.getString("shortid") shouldBe Some(firstId.parts.entropy)
      visibleByShortid.getString("entity_id") shouldBe Some(firstId.value)
    }

    "search and load only the current author's posts for the author dashboard" in {
      val subsystem = DefaultSubsystemFactory.default(Some("command"))
      val component = (new ComponentFactory)
        .create(ComponentCreate(subsystem, ComponentOrigin.Repository("test")))
        .primary
      val baseContext = ExecutionContext.withFrameworkCommandExecutionMode(
        component.logic.executionContext(),
        CommandExecutionMode.SyncJob
      )
      given ExecutionContext = _with_authenticated_principal(baseContext, "my_posts_author")
      val mineDraft = SaveEditorBlogPost.unsafeForTest(Request.of("blog", "blog", "saveEditorPost"), Record.dataAuto(
        "slug" -> "my-post-draft",
        "title" -> "My Dashboard Needle Draft",
        "content" -> "<article><p>Draft</p></article>"
      ))
      val minePublished = SaveEditorBlogPost.unsafeForTest(Request.of("blog", "blog", "saveEditorPost"), Record.dataAuto(
        "slug" -> "my-post-published",
        "title" -> "My Dashboard Needle Published",
        "content" -> "<article><p>Published</p></article>",
        "publish" -> true
      ))
      val myDraftRecord = _record(_success(component.logic.executeAction(mineDraft, summon[ExecutionContext])))
      val myPublishedRecord = _record(_success(component.logic.executeAction(minePublished, summon[ExecutionContext])))
      val otherContext = _with_authenticated_principal(baseContext, "other_posts_author")
      val other = SaveEditorBlogPost.unsafeForTest(Request.of("blog", "blog", "saveEditorPost"), Record.dataAuto(
        "slug" -> "other-post-draft",
        "title" -> "My Dashboard Needle Other",
        "content" -> "<article><p>Other</p></article>"
      ))
      val otherRecord = _record(_success(component.logic.executeAction(other, otherContext)))

      val search = SearchMyBlogPosts.unsafeForTest(Request.of("blog", "blog", "searchMyPosts"), Record.dataAuto(
        "text" -> "My Dashboard Needle",
        "limit" -> 10
      ))
      val result = _record(_success(component.logic.executeAction(search, summon[ExecutionContext])))

      result.getInt("totalCount") shouldBe Some(2)
      val rows = _records(result, "data")
      rows.map(_.getString("title").get).toSet shouldBe Set("My Dashboard Needle Draft", "My Dashboard Needle Published")
      val draftStatuses = rows.map(row => row.getString("post_status").orElse(row.getString("postStatus")))
      draftStatuses should contain (Some("draft"))
      draftStatuses should contain (Some("published"))

      val otherResult = _record(_success(component.logic.executeAction(search, otherContext)))
      otherResult.getInt("totalCount") shouldBe Some(1)
      _records(otherResult, "data").map(_.getString("title").get).toSet shouldBe Set("My Dashboard Needle Other")

      val getMine = GetMyBlogPost.unsafeForTest(Request.of("blog", "blog", "getMyPost"), Record.dataAuto(
        "id" -> myDraftRecord.getAsC[EntityId]("id").toOption.flatten.getOrElse(fail("my draft id missing"))
      ))
      _record(_success(component.logic.executeAction(getMine, summon[ExecutionContext]))).getString("title") shouldBe Some("My Dashboard Needle Draft")

      val getOther = GetMyBlogPost.unsafeForTest(Request.of("blog", "blog", "getMyPost"), Record.dataAuto(
        "id" -> otherRecord.getAsC[EntityId]("id").toOption.flatten.getOrElse(fail("other id missing"))
      ))
      component.logic.executeAction(getOther, summon[ExecutionContext]) shouldBe a[Consequence.Failure[_]]

      val publicGetDraft = GetBlogPost.unsafeForTest(Request.of("blog", "blog", "getPost"), Record.dataAuto(
        "id" -> myDraftRecord.getAsC[EntityId]("id").toOption.flatten.getOrElse(fail("my draft id missing"))
      ))
      component.logic.executeAction(publicGetDraft, summon[ExecutionContext]) shouldBe a[Consequence.Failure[_]]
      val publicGetPublished = GetBlogPost.unsafeForTest(Request.of("blog", "blog", "getPost"), Record.dataAuto(
        "id" -> myPublishedRecord.getAsC[EntityId]("id").toOption.flatten.getOrElse(fail("my published id missing"))
      ))
      _record(_success(component.logic.executeAction(publicGetPublished, summon[ExecutionContext]))).getString("title") shouldBe Some("My Dashboard Needle Published")
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
      given ExecutionContext = _with_authenticated_principal(baseContext, "editor_owner", Map("authorAccountId" -> authorId.value))
      val create = SaveEditorBlogPost.unsafeForTest(Request.of("blog", "blog", "saveEditorPost"), Record.dataAuto(
        "slug" -> "owned-editor-post",
        "title" -> "Owned Editor Post",
        "content" -> "<article><p>Original</p></article>"
      ))
      val created = _record(_success(component.logic.executeAction(create, summon[ExecutionContext])))
      val postId = created.getAsC[EntityId]("id").toOption.flatten.getOrElse(fail("response id missing"))
      val otherContext = _with_authenticated_principal(baseContext, "editor_other", Map("authorAccountId" -> otherId.value))
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
      stored.lifecycleAttributes.postStatus shouldBe PostStatus.Draft
    }

    "preserve editor content with failed inline image markers without stale inline bindings" in {
      val subsystem = DefaultSubsystemFactory.default(Some("command"))
      val component = (new ComponentFactory)
        .create(ComponentCreate(subsystem, ComponentOrigin.Repository("test")))
        .primary
      val baseContext = ExecutionContext.withFrameworkCommandExecutionMode(
        component.logic.executionContext(),
        CommandExecutionMode.SyncJob
      )
      val authorId = EntityId("textus", "author_editor_invalid_blob", EntityCollectionId("textus", "account", "account"))
      given ExecutionContext = _with_authenticated_principal(baseContext, "editor_invalid_blob", Map("authorAccountId" -> authorId.value))
      val validBlob = _blob_id("editor_valid_inline")
      val missingBlob = _blob_id("editor_missing_inline")
      _success(BlobPayloadSupport.putManagedPayload(
        component = component,
        id = validBlob,
        kind = BlobKind.Image,
        filename = Some("valid.png"),
        contentType = ContentType.IMAGE_PNG,
        payload = Bag.binary("valid".getBytes(java.nio.charset.StandardCharsets.UTF_8))
      ))
      val create = SaveEditorBlogPost.unsafeForTest(Request.of("blog", "blog", "saveEditorPost"), Record.dataAuto(
        "slug" -> "editor_invalid_blob",
        "title" -> "Editor Invalid Blob",
        "content" -> s"""<article><p>Original</p><img src="/web/blob/content/${validBlob.value}" alt=""></article>"""
      ))
      val created = _record(_success(component.logic.executeAction(create, summon[ExecutionContext])))
      val postId = created.getAsC[EntityId]("id").toOption.flatten.getOrElse(fail("response id missing"))
      val update = SaveEditorBlogPost.unsafeForTest(Request.of("blog", "blog", "saveEditorPost"), Record.dataAuto(
        "id" -> postId,
        "slug" -> "editor_invalid_blob",
        "title" -> "Should Not Save",
        "content" -> s"""<article><p>Broken</p><img src="/web/blob/content/${missingBlob.value}" alt=""></article>""",
        "publish" -> true
      ))

      val updated = _record(_success(component.logic.executeAction(update, summon[ExecutionContext])))
      updated.getInt("inlineImageCount") shouldBe Some(0)

      val stored = _success(EntityStore.standard().load[BlogPost](postId)).getOrElse(fail("post missing"))
      stored.toRecord().getString("title") shouldBe Some("Should Not Save")
      stored.lifecycleAttributes.postStatus shouldBe PostStatus.Published
      stored.contentAttributes.content.map(_.value).getOrElse("") should include ("textus:image-normalization-failed")
      stored.contentAttributes.references shouldBe empty
      _associations(postId).map(_.targetEntityId) shouldBe empty
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
      given ExecutionContext = _with_authenticated_principal(baseContext, "editor_duplicate_inline", Map("authorAccountId" -> authorId.value))
      val blobId = _blob_id("editor_duplicate_inline_blob")
      _success(BlobPayloadSupport.putManagedPayload(
        component = component,
        id = blobId,
        kind = BlobKind.Image,
        filename = Some("duplicate.png"),
        contentType = ContentType.IMAGE_PNG,
        payload = Bag.binary("duplicate".getBytes(java.nio.charset.StandardCharsets.UTF_8))
      ))
      val create = SaveEditorBlogPost.unsafeForTest(Request.of("blog", "blog", "saveEditorPost"), Record.dataAuto(
        "slug" -> "editor_duplicate_inline",
        "title" -> "Editor Duplicate Inline",
        "content" -> s"""<article><img src="/web/blob/content/${blobId.value}" alt=""><p>Again</p><img src="/web/blob/content/${blobId.value}" alt=""></article>"""
      ))

      val created = _record(_success(component.logic.executeAction(create, summon[ExecutionContext])))

      created.getInt("inlineImageCount") shouldBe Some(2)
      val postId = created.getAsC[EntityId]("id").toOption.flatten.getOrElse(fail("response id missing"))
      val stored = _success(EntityStore.standard().load[BlogPost](postId)).getOrElse(fail("post missing"))
      val imageIds = stored.contentAttributes.references.map(_.targetEntityId).flatten
      imageIds.distinct.size shouldBe 1
      _associations(postId).filter(_.targetEntityId == imageIds.head).map(_.role) shouldBe Vector("inline")
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
      given ExecutionContext = _with_authenticated_principal(baseContext, "editor_picker_user")
      val imageBlob = _blob_id("picker_image_blob")
      val attachmentBlob = _blob_id("picker_attachment_blob")
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
      rows.head.getString("url") shouldBe Some(s"/web/blob/content/${imageBlob.parts.entropy}")
    }

    "place the Blog Web app in CAR metadata and Web app resource roots" in {
      val root = java.nio.file.Paths.get(".").toAbsolutePath.normalize()

      java.nio.file.Files.exists(root.resolve("src/main/car/web/web.yaml")) shouldBe true
      java.nio.file.Files.exists(root.resolve("src/main/web/web.yaml")) shouldBe false
      java.nio.file.Files.exists(root.resolve("src/main/web/index.html")) shouldBe true
      java.nio.file.Files.exists(root.resolve("src/main/web/publicblogs.html")) shouldBe true
      java.nio.file.Files.exists(root.resolve("src/main/web/userblogs.html")) shouldBe true
      java.nio.file.Files.exists(root.resolve("src/main/web/new.html")) shouldBe true
      java.nio.file.Files.exists(root.resolve("src/main/web/update.html")) shouldBe true
      java.nio.file.Files.exists(root.resolve("src/main/web/new__success.html")) shouldBe true
      java.nio.file.Files.exists(root.resolve("src/main/web/publicblogs__success.html")) shouldBe true
      java.nio.file.Files.exists(root.resolve("src/main/web/publicpost__success.html")) shouldBe true
      java.nio.file.Files.exists(root.resolve("src/main/web/userblogs__success.html")) shouldBe true
      java.nio.file.Files.exists(root.resolve("src/main/web/userpost__success.html")) shouldBe true
      java.nio.file.Files.exists(root.resolve("src/main/web/update__error.html")) shouldBe true
      java.nio.file.Files.exists(root.resolve("src/main/web/WEB-INF/layouts/default.html")) shouldBe true
      java.nio.file.Files.exists(root.resolve("src/main/web/WEB-INF/layouts/reader.html")) shouldBe true
      java.nio.file.Files.exists(root.resolve("src/main/web/WEB-INF/layouts/my.html")) shouldBe true
      java.nio.file.Files.exists(root.resolve("src/main/web/WEB-INF/layouts/edit.html")) shouldBe true
      java.nio.file.Files.exists(root.resolve("src/main/web/WEB-INF/layouts/result-edit.html")) shouldBe true
      java.nio.file.Files.exists(root.resolve("src/main/web/WEB-INF/layouts/plain-result.html")) shouldBe true
      java.nio.file.Files.exists(root.resolve("src/main/web/WEB-INF/partials/head.html")) shouldBe true
      java.nio.file.Files.exists(root.resolve("src/main/web/WEB-INF/partials/navigation-public.html")) shouldBe true
      java.nio.file.Files.exists(root.resolve("src/main/web/WEB-INF/partials/navigation-user.html")) shouldBe true
      java.nio.file.Files.exists(root.resolve("src/main/web/WEB-INF/partials/navigation-edit.html")) shouldBe true
      java.nio.file.Files.exists(root.resolve("src/main/web/WEB-INF/partials/feed-footer.html")) shouldBe true
      java.nio.file.Files.exists(root.resolve("src/main/web/WEB-INF/partials/scripts.html")) shouldBe true
      java.nio.file.Files.exists(root.resolve("src/main/web/assets/blog.css")) shouldBe true
      java.nio.file.Files.exists(root.resolve("src/main/web/assets/blog.js")) shouldBe true
      java.nio.file.Files.exists(root.resolve("src/main/web/blog/index.html")) shouldBe false
      java.nio.file.Files.exists(root.resolve("src/main/web/blog-my/index.html")) shouldBe false
      java.nio.file.Files.exists(root.resolve("src/main/web/blog-edit/index.html")) shouldBe false

      val webYaml = java.nio.file.Files.readString(root.resolve("src/main/car/web/web.yaml"))
      webYaml should include ("/web/blog")
      webYaml should include ("layout: default")
      webYaml should include ("composition: article")
      webYaml should include ("pages:")
      webYaml should include ("publicblogs:\n    layout: reader")
      webYaml should include ("publicblogs:\n    layout: reader\n    mode: article")
      webYaml should include ("userblogs:\n    layout: my")
      webYaml should include ("userblogs:\n    layout: my\n    mode: article")
      webYaml should include ("new:\n    layout: edit")
      webYaml should include ("new:\n    layout: edit\n    mode: article")
      webYaml should include ("update:\n    layout: edit")
      webYaml should include ("update:\n    layout: edit\n    mode: article")
      webYaml should include ("blog-component.blog.search-posts:\n    enabled: true\n    layout: reader")
      webYaml should include ("blog-component.blog.search-my-posts:\n    enabled: true\n    layout: my")
      webYaml should include ("blog-component.blog.get-post:\n    enabled: true\n    layout: reader")
      webYaml should include ("blog-component.blog.get-my-post:\n    enabled: true\n    layout: result-edit")
      webYaml should not include "blog-my"
      webYaml should not include "blog-edit"
      webYaml should include ("blog-component.blog.search-my-posts: protected")
      webYaml should include ("blog-component.blog.get-my-post: protected")

      val publicHtml = java.nio.file.Files.readString(root.resolve("src/main/web/publicblogs.html"))
      publicHtml should not include ("<!doctype html")
      publicHtml should not include ("<head>")
      publicHtml should not include ("<main")
      publicHtml should not include ("navbar navbar-expand-lg")
      publicHtml should not include ("/web/assets/bootstrap.min.css")
      publicHtml should not include ("/web/assets/textus-widgets.css")
      publicHtml should include ("/form/blog-component/blog/search-posts")
      publicHtml should include ("data-tag-nav")
      publicHtml should include ("data-list-section")
      publicHtml should include ("data-detail-section")
      publicHtml should include ("card mb-3")
      publicHtml should include ("row g-2 align-items-end")
      publicHtml should include ("alert alert-info")
      publicHtml should include ("data-tag-filter hidden")
      publicHtml should include ("d-none flex-wrap")
      publicHtml should include ("Browse tags")
      publicHtml should include ("data-tag-filter-input")
      publicHtml should include ("placeholder=\"Tag path\"")
      publicHtml should include ("data-tag-clear")
      publicHtml should not include ("/rest/v1/blog/blog/atomFeed")
      publicHtml should not include ("list-toolbar")
      publicHtml should not include ("reader-pane")
      publicHtml should not include ("article-pane")
      publicHtml should not include ("class=\"tag-nav-panel")
      publicHtml should not include ("feed-tools")
      publicHtml should not include ("<a class=\"nav-link\" href=\"/rest/v1/blog/blog/atomFeed\"")
      publicHtml should not include "textus-list"
      publicHtml should not include "data-editor-form"
      publicHtml should not include "data-upload-form"

      val publicNavigation = java.nio.file.Files.readString(root.resolve("src/main/web/WEB-INF/partials/navigation-public.html"))
      publicNavigation should include ("returnTo=%2Fweb%2Fblog%2Fuserblogs")
      publicNavigation should include ("data-my-posts-link")
      publicNavigation should include ("data-login-link")
      publicNavigation should include ("data-signup-link")
      publicNavigation should include ("data-logout-form")
      val feedFooter = java.nio.file.Files.readString(root.resolve("src/main/web/WEB-INF/partials/feed-footer.html"))
      feedFooter should include ("/rest/v1/blog/blog/atomFeed")
      val headPartial = java.nio.file.Files.readString(root.resolve("src/main/web/WEB-INF/partials/head.html"))
      headPartial should include ("/web/assets/bootstrap.min.css")
      headPartial should include ("/web/assets/textus-widgets.css")
      headPartial should include ("/web/blog/assets/blog.css")
      val scriptsPartial = java.nio.file.Files.readString(root.resolve("src/main/web/WEB-INF/partials/scripts.html"))
      scriptsPartial should include ("/web/assets/bootstrap.bundle.min.js")
      scriptsPartial should include ("/web/assets/textus-widgets.js")
      scriptsPartial should include ("/web/blog/assets/blog.js")
      val readerLayout = java.nio.file.Files.readString(root.resolve("src/main/web/WEB-INF/layouts/reader.html"))
      readerLayout should include ("data-page=\"reader\"")
      readerLayout should include ("<main class=\"container py-4\">")
      readerLayout should include ("textus-app-article")
      readerLayout should include ("${partial.navigation-public}")
      readerLayout should include ("${content}")
      readerLayout should include ("${partial.feed-footer}")
      val myLayout = java.nio.file.Files.readString(root.resolve("src/main/web/WEB-INF/layouts/my.html"))
      myLayout should include ("data-page=\"my\"")
      myLayout should include ("textus-app-article")
      myLayout should include ("${partial.navigation-user}")
      val editLayout = java.nio.file.Files.readString(root.resolve("src/main/web/WEB-INF/layouts/edit.html"))
      editLayout should include ("data-page=\"edit\"")
      editLayout should include ("textus-app-article")
      editLayout should include ("${partial.navigation-edit}")
      val resultEditLayout = java.nio.file.Files.readString(root.resolve("src/main/web/WEB-INF/layouts/result-edit.html"))
      resultEditLayout should include ("data-page=\"result-edit\"")
      resultEditLayout should include ("textus-app-article")

      val myHtml = java.nio.file.Files.readString(root.resolve("src/main/web/userblogs.html"))
      myHtml should not include ("<!doctype html")
      myHtml should not include ("<main")
      myHtml should not include ("navbar navbar-expand-lg")
      myHtml should include ("data-my-post-list")
      myHtml should include ("data-open-upload-dialog")
      myHtml should include ("data-upload-form")
      myHtml should include ("/web/blog/new")
      java.nio.file.Files.readString(root.resolve("src/main/web/WEB-INF/partials/navigation-user.html")) should include ("<a class=\"nav-link\" href=\"/web/blog/jobs\">My jobs</a>")
      myHtml should not include ("btn btn-outline-secondary\" href=\"/web/blog/jobs\"")
      myHtml should include ("/form/blog-component/blog/search-my-posts")
      myHtml should include ("card mb-3")
      myHtml should include ("row g-2 align-items-end")
      myHtml should include ("class=\"modal fade\"")
      myHtml should include ("data-bs-dismiss=\"modal\"")
      myHtml should include ("id=\"blogUploadModalTitle\"")
      myHtml should not include ("accept=\".zip,application/zip\"")
      myHtml should not include "textus-list"
      myHtml should not include ("<dialog")

      val newHtml = java.nio.file.Files.readString(root.resolve("src/main/web/new.html"))
      newHtml should not include ("<!doctype html")
      newHtml should not include ("<main")
      newHtml should not include ("navbar navbar-expand-lg")
      newHtml should include ("data-editor-form")
      newHtml should include ("textus.form.page")
      newHtml should include ("value=\"new\"")
      newHtml should include ("Post metadata")
      newHtml should include ("Tags and publishing")
      newHtml should include ("class=\"card\"")
      newHtml should include ("name=\"contentMarkup\"")
      newHtml should include ("data-editor-markup")
      newHtml should include ("value=\"markdown-gfm\"")
      newHtml should not include ("<article>\\n  <p></p>\\n</article>")
      newHtml should include ("data-open-image-picker")
      newHtml should include ("data-image-dialog")
      newHtml should include ("class=\"modal fade\"")
      newHtml should include ("id=\"blogImageModalTitle\"")
      newHtml should include ("data-tag-input")
      newHtml should include ("data-tag-suggestions")
      newHtml should include ("placeholder=\"topic, topic.subtopic\"")
      newHtml should not include ("<dialog")

      val updateHtml = java.nio.file.Files.readString(root.resolve("src/main/web/update.html"))
      updateHtml should not include ("<!doctype html")
      updateHtml should not include ("<main")
      updateHtml should not include ("navbar navbar-expand-lg")
      updateHtml should include ("data-editor-form")
      updateHtml should include ("value=\"update\"")
      updateHtml should include ("Post metadata")
      updateHtml should include ("Tags and publishing")
      updateHtml should include ("class=\"card\"")
      updateHtml should include ("name=\"id\"")
      updateHtml should include ("name=\"contentMarkup\"")
      updateHtml should include ("data-editor-markup")
      updateHtml should include ("Without JavaScript")
      updateHtml should include ("class=\"modal fade\"")
      updateHtml should include ("data-tag-input")
      updateHtml should include ("data-tag-suggestions")
      updateHtml should include ("placeholder=\"topic, topic.subtopic\"")
      updateHtml should not include ("<dialog")
      val blogJs = java.nio.file.Files.readString(root.resolve("src/main/web/assets/blog.js"))
      blogJs should not include ("addEventListener(\"submit\", saveEditorPost)")
      blogJs should not include "paths.save"
      blogJs should include ("normalizeTagInputs")
      blogJs should include ("normalizedTagValues")
      blogJs should include ("renderTagSuggestions")
      blogJs should include ("appendTagPath")
      blogJs should include ("function imageReferenceSnippet")
      blogJs should include ("function syncEditorMarkupValue")
      blogJs should include ("![](${src})")
      blogJs should include ("[[${src}]]")
      blogJs should include ("post ? contentSource(post) : \"\"")
      blogJs should include ("is-active")
      blogJs should include ("data-list-section")
      blogJs should include ("data-detail-section")
      blogJs should include ("classList.toggle(\"d-none\"")
      blogJs should include ("classList.toggle(\"d-flex\"")
      blogJs should include ("els.backToList.href = publicListHref()")
      blogJs should include ("function publicListHref()")
      blogJs should include ("?tag=${encodeURIComponent(state.activeTag)}")
      blogJs should include ("function showModalElement")
      blogJs should include ("function hideModalElement")
      blogJs should include ("window.bootstrap?.Modal?.getOrCreateInstance")
      blogJs should not include ("reader-detail-mode")
      val blogCss = java.nio.file.Files.readString(root.resolve("src/main/web/assets/blog.css"))
      blogCss should not include ("list-toolbar")
      blogCss should not include ("reader-detail-mode")
      blogCss should not include ("article-pane")
      blogCss should not include (".tag-nav-panel")
      blogCss should not include ("feed-tools")
      blogCss should not include ("dialog-head")
      blogCss should not include ("image-dialog")
      blogCss should include (".tag-filter:has(span:empty)")

      val publicResultHtml = java.nio.file.Files.readString(root.resolve("src/main/web/publicblogs__success.html"))
      publicResultHtml should not include ("<!doctype html")
      publicResultHtml should not include ("<main")
      publicResultHtml should not include ("navbar navbar-expand-lg")
      publicResultHtml should include ("textus:card-list")
      publicResultHtml should include ("detail-param-textus.form.page=\"publicpost\"")
      publicResultHtml should include ("card mb-3")
      publicResultHtml should include ("row g-2 align-items-end")
      publicResultHtml should include ("alert alert-info")
      publicResultHtml should include ("data-tag-filter")
      publicResultHtml should include ("Tag:")
      publicResultHtml should include ("${form.tag}")
      publicResultHtml should include ("/web/blog/publicblogs")
      publicResultHtml should not include ("<footer class=\"container py-3 border-top\">")
      publicResultHtml should not include ("/rest/v1/blog/blog/atomFeed")
      val publicPostHtml = java.nio.file.Files.readString(root.resolve("src/main/web/publicpost__success.html"))
      publicPostHtml should not include ("<!doctype html")
      publicPostHtml should not include ("<main")
      publicPostHtml should not include ("navbar navbar-expand-lg")
      publicPostHtml should include ("textus:record-card")
      publicPostHtml should include ("textus:html-field")
      val userPostHtml = java.nio.file.Files.readString(root.resolve("src/main/web/userpost__success.html"))
      userPostHtml should not include ("<!doctype html")
      userPostHtml should not include ("<main")
      userPostHtml should not include ("navbar navbar-expand-lg")
      userPostHtml should include ("/form/blog-component/blog/save-editor-post")
      userPostHtml should include ("value=\"${result.body.shortid}\"")
      userPostHtml should include ("name=\"publish\" value=\"${result.body.post_status}\"")
      userPostHtml should include ("Post metadata")
      userPostHtml should include ("class=\"card\"")
      userPostHtml should include ("name=\"contentMarkup\"")
      userPostHtml should include ("data-editor-markup-value")
      userPostHtml should include ("data-current-content-markup=\"${result.body.contentMarkup}\"")
      userPostHtml should include ("${result.body.contentSource}</textarea>")
      userPostHtml should include ("data-tag-input")
      userPostHtml should include ("data-tag-suggestions")
      userPostHtml should not include ("data-page=\"result-edit\"")
      userPostHtml should not include ("/web/blog/assets/blog.js")
      userPostHtml should include ("placeholder=\"topic, topic.subtopic\"")
      userPostHtml should include ("/web/blog/jobs")
      userPostHtml should not include "update?id=${result.body.entity_id}"
      publicResultHtml should include ("detail-param-id=\"{shortid}\"")
      val userResultHtml = java.nio.file.Files.readString(root.resolve("src/main/web/userblogs__success.html"))
      userResultHtml should not include ("<!doctype html")
      userResultHtml should not include ("<main")
      userResultHtml should not include ("navbar navbar-expand-lg")
      userResultHtml should include ("detail-param-id=\"{shortid}\"")
      userResultHtml should include ("card mb-3")
      userResultHtml should include ("row g-2 align-items-end")
      userResultHtml should include ("alert alert-info")
      userResultHtml should include ("data-tag-filter")
      userResultHtml should include ("Tag:")
      userResultHtml should include ("${form.tag}")
      userResultHtml should include ("/web/blog/userblogs")
    }

    "register existing Blob images through BlobAttachment Association" in {
      val subsystem = DefaultSubsystemFactory.default(Some("command"))
      val component = (new ComponentFactory)
        .create(ComponentCreate(subsystem, ComponentOrigin.Repository("test")))
        .primary
      given ExecutionContext = _with_authenticated_principal(
        ExecutionContext.withFrameworkCommandExecutionMode(component.logic.executionContext(), CommandExecutionMode.SyncJob),
        "author_assoc"
      )
      val blobId = _blob_id("assoc_primary_blob")
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
      given ExecutionContext = _with_authenticated_principal(
        ExecutionContext.withFrameworkCommandExecutionMode(component.logic.executionContext(), CommandExecutionMode.SyncJob),
        "existing_image_author"
      )
      val action = RegisterBlogPost.unsafeForTest(
        Request.of("blog", "blog", "registerPost"),
        Record.dataAuto(
          "slug" -> "existing-image-id",
          "title" -> "Existing Image ID",
          "content" -> "<article><p>Body</p></article>",
          "entityImages" -> Vector(Record.dataAuto(
            "existingImageId" -> EntityId("textus_blog", "legacy_image", EntityCollectionId("textus_blog", "blog_component", "image_asset")),
            "role" -> "primary"
          ))
        )
      )

      val result = component.logic.executeAction(action, summon[ExecutionContext])

      result shouldBe a[Consequence.Failure[_]]
      val postId = EntityId(summon[ExecutionContext].major, summon[ExecutionContext].minor, BlogPost.collectionId, entropy = Some("existing_image_id"))
      _success(EntityStore.standard().load[BlogPost](postId)) shouldBe None
    }

    "preserve direct registerPost path-only image refs as failed inline markers" in {
      val subsystem = DefaultSubsystemFactory.default(Some("command"))
      val component = (new ComponentFactory)
        .create(ComponentCreate(subsystem, ComponentOrigin.Repository("test")))
        .primary
      given ExecutionContext = _with_authenticated_principal(
        ExecutionContext.withFrameworkCommandExecutionMode(component.logic.executionContext(), CommandExecutionMode.SyncJob),
        "path_only_author"
      )
      val request = Request.of("blog", "blog", "registerPost")
      val action = RegisterBlogPost.unsafeForTest(request, Record.dataAuto(
        "slug" -> "path-only",
        "title" -> "Path Only",
        "content" -> """<article><img src="images/inline.png"></article>"""
      ))

      val created = _record(_success(component.logic.executeAction(action, summon[ExecutionContext])))

      created.getInt("inlineImageCount") shouldBe Some(0)
      val postId = created.getAsC[EntityId]("id").toOption.flatten.getOrElse(fail("response id missing"))
      val stored = _success(EntityStore.standard().load[BlogPost](postId)).getOrElse(fail("post missing"))
      stored.contentAttributes.content.map(_.value).getOrElse("") should include ("textus:image-normalization-failed")
      stored.contentAttributes.references shouldBe empty
    }

    "reject supplied contentReferences on registerPost input" in {
      val subsystem = DefaultSubsystemFactory.default(Some("command"))
      val component = (new ComponentFactory)
        .create(ComponentCreate(subsystem, ComponentOrigin.Repository("test")))
        .primary
      given ExecutionContext = _with_authenticated_principal(
        ExecutionContext.withFrameworkCommandExecutionMode(component.logic.executionContext(), CommandExecutionMode.SyncJob),
        "supplied_reference_author"
      )
      val request = Request.of("blog", "blog", "registerPost")
      val action = RegisterBlogPost.unsafeForTest(request, Record.dataAuto(
        "slug" -> "supplied-reference-bypass",
        "title" -> "Supplied Reference Bypass",
        "content" -> """<article><p>valid content</p></article>""",
        "contentReferences" -> Vector(Record.dataAuto(
          "elementKind" -> "img",
          "attributeName" -> "src",
          "referenceKind" -> "blob",
          "targetEntityId" -> _blob_id("supplied_reference_blob").value
        ))
      ))

      val result = component.logic.executeAction(action, summon[ExecutionContext])

      result shouldBe a[Consequence.Failure[_]]
      result match {
        case Consequence.Failure(c) => c.show should include ("contentReferences is server-derived metadata")
        case _ => fail("expected contentReferences rejection")
      }
      val postId = EntityId(summon[ExecutionContext].major, summon[ExecutionContext].minor, BlogPost.collectionId, entropy = Some("supplied_reference_bypass"))
      _success(EntityStore.standard().load[BlogPost](postId)) shouldBe None
    }

    "reject supplied contentReferences on saveEditorPost input" in {
      val subsystem = DefaultSubsystemFactory.default(Some("command"))
      val component = (new ComponentFactory)
        .create(ComponentCreate(subsystem, ComponentOrigin.Repository("test")))
        .primary
      given ExecutionContext = _with_authenticated_principal(
        ExecutionContext.withFrameworkCommandExecutionMode(component.logic.executionContext(), CommandExecutionMode.SyncJob),
        "supplied_editor_reference_author"
      )
      val request = Request.of("blog", "blog", "saveEditorPost")
      val action = SaveEditorBlogPost.unsafeForTest(request, Record.dataAuto(
        "title" -> "Supplied Editor Reference",
        "content" -> """<article><p>valid content</p></article>""",
        "contentReferences" -> Vector(Record.dataAuto(
          "elementKind" -> "img",
          "attributeName" -> "src",
          "referenceKind" -> "blob",
          "targetEntityId" -> _blob_id("supplied_editor_reference_blob").value
        ))
      ))

      val result = component.logic.executeAction(action, summon[ExecutionContext])

      result shouldBe a[Consequence.Failure[_]]
      result match {
        case Consequence.Failure(c) => c.show should include ("contentReferences is server-derived metadata")
        case _ => fail("expected contentReferences rejection")
      }
    }

    "derive representative images from Associations and enforce public lifecycle visibility" in {
      val subsystem = DefaultSubsystemFactory.default(Some("command"))
      val component = (new ComponentFactory)
        .create(ComponentCreate(subsystem, ComponentOrigin.Repository("test")))
        .primary
      given ExecutionContext = _with_authenticated_principal(
        ExecutionContext.withFrameworkCommandExecutionMode(component.logic.executionContext(), CommandExecutionMode.SyncJob),
        "author_lifecycle"
      )
      val coverBlobId = _blob_id("lifecycle_cover_blob")
      val galleryBlobId = _blob_id("lifecycle_gallery_blob")
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
        "publisherAccountId" -> "author_lifecycle"
      ))
      val publishedUpdate = _record(_success(component.logic.executeAction(publish, managerContext)))
      publishedUpdate.getString("post_status") shouldBe Some("published")
      val storedPublished = _success(EntityStore.standard().load[BlogPost](postId)).getOrElse(fail("stored post missing"))
      storedPublished.id.value shouldBe postId.value
      storedPublished.lifecycleAttributes.postStatus shouldBe PostStatus.Published
      storedPublished.toRecord().getString("aliveness") shouldBe Some("alive")

      val published = _record(_success(component.logic.executeAction(get, summon[ExecutionContext])))
      published.getAsC[EntityId]("representativeBlobId").toOption.flatten shouldBe Some(coverBlobId)
      published.getAny("primaryImageId") shouldBe None
      val search = SearchBlogPosts.unsafeForTest(Request.of("blog", "blog", "searchPosts"), Record.dataAuto("text" -> "Lifecycle Association"))
      val hiddenSearch = _record(_success(component.logic.executeAction(search, summon[ExecutionContext])))
      hiddenSearch.getInt("fetchedCount") shouldBe Some(1)

      val deactivate = DeactivateBlogPost.unsafeForTest(Request.of("blog", "blog", "deactivatePost"), Record.dataAuto(
        "id" -> postId,
        "operatorAccountId" -> "author_lifecycle"
      ))
      _success(component.logic.executeAction(deactivate, managerContext))

      component.logic.executeAction(get, summon[ExecutionContext]) shouldBe a[Consequence.Failure[_]]
    }

    "derive representative image from first inline Association fallback" in {
      val subsystem = DefaultSubsystemFactory.default(Some("command"))
      val component = (new ComponentFactory)
        .create(ComponentCreate(subsystem, ComponentOrigin.Repository("test")))
        .primary
      given ExecutionContext = _with_authenticated_principal(
        ExecutionContext.withFrameworkCommandExecutionMode(component.logic.executionContext(), CommandExecutionMode.SyncJob),
        "author_inline_representative"
      )
      val inline1 = _blob_id("inline_representative_1")
      val inline2 = _blob_id("inline_representative_2")
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
          "content" -> s"""<article><p>Body</p><img src="/web/blob/content/${inline1.value}"><img src="/web/blob/content/${inline2.value}"></article>""",
          "publish" -> true
        )
      )
      val registered = _record(_success(component.logic.executeAction(register, summon[ExecutionContext])))
      val postId = registered.getAsC[EntityId]("id").toOption.flatten.getOrElse(fail("response id missing"))
      val get = GetBlogPost.unsafeForTest(Request.of("blog", "blog", "getPost"), Record.dataAuto("id" -> postId))

      val published = _record(_success(component.logic.executeAction(get, summon[ExecutionContext])))

      published.getAsC[EntityId]("representativeBlobId").toOption.flatten shouldBe Some(inline1)
      published.getString("representativeBlobUrl") shouldBe Some(s"/web/blob/content/${inline1.parts.entropy}")
    }

    "derive representative image URL from Blob metadata accessUrl" in {
      val subsystem = DefaultSubsystemFactory.default(Some("command"))
      val component = (new ComponentFactory)
        .create(ComponentCreate(subsystem, ComponentOrigin.Repository("test")))
        .primary
      given ExecutionContext = _with_authenticated_principal(
        ExecutionContext.withFrameworkCommandExecutionMode(component.logic.executionContext(), CommandExecutionMode.SyncJob),
        "author_external_representative"
      )
      val externalBlobId = _blob_id("external_representative_cover")
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
      given ExecutionContext = _with_authenticated_principal(
        ExecutionContext.withFrameworkCommandExecutionMode(component.logic.executionContext(), CommandExecutionMode.SyncJob),
        "author_search"
      )
      val needle = "BI02 Search Needle"
      val draft = RegisterBlogPost.unsafeForTest(
        Request.of("blog", "blog", "registerPost"),
        Record.dataAuto(
          "slug" -> "bi02-search-draft",
          "title" -> s"$needle Draft",
          "content" -> "<article><p>Draft body</p></article>",
          "publish" -> false
        )
      )
      val published = RegisterBlogPost.unsafeForTest(
        Request.of("blog", "blog", "registerPost"),
        Record.dataAuto(
          "slug" -> "bi02-search-published",
          "title" -> s"$needle Published",
          "content" -> "<article><p>Published body</p></article>",
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

    "sync BlogPost tags in shared blog tag space and filter public posts by parent tag" in {
      val subsystem = DefaultSubsystemFactory.default(Some("command"))
      val component = (new ComponentFactory)
        .create(ComponentCreate(subsystem, ComponentOrigin.Repository("test")))
        .primary
      given ExecutionContext = _with_authenticated_principal(
        ExecutionContext.withFrameworkCommandExecutionMode(component.logic.executionContext(), CommandExecutionMode.SyncJob),
        "author_tags"
      )
      val tagRepository = TagRepository.entityStore()
      val root = _success(tagRepository.create(TagCreate(None, "phase20blog", None, tagSpace = TagSpace.Blog)))
      val child = _success(tagRepository.create(TagCreate(None, "scala", Some(root.id), tagSpace = TagSpace.Blog)))
      val other = _success(tagRepository.create(TagCreate(None, "life", None, tagSpace = TagSpace.Blog)))

      val tagged = RegisterBlogPost.unsafeForTest(
        Request.of("blog", "blog", "registerPost"),
        Record.dataAuto(
          "slug" -> "tagged-scala-post",
          "title" -> "Tagged Scala Post",
          "content" -> "<article><p>Tagged</p></article>",
          "publish" -> true,
          "tags" -> Vector(child.path)
        )
      )
      val untagged = RegisterBlogPost.unsafeForTest(
        Request.of("blog", "blog", "registerPost"),
        Record.dataAuto(
          "slug" -> "tagged-life-post",
          "title" -> "Tagged Life Post",
          "content" -> "<article><p>Other</p></article>",
          "publish" -> true,
          "tags" -> Vector(other.path)
        )
      )
      val taggedRecord = _record(_success(component.logic.executeAction(tagged, summon[ExecutionContext])))
      _success(component.logic.executeAction(untagged, summon[ExecutionContext]))
      val postId = taggedRecord.getAsC[EntityId]("id").toOption.flatten.getOrElse(fail("tagged post id missing"))

      val detail = _record(_success(component.logic.executeAction(
        GetBlogPost.unsafeForTest(Request.of("blog", "blog", "getPost"), Record.dataAuto("id" -> postId)),
        summon[ExecutionContext]
      )))
      val detailTags = _records(detail, "tags")
      detailTags.map(_.getString("path")) should contain (Some(child.path))

      val parentSearch = _record(_success(component.logic.executeAction(
        SearchBlogPosts.unsafeForTest(Request.of("blog", "blog", "searchPosts"), Record.dataAuto("tag" -> root.path)),
        summon[ExecutionContext]
      )))
      val directSearch = _record(_success(component.logic.executeAction(
        SearchBlogPosts.unsafeForTest(Request.of("blog", "blog", "searchPosts"), Record.dataAuto("tag" -> root.path, "includeDescendants" -> false)),
        summon[ExecutionContext]
      )))

      _records(parentSearch, "data").map(_.getString("slug")) should contain (Some("tagged-scala-post"))
      _records(parentSearch, "data").map(_.getString("slug")) should not contain Some("tagged-life-post")
      _records(directSearch, "data").map(_.getString("slug")) should not contain Some("tagged-scala-post")
    }

    "auto-create Blog tag paths from editor input and list the shared Blog tag tree" in {
      val subsystem = DefaultSubsystemFactory.default(Some("command"))
      val component = (new ComponentFactory)
        .create(ComponentCreate(subsystem, ComponentOrigin.Repository("test")))
        .primary
      given ExecutionContext = _with_authenticated_principal(
        ExecutionContext.withFrameworkCommandExecutionMode(component.logic.executionContext(), CommandExecutionMode.SyncJob),
        "author_auto_tags"
      )
      val prefix = "phase20auto"
      val post = RegisterBlogPost.unsafeForTest(
        Request.of("blog", "blog", "registerPost"),
        Record.dataAuto(
          "slug" -> "auto-created-tags-post",
          "title" -> "Auto Created Tags Post",
          "content" -> "<article><p>Tagged</p></article>",
          "publish" -> true,
          "tags" -> s"$prefix.scala\n$prefix.cncf"
        )
      )
      val created = _record(_success(component.logic.executeAction(post, summon[ExecutionContext])))
      val postId = created.getAsC[EntityId]("id").toOption.flatten.getOrElse(fail("post id missing"))

      val detail = _record(_success(component.logic.executeAction(
        GetBlogPost.unsafeForTest(Request.of("blog", "blog", "getPost"), Record.dataAuto("id" -> postId)),
        summon[ExecutionContext]
      )))
      val detailPaths = _records(detail, "tags").flatMap(_.getString("path"))
      detailPaths should contain allOf (s"$prefix.scala", s"$prefix.cncf")
      detail.getString("tagPaths").getOrElse("") should include (s"$prefix.scala")

      val tree = _record(_success(component.logic.executeAction(
        ListBlogTags.unsafeForTest(Request.of("blog", "blog", "listTags"), Record.empty),
        summon[ExecutionContext]
      )))
      val treePaths = _records(tree, "data").flatMap(_.getString("path"))
      treePaths should contain allOf (prefix, s"$prefix.scala", s"$prefix.cncf")
      val scalaTag = _records(tree, "data").find(_.getString("path").contains(s"$prefix.scala")).getOrElse(fail("scala tag missing"))
      scalaTag.getString("usageKind").orElse(scalaTag.getString("usage_kind")) shouldBe Some("cms")
    }

    "render Atom feed XML for published active posts" in {
      val subsystem = DefaultSubsystemFactory.default(Some("command"))
      val component = (new ComponentFactory)
        .create(ComponentCreate(subsystem, ComponentOrigin.Repository("test")))
        .primary
      given ExecutionContext = _with_authenticated_principal(
        ExecutionContext.withFrameworkCommandExecutionMode(component.logic.executionContext(), CommandExecutionMode.SyncJob),
        "author_atom_feed"
      )
      val marker = "Atom Feed Needle"
      val draft = RegisterBlogPost.unsafeForTest(
        Request.of("blog", "blog", "registerPost"),
        Record.dataAuto(
          "slug" -> "atom-feed-draft",
          "title" -> s"$marker Draft",
          "content" -> "<article><p>Draft body</p></article>",
          "publish" -> false
        )
      )
      val published = RegisterBlogPost.unsafeForTest(
        Request.of("blog", "blog", "registerPost"),
        Record.dataAuto(
          "slug" -> "atom-feed-published",
          "title" -> s"$marker Published",
          "content" -> s"<article><p>$marker Published & Body</p></article>",
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
          xml should include ("&lt;article&gt;&lt;p&gt;Atom Feed Needle Published &amp;amp; Body&lt;/p&gt;&lt;/article&gt;")
          xml should not include "atom-feed-draft"
        case other =>
          fail(s"expected HTTP Atom response but got $other")
      }
    }
  }

  private def _associations(postId: EntityId)(using ExecutionContext) =
    {
      val blobs = _success(
        AssociationRepository.entityStore(AssociationStoragePolicy.blobAttachmentDefault).list(
          AssociationFilter(
            domain = AssociationDomain.BlobAttachment,
            sourceEntityId = Some(postId.value),
            targetKind = Some("blob")
          )
        )
      )
      val media = _success(
        AssociationRepository.entityStore(AssociationStoragePolicy.mediaAttachmentDefault).list(
          AssociationFilter(
            domain = AssociationDomain.MediaAttachment,
            sourceEntityId = Some(postId.value),
            targetKind = Some("image")
          )
        )
      )
      blobs ++ media
    }

  private def _normalize_blog_post_id(id: EntityId): EntityId =
    val collection = EntityCollectionId(id.major, id.minor, BlogPost.collectionId.name)
    if (id.collection == collection)
      id
    else
      EntityId(id.major, id.minor, collection, id.timestamp, id.entropy)

  private def _blob_id(value: String): EntityId =
    EntityId("cncf", "builtin", BlobRepository.CollectionId, entropy = Some(value))

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

  private def _with_deterministic_id_generation(
    context: ExecutionContext,
    seed: String = "blogslug"
  ): ExecutionContext =
    ExecutionContext.withIdGenerationContext(
      context,
      IdGenerationContext.deterministic(IdGenerationContext.IdNamespace(context.major, context.minor), seed)
    )

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
