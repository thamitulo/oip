package za.co.oip.forms;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds the ruled lines of form tables (cell borders) in a rendered page image. Works the same for digital and
 * scanned pages, so answers can be placed in the cell next to a label instead of across its border.
 */
final class RuleDetector {

    /** Darker than this (0–255 grey) counts as ink. */
    static final int INK = 150;
    /** Shorter vertical runs are glyph strokes, not cell borders (points). */
    static final float MIN_VERTICAL_PT = 11f;
    static final float MIN_HORIZONTAL_PT = 24f;
    /** Wider "lines" are filled areas or pictures, not borders (points). */
    static final float MAX_THICKNESS_PT = 3f;

    private RuleDetector() {
    }

    record Rules(List<Layout.Rule> vertical, List<Layout.Rule> horizontal) {
        static final Rules NONE = new Rules(List.of(), List.of());
    }

    /** @param ptPerPx points per image pixel (72 / dpi) */
    static Rules detect(BufferedImage image, float ptPerPx) {
        BufferedImage gray = image.getType() == BufferedImage.TYPE_BYTE_GRAY ? image : toGray(image);
        byte[] px = ((DataBufferByte) gray.getRaster().getDataBuffer()).getData();
        int w = gray.getWidth(), h = gray.getHeight();
        return new Rules(
                merge(runs(px, w, h, true, Math.round(MIN_VERTICAL_PT / ptPerPx)), true, ptPerPx),
                merge(runs(px, w, h, false, Math.round(MIN_HORIZONTAL_PT / ptPerPx)), false, ptPerPx));
    }

    /** Ink runs along columns (vertical) or rows (horizontal): {position, start, end} in pixels. Gaps of 1px are bridged. */
    private static List<int[]> runs(byte[] px, int w, int h, boolean vertical, int minLen) {
        List<int[]> out = new ArrayList<>();
        int outer = vertical ? w : h, inner = vertical ? h : w;
        for (int a = 0; a < outer; a++) {
            int start = -1, last = -1;
            for (int b = 0; b < inner; b++) {
                int v = px[vertical ? b * w + a : a * w + b] & 0xff;
                if (v < INK) {
                    if (start < 0) start = b;
                    last = b;
                } else if (start >= 0 && b - last > 2) {
                    if (last - start + 1 >= minLen) out.add(new int[]{a, start, last});
                    start = -1;
                }
            }
            if (start >= 0 && last - start + 1 >= minLen) out.add(new int[]{a, start, last});
        }
        return out;
    }

    /** Joins runs in neighbouring columns/rows into one rule; drops thick ones (filled areas). */
    private static List<Layout.Rule> merge(List<int[]> runs, boolean vertical, float s) {
        List<int[]> groups = new ArrayList<>(); // {posStart, posEnd, start, end}
        for (int[] r : runs) {
            int[] hit = null;
            for (int i = groups.size() - 1; i >= 0 && i >= groups.size() - 40; i--) {
                int[] g = groups.get(i);
                if (r[0] - g[1] <= 1 && r[1] <= g[3] && r[2] >= g[2]) {
                    hit = g;
                    break;
                }
            }
            if (hit != null) {
                hit[1] = r[0];
                hit[2] = Math.min(hit[2], r[1]);
                hit[3] = Math.max(hit[3], r[2]);
            } else {
                groups.add(new int[]{r[0], r[0], r[1], r[2]});
            }
        }
        List<Layout.Rule> rules = new ArrayList<>();
        for (int[] g : groups) {
            if ((g[1] - g[0] + 1) * s > MAX_THICKNESS_PT) continue;
            float pos = (g[0] + g[1] + 1) / 2f * s;
            rules.add(vertical ? new Layout.Rule(pos, g[2] * s, pos, (g[3] + 1) * s)
                    : new Layout.Rule(g[2] * s, pos, (g[3] + 1) * s, pos));
        }
        return rules;
    }

    private static BufferedImage toGray(BufferedImage src) {
        BufferedImage g = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        g.getGraphics().drawImage(src, 0, 0, null);
        return g;
    }
}
