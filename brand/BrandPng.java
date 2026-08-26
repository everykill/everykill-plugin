import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Everykill mark -> PNG, at every size we actually ship.
 *
 * No rasteriser on this machine (no rsvg/inkscape/magick) and Java2D draws the
 * same geometry natively, so there's no reason to install one. Same numbers as
 * brandmark.py: four uprights at 9px pitch in a 64-box, struck through, rust on
 * --panel with a --line border.
 */
public class BrandPng
{
	static final Color PANEL = new Color(0x16, 0x18, 0x1d);
	static final Color LINE  = new Color(0x23, 0x26, 0x2d);
	static final Color ACC   = new Color(0xd9, 0x4f, 0x2b);

	static final int[] UPRIGHTS = {18, 27, 36, 45};
	static final int TOP = 20, BOT = 44;
	static final int SX1 = 13, SY1 = 41, SX2 = 50, SY2 = 23;
	static final float STROKE = 4.6f;
	static final double RADIUS_RATIO = 5.0 / 28.0;

	public static void main(String[] args) throws Exception
	{
		File dir = new File(args.length > 0 ? args[0] : "brand");
		dir.mkdirs();

		write(new File(dir, "everykill-1024.png"), 1024, false);
		write(new File(dir, "everykill-512.png"), 512, false);
		write(new File(dir, "everykill-256.png"), 256, false);
		write(new File(dir, "everykill-128.png"), 128, false);
		write(new File(dir, "everykill-48.png"), 48, false);
		write(new File(dir, "everykill-discord-512.png"), 512, true);
		write(new File(dir, "everykill-discord-1024.png"), 1024, true);
	}

	static void write(File out, int size, boolean round) throws Exception
	{
		BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

		// everything is authored in a 64-unit box, then scaled once. keeps every
		// export identical rather than re-deriving coordinates per size.
		double s = size / 64.0;
		g.scale(s, s);

		// the border is drawn INSIDE the shape (inset by half the stroke) so it
		// isn't half-clipped by the image edge.
		float bw = 2f;
		if (round)
		{
			g.setColor(PANEL);
			g.fill(new Ellipse2D.Double(0, 0, 64, 64));
			g.setColor(LINE);
			g.setStroke(new BasicStroke(bw));
			g.draw(new Ellipse2D.Double(bw / 2, bw / 2, 64 - bw, 64 - bw));
		}
		else
		{
			double r = 64 * RADIUS_RATIO;
			g.setColor(PANEL);
			g.fill(new RoundRectangle2D.Double(0, 0, 64, 64, r * 2, r * 2));
			g.setColor(LINE);
			g.setStroke(new BasicStroke(bw));
			g.draw(new RoundRectangle2D.Double(bw / 2, bw / 2, 64 - bw, 64 - bw,
				r * 2 - bw, r * 2 - bw));
		}

		g.setColor(ACC);
		g.setStroke(new BasicStroke(STROKE, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		for (int x : UPRIGHTS)
		{
			g.draw(new Line2D.Double(x, TOP, x, BOT));
		}
		g.draw(new Line2D.Double(SX1, SY1, SX2, SY2));

		g.dispose();
		ImageIO.write(img, "png", out);
		System.out.printf("  %-32s %4dx%-4d %6d bytes%n",
			out.getName(), size, size, out.length());
	}
}
