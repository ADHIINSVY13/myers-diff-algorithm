import java.io.BufferedOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;

public class Main {

    /*
     * ============================================================
     * PART 1: REPRESENTING A FILE
     * ============================================================
     *
     * Part A requires line comparisons based on raw bytes.
     *
     * Therefore, we must not read a file using readAllLines() or
     * convert the entire file into ordinary UTF-8 strings for
     * comparison. Doing that can change how certain bytes behave.
     *
     * Instead, we store the original bytes and record the starting
     * and ending positions of each line.
     *
     * Rules:
     * 1. A byte '\n' separates lines.
     * 2. The newline byte is not part of the line itself.
     * 3. A '\r' before '\n' remains part of the line.
     * 4. A final newline does not create an extra empty line.
     *
     * Example:
     *
     * File bytes: "cat\n dog"
     *
     * Line 0: "cat"
     * Line 1: " dog"
     *
     * The original bytes remain available for exact output.
     */

    static class FileLines {

        final byte[] bytes;

        // start[i] is the first byte of line i.
        // end[i] is the position immediately after the line.
        final int[] start;
        final int[] end;

        int size;

        FileLines(byte[] input) {
            bytes = input;

            int capacity = countLines(input);

            start = new int[capacity];
            end = new int[capacity];

            int lineStart = 0;

            for (int i = 0; i < input.length; i++) {

                if (input[i] == '\n') {

                    // Save the line without its newline byte.
                    addLine(lineStart, i);

                    // The next line begins after '\n'.
                    lineStart = i + 1;
                }
            }

            // If the file does not end in '\n', save its final line.
            if (lineStart < input.length) {
                addLine(lineStart, input.length);
            }
        }

        private void addLine(int from, int to) {
            start[size] = from;
            end[size] = to;
            size++;
        }

        /*
         * Calculate how much storage is required for line positions.
         *
         * Every newline finishes one line. If the final byte is not
         * a newline, there is one additional unfinished line.
         */
        private static int countLines(byte[] input) {
            int count = 0;

            for (byte value : input) {
                if (value == '\n') {
                    count++;
                }
            }

            if (input.length > 0
                    && input[input.length - 1] != '\n') {
                count++;
            }

            return count;
        }

        int length(int line) {
            return end[line] - start[line];
        }

        /*
         * Create a comparison key for a line.
         *
         * ISO-8859-1 maps each byte to exactly one character.
         * Consequently, two keys are equal precisely when their
         * original byte sequences are equal.
         *
         * These keys are for comparisons only. Output still uses
         * the original bytes stored in the file.
         */
        String key(int line) {
            return new String(
                    bytes,
                    start[line],
                    length(line),
                    StandardCharsets.ISO_8859_1
            );
        }

        /*
         * Part B operates on Unicode code points, not UTF-16 char
         * positions. For example, many emoji occupy two Java char
         * positions but represent one Unicode code point.
         */
        int[] codePoints(int line) {
            String text = new String(
                    bytes,
                    start[line],
                    length(line),
                    StandardCharsets.UTF_8
            );

            return text.codePoints().toArray();
        }
    }


    /*
     * ============================================================
     * PART 2: ASSIGNING NUMBERS TO DISTINCT LINES
     * ============================================================
     *
     * Myers' algorithm only needs to know whether two elements are
     * equal. It does not need to compare entire line strings every
     * time it visits a position.
     *
     * We assign an integer ID to each distinct line.
     *
     * Both files share the same map:
     *
     * A line "hello" in either file receives the same ID.
     * Different byte sequences receive different IDs.
     *
     * The diff algorithm can then compare integer arrays.
     */

    static int[] numberLines(
            FileLines file,
            HashMap<String, Integer> ids
    ) {
        int[] result = new int[file.size];

        for (int i = 0; i < file.size; i++) {

            String line = file.key(i);
            Integer id = ids.get(line);

            if (id == null) {
                id = ids.size();
                ids.put(line, id);
            }

            result[i] = id;
        }

        return result;
    }


    /*
     * ============================================================
     * PART 3: MYERS' SHORTEST EDIT SCRIPT
     * ============================================================
     *
     * This implementation works with any two integer sequences.
     *
     * For Part A, the integers represent lines.
     * For Part B, they represent Unicode code points.
     *
     * Imagine an edit graph:
     *
     *   (x, y) represents the current positions in sequences A and B.
     *
     *   Delete: move right; consume one element from A.
     *   Insert: move down; consume one element from B.
     *   Keep:   move diagonally; consume equal elements from both.
     *
     * A diagonal move costs no edits. A sequence of diagonal moves
     * through equal elements is called a "snake".
     *
     * The shortest edit script minimizes the number of insertions
     * and deletions required to transform A into B.
     *
     * MEMORY OPTIMIZATION
     *
     * A straightforward Myers implementation saves the complete
     * frontier array for every edit distance. That makes recovering
     * the path easy, but can consume excessive memory.
     *
     * Instead, this implementation searches forward from the start
     * and backward from the end. When the searches overlap, we get
     * a point on a shortest path.
     *
     * We split the problem at that point and solve each half.
     * Reusing the frontier arrays avoids storing a complete history
     * of every search round.
     *
     * Results:
     *
     * deleted[i] = A element i must be deleted.
     * inserted[j] = B element j must be inserted.
     *
     * Unmarked elements are retained in their original order.
     */

    record Point(int x, int y) { }


    static class MyersDiff {

        private final int[] a;
        private final int[] b;

        final boolean[] deleted;
        final boolean[] inserted;

        private final int[] forward;
        private final int[] backward;

        private final int offset;

        MyersDiff(int[] a, int[] b) {
            this.a = a;
            this.b = b;

            deleted = new boolean[a.length];
            inserted = new boolean[b.length];

            /*
             * A diagonal number can be negative, so we use offset
             * to translate diagonal k into a valid array index:
             *
             * array index = k + offset
             */
            int rounds = (a.length + b.length + 1) / 2 + 1;

            offset = rounds + 1;

            forward = new int[2 * rounds + 3];
            backward = new int[2 * rounds + 3];
        }

        /*
         * Start the recursive diff on the complete input sequences.
         */
        MyersDiff compute() {
            solve(0, a.length, 0, b.length);
            return this;
        }

        /*
         * Solve the subproblem:
         *
         * A[aFrom .. aTo)
         * B[bFrom .. bTo)
         *
         * The ending positions are exclusive.
         */
        private void solve(
                int aFrom,
                int aTo,
                int bFrom,
                int bTo
        ) {

            /*
             * Step 1: Skip the common prefix.
             *
             * If the next elements are equal, keeping them is
             * optimal. They do not need to be explored further.
             */
            while (aFrom < aTo
                    && bFrom < bTo
                    && a[aFrom] == b[bFrom]) {

                aFrom++;
                bFrom++;
            }

            /*
             * Step 2: Skip the common suffix.
             *
             * Matching elements at the end can also be retained.
             * Restricting the problem to the middle makes it smaller.
             */
            while (aFrom < aTo
                    && bFrom < bTo
                    && a[aTo - 1] == b[bTo - 1]) {

                aTo--;
                bTo--;
            }

            /*
             * Step 3: Handle empty sides.
             *
             * If A is empty, every remaining B element is inserted.
             * If B is empty, every remaining A element is deleted.
             *
             * These are important base cases for recursion.
             */
            if (aFrom == aTo) {
                for (int j = bFrom; j < bTo; j++) {
                    inserted[j] = true;
                }
                return;
            }

            if (bFrom == bTo) {
                for (int i = aFrom; i < aTo; i++) {
                    deleted[i] = true;
                }
                return;
            }

            /*
             * Step 4: Find a shortest-path split.
             *
             * The returned point is relative to the current
             * subproblem, so convert it to absolute positions.
             */
            Point middle = findMiddle(
                    aFrom, aTo,
                    bFrom, bTo
            );

            int splitA = aFrom + middle.x();
            int splitB = bFrom + middle.y();

            /*
             * Step 5: Solve the two smaller subproblems.
             *
             * The first part ends at the split.
             * The second part starts at the split.
             */
            solve(aFrom, splitA, bFrom, splitB);
            solve(splitA, aTo, splitB, bTo);
        }


        /*
         * Find a point where the forward and backward searches
         * overlap on a shortest edit path.
         *
         * The coordinates returned are relative to the beginning
         * of the current subproblem.
         */
        private Point findMiddle(
                int aFrom,
                int aTo,
                int bFrom,
                int bTo
        ) {

            int n = aTo - aFrom;
            int m = bTo - bFrom;

            /*
             * The final point is (n, m).
             * Its diagonal is delta = x - y = n - m.
             *
             * Whether delta is odd or even determines which search
             * round can first detect the overlap.
             */
            int delta = n - m;
            boolean odd = (delta & 1) != 0;

            int maxRounds = (n + m + 1) / 2;

            // Both searches begin at the origin in their own view.
            forward[1 + offset] = 0;
            backward[1 + offset] = 0;

            for (int d = 0; d <= maxRounds; d++) {

                /*
                 * ------------------------------------------------
                 * FORWARD SEARCH
                 * ------------------------------------------------
                 *
                 * Explore paths from (0, 0).
                 *
                 * At each diagonal, choose the predecessor that
                 * reaches farther in the A direction, then follow
                 * all immediately available matching elements.
                 */
                for (int k = -d; k <= d; k += 2) {

                    int x = nextX(forward, k, d);
                    int y = x - k;

                    // Continue through equal elements without edits.
                    while (x < n && y < m
                            && a[aFrom + x] == b[bFrom + y]) {
                        x++;
                        y++;
                    }

                    forward[k + offset] = x;

                    /*
                     * When delta is odd, the backward search from
                     * the previous round may already have crossed
                     * this forward path.
                     */
                    int opposite = delta - k;

                    boolean known =
                            opposite >= -(d - 1)
                            && opposite <= d - 1;

                    if (odd && known
                            && x + backward[opposite + offset] >= n) {

                        return new Point(x, y);
                    }
                }


                /*
                 * ------------------------------------------------
                 * BACKWARD SEARCH
                 * ------------------------------------------------
                 *
                 * Search from the end toward the beginning.
                 *
                 * Here x and y measure distances backward from
                 * the ends of the two sequences.
                 */
                for (int k = -d; k <= d; k += 2) {

                    int x = nextX(backward, k, d);
                    int y = x - k;

                    // Follow equal elements in reverse order.
                    while (x < n && y < m
                            && a[aTo - 1 - x] == b[bTo - 1 - y]) {
                        x++;
                        y++;
                    }

                    backward[k + offset] = x;

                    /*
                     * When delta is even, overlap can be detected
                     * using the forward values from this round.
                     */
                    int opposite = delta - k;

                    boolean known =
                            opposite >= -d
                            && opposite <= d;

                    if (!odd && known
                            && x + forward[opposite + offset] >= n) {

                        /*
                         * The backward coordinates are distances
                         * from the ends. Convert them into normal
                         * coordinates measured from the starts.
                         */
                        return new Point(n - x, m - y);
                    }
                }
            }

            // For valid inputs, the two searches must overlap.
            throw new IllegalStateException(
                    "Unable to find a middle point"
            );
        }


        /*
         * Choose the starting x-coordinate on diagonal k in round d.
         *
         * There are two possible predecessors:
         *
         * 1. From diagonal k + 1: an insertion, so x stays the same.
         * 2. From diagonal k - 1: a deletion, so x increases by one.
         *
         * Myers chooses the predecessor that reaches farther.
         */
        private int nextX(int[] frontier, int k, int d) {

            boolean insertion =
                    k == -d
                    || (k != d
                    && frontier[k - 1 + offset]
                    < frontier[k + 1 + offset]);

            if (insertion) {
                return frontier[k + 1 + offset];
            }

            return frontier[k - 1 + offset] + 1;
        }
    }


    /*
     * ============================================================
     * PART 4: CHARACTER-LEVEL HIGHLIGHTS
     * ============================================================
     *
     * Part B uses the same diff algorithm on Unicode code points.
     *
     * Suppose the old line is:
     *
     *     hello
     *
     * And the new line is:
     *
     *     hallo
     *
     * The character diff marks the changed positions in each line.
     * We then convert those marks into half-open ranges.
     *
     * A range 1-3 means positions 1 and 2 are included, but
     * position 3 is not.
     *
     * If nothing changed on one side, its range is represented by ".".
     */

    static String highlight(int[] oldChars, int[] newChars) {

        MyersDiff characterDiff =
                new MyersDiff(oldChars, newChars).compute();

        String oldRanges = toRanges(characterDiff.deleted);
        String newRanges = toRanges(characterDiff.inserted);

        return "? " + oldRanges + " | " + newRanges;
    }


    /*
     * Convert a boolean array into sorted, merged ranges.
     *
     * Example:
     *
     * marks:  [false, true, true, false, true]
     *
     * ranges: 1-3,4-5
     *
     * The second range ends at 5 because its last marked index
     * is 4, and the ending index is exclusive.
     *
     * Consecutive marked positions belong to the same range.
     */
    static String toRanges(boolean[] marks) {

        StringBuilder result = new StringBuilder();

        int i = 0;

        while (i < marks.length) {

            // Ignore positions that were not changed.
            if (!marks[i]) {
                i++;
                continue;
            }

            int from = i;

            // Collect one continuous run of changed positions.
            while (i < marks.length && marks[i]) {
                i++;
            }

            if (result.length() > 0) {
                result.append(',');
            }

            result.append(from)
                  .append('-')
                  .append(i);
        }

        // A dot means there are no changed positions on this side.
        return result.length() == 0 ? "." : result.toString();
    }


    /*
     * ============================================================
     * PART 5: OUTPUT
     * ============================================================
     *
     * Output markers:
     *
     *  space  = retained line
     *  -      = deleted line
     *  +      = inserted line
     *  ?      = character highlight information
     *
     * A change block contains consecutive deletions and insertions
     * between retained lines.
     *
     * All deletions in a block must appear before its insertions.
     * In highlight mode, inserted lines are paired with deleted lines
     * by their order inside the block.
     */

    static void writeLine(
            OutputStream output,
            char marker,
            FileLines file,
            int line
    ) throws IOException {

        output.write(marker);

        // Copy the exact bytes from the original file.
        output.write(
                file.bytes,
                file.start[line],
                file.length(line)
        );

        output.write('\n');
    }


    static void printDiff(
            FileLines a,
            FileLines b,
            MyersDiff diff,
            boolean showHighlights,
            OutputStream output
    ) throws IOException {

        int i = 0;
        int j = 0;

        /*
         * Temporary arrays store the line indices belonging to the
         * current change block. They are allocated once and reused.
         */
        int[] deletedLines = new int[a.size];
        int[] insertedLines = new int[b.size];

        while (i < a.size || j < b.size) {

            /*
             * If neither next element is marked for editing, both
             * belong to the retained sequence.
             */
            boolean keep =
                    i < a.size
                    && j < b.size
                    && !diff.deleted[i]
                    && !diff.inserted[j];

            if (keep) {
                writeLine(output, ' ', a, i);
                i++;
                j++;
                continue;
            }

            /*
             * Gather the current change block.
             *
             * Collect deletions from A and insertions from B until
             * the next retained line is reached.
             */
            int deleteCount = 0;
            int insertCount = 0;

            while (
                    (i < a.size && diff.deleted[i])
                    || (j < b.size && diff.inserted[j])
            ) {

                while (i < a.size && diff.deleted[i]) {
                    deletedLines[deleteCount++] = i;
                    i++;
                }

                while (j < b.size && diff.inserted[j]) {
                    insertedLines[insertCount++] = j;
                    j++;
                }
            }

            /*
             * First print every deletion in this block.
             * This enforces the required delete-before-insert order.
             */
            for (int d = 0; d < deleteCount; d++) {
                writeLine(
                        output,
                        '-',
                        a,
                        deletedLines[d]
                );
            }

            /*
             * Print insertions. In highlight mode, pair insertion i
             * with deletion i whenever a corresponding deletion exists.
             */
            for (int ins = 0; ins < insertCount; ins++) {

                int newLine = insertedLines[ins];

                writeLine(output, '+', b, newLine);

                boolean hasMatchingDeletion = ins < deleteCount;

                if (showHighlights && hasMatchingDeletion) {

                    int oldLine = deletedLines[ins];

                    int[] oldChars = a.codePoints(oldLine);
                    int[] newChars = b.codePoints(newLine);

                    String ranges = highlight(oldChars, newChars);

                    // The range description uses ASCII characters.
                    output.write(
                            ranges.getBytes(StandardCharsets.US_ASCII)
                    );

                    output.write('\n');
                }
            }
        }
    }


    /*
     * ============================================================
     * PART 6: COMMAND-LINE INTERFACE
     * ============================================================
     *
     * Supported commands:
     *
     * java Main lines A B
     * java Main highlight A B
     *
     * The files are both read before any diff output is written.
     * This ensures that an unreadable input does not leave a partial
     * diff on standard output.
     *
     * Exit code 0: success.
     * Exit code 2: invalid arguments or an unreadable input file.
     */

    public static void main(String[] args) throws IOException {

        /*
         * Buffer output so large files are not written one byte at
         * a time to the operating system.
         */
        OutputStream output = new BufferedOutputStream(
                new FileOutputStream(FileDescriptor.out),
                1 << 16
        );

        int exitCode = run(args, output, System.err);

        output.flush();

        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }


    static int run(
            String[] args,
            OutputStream output,
            PrintStream error
    ) throws IOException {

        boolean valid =
                args.length == 3
                && (
                    args[0].equals("lines")
                    || args[0].equals("highlight")
                );

        if (!valid) {
            error.println("usage: java Main lines|highlight A B");
            return 2;
        }

        boolean showHighlights = args[0].equals("highlight");

        FileLines a;
        FileLines b;

        try {
            // Read both complete files before generating output.
            a = new FileLines(
                    Files.readAllBytes(Paths.get(args[1]))
            );

            b = new FileLines(
                    Files.readAllBytes(Paths.get(args[2]))
            );

        } catch (IOException | RuntimeException ex) {
            error.println("cannot read input file: " + ex);
            return 2;
        }

        /*
         * Use one shared dictionary for both files. This guarantees
         * that identical lines have identical integer IDs.
         */
        HashMap<String, Integer> ids = new HashMap<>();

        int[] linesA = numberLines(a, ids);
        int[] linesB = numberLines(b, ids);

        // Compute the shortest edit script.
        MyersDiff diff = new MyersDiff(linesA, linesB).compute();

        // Print the line diff, with optional character highlights.
        printDiff(a, b, diff, showHighlights, output);

        return 0;
    }
}