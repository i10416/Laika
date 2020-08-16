/*
 * Copyright 2012-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package laika.format

import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.{Date, Locale, UUID}

import cats.effect.{Resource, Sync}
import cats.implicits._
import laika.api.builder.OperationConfig
import laika.ast.{DocumentMetadata, DocumentTreeRoot, Path, TemplateRoot}
import laika.config.Config.ConfigResult
import laika.config.{Config, ConfigDecoder, ConfigEncoder, DefaultKey, Key}
import laika.factory.{BinaryPostProcessor, BinaryPostProcessorBuilder, RenderFormat, TwoPhaseRenderFormat}
import laika.io.model.{BinaryOutput, RenderedTreeRoot}
import laika.io.runtime.Runtime
import laika.theme
import laika.theme.{FontDefinition, Theme}
import laika.render.FOFormatter
import laika.render.FOFormatter.Preamble
import laika.render.pdf.{FOConcatenation, FopFactoryBuilder, PDFRenderer}
import org.apache.fop.apps.FopFactory

/** A post processor for PDF output, based on an interim XSL-FO renderer. 
 *  May be directly passed to the `Render` or `Transform` APIs:
 *
  * {{{
  * implicit val cs: ContextShift[IO] = 
  *   IO.contextShift(ExecutionContext.global)
  *
  * val blocker = Blocker.liftExecutionContext(
  *   ExecutionContext.fromExecutor(Executors.newCachedThreadPool())
  * )
  *
  * val transformer = Transformer
  *   .from(Markdown)
  *   .to(PDF)
  *   .using(GitHubFlavor)
  *   .io(blocker)
  *   .parallel[IO]
  *   .build
  *
  * val res: IO[Unit] = transformer
  *   .fromDirectory("src")
  *   .toFile("demo.pdf")
  *   .transform
  * }}}
 *  
 *  In the example above the input from an entire directory gets
 *  merged into a single output file.
 * 
 *  @author Jens Halm
 */
class PDF private(val interimFormat: RenderFormat[FOFormatter], fopFactory: Option[FopFactory]) 
  extends TwoPhaseRenderFormat[FOFormatter, BinaryPostProcessorBuilder] {

  override val description: String = "PDF"

  @deprecated("custom fop factories are deprecated as this would bypass laika's font registration", "0.16.0")
  def withFopFactory (fopFactory: FopFactory): PDF = new PDF(interimFormat, Some(fopFactory))
  
  /** Adds a preamble to each document for navigation and replaces the template with a fallback.
    * The modified tree will be used for rendering the interim XSL-FO result.
    * The original template will only be applied to the concatenated result of the XSL-FO renderer
    * in a later step.
    */
  def prepareTree (root: DocumentTreeRoot): Either[Throwable, DocumentTreeRoot] =
    Right(root
      .copy(tree = root.tree.withDefaultTemplate(TemplateRoot.fallback, "fo"))
      .mapDocuments { doc =>
        val preamble = Preamble(doc.title.fold(doc.name)(_.extractText))
        doc.copy(content = doc.content.copy(content = preamble +: doc.content.content))
      })

  /** Processes the interim XSL-FO result, transforms it to PDF and writes it to the specified final output.
    */
  def postProcessor: BinaryPostProcessorBuilder = new BinaryPostProcessorBuilder {
    
    def build[F[_] : Sync](config: Config, theme: Theme[F]): Resource[F, BinaryPostProcessor] = Resource.liftF {
      val pdfConfig = PDF.BookConfig.decodeWithDefaults(config).getOrElse(PDF.BookConfig())
      theme.inputs.flatMap { inputs =>
        fopFactory.fold(FopFactoryBuilder.build(pdfConfig, inputs.binaryInputs))(Sync[F].pure).map { fopFactory =>
          new BinaryPostProcessor {
            private val renderer = new PDFRenderer(fopFactory)
            override def process[G[_] : Sync : Runtime](result: RenderedTreeRoot[G], output: BinaryOutput[G], opConfig: OperationConfig): G[Unit] =
              for {
                fo <- Sync[G].fromEither(FOConcatenation(result, pdfConfig, opConfig))
                _  <- renderer.render(fo, output, pdfConfig.metadata, result.staticDocuments)
              } yield ()
          }
        }
      }
    }
  }
  
}

/** The default instance of the PDF renderer.
  */
object PDF extends PDF(XSLFO, None) {

  /** Configuration options for the generated EPUB output.
    *
    * The duplication of the existing `BookConfig` instance from laika-core happens to have a different
    * implicit key association with the EPUB-specific instance.
    *
    * @param metadata the metadata associated with the document
    * @param navigationDepth the number of levels to generate a table of contents for
    * @param fonts the fonts that should be embedded in the PDF output
    * @param coverImage the path to the cover image within the virtual document tree   
    */
  case class BookConfig(metadata: DocumentMetadata = DocumentMetadata(),
                        navigationDepth: Option[Int] = None,
                        fonts: Seq[FontDefinition] = Nil,
                        coverImage: Option[Path] = None) {
    lazy val identifier: String = metadata.identifier.getOrElse(s"urn:uuid:${UUID.randomUUID.toString}")
    lazy val date: Date = metadata.date.getOrElse(new Date)
    lazy val formattedDate: String = DateTimeFormatter.ISO_INSTANT.format(date.toInstant.truncatedTo(ChronoUnit.SECONDS))
    lazy val language: String = metadata.language.getOrElse(Locale.getDefault.getDisplayName)
  }

  object BookConfig {

    implicit val decoder: ConfigDecoder[BookConfig] = laika.theme.BookConfig.decoder.map(c => BookConfig(
      c.metadata, c.navigationDepth, c.fonts, c.coverImage
    ))
    implicit val encoder: ConfigEncoder[BookConfig] = laika.theme.BookConfig.encoder.contramap(c =>
      theme.BookConfig(c.metadata, c.navigationDepth, c.fonts, c.coverImage)
    )
    implicit val defaultKey: DefaultKey[BookConfig] = DefaultKey(Key("laika","pdf"))

    def decodeWithDefaults (config: Config): ConfigResult[BookConfig] = for {
      pdfConfig    <- config.getOpt[BookConfig].map(_.getOrElse(BookConfig()))
      commonConfig <- config.getOpt[theme.BookConfig].map(_.getOrElse(theme.BookConfig()))
    } yield {
      BookConfig(
        pdfConfig.metadata.withDefaults(commonConfig.metadata),
        pdfConfig.navigationDepth.orElse(commonConfig.navigationDepth),
        pdfConfig.fonts ++ commonConfig.fonts,
        pdfConfig.coverImage.orElse(commonConfig.coverImage)
      )
    }

  }

}
