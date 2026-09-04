package hawk.gr.ac;

import java.util.*;

/**
 * Aho-Corasick automaton for multi-pattern string matching.
 * <p>
 * Builds a trie from codebook vocabulary (~104k attribute words),
 * constructs failure links, and scans input text in O(n+m) time.
 * Each matched word carries its codebook position(s) and index(es).
 *
 * <h3>Usage</h3>
 * <pre>
 *   AhoCorasickMatcher m = new AhoCorasickMatcher();
 *   m.add("跑步鞋", "a", 579);
 *   m.add("Nike", "b", 204);
 *   m.build();
 *   List{@code <Match>} matches = m.search("Nike白色跑步鞋");
 * </pre>
 */
public class AhoCorasickMatcher {

    /** A match result: a dictionary word found in the text. */
    public static class Match {
        public final String word;
        public final String position;   // a-f
        public final int index;         // codebook index
        public final int start;
        public final int end;

        Match(String word, String position, int index, int start, int end) {
            this.word = word;
            this.position = position;
            this.index = index;
            this.start = start;
            this.end = end;
        }

        @Override
        public String toString() {
            return String.format("%s->%s_%d@[%d,%d]", word, position, index, start, end);
        }
    }

    // Output record stored at trie nodes
    private static class Output {
        final int posIdx;    // 0-5 for a-f
        final int codeIdx;   // codebook index
        final int length;    // pattern length in characters

        Output(int posIdx, int codeIdx, int length) {
            this.posIdx = posIdx; this.codeIdx = codeIdx; this.length = length;
        }
    }

    private static class Node {
        final Map<Character, Node> children = new HashMap<>();
        Node fail;
        final List<Output> outputs = new ArrayList<>();
    }

    private final Node root = new Node();
    private final String[] positions = {"a","b","c","d","e","f","g","h"};
    private final Map<String,Integer> posToIdx = new HashMap<>();
    private boolean built = false;
    private int patternCount = 0;

    public AhoCorasickMatcher() {
        for (int i = 0; i < positions.length; i++) {
            posToIdx.put(positions[i], i);
        }
    }

    /** Add a pattern word → (position, index). Same word can be added for multiple positions. */
    public void add(String word, String position, int index) {
        if (built) throw new IllegalStateException("Already built");
        Node node = root;
        for (int i = 0; i < word.length(); i++) {
            node = node.children.computeIfAbsent(word.charAt(i), k -> new Node());
        }
        int pIdx = posToIdx.getOrDefault(position, -1);
        if (pIdx >= 0) {
            node.outputs.add(new Output(pIdx, index, word.length()));
            patternCount++;
        }
    }

    /** Build failure links. Must be called after all patterns are added. */
    public void build() {
        built = true;
        Queue<Node> queue = new LinkedList<>();

        // Level-1 children: fail → root
        for (Node child : root.children.values()) {
            child.fail = root;
            queue.add(child);
        }

        // BFS
        while (!queue.isEmpty()) {
            Node node = queue.poll();
            for (Map.Entry<Character, Node> e : node.children.entrySet()) {
                char c = e.getKey();
                Node child = e.getValue();
                queue.add(child);

                Node fail = node.fail;
                while (fail != root && !fail.children.containsKey(c)) {
                    fail = fail.fail;
                }
                if (fail.children.containsKey(c) && fail.children.get(c) != child) {
                    child.fail = fail.children.get(c);
                } else {
                    child.fail = root;
                }

                // Merge outputs from fail node
                for (Output o : child.fail.outputs) {
                    child.outputs.add(o);
                }
            }
        }
    }

    /**
     * Search text and return all matching attribute words.
     * Overlapping matches are all returned; caller should select
     * the best match per position.
     */
    public List<Match> search(String text) {
        if (!built) throw new IllegalStateException("Not built yet");
        List<Match> results = new ArrayList<>();
        Node node = root;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            while (node != root && !node.children.containsKey(c)) {
                node = node.fail;
            }

            if (node.children.containsKey(c)) {
                node = node.children.get(c);
            }

            // Collect all outputs reachable from this node (including fail chain)
            Node temp = node;
            while (temp != root) {
                for (Output o : temp.outputs) {
                    int start = i - o.length + 1;
                    int end = i + 1;
                    String word = text.substring(start, end);
                    results.add(new Match(word, positions[o.posIdx], o.codeIdx, start, end));
                }
                temp = temp.fail;
            }
        }
        return results;
    }

    public int patternCount() { return patternCount; }
}
