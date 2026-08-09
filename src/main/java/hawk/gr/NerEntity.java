package hawk.gr;

/**
 * Represents a named entity extracted from text by the NER pipeline.
 *
 * @param type entity type label (e.g., "品牌", "颜色_色彩")
 * @param span the matched text span (e.g., "小米", "黑色")
 * @param prob confidence probability (0.0–1.0)
 */
public class NerEntity {
    private final String type;
    private final String span;
    private final double prob;

    public NerEntity(String type, String span, double prob) {
        this.type = type;
        this.span = span;
        this.prob = prob;
    }

    public String type()  { return type; }
    public String span()  { return span; }
    public double prob()  { return prob; }

    @Override
    public String toString() {
        return type + ":" + span + "(" + String.format("%.2f", prob) + ")";
    }
}
