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

    // Reads raw file bytes and records each line's byte boundaries.

    static class FileLines {
        byte[] content;      // the whole file
        int[] lineStart;     // line i starts at content[lineStart[i]]
        int[] lineEnd;       // and ends just before content[lineEnd[i]] (the '\n' is not included)
        int lineCount;

        FileLines(byte[] content) {
            this.content = content;
            int total = countLines(content);
            lineStart = new int[total];
            lineEnd = new int[total];

            int startOfCurrentLine = 0;
            for (int pos = 0; pos < content.length; pos++) {
                if (content[pos] == '\n') {
                    addLine(startOfCurrentLine, pos);
                    startOfCurrentLine = pos + 1;
                }
            }
            // the last line may have no '\n' after it
            if (startOfCurrentLine < content.length) {
                addLine(startOfCurrentLine, content.length);
            }
        }

        private void addLine(int start, int end) {
            lineStart[lineCount] = start;
            lineEnd[lineCount] = end;
            lineCount++;
        }

        // one line per '\n', plus one more if the file does not end with '\n'
        private static int countLines(byte[] content) {
            int count = 0;
            for (byte value : content) {
                if (value == '\n') count++;
            }
            boolean lastLineHasNoNewline = content.length > 0 && content[content.length - 1] != '\n';
            if (lastLineHasNoNewline) count++;
            return count;
        }

        int lineLength(int line) {
            return lineEnd[line] - lineStart[line];
        }

        // Line as a String that is equal to another only if the bytes are equal.
        String lineAsKey(int line) {
            return new String(content, lineStart[line], lineLength(line), StandardCharsets.ISO_8859_1);
        }

        // Line as Unicode code points, for Part B (highlight files are always valid UTF-8).
        int[] lineAsCodePoints(int line) {
            String text = new String(content, lineStart[line], lineLength(line), StandardCharsets.UTF_8);
            return text.codePoints().toArray();
        }
    }

    // Gives every different line a number.
    static int[] linesToNumbers(FileLines file, HashMap<String, Integer> numberOfLine) {
        int[] numbers = new int[file.lineCount];
        for (int line = 0; line < file.lineCount; line++) {
            String key = file.lineAsKey(line);
            Integer number = numberOfLine.get(key);
            if (number == null) {
                number = numberOfLine.size();      // next unused number
                numberOfLine.put(key, number);
            }
            numbers[line] = number;
        }
        return numbers;
    }

    // Myers' linear-space algorithm finds a shortest edit script.

    // A point (x, y) in the edit graph
    record Point(int x, int y) { }

    static class MyersDiff {
        private final int[] a;
        private final int[] b;
        final boolean[] isDeleted;
        final boolean[] isInserted;

        // V arrays for the forward and the backward search.
        private final int[] forwardV;
        private final int[] backwardV;
        private final int offset;

        MyersDiff(int[] a, int[] b) {
            this.a = a;
            this.b = b;
            isDeleted = new boolean[a.length];
            isInserted = new boolean[b.length];

            int maxRounds = (a.length + b.length + 1) / 2 + 1;
            offset = maxRounds + 1;
            forwardV = new int[2 * maxRounds + 3];
            backwardV = new int[2 * maxRounds + 3];
        }

        // Runs the diff on the whole of a and b.
        MyersDiff compute() {
            diffRange(0, a.length, 0, b.length);
            return this;
        }

        // Diff of a[aStart..aEnd) against b[bStart..bEnd)  (end not included)
        private void diffRange(int aStart, int aEnd, int bStart, int bEnd) {
            // Equal elements at the start are always kept: skip them
            while (aStart < aEnd && bStart < bEnd && a[aStart] == b[bStart]) {
                aStart++;
                bStart++;
            }
            // Equal elements at the end are always kept: skip them
            while (aStart < aEnd && bStart < bEnd && a[aEnd - 1] == b[bEnd - 1]) {
                aEnd--;
                bEnd--;
            }

            // Nothing left in A: the rest of B is inserted
            if (aStart == aEnd) {
                for (int j = bStart; j < bEnd; j++) isInserted[j] = true;
                return;
            }
            // Nothing left in B: the rest of A is deleted
            if (bStart == bEnd) {
                for (int i = aStart; i < aEnd; i++) isDeleted[i] = true;
                return;
            }

            Point split = findMiddleSnake(aStart, aEnd, bStart, bEnd);
            int splitA = aStart + split.x();
            int splitB = bStart + split.y();
            diffRange(aStart, splitA, bStart, splitB);     // top-left part
            diffRange(splitA, aEnd, splitB, bEnd);         // bottom-right part
        }

        // Finds a point on a shortest edit path for a[aStart..aEnd) vs b[bStart..bEnd).
        private Point findMiddleSnake(int aStart, int aEnd, int bStart, int bEnd) {
            int n = aEnd - aStart;              // length of this part of a
            int m = bEnd - bStart;              // length of this part of b
            int delta = n - m;                  // the diagonal of the end point (n, m)
            boolean deltaIsOdd = (delta % 2) != 0;
            int maxRounds = (n + m + 1) / 2;    // each search needs at most half of the edits

            // Start values, so that in round d = 0 both searches begin at x = 0 on diagonal 0
            forwardV[1 + offset] = 0;
            backwardV[1 + offset] = 0;

            for (int d = 0; d <= maxRounds; d++) {

                // ---------- forward search, from (0, 0) towards (n, m) ----------
                for (int k = -d; k <= d; k += 2) {
                    int x = nextStartX(forwardV, k, d);
                    int y = x - k;

                    // Follow the snake: keep moving diagonally while the elements are equal
                    while (x < n && y < m && a[aStart + x] == b[bStart + y]) {
                        x++;
                        y++;
                    }
                    forwardV[k + offset] = x;

                    // If delta is odd, the two searches can only meet right after a forward step.
                    int backwardK = delta - k;
                    boolean backwardKnowsThisDiagonal = backwardK >= -(d - 1) && backwardK <= d - 1;
                    if (deltaIsOdd && backwardKnowsThisDiagonal
                            && x + backwardV[backwardK + offset] >= n) {
                        return new Point(x, y);
                    }
                }

                // Here x and y count how far we are from the END of a and b.
                for (int k = -d; k <= d; k += 2) {
                    int x = nextStartX(backwardV, k, d);
                    int y = x - k;

                    // Follow the snake backwards (moving up-left)
                    while (x < n && y < m && a[aEnd - 1 - x] == b[bEnd - 1 - y]) {
                        x++;
                        y++;
                    }
                    backwardV[k + offset] = x;

                    // If delta is even, the two searches can only meet right after a backward step.
                    int forwardK = delta - k;
                    boolean forwardKnowsThisDiagonal = forwardK >= -d && forwardK <= d;
                    if (!deltaIsOdd && forwardKnowsThisDiagonal
                            && x + forwardV[forwardK + offset] >= n) {
                        // turn "distance from the end" back into a normal point
                        return new Point(n - x, m - y);
                    }
                }
            }
            throw new IllegalStateException("middle snake not found");   // cannot happen
        }

        // The usual Myers step: where do we start on diagonal k in round d?
        private int nextStartX(int[] v, int k, int d) {
            boolean goDown = (k == -d) || (k != d && v[k - 1 + offset] < v[k + 1 + offset]);
            if (goDown) {
                return v[k + 1 + offset];
            }
            return v[k - 1 + offset] + 1;
        }
    }

    // Builds character-level changed ranges for Part B.

    // 12-13 | 11-12".
    static String buildHighlightLine(int[] oldChars, int[] newChars) {
        MyersDiff charDiff = new MyersDiff(oldChars, newChars).compute();
        return "? " + marksToRanges(charDiff.isDeleted) + " | " + marksToRanges(charDiff.isInserted);
    }

    static String marksToRanges(boolean[] marked) {
        StringBuilder ranges = new StringBuilder();
        int pos = 0;
        while (pos < marked.length) {
            if (!marked[pos]) {
                pos++;
                continue;
            }
            int rangeStart = pos;
            while (pos < marked.length && marked[pos]) pos++;
            if (ranges.length() > 0) ranges.append(',');
            ranges.append(rangeStart).append('-').append(pos);
        }
        return ranges.length() == 0 ? "." : ranges.toString();
    }

    // Prints kept, deleted, and inserted lines in the required order.

    // Writes: prefix character + the exact bytes of the line + '\n'
    static void writeLine(OutputStream out, char prefix, FileLines file, int line) throws IOException {
        out.write(prefix);
        out.write(file.content, file.lineStart[line], file.lineLength(line));
        out.write('\n');
    }

    static void printDiff(FileLines fileA, FileLines fileB, MyersDiff diff, boolean showHighlights,
                          OutputStream out) throws IOException {
        int countA = fileA.lineCount;
        int countB = fileB.lineCount;
        int lineA = 0;      // next line of A to print
        int lineB = 0;      // next line of B to print

        // line numbers of the current change block (made once, reused for every block)
        int[] deletedLines = new int[countA];
        int[] insertedLines = new int[countB];

        while (lineA < countA || lineB < countB) {

            // Keep line: neither side is marked, so these two lines are the same
            boolean bothKept = lineA < countA && lineB < countB
                    && !diff.isDeleted[lineA] && !diff.isInserted[lineB];
            if (bothKept) {
                writeLine(out, ' ', fileA, lineA);
                lineA++;
                lineB++;
                continue;
            }

            // Otherwise we are at the start of a change block.
            int deletedCount = 0;
            int insertedCount = 0;
            while ((lineA < countA && diff.isDeleted[lineA]) || (lineB < countB && diff.isInserted[lineB])) {
                while (lineA < countA && diff.isDeleted[lineA]) deletedLines[deletedCount++] = lineA++;
                while (lineB < countB && diff.isInserted[lineB]) insertedLines[insertedCount++] = lineB++;
            }

            // Delete-first rule: all "-" lines, then all "+" lines
            for (int i = 0; i < deletedCount; i++) {
                writeLine(out, '-', fileA, deletedLines[i]);
            }
            for (int i = 0; i < insertedCount; i++) {
                writeLine(out, '+', fileB, insertedLines[i]);

                // Part B: the i-th "+" line is paired with the i-th "-" line (if there is one)
                boolean hasPair = i < deletedCount;
                if (showHighlights && hasPair) {
                    int[] oldChars = fileA.lineAsCodePoints(deletedLines[i]);
                    int[] newChars = fileB.lineAsCodePoints(insertedLines[i]);
                    out.write(buildHighlightLine(oldChars, newChars).getBytes(StandardCharsets.US_ASCII));
                    out.write('\n');
                }
            }
        }
    }

    // Validates arguments, runs the requested mode, and writes output.

    public static void main(String[] args) throws IOException {
        OutputStream out = new BufferedOutputStream(new FileOutputStream(FileDescriptor.out), 1 << 16);
        int exitCode = run(args, out, System.err);
        out.flush();
        if (exitCode != 0) System.exit(exitCode);
    }

    // Does the whole job and returns the exit code (0 = ok, 2 = bad arguments or unreadable file).
    static int run(String[] args, OutputStream out, PrintStream err) throws IOException {
        boolean validCommand = args.length == 3 && (args[0].equals("lines") || args[0].equals("highlight"));
        if (!validCommand) {
            err.println("usage: java Main lines|highlight A B");
            return 2;
        }
        boolean showHighlights = args[0].equals("highlight");

        // Read both files before printing anything, so a bad file leaves stdout empty
        FileLines fileA;
        FileLines fileB;
        try {
            fileA = new FileLines(Files.readAllBytes(Paths.get(args[1])));
            fileB = new FileLines(Files.readAllBytes(Paths.get(args[2])));
        } catch (IOException | RuntimeException e) {
            err.println("cannot read input file: " + e);
            return 2;
        }

        // Both files share one map, so the same line gets the same number in A and in B
        HashMap<String, Integer> numberOfLine = new HashMap<>();
        int[] numbersA = linesToNumbers(fileA, numberOfLine);
        int[] numbersB = linesToNumbers(fileB, numberOfLine);

        MyersDiff diff = new MyersDiff(numbersA, numbersB).compute();
        printDiff(fileA, fileB, diff, showHighlights, out);
        return 0;
    }
}
