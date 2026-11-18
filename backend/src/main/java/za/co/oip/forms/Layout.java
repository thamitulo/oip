package za.co.oip.forms;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Text layout of a PDF page in points, origin at the top-left of the visible page (y grows downwards), the way the
 * page looks on screen. {@link FormRenderer} converts back to PDF user space when drawing.
 */
public final class Layout {

    private Layout() {
    }

    /** One word and its box. */
    public record Word(String text, float x0, float y0, float x1, float y1) {
        float centerY() {
            return (y0 + y1) / 2;
        }

        float height() {
            return y1 - y0;
        }

        /** Underscores, dots and similar characters printed as a writing line, not content. */
        boolean isFiller() {
            return text.matches("[_.…:\\-–—|\\[\\]()\\s]+");
        }
    }

    /** A ruled line (table cell border) in the same coordinates; vertical when x0 == x1. */
    public record Rule(float x0, float y0, float x1, float y1) {
    }

    /** Words on one visual line, left to right. */
    public record Line(List<Word> words) {
        float y0() {
            return (float) words.stream().mapToDouble(Word::y0).min().orElse(0);
        }

        float y1() {
            return (float) words.stream().mapToDouble(Word::y1).max().orElse(0);
        }

        float height() {
            return y1() - y0();
        }
    }

    /** One page: size, words, whether the words came from OCR, and the table rules found on it. */
    public record Page(int index, float width, float height, List<Word> words, boolean ocr,
                       List<Rule> verticalRules, List<Rule> horizontalRules) {

        public Page(int index, float width, float height, List<Word> words, boolean ocr) {
            this(index, width, height, words, ocr, List.of(), List.of());
        }

        /** Groups words into lines by vertical overlap. */
        public List<Line> lines() {
            List<Word> sorted = new ArrayList<>(words);
            sorted.sort(Comparator.comparingDouble(Word::centerY));
            List<List<Word>> groups = new ArrayList<>();
            for (Word w : sorted) {
                List<Word> last = groups.isEmpty() ? null : groups.getLast();
                if (last != null) {
                    double cy = last.stream().mapToDouble(Word::centerY).average().orElse(0);
                    double h = Math.max(4, last.stream().mapToDouble(Word::height).average().orElse(8));
                    if (Math.abs(w.centerY() - cy) <= h * 0.5) {
                        last.add(w);
                        continue;
                    }
                }
                groups.add(new ArrayList<>(List.of(w)));
            }
            return groups.stream().map(g -> {
                g.sort(Comparator.comparingDouble(Word::x0));
                return new Line(List.copyOf(g));
            }).toList();
        }
    }
}
