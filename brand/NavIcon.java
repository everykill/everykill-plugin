import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * The sidebar nav icon.
 *
 * Not the same file as the hub icon, on purpose. RuneLite's sidebar draws these
 * on its own dark strip and every core plugin ships a BARE glyph on transparency
 * - a plugin that brings its own tile looks like a sticker stuck on the toolbar.
 *
 * So: the tally strokes only, no plate, no border. Same geometry as everything
 * else in brand/.
 */
public class NavIcon
{
	static final Color ACC = new Color(0xd9, 0x4f, 0x2b);

	static final int[] UPRIGHTS = {18, 27, 36, 45};
	static final int TOP = 20, BOT = 44;
	static final int SX1 = 13, SY1 = 41, SX2 = 50, SY2 = 23;

	public static void main(String[] args) throws Exception
	{
		File out = new File(args.length > 0 ? args[0] : "panel_icon.png");
		int size = args.length > 1 ? Integer.parseInt(args[1]) : 20;

		BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

		// the marks occupy x 13..50, y 20..44 of the 64-box. with no plate around
		// them that leaves dead space, so crop to the ink and scale THAT to fill -
		// otherwise the glyph renders about 60% the size of every neighbouring icon.
		double inkX = 13 - 2.3, inkY = 20 - 2.3;        // half stroke of bleed
		double inkW = (50 + 2.3) - inkX;
		double inkH = (44 + 2.3) - inkY;
		double span = Math.max(inkW, inkH);
		double s = size / span;

		g.scale(s, s);
		g.translate(-inkX + (span - inkW) / 2, -inkY + (span - inkH) / 2);

		g.setColor(ACC);
		g.setStroke(new BasicStroke(4.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		for (int x : UPRIGHTS)
		{
			g.draw(new Line2D.Double(x, TOP, x, BOT));
		}
		g.draw(new Line2D.Double(SX1, SY1, SX2, SY2));

		g.dispose();
		ImageIO.write(img, "png", out);
		System.out.printf("  %s  %dx%d  %d bytes%n", out.getName(), size, size, out.length());
	}
}
