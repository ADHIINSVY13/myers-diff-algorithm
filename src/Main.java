import java.io.BufferedOutputStream;          // wraps an output stream with a buffer so writes are fast
import java.io.FileDescriptor;                // gives access to the raw stdout/stderr handles
import java.io.FileOutputStream;              // writes bytes to a file descriptor (we use it for stdout)
import java.io.IOException;                   // exception type for I/O failures
import java.io.OutputStream;                  // generic byte output stream type
import java.io.PrintStream;                   // used for printing error text (System.err)
import java.nio.charset.StandardCharsets;     // constants such as UTF_8 / ISO_8859_1 / US_ASCII
import java.nio.file.Files;                   // helper to read whole files
import java.nio.file.Paths;                   // helper to build a file path from a string
import java.util.HashMap;                     // hash table: line text -> number

public class Main {                           // the program's only class

    // Reads raw file bytes and records each line's byte boundaries.

    static class FileLines {                  // holds one file and where each of its lines starts and ends
        byte[] content;      // the whole file as raw bytes
        int[] lineStart;     // line i starts at content[lineStart[i]]
        int[] lineEnd;       // and ends just before content[lineEnd[i]] (the '\n' is not included)
        int lineCount;       // how many lines have been recorded so far

        FileLines(byte[] content) {                       // constructor: receives the file bytes
            this.content = content;                       // remember the bytes
            int total = countLines(content);              // count lines first so arrays can be sized exactly
            lineStart = new int[total];                   // allocate start positions array
            lineEnd = new int[total];                     // allocate end positions array

            int startOfCurrentLine = 0;                   // the line we are scanning begins at byte 0
            for (int pos = 0; pos < content.length; pos++) {      // look at every byte
                if (content[pos] == '\n') {                       // a newline ends the current line
                    addLine(startOfCurrentLine, pos);             // record [start, pos) as a line
                    startOfCurrentLine = pos + 1;                 // next line starts after the '\n'
                }
            }
            // the last line may have no '\n' after it
            if (startOfCurrentLine < content.length) {            // leftover bytes = unterminated last line
                addLine(startOfCurrentLine, content.length);      // record it, ending at end of file
            }
        }

        private void addLine(int start, int end) {        // stores one line's boundaries
            lineStart[lineCount] = start;                 // save where it starts
            lineEnd[lineCount] = end;                     // save where it ends
            lineCount++;                                  // move to the next free slot
        }

        // one line per '\n', plus one more if the file does not end with '\n'
        private static int countLines(byte[] content) {   // counts lines without storing them
            int count = 0;                                // running total
            for (byte value : content) {                  // go through every byte
                if (value == '\n') count++;               // each newline = one line
            }
            boolean lastLineHasNoNewline = content.length > 0 && content[content.length - 1] != '\n';  // file non-empty and last byte isn't '\n'
            if (lastLineHasNoNewline) count++;            // that unterminated line also counts
            return count;                                 // total number of lines
        }

        int lineLength(int line) {                        // length of a line in bytes
            return lineEnd[line] - lineStart[line];       // end minus start
        }

        // Line as a String that is equal to another only if the bytes are equal.
        String lineAsKey(int line) {                      // turns a line into a hashable String
            // ISO_8859_1 maps each byte to exactly one char, so no bytes are lost or merged
            return new String(content, lineStart[line], lineLength(line), StandardCharsets.ISO_8859_1);
        }

        // Line as Unicode code points, for Part B (highlight files are always valid UTF-8).
        int[] lineAsCodePoints(int line) {                // used for character-level diff
            String text = new String(content, lineStart[line], lineLength(line), StandardCharsets.UTF_8);  // decode bytes as UTF-8
            return text.codePoints().toArray();           // one int per character (handles emoji etc.)
        }
    }

    // Gives every different line a number.
    static int[] linesToNumbers(FileLines file, HashMap<String, Integer> numberOfLine) {   // converts lines to ints so comparing is fast
        int[] numbers = new int[file.lineCount];          // one number per line
        for (int line = 0; line < file.lineCount; line++) {   // for each line
            String key = file.lineAsKey(line);            // get its text key
            Integer number = numberOfLine.get(key);       // have we seen this text before?
            if (number == null) {                         // no: it's new
                number = numberOfLine.size();      // next unused number
                numberOfLine.put(key, number);            // remember it
            }
            numbers[line] = number;                       // store the number for this line
        }
        return numbers;                                   // the file as an int sequence
    }

    // Myers' linear-space algorithm finds a shortest edit script.

    // A point (x, y) in the edit graph
    record Point(int x, int y) { }                        // simple immutable pair of ints

    static class MyersDiff {                              // computes which elements of a/b are deleted/inserted
        private final int[] a;                            // old sequence
        private final int[] b;                            // new sequence
        final boolean[] isDeleted;                        // isDeleted[i] = a[i] is not in the common part
        final boolean[] isInserted;                       // isInserted[j] = b[j] is not in the common part

        // V arrays for the forward and the backward search.
        private final int[] forwardV;                     // furthest x reached per diagonal, forward search
        private final int[] backwardV;                    // same for backward search
        private final int offset;                         // shifts negative diagonal numbers to valid indexes

        MyersDiff(int[] a, int[] b) {                     // constructor
            this.a = a;                                   // store old sequence
            this.b = b;                                   // store new sequence
            isDeleted = new boolean[a.length];            // all false at first
            isInserted = new boolean[b.length];           // all false at first

            int maxRounds = (a.length + b.length + 1) / 2 + 1;    // upper bound for rounds in any sub-problem
            offset = maxRounds + 1;                       // diagonal k is stored at index k + offset
            forwardV = new int[2 * maxRounds + 3];        // room for diagonals -maxRounds-1 .. maxRounds+1
            backwardV = new int[2 * maxRounds + 3];       // same size for backward array
        }

        // Runs the diff on the whole of a and b.
        MyersDiff compute() {                             // public entry point
            diffRange(0, a.length, 0, b.length);          // diff everything
            return this;                                  // allows chaining: new MyersDiff(..).compute()
        }

        // Diff of a[aStart..aEnd) against b[bStart..bEnd)  (end not included)
        private void diffRange(int aStart, int aEnd, int bStart, int bEnd) {   // recursive divide and conquer
            // Equal elements at the start are always kept: skip them
            while (aStart < aEnd && bStart < bEnd && a[aStart] == b[bStart]) {   // common prefix
                aStart++;                                 // advance in a
                bStart++;                                 // advance in b
            }
            // Equal elements at the end are always kept: skip them
            while (aStart < aEnd && bStart < bEnd && a[aEnd - 1] == b[bEnd - 1]) {   // common suffix
                aEnd--;                                   // shrink a from the end
                bEnd--;                                   // shrink b from the end
            }

            // Nothing left in A: the rest of B is inserted
            if (aStart == aEnd) {                         // a part is empty
                for (int j = bStart; j < bEnd; j++) isInserted[j] = true;   // mark all remaining b as inserted
                return;                                   // done with this range
            }
            // Nothing left in B: the rest of A is deleted
            if (bStart == bEnd) {                         // b part is empty
                for (int i = aStart; i < aEnd; i++) isDeleted[i] = true;    // mark all remaining a as deleted
                return;                                   // done with this range
            }

            Point split = findMiddleSnake(aStart, aEnd, bStart, bEnd);   // find a point on a shortest path
            int splitA = aStart + split.x();              // convert to absolute index in a
            int splitB = bStart + split.y();              // convert to absolute index in b
            diffRange(aStart, splitA, bStart, splitB);     // top-left part
            diffRange(splitA, aEnd, splitB, bEnd);         // bottom-right part
        }

        // Finds a point on a shortest edit path for a[aStart..aEnd) vs b[bStart..bEnd).
        private Point findMiddleSnake(int aStart, int aEnd, int bStart, int bEnd) {
            int n = aEnd - aStart;              // length of this part of a
            int m = bEnd - bStart;              // length of this part of b
            int delta = n - m;                  // the diagonal of the end point (n, m)
            boolean deltaIsOdd = (delta % 2) != 0;    // parity decides in which search the two paths meet
            int maxRounds = (n + m + 1) / 2;    // each search needs at most half of the edits

            // Start values, so that in round d = 0 both searches begin at x = 0 on diagonal 0
            forwardV[1 + offset] = 0;           // forward: virtual start for diagonal 1
            backwardV[1 + offset] = 0;          // backward: same

            for (int d = 0; d <= maxRounds; d++) {    // d = number of edits allowed so far

                // ---------- forward search, from (0, 0) towards (n, m) ----------
                for (int k = -d; k <= d; k += 2) {            // diagonals reachable with d edits
                    int x = nextStartX(forwardV, k, d);       // choose to come from above or left
                    int y = x - k;                            // y follows from x and the diagonal

                    // Follow the snake: keep moving diagonally while the elements are equal
                    while (x < n && y < m && a[aStart + x] == b[bStart + y]) {
                        x++;                                  // matched, move right
                        y++;                                  // and down
                    }
                    forwardV[k + offset] = x;                 // remember furthest x on this diagonal

                    // If delta is odd, the two searches can only meet right after a forward step.
                    int backwardK = delta - k;                // the same diagonal as seen from the backward side
                    boolean backwardKnowsThisDiagonal = backwardK >= -(d - 1) && backwardK <= d - 1;   // backward has done d-1 rounds
                    if (deltaIsOdd && backwardKnowsThisDiagonal
                            && x + backwardV[backwardK + offset] >= n) {   // forward x + backward distance covers all of a: paths overlap
                        return new Point(x, y);               // overlap point = split point
                    }
                }

                // Here x and y count how far we are from the END of a and b.
                for (int k = -d; k <= d; k += 2) {            // backward search diagonals
                    int x = nextStartX(backwardV, k, d);      // start x for this diagonal
                    int y = x - k;                            // matching y

                    // Follow the snake backwards (moving up-left)
                    while (x < n && y < m && a[aEnd - 1 - x] == b[bEnd - 1 - y]) {
                        x++;                                  // one more matching element from the end
                        y++;                                  // same in b
                    }
                    backwardV[k + offset] = x;                // remember furthest backward x

                    // If delta is even, the two searches can only meet right after a backward step.
                    int forwardK = delta - k;                 // matching forward diagonal
                    boolean forwardKnowsThisDiagonal = forwardK >= -d && forwardK <= d;   // forward has done d rounds
                    if (!deltaIsOdd && forwardKnowsThisDiagonal
                            && x + forwardV[forwardK + offset] >= n) {     // paths overlap
                        // turn "distance from the end" back into a normal point
                        return new Point(n - x, m - y);
                    }
                }
            }
            throw new IllegalStateException("middle snake not found");   // cannot happen
        }

        // The usual Myers step: where do we start on diagonal k in round d?
        private int nextStartX(int[] v, int k, int d) {
            boolean goDown = (k == -d) || (k != d && v[k - 1 + offset] < v[k + 1 + offset]);   // pick the neighbour that got further
            if (goDown) {                                     // came from diagonal k+1 by an insertion (down)
                return v[k + 1 + offset];                     // x stays the same
            }
            return v[k - 1 + offset] + 1;                     // came from k-1 by a deletion (right): x+1
        }
    }

    // Builds character-level changed ranges for Part B.

    // 12-13 | 11-12".
    static String buildHighlightLine(int[] oldChars, int[] newChars) {   // makes e.g. "? 12-13 | 11-12"
        MyersDiff charDiff = new MyersDiff(oldChars, newChars).compute();   // diff the two lines character by character
        return "? " + marksToRanges(charDiff.isDeleted) + " | " + marksToRanges(charDiff.isInserted);   // old ranges | new ranges
    }

    static String marksToRanges(boolean[] marked) {       // turns true/false flags into "start-end,start-end"
        StringBuilder ranges = new StringBuilder();       // result under construction
        int pos = 0;                                      // current position
        while (pos < marked.length) {                     // scan everything
            if (!marked[pos]) {                           // unmarked: skip
                pos++;
                continue;
            }
            int rangeStart = pos;                         // a marked run begins here
            while (pos < marked.length && marked[pos]) pos++;   // move to the end of the run
            if (ranges.length() > 0) ranges.append(',');  // comma between ranges
            ranges.append(rangeStart).append('-').append(pos);  // end is exclusive
        }
        return ranges.length() == 0 ? "." : ranges.toString();  // "." means no changes
    }

    // Prints kept, deleted, and inserted lines in the required order.

    // Writes: prefix character + the exact bytes of the line + '\n'
    static void writeLine(OutputStream out, char prefix, FileLines file, int line) throws IOException {
        out.write(prefix);                                // ' ', '-' or '+'
        out.write(file.content, file.lineStart[line], file.lineLength(line));   // raw bytes of the line, unchanged
        out.write('\n');                                  // always end with a newline
    }

    static void printDiff(FileLines fileA, FileLines fileB, MyersDiff diff, boolean showHighlights,
                          OutputStream out) throws IOException {
        int countA = fileA.lineCount;                     // number of lines in A
        int countB = fileB.lineCount;                     // number of lines in B
        int lineA = 0;      // next line of A to print
        int lineB = 0;      // next line of B to print

        // line numbers of the current change block (made once, reused for every block)
        int[] deletedLines = new int[countA];             // buffer for deleted line indexes
        int[] insertedLines = new int[countB];            // buffer for inserted line indexes

        while (lineA < countA || lineB < countB) {        // until both files are fully consumed

            // Keep line: neither side is marked, so these two lines are the same
            boolean bothKept = lineA < countA && lineB < countB
                    && !diff.isDeleted[lineA] && !diff.isInserted[lineB];
            if (bothKept) {
                writeLine(out, ' ', fileA, lineA);        // unchanged line, prefix is a space
                lineA++;                                  // advance both files
                lineB++;
                continue;                                 // next iteration
            }

            // Otherwise we are at the start of a change block.
            int deletedCount = 0;                         // deletions in this block
            int insertedCount = 0;                        // insertions in this block
            while ((lineA < countA && diff.isDeleted[lineA]) || (lineB < countB && diff.isInserted[lineB])) {   // still inside changes
                while (lineA < countA && diff.isDeleted[lineA]) deletedLines[deletedCount++] = lineA++;      // collect deleted lines
                while (lineB < countB && diff.isInserted[lineB]) insertedLines[insertedCount++] = lineB++;   // collect inserted lines
            }

            // Delete-first rule: all "-" lines, then all "+" lines
            for (int i = 0; i < deletedCount; i++) {
                writeLine(out, '-', fileA, deletedLines[i]);          // print each deleted line
            }
            for (int i = 0; i < insertedCount; i++) {
                writeLine(out, '+', fileB, insertedLines[i]);         // print each inserted line

                // Part B: the i-th "+" line is paired with the i-th "-" line (if there is one)
                boolean hasPair = i < deletedCount;                   // is there a matching deleted line?
                if (showHighlights && hasPair) {
                    int[] oldChars = fileA.lineAsCodePoints(deletedLines[i]);    // old line as characters
                    int[] newChars = fileB.lineAsCodePoints(insertedLines[i]);   // new line as characters
                    out.write(buildHighlightLine(oldChars, newChars).getBytes(StandardCharsets.US_ASCII));   // print "? ..." line
                    out.write('\n');                                  // end of the highlight line
                }
            }
        }
    }

    // Validates arguments, runs the requested mode, and writes output.

    public static void main(String[] args) throws IOException {
        OutputStream out = new BufferedOutputStream(new FileOutputStream(FileDescriptor.out), 1 << 16);   // buffered stdout, 64 KB buffer
        int exitCode = run(args, out, System.err);        // do the work
        out.flush();                                      // make sure everything is written
        if (exitCode != 0) System.exit(exitCode);         // non-zero exit signals an error
    }

    // Does the whole job and returns the exit code (0 = ok, 2 = bad arguments or unreadable file).
    static int run(String[] args, OutputStream out, PrintStream err) throws IOException {
        boolean validCommand = args.length == 3 && (args[0].equals("lines") || args[0].equals("highlight"));   // need: mode, fileA, fileB
        if (!validCommand) {
            err.println("usage: java Main lines|highlight A B");   // tell the user how to call it
            return 2;                                     // error code
        }
        boolean showHighlights = args[0].equals("highlight");   // "highlight" mode adds the "?" lines

        // Read both files before printing anything, so a bad file leaves stdout empty
        FileLines fileA;
        FileLines fileB;
        try {
            fileA = new FileLines(Files.readAllBytes(Paths.get(args[1])));   // read + index file A
            fileB = new FileLines(Files.readAllBytes(Paths.get(args[2])));   // read + index file B
        } catch (IOException | RuntimeException e) {
            err.println("cannot read input file: " + e);  // report the problem
            return 2;
        }

        // Both files share one map, so the same line gets the same number in A and in B
        HashMap<String, Integer> numberOfLine = new HashMap<>();   // shared dictionary
        int[] numbersA = linesToNumbers(fileA, numberOfLine);      // A as numbers
        int[] numbersB = linesToNumbers(fileB, numberOfLine);      // B as numbers

        MyersDiff diff = new MyersDiff(numbersA, numbersB).compute();   // run the line-level diff
        printDiff(fileA, fileB, diff, showHighlights, out);             // print the result
        return 0;                                         // success
    }
}
