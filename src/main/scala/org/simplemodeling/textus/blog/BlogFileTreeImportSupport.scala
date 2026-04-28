package org.simplemodeling.textus.blog

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters._
import org.goldenport.Consequence
import org.goldenport.cncf.html.HtmlTree
import org.yaml.snakeyaml.Yaml

/*
 * @since   Apr. 29, 2026
 * @version Apr. 29, 2026
 * @author  ASAMI, Tomoharu
 */
final case class BlogTreeMetadata(
  slug: Option[String] = None,
  entryHtmlPath: Option[String] = None,
  title: Option[String] = None,
  description: Option[String] = None,
  canonicalPath: Option[String] = None,
  entityImages: Vector[BlogTreeEntityImage] = Vector.empty
)

final case class BlogTreeEntityImage(
  path: String,
  role: String,
  sortOrder: Option[Int] = None,
  caption: Option[String] = None
)

final case class BlogInlineImageDraft(
  index: Int,
  sourcePath: String,
  altText: Option[String],
  titleText: Option[String]
)

final case class BlogRegistrationDraft(
  slug: Option[String],
  title: String,
  content: String,
  description: Option[String],
  canonicalPath: Option[String],
  entityImages: Vector[BlogTreeEntityImage],
  inlineImages: Vector[BlogInlineImageDraft]
)

object BlogFileTreeImportSupport {
  def normalizeTree(root: Path): Consequence[BlogRegistrationDraft] =
    normalizeTreeWithInlineImageUrls(root)(img => Some(s"/web/blob/content/${_public_url_key(img.sourcePath)}"))

  def normalizeTreeWithInlineImageUrls(
    root: Path
  )(resolve: BlogInlineImageDraft => Option[String]): Consequence[BlogRegistrationDraft] =
    for {
      metadata <- loadMetadata(root)
      entry <- _entry_html(root, metadata)
      html <- _read_string(entry)
      draft <- normalizeWithInlineImageUrls(html, metadata)(resolve)
      _ <- _validate_entity_images(root, draft.entityImages)
      _ <- _validate_inline_images(root, entry, draft.inlineImages)
    } yield draft

  def loadMetadata(root: Path): Consequence[BlogTreeMetadata] = {
    val path = root.resolve("META-INF").resolve("blog.yaml")
    if (!Files.isRegularFile(path))
      Consequence.operationInvalid(s"Blog file tree metadata is missing: ${root.relativize(path)}")
    else
      _read_string(path).flatMap(parseMetadata)
  }

  def parseMetadata(text: String): Consequence[BlogTreeMetadata] =
    try {
      new Yaml().load[AnyRef](text) match {
        case m: java.util.Map[_, _] =>
          val map = m.asScala.toMap.asInstanceOf[Map[Any, Any]]
          _entity_images(map.get("entityImages")).map { entityImages =>
            BlogTreeMetadata(
              slug = _string(map, "slug"),
              entryHtmlPath = _string(map, "entryHtmlPath").orElse(_string(map, "entry")),
              title = _string(map, "title"),
              description = _string(map, "description"),
              canonicalPath = _string(map, "canonicalPath"),
              entityImages = entityImages
            )
          }
        case null =>
          Consequence.success(BlogTreeMetadata())
        case other =>
          Consequence.operationInvalid(s"META-INF/blog.yaml must be a mapping: ${other.getClass.getName}")
      }
    } catch {
      case e: Exception =>
        Consequence.operationInvalid(s"failed to parse META-INF/blog.yaml: ${e.getMessage}")
    }

  def normalize(entryHtml: String, metadata: BlogTreeMetadata): Consequence[BlogRegistrationDraft] =
    HtmlTree.parse(entryHtml).flatMap { document =>
      document.articleFragment.flatMap { fragment =>
        val title = metadata.title.orElse(document.title).map(_.trim).filter(_.nonEmpty)
        title
          .map { resolvedTitle =>
            val inlineImages = _inline_images(fragment)
            Consequence.success(
              BlogRegistrationDraft(
                slug = metadata.slug,
                title = resolvedTitle,
                content = fragment.render,
                description = metadata.description.orElse(document.description),
                canonicalPath = metadata.canonicalPath.orElse(document.canonical),
                entityImages = metadata.entityImages,
                inlineImages = inlineImages
              )
            )
          }
          .getOrElse(Consequence.operationInvalid("Blog file tree metadata does not define a title and HTML head title is missing"))
      }
    }

  def normalizeWithInlineImageUrls(
    entryHtml: String,
    metadata: BlogTreeMetadata
  )(resolve: BlogInlineImageDraft => Option[String]): Consequence[BlogRegistrationDraft] =
    HtmlTree.parse(entryHtml).flatMap { document =>
      document.articleFragment.flatMap { fragment =>
        val title = metadata.title.orElse(document.title).map(_.trim).filter(_.nonEmpty)
        title
          .map { resolvedTitle =>
            val inlineImages = _inline_images(fragment)
            val byIndex = inlineImages.map(x => x.index -> x).toMap
            val rewritten = fragment.rewriteImageSources { img =>
              byIndex.get(img.index).flatMap(resolve)
            }
            Consequence.success(
              BlogRegistrationDraft(
                slug = metadata.slug,
                title = resolvedTitle,
                content = rewritten.render,
                description = metadata.description.orElse(document.description),
                canonicalPath = metadata.canonicalPath.orElse(document.canonical),
                entityImages = metadata.entityImages,
                inlineImages = inlineImages
              )
            )
          }
          .getOrElse(Consequence.operationInvalid("Blog file tree metadata does not define a title and HTML head title is missing"))
      }
    }

  private def _read_string(path: Path): Consequence[String] =
    try {
      Consequence.success(Files.readString(path, StandardCharsets.UTF_8))
    } catch {
      case e: Exception =>
        Consequence.operationInvalid(s"failed to read ${path}: ${e.getMessage}")
    }

  private def _inline_images(fragment: org.goldenport.cncf.html.HtmlFragment): Vector[BlogInlineImageDraft] =
    fragment.images.map { img =>
      BlogInlineImageDraft(img.index, img.src, img.alt, img.title)
    }

  private def _entry_html(root: Path, metadata: BlogTreeMetadata): Consequence[Path] =
    metadata.entryHtmlPath match {
      case Some(path) =>
        val resolved = _resolve_tree_path(root, path)
        if (!_within(root, resolved))
          Consequence.operationInvalid(s"Blog entry HTML escapes the file tree: ${path}")
        else if (Files.isRegularFile(resolved))
          Consequence.success(resolved)
        else
          Consequence.operationInvalid(s"Blog entry HTML is missing: ${path}")
      case None =>
        val stream = Files.walk(root)
        val candidates = try {
          stream.iterator().asScala
            .filter(Files.isRegularFile(_))
            .filter(x => x.getFileName.toString.toLowerCase.endsWith(".html"))
            .filterNot(x => root.relativize(x).iterator().asScala.exists(_.toString == "META-INF"))
            .toVector
            .sortBy(x => root.relativize(x).toString)
        } finally {
          stream.close()
        }
        candidates.headOption
          .map(Consequence.success)
          .getOrElse(Consequence.operationInvalid("Blog file tree does not contain an HTML entry file"))
    }

  private def _validate_entity_images(
    root: Path,
    images: Vector[BlogTreeEntityImage]
  ): Consequence[Unit] =
    images
      .find { x =>
        val resolved = _resolve_tree_path(root, x.path)
        x.path.trim.isEmpty || !_within(root, resolved) || !Files.isRegularFile(resolved)
      }
      .map(x => Consequence.operationInvalid(s"Blog entity image is missing: ${x.path}"))
      .getOrElse(Consequence.success(()))

  private def _validate_inline_images(
    root: Path,
    entry: Path,
    images: Vector[BlogInlineImageDraft]
  ): Consequence[Unit] = {
    val base = entry.getParent
    images
      .filter(x => _is_local_relative_src(x.sourcePath))
      .find { x =>
        val resolved = _resolve_tree_path(base, x.sourcePath)
        !_within(root, resolved) || !Files.isRegularFile(resolved)
      }
      .map(x => Consequence.operationInvalid(s"Blog inline image is missing: ${x.sourcePath}"))
      .getOrElse(Consequence.success(()))
  }

  private def _resolve_tree_path(root: Path, path: String): Path =
    root.resolve(path).normalize()

  private def _within(root: Path, path: Path): Boolean =
    path.normalize().startsWith(root.normalize())

  private def _is_local_relative_src(path: String): Boolean = {
    val lower = path.toLowerCase
    path.nonEmpty &&
      !path.startsWith("/") &&
      !path.startsWith("#") &&
      !lower.startsWith("http://") &&
      !lower.startsWith("https://") &&
      !lower.startsWith("data:") &&
      !lower.startsWith("blob:")
  }

  private def _public_url_key(path: String): String =
    path.trim.map {
      case c if c.isLetterOrDigit => c
      case c @ ('-' | '_' | '.') => c
      case _ => '-'
    }.mkString.replaceAll("-+", "-").stripPrefix("-") match {
      case "" => "inline-image"
      case s => s
    }

  private def _entity_images(p: Option[Any]): Consequence[Vector[BlogTreeEntityImage]] =
    p match {
      case Some(xs: java.util.List[_]) =>
        xs.asScala.toVector.zipWithIndex.foldLeft(Consequence.success(Vector.empty[BlogTreeEntityImage])) {
          case (z, (m: java.util.Map[_, _], index)) =>
            z.flatMap { images =>
              val map = m.asScala.toMap.asInstanceOf[Map[Any, Any]]
              for {
                path <- _string(map, "path")
                  .map(Consequence.success)
                  .getOrElse(Consequence.operationInvalid(s"META-INF/blog.yaml entityImages[$index].path is required"))
                role <- _string(map, "role")
                  .map(Consequence.success)
                  .getOrElse(Consequence.operationInvalid(s"META-INF/blog.yaml entityImages[$index].role is required"))
              } yield images :+ BlogTreeEntityImage(
                path = path,
                role = role,
                sortOrder = _int(map, "sortOrder"),
                caption = _string(map, "caption")
              )
            }
          case (_, (other, index)) =>
            Consequence.operationInvalid(s"META-INF/blog.yaml entityImages[$index] must be a mapping: ${other.getClass.getName}")
        }
      case Some(other) =>
        Consequence.operationInvalid(s"META-INF/blog.yaml entityImages must be a list: ${other.getClass.getName}")
      case _ => Consequence.success(Vector.empty)
    }

  private def _string(map: Map[Any, Any], key: String): Option[String] =
    map.get(key).map(_.toString.trim).filter(_.nonEmpty)

  private def _int(map: Map[Any, Any], key: String): Option[Int] =
    _string(map, key).flatMap(x => scala.util.Try(x.toInt).toOption)
}
