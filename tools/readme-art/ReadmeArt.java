// SPDX-License-Identifier: GPL-3.0-only

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Draws the README pictures (the title, the three promises and the section headings) as SVG, in the
 * colors of "アイてる!? - AITEL -" and with its bird. Both come from the app: the colors from apps/rotep
 * ui/theme/Color.kt, the bird from the still pictures in res/drawable-nodpi, next to the fonts. Change
 * them there first, then here.
 *
 * The letters are turned into outlines: GitHub shows an SVG as an image, and an image cannot load a
 * font; for the same reason the bird is a PNG inside the SVG. Every picture has its own background, so it reads the same on GitHub's light and dark pages.
 *
 * Run from the repository root, with the JDK the build uses:
 *   java tools/readme-art/ReadmeArt.java apps/rotep/src/main/res/font docs/images
 */
public class ReadmeArt {
    // Light theme of the app (Color.kt)
    /** The four colored blocks: request, confirm, call, and a booking the check found nothing wrong with. */
    static final String AMBER = "#FFB020";
    static final String LAVENDER = "#B79BF2";
    static final String SKY = "#7FCDF7";
    static final String TEAL = "#3DC6B3";
    /** The page behind the request block (pageRequest). */
    static final String PAGE = "#FFE4B1";
    /** The text on the colored blocks and the title: navy. */
    static final String INK = "#2A2240";
    /** "- AITEL -" in the title (primary). */
    static final String VIOLET = "#5A3ED4";
    /** The logo's orange: the title's offset shadow only. */
    static final String LOGO = "#FF6B2C";

    /** The app's name: "アイてる!? - AITEL -". The first part is the short name (under the home screen icon). */
    static final String NAME = "アイてる!?";
    static final String SUBNAME = "- AITEL -";

    /** The same birds as AitelMood in the app: the name of each one's still picture. */
    enum Mood {
        HAPPY("dance"), ASKING("think"), WAITING("wait"), GOING("walk"), TALKING("explain"), RESTING("rest"),
        GREAT("dance"), WORRIED("think"), SAD("sad"), SORRY("sorry");

        final String bird;

        Mood(String bird) {
            this.bird = bird;
        }
    }

    record Heading(String file, String first, String second, String color, Mood mood) {}

    record Card(String first, String second, String color, Mood mood) {}

    /** The section headings of README.md. Two parts, as the app's blocks have two lines; both are navy. */
    static final List<Heading> HEADINGS = List.of(
        new Heading("news", "最近の", "更新", TEAL, Mood.GOING),
        new Heading("what", "なにが", "できるの？", AMBER, Mood.HAPPY),
        new Heading("status", "いま、", "どこまで？", LAVENDER, Mood.WAITING),
        new Heading("how", "どうやって", "動くの？", SKY, Mood.TALKING),
        new Heading("try", "ためして", "みる？", TEAL, Mood.GREAT),
        new Heading("safety", "あぶなく", "ない？", AMBER, Mood.WORRIED),
        new Heading("license", "ライセンス", "のこと", SKY, Mood.RESTING)
    );

    static final List<Card> PROMISES = List.of(
        new Card("自分の番号で", "電話する", AMBER, Mood.HAPPY),
        new Card("はじめに", "AI と名乗る", LAVENDER, Mood.TALKING),
        new Card("任せたこと", "だけ決める", SKY, Mood.ASKING)
    );

    /** Dela Gothic One: the name "アイてる!? - AITEL -" only. */
    static Font logo;

    /** 源暎きわみゴ: the big headings only. */
    static Font display;

    /** The app's still pictures of the bird. */
    static Path birds;

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        if (args.length != 2) {
            System.err.println("usage: java tools/readme-art/ReadmeArt.java <font dir> <output dir>");
            System.exit(2);
        }
        Path fonts = Path.of(args[0]);
        Path out = Path.of(args[1]);
        logo = Font.createFont(Font.TRUETYPE_FONT, fonts.resolve("dela_gothic_one.ttf").toFile());
        display = Font.createFont(Font.TRUETYPE_FONT, fonts.resolve("genei_kiwami_go.ttf").toFile());
        birds = fonts.resolveSibling("drawable-nodpi");
        Files.createDirectories(out.resolve("headings"));
        write(out.resolve("aitel-hero.svg"), hero());
        write(out.resolve("aitel-promises.svg"), promises());
        for (Heading h : HEADINGS) write(out.resolve("headings/" + h.file() + ".svg"), heading(h));
    }

    // ---- Pictures ----

    /** The top of the README: the app's title bar and its first colored block, drawn large. */
    static String hero() {
        double w = 960, h = 440;
        Svg svg = new Svg(w, h, "アイてる!? - AITEL - 予約の電話、まかせてみる？");
        svg.rect(0, 0, w, h, 46, PAGE, null);

        // The name: "アイてる!?" in navy over its orange copy and "- AITEL -" in violet, on one
        // baseline, as on the app's title bar. Both shrink together when they do not fit on one line
        double left = 56, top = 50, gapX = 20;
        double nameSize = 120, tagSize = 44;
        double wide = text(logo, nameSize, NAME).width() + gapX + text(logo, tagSize, SUBNAME).width();
        if (wide > w - 2 * left) {
            double k = (w - 2 * left) / wide;
            nameSize = Math.floor(nameSize * k);
            tagSize = Math.floor(tagSize * k);
        }
        Text name = text(logo, nameSize, NAME);
        Text tag = text(logo, tagSize, SUBNAME);
        double base = top - name.bounds().getMinY();
        double nameX = left - name.bounds().getMinX();
        double shift = 0.06 * nameSize;
        svg.text(name, nameX + shift, base + shift, LOGO);
        svg.text(name, nameX, base, INK);
        double tagX = nameX + name.bounds().getMaxX() + gapX - tag.bounds().getMinX();
        svg.text(tag, tagX, base, VIOLET);
        double titleRight = tagX + tag.bounds().getMaxX();

        // A few dots of the screen colors where the title leaves room
        if (w - titleRight > 110) {
            svg.circle(w - 74, 64, 15, SKY);
            svg.circle(w - 112, 104, 9, LAVENDER);
            svg.circle(w - 60, 122, 7, TEAL);
        }

        // The request block: a question and the bird on its lighter shape
        double bx = 40, by = base + name.bounds().getMaxY() + 34, bw = w - 80, bh = h - 40 - by;
        svg.rect(bx, by, bw, bh, 44, AMBER, null);
        double s = Math.min(bh - 30, 196);
        double fx = bx + bw - s - 40, fy = by + (bh - s) / 2;
        svg.circle(fx + s / 2, fy + s / 2, s * 0.56, backdrop(AMBER));
        svg.raw(bird(fx, fy, s, Mood.HAPPY));
        double room = bw - s - 120;
        double size = fit(display, 66, room, "予約の電話、", "まかせてみる？");
        Text l1 = text(display, size, "予約の電話、");
        Text l2 = text(display, size, "まかせてみる？");
        double gap = size * 1.22;
        double block = gap + l2.bounds().getMaxY() - l1.bounds().getMinY();
        double base1 = by + (bh - block) / 2 - l1.bounds().getMinY();
        svg.text(l1, bx + 44 - l1.bounds().getMinX(), base1, INK);
        svg.text(l2, bx + 44 - l2.bounds().getMinX(), base1 + gap, INK);
        return svg.end();
    }

    /** Three cards: what the app always does. The README explains each one under the picture. */
    static String promises() {
        double w = 960, h = 300, gap = 24, cw = (w - 2 * gap) / 3;
        StringBuilder label = new StringBuilder();
        for (Card c : PROMISES) label.append(label.isEmpty() ? "" : " / ").append(phrase(c.first(), c.second()));
        Svg svg = new Svg(w, h, label.toString());
        for (int i = 0; i < PROMISES.size(); i++) {
            Card c = PROMISES.get(i);
            double x = i * (cw + gap);
            svg.rect(x, 0, cw, h, 40, c.color(), null);
            double s = 112;
            svg.circle(x + cw - s / 2 - 26, 26 + s / 2, s * 0.56, backdrop(c.color()));
            svg.raw(bird(x + cw - s - 26, 26, s, c.mood()));
            double size = fit(display, 38, cw - 60, c.first(), c.second());
            Text l1 = text(display, size, c.first());
            Text l2 = text(display, size, c.second());
            double base2 = h - 44;
            double base1 = base2 - size * 1.25;
            svg.text(l1, x + 30 - l1.bounds().getMinX(), base1, INK);
            svg.text(l2, x + 30 - l2.bounds().getMinX(), base2, INK);
        }
        return svg.end();
    }

    /** A section heading: a colored band, the words, and the bird at the right end. */
    static String heading(Heading hd) {
        double w = 720, h = 100;
        Svg svg = new Svg(w, h, phrase(hd.first(), hd.second()));
        svg.rect(0, 0, w, h, h / 2, hd.color(), null);
        double s = 80;
        svg.raw(bird(w - s - 34, h / 2 - 0.5 * s, s, hd.mood()));
        double size = fit(display, 46, w - s - 120, hd.first() + hd.second());
        Text a = text(display, size, hd.first());
        Text b = text(display, size, hd.second());
        double minY = Math.min(a.bounds().getMinY(), b.bounds().getMinY());
        double maxY = Math.max(a.bounds().getMaxY(), b.bounds().getMaxY());
        double base = h / 2 - (minY + maxY) / 2;
        double x = 44 - a.bounds().getMinX();
        svg.text(a, x, base, INK);
        svg.text(b, x + a.width(), base, INK);
        return svg.end();
    }

    // ---- The bird ----

    /** The shape behind the bird: the block's own color, 45 % of the way to white (heroBackdrop). */
    static String backdrop(String color) {
        StringBuilder b = new StringBuilder("#");
        for (int i = 1; i < 7; i += 2) {
            int c = Integer.parseInt(color.substring(i, i + 2), 16);
            b.append(String.format(Locale.ROOT, "%02X", Math.round(c + (255 - c) * 0.45)));
        }
        return b.toString();
    }

    /** The bird of [mood], fitted in the square of size s at (x, y). Twice the size it is shown at, for sharp screens. */
    static String bird(double x, double y, double s, Mood mood) {
        try {
            BufferedImage image = ImageIO.read(birds.resolve("aitel_bird_" + mood.bird + "_still.png").toFile());
            double k = s / Math.max(image.getWidth(), image.getHeight());
            double w = image.getWidth() * k, h = image.getHeight() * k;
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(shrink(image, Math.min(1, 2 * k)), "png", png);
            return "<image x=\"" + n(x + (s - w) / 2) + "\" y=\"" + n(y + (s - h) / 2) + "\" width=\"" + n(w) + "\" height=\"" + n(h)
                + "\" href=\"data:image/png;base64," + Base64.getEncoder().encodeToString(png.toByteArray()) + "\"/>";
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The picture at [k] times its size, halved step by step so that the thin lines stay. */
    static BufferedImage shrink(BufferedImage image, double k) {
        BufferedImage now = image;
        for (double left = k; left < 1; ) {
            double step = Math.max(left, 0.5);
            int w = Math.max(1, (int) Math.round(now.getWidth() * step));
            int h = Math.max(1, (int) Math.round(now.getHeight() * step));
            BufferedImage next = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = next.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(now, 0, 0, w, h, null);
            g.dispose();
            now = next;
            left /= step;
        }
        return now;
    }

    // ---- Text as outlines ----

    record Text(String d, double width, Rectangle2D bounds, boolean evenOdd) {}

    static Text text(Font font, double size, String s) {
        Font f = font.deriveFont(Map.of(TextAttribute.SIZE, (float) size, TextAttribute.KERNING, TextAttribute.KERNING_ON));
        int missing = f.canDisplayUpTo(s);
        if (missing >= 0) {
            throw new IllegalStateException(font.getFontName() + " has no glyph for '" + s.charAt(missing) + "' in \"" + s + "\"");
        }
        TextLayout layout = new TextLayout(s, f, new FontRenderContext(null, true, true));
        Shape shape = layout.getOutline(null);
        PathIterator it = shape.getPathIterator(null);
        boolean evenOdd = it.getWindingRule() == PathIterator.WIND_EVEN_ODD;
        return new Text(pathData(it), layout.getAdvance(), shape.getBounds2D(), evenOdd);
    }

    /** A Java shape as the "d" of an SVG path. */
    static String pathData(PathIterator it) {
        StringBuilder d = new StringBuilder();
        double[] c = new double[6];
        for (; !it.isDone(); it.next()) {
            switch (it.currentSegment(c)) {
                case PathIterator.SEG_MOVETO -> d.append('M').append(p(c[0], c[1]));
                case PathIterator.SEG_LINETO -> d.append('L').append(p(c[0], c[1]));
                case PathIterator.SEG_QUADTO -> d.append('Q').append(p(c[0], c[1])).append(' ').append(p(c[2], c[3]));
                case PathIterator.SEG_CUBICTO -> d.append('C').append(p(c[0], c[1])).append(' ').append(p(c[2], c[3]))
                    .append(' ').append(p(c[4], c[5]));
                case PathIterator.SEG_CLOSE -> d.append('Z');
                default -> throw new IllegalStateException("unknown path segment");
            }
        }
        return d.toString();
    }

    /** The largest size, up to [size], at which every one of the lines fits in [width]. */
    static double fit(Font font, double size, double width, String... lines) {
        double fitted = size;
        for (String s : lines) {
            if (s.isEmpty()) continue;
            double w = text(font, size, s).width();
            if (w > width) fitted = Math.min(fitted, Math.floor(size * width / w));
        }
        return fitted;
    }

    /** The two colored parts as one phrase for the title that screen readers read: a space where kana meets a Latin letter. */
    static String phrase(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return a + b;
        boolean latin = isLatin(a.charAt(a.length() - 1)) || isLatin(b.charAt(0));
        return latin ? a + " " + b : a + b;
    }

    static boolean isLatin(char c) {
        return c < 128 && Character.isLetterOrDigit(c);
    }

    // ---- SVG ----

    static final class Svg {
        private final StringBuilder b = new StringBuilder();

        Svg(double w, double h, String label) {
            b.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(n(w)).append("\" height=\"").append(n(h))
                .append("\" viewBox=\"0 0 ").append(n(w)).append(' ').append(n(h)).append("\" role=\"img\" aria-label=\"")
                .append(escape(label)).append("\">\n<title>").append(escape(label)).append("</title>\n");
        }

        void rect(double x, double y, double w, double h, double r, String fill, String stroke) {
            b.append(rectTag(x, y, w, h, r, fill, stroke)).append('\n');
        }

        void circle(double cx, double cy, double r, String fill) {
            b.append(circleTag(cx, cy, r, fill)).append('\n');
        }

        void text(Text t, double x, double baseline, String fill) {
            b.append("<path transform=\"translate(").append(n(x)).append(' ').append(n(baseline)).append(")\" fill=\"")
                .append(fill).append('"');
            if (t.evenOdd()) b.append(" fill-rule=\"evenodd\"");
            b.append(" d=\"").append(t.d()).append("\"/>\n");
        }

        void raw(String s) {
            b.append(s).append('\n');
        }

        String end() {
            return b.append("</svg>\n").toString();
        }
    }

    static String rectTag(double x, double y, double w, double h, double r, String fill, String stroke) {
        String s = "<rect x=\"" + n(x) + "\" y=\"" + n(y) + "\" width=\"" + n(w) + "\" height=\"" + n(h) + "\" rx=\"" + n(r)
            + "\" fill=\"" + fill + "\"";
        if (stroke != null) s += " stroke=\"" + stroke + "\" stroke-width=\"2\"";
        return s + "/>";
    }

    static String circleTag(double cx, double cy, double r, String fill) {
        return "<circle cx=\"" + n(cx) + "\" cy=\"" + n(cy) + "\" r=\"" + n(r) + "\" fill=\"" + fill + "\"/>";
    }

    static String p(double x, double y) {
        return n(x) + " " + n(y);
    }

    static String n(double v) {
        String s = String.format(Locale.ROOT, "%.1f", v);
        if (s.endsWith(".0")) s = s.substring(0, s.length() - 2);
        return s.equals("-0") ? "0" : s;
    }

    static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    static void write(Path file, String svg) throws Exception {
        Files.writeString(file, svg, StandardCharsets.UTF_8);
        System.out.println(file + "  " + Files.size(file) + " bytes");
    }
}
