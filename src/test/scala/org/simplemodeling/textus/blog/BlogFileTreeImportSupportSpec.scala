package org.simplemodeling.textus.blog

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.{ZipEntry, ZipOutputStream}
import org.scalatest.GivenWhenThen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.goldenport.Consequence

/*
 * @since   Apr. 29, 2026
 * @version Apr. 29, 2026
 * @author  ASAMI, Tomoharu
 */
final class BlogFileTreeImportSupportSpec extends AnyWordSpec with Matchers with GivenWhenThen {
  "BlogFileTreeImportSupport" should {
    "prefer META-INF metadata and extract article content with inline images" in {
      Given("full HTML whose head title differs from metadata")
      val html =
        """<html>
          |<head>
          |  <title>HTML title</title>
          |  <meta name="description" content="HTML description">
          |  <link rel="canonical" href="/html/canonical">
          |</head>
          |<body>
          |  <article data-blog-content>
          |    <h1>Article</h1>
          |    <img src="./images/a.jpg" alt="A">
          |  </article>
          |</body>
          |</html>""".stripMargin
      val metadata = BlogTreeMetadata(
        slug = Some("meta-slug"),
        title = Some("META title"),
        description = Some("META description"),
        canonicalPath = Some("/meta/canonical"),
        entityImages = Vector(BlogTreeEntityImage("thumb.jpg", "thumbnail"))
      )

      When("normalizing the tree import input")
      val draft = _success(BlogFileTreeImportSupport.normalize(html, metadata))

      Then("metadata wins and article data is extracted from the HTML tree")
      draft.title shouldBe "META title"
      draft.description shouldBe Some("META description")
      draft.canonicalPath shouldBe Some("/meta/canonical")
      draft.content should include ("<h1>Article</h1>")
      draft.content should not include ("<html>")
      draft.inlineImages.map(_.sourcePath) shouldBe Vector("./images/a.jpg")
      draft.entityImages.map(_.role) shouldBe Vector("thumbnail")
    }

    "fall back to HTML head metadata when META-INF omits it" in {
      Given("metadata without title, description, or canonical path")
      val html =
        """<html>
          |<head>
          |  <title>HTML title</title>
          |  <meta name="description" content="HTML description">
          |  <link rel="canonical" href="/html/canonical">
          |</head>
          |<body><article><p>Article</p></article></body>
          |</html>""".stripMargin

      When("normalizing the tree import input")
      val draft = _success(BlogFileTreeImportSupport.normalize(html, BlogTreeMetadata()))

      Then("missing metadata is filled from the HTML head")
      draft.title shouldBe "HTML title"
      draft.description shouldBe Some("HTML description")
      draft.canonicalPath shouldBe Some("/html/canonical")
    }

    "fail when no article can be extracted" in {
      Given("full HTML without an article element")
      val html = "<html><head><title>Title</title></head><body><p>No article</p></body></html>"

      When("normalizing the tree import input")
      val result = BlogFileTreeImportSupport.normalize(html, BlogTreeMetadata())

      Then("the failure is deterministic")
      result shouldBe a[Consequence.Failure[_]]
    }

    "rewrite inline image sources when registered public URLs are supplied" in {
      Given("full HTML with two relative inline images")
      val html =
        """<html>
          |<head><title>Title</title></head>
          |<body><article><img src="a.png"><img src="b.png"></article></body>
          |</html>""".stripMargin

      When("normalizing with post-registration image URLs")
      val draft = _success(
        BlogFileTreeImportSupport.normalizeWithInlineImageUrls(html, BlogTreeMetadata()) { img =>
          Some(s"/web/blob/content/${img.index}")
        }
      )

      Then("the stored fragment contains public URLs while drafts keep source paths")
      draft.content should include ("""src="/web/blob/content/0"""")
      draft.content should include ("""src="/web/blob/content/1"""")
      draft.inlineImages.map(_.sourcePath) shouldBe Vector("a.png", "b.png")
    }

    "read META-INF blog metadata and validate relative image paths from a file tree" in {
      Given("a local-previewable file tree")
      val root = Files.createTempDirectory("blog-file-tree")
      val metainf = Files.createDirectories(root.resolve("META-INF"))
      val images = Files.createDirectories(root.resolve("images"))
      Files.writeString(
        metainf.resolve("blog.yaml"),
        """slug: tree-slug
          |entryHtmlPath: index.html
          |title: Tree title
          |entityImages:
          |  - path: images/hero.jpg
          |    role: thumbnail
          |    sortOrder: 0
          |""".stripMargin
      )
      Files.writeString(images.resolve("hero.jpg"), "hero")
      Files.writeString(images.resolve("inline.png"), "inline")
      Files.writeString(
        root.resolve("index.html"),
        """<html>
          |<head><title>HTML title</title></head>
          |<body><article><p>Tree body</p><img src="images/inline.png" alt="Inline"></article></body>
          |</html>""".stripMargin
      )

      When("normalizing from the file tree root")
      val draft = _success(BlogFileTreeImportSupport.normalizeTree(root))

      Then("META-INF metadata and article image paths are resolved")
      draft.slug shouldBe Some("tree-slug")
      draft.title shouldBe "Tree title"
      draft.content should include ("""src="/web/blob/content/images-inline.png"""")
      draft.content should not include ("""src="images/inline.png"""")
      draft.entityImages.map(_.role) shouldBe Vector("thumbnail")
      draft.inlineImages.map(_.sourcePath) shouldBe Vector("images/inline.png")
    }

    "extract a ZIP article tree and preserve HTML-relative image resolution" in {
      Given("a ZIP with nested entry HTML and relative article images")
      val zip = _zip(Vector(
        "META-INF/blog.yaml" ->
          """slug: zipped-post
            |entryHtmlPath: posts/index.html
            |title: ZIP title
            |entityImages:
            |  - path: assets/hero.jpg
            |    role: thumbnail
            |""".stripMargin,
        "posts/index.html" ->
          """<html><head><title>HTML title</title></head>
            |<body><article><p>ZIP body</p><img src="images/inline.png"></article></body></html>""".stripMargin,
        "posts/images/inline.png" -> "inline",
        "assets/hero.jpg" -> "hero"
      ))

      When("extracting and normalizing the ZIP tree")
      val root = _success(BlogFileTreeImportSupport.extractZipTree(zip))
      val draft =
        try _success(BlogFileTreeImportSupport.normalizeTree(root))
        finally BlogFileTreeImportSupport.cleanupTree(root)

      Then("the draft uses META-INF metadata and resolves inline paths relative to the HTML file")
      draft.slug shouldBe Some("zipped-post")
      draft.title shouldBe "ZIP title"
      draft.content should include ("""src="/web/blob/content/posts-images-inline.png"""")
      draft.inlineImages.map(_.treePath) shouldBe Vector(Some("posts/images/inline.png"))
      draft.entityImages.map(_.path) shouldBe Vector("assets/hero.jpg")
    }

    "reject ZIP entries that escape the import tree" in {
      Given("a ZIP with a parent-directory entry")
      val zip = _zip(Vector("../evil.png" -> "evil"))

      When("extracting the ZIP")
      val result = BlogFileTreeImportSupport.extractZipTree(zip)

      Then("validation fails deterministically")
      result shouldBe a[Consequence.Failure[_]]
    }

    "reject non-ZIP payloads before treating them as an empty import tree" in {
      Given("bytes that are not a ZIP archive")
      val bytes = "not a zip".getBytes(java.nio.charset.StandardCharsets.UTF_8)

      When("extracting the ZIP")
      val result = BlogFileTreeImportSupport.extractZipTree(bytes)

      Then("validation fails deterministically")
      result shouldBe a[Consequence.Failure[_]]
    }

    "fail when an entity image metadata entry is incomplete" in {
      Given("a metadata YAML with an entity image missing path")
      val yaml =
        """entityImages:
          |  - role: thumbnail
          |""".stripMargin

      When("parsing metadata")
      val result = BlogFileTreeImportSupport.parseMetadata(yaml)

      Then("validation fails instead of dropping the entry")
      result shouldBe a[Consequence.Failure[_]]
    }

    "fail when a relative article image is missing from the file tree" in {
      Given("a file tree with a missing inline image")
      val root = Files.createTempDirectory("blog-file-tree-missing-image")
      val metainf = Files.createDirectories(root.resolve("META-INF"))
      Files.writeString(metainf.resolve("blog.yaml"), "entryHtmlPath: index.html\n")
      Files.writeString(
        root.resolve("index.html"),
        """<html><head><title>Title</title></head>
          |<body><article><img src="images/missing.png"></article></body></html>""".stripMargin
      )

      When("normalizing from the file tree root")
      val result = BlogFileTreeImportSupport.normalizeTree(root)

      Then("validation fails before registration")
      result shouldBe a[Consequence.Failure[_]]
    }
  }

  private def _success[A](p: Consequence[A]): A = p match {
    case Consequence.Success(value) => value
    case m: Consequence.Failure[_] => fail(m.toString)
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
