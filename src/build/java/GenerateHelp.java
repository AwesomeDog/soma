import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.ext.heading.anchor.HeadingAnchorExtension;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

final class GenerateHelp {

  private static final String ANCHOR_PREFIX = "help-";
  private static final String CONTENT_MARKER = "<!-- generated-help-content -->";
  private static final Pattern MERMAID_BLOCK =
      Pattern.compile(
          "<pre class=\"mermaid\"><code class=\"language-mermaid\">(.*?)</code></pre>",
          Pattern.DOTALL);
  private static final List<Extension> EXTENSIONS =
      List.of(
          TablesExtension.create(),
          HeadingAnchorExtension.builder().idPrefix(ANCHOR_PREFIX).build());

  private GenerateHelp() {}

  public static void main(String[] args) throws IOException {
    if (args.length != 3) {
      throw new IllegalArgumentException(
          "usage: GenerateHelp <input.md> <template.html> <output.html>");
    }

    var parser = Parser.builder().extensions(EXTENSIONS).build();
    var renderer =
        HtmlRenderer.builder()
            .extensions(EXTENSIONS)
            .escapeHtml(true)
            .sanitizeUrls(true)
            .attributeProviderFactory(
                context ->
                    (node, tagName, attributes) -> {
                      if (node instanceof FencedCodeBlock block && tagName.equals("pre")) {
                        attributes.put(
                            "class", "mermaid".equals(block.getInfo()) ? "mermaid" : "card pad");
                      } else if (node instanceof TableBlock && tagName.equals("table")) {
                        attributes.put("class", "card");
                      } else if (node instanceof Link link
                          && link.getDestination().startsWith("#")) {
                        attributes.put(
                            "href", "#" + ANCHOR_PREFIX + link.getDestination().substring(1));
                      }
                    })
            .build();
    var content = renderer.render(parser.parse(Files.readString(Path.of(args[0]), UTF_8)));
    content = MERMAID_BLOCK.matcher(content).replaceAll("<pre class=\"mermaid\">$1</pre>");
    var template = Files.readString(Path.of(args[1]), UTF_8);
    var marker = template.indexOf(CONTENT_MARKER);
    if (marker < 0 || marker != template.lastIndexOf(CONTENT_MARKER)) {
      throw new IllegalArgumentException("template must contain exactly one " + CONTENT_MARKER);
    }

    var output = Path.of(args[2]);
    Files.createDirectories(output.getParent());
    Files.writeString(output, template.replace(CONTENT_MARKER, content), UTF_8);
  }
}
