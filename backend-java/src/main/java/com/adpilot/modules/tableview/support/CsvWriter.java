package com.adpilot.modules.tableview.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.List;

/**
 * Minimal RFC&nbsp;4180 CSV writer used by the server-side export (Req 2.8). It
 * writes one row at a time to the supplied {@link Writer}, so the export can
 * stream page-by-page without materializing the whole result set as a string.
 *
 * <p>Field quoting rules (RFC 4180):
 * <ul>
 *   <li>A field is wrapped in double quotes when it contains a comma, a double
 *       quote, a carriage return, or a line feed.</li>
 *   <li>A literal double quote inside a field is escaped by doubling it.</li>
 *   <li>{@code null} is emitted as an empty field.</li>
 * </ul>
 * Rows are terminated with CRLF. Before quoting, each value is passed through
 * CSV-injection neutralization: a leading {@code = + - @}, TAB or CR gets a single
 * quote {@code '} prefix so spreadsheet apps treat the cell as text rather than a
 * formula. That prefix becomes part of the cell text, so values still round-trip
 * consistently (preserving export fidelity).
 */
public final class CsvWriter {

    private final Writer out;

    public CsvWriter(Writer out) {
        this.out = out;
    }

    /** Write one CSV record, quoting/escaping each cell as needed. */
    public void writeRow(List<String> cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(escape(cells.get(i)));
        }
        sb.append("\r\n");
        try {
            out.write(sb.toString());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write CSV row", e);
        }
    }

    /** Flush any buffered output to the underlying writer. */
    public void flush() {
        try {
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to flush CSV output", e);
        }
    }

    /**
     * Escape a single field per RFC 4180, first neutralizing spreadsheet formula
     * injection. Returns an empty string for {@code null} so a missing value renders
     * as an empty CSV cell.
     *
     * <p>CSV-injection neutralization: exported values are tenant/import-controlled
     * (campaign names, customer search terms, ...) and flow into files opened in
     * spreadsheet apps. A cell whose first character is one of {@code = + - @}, a TAB
     * ({@code 0x09}) or a CR ({@code 0x0D}) can be interpreted as a formula. To force
     * such cells to be treated as text we prefix a single quote {@code '} before
     * applying RFC-4180 quoting. The prefix becomes part of the cell text, so the
     * value still round-trips consistently.
     */
    public static String escape(String value) {
        if (value == null) {
            return "";
        }
        String neutralized = neutralizeFormula(value);
        return quote(neutralized);
    }

    /**
     * Prefix a single quote {@code '} when the value begins with a character a
     * spreadsheet could interpret as the start of a formula ({@code = + - @}), or a
     * leading TAB/CR. Empty and other values are returned unchanged.
     */
    private static String neutralizeFormula(String value) {
        if (value.isEmpty()) {
            return value;
        }
        char first = value.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@'
                || first == '\t' || first == '\r') {
            return "'" + value;
        }
        return value;
    }

    /** Apply RFC-4180 quoting/escaping to an already-neutralized value. */
    private static String quote(String value) {
        boolean mustQuote = value.indexOf(',') >= 0
                || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0;
        if (!mustQuote) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
