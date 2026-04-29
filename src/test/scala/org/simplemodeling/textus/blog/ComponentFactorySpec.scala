package org.simplemodeling.textus.blog

import java.io.ByteArrayOutputStream
import java.util.zip.{ZipEntry, ZipOutputStream}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.goldenport.Consequence
import org.goldenport.cncf.action.{ActionCall, CommandExecutionMode}
import org.goldenport.bag.Bag
import org.goldenport.cncf.blob.*
import org.goldenport.cncf.component.{ComponentCreate, ComponentOrigin}
import org.goldenport.cncf.context.ExecutionContext
import org.goldenport.cncf.entity.EntityStore
import org.goldenport.cncf.subsystem.DefaultSubsystemFactory
import org.goldenport.datatype.ContentType
import org.goldenport.protocol.Request
import org.goldenport.protocol.operation.OperationResponse
import org.goldenport.record.Record
import org.simplemodeling.model.datatype.{EntityCollectionId, EntityId}
import org.simplemodeling.textus.blog.entity.BlogPost

/*
 * @since   Apr. 29, 2026
 * @version Apr. 29, 2026
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

      val inlineBlobMetadata = _success(BlobRepository.entityStore().list())
        .find(_.attributes.get("sourcePath").exists(_.endsWith("posts/images/inline.png")))
        .getOrElse(fail("inline Blob metadata not created"))
      val inlineBlob = _success(BlobPayloadSupport.service(component).flatMap { service =>
        service.blobStore.get(inlineBlobMetadata.storageRef.getOrElse(fail("inline Blob has no storageRef")))
      })
      inlineBlob.payload.openInputStream().readAllBytes().toVector shouldBe "inline-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8).toVector
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
  }

  private def _record(response: OperationResponse): Record =
    response match {
      case OperationResponse.RecordResponse(record) => record
      case other => fail(s"expected record response but got $other")
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
