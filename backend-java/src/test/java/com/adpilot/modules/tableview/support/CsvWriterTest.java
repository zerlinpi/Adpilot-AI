package com.adpilot.modules.tableview.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CsvWriter#escape(String)}, focused on CSV formula-injection
 * neutralization (a leading {@code = + - @}, TAB or CR gets a {@code '} prefix) layered
 * on top of the existing RFC-4180 quoting.
 */
class CsvWriterTest {

    // ── formula-injection neutralization ─────────────────────────────────────

    @Test
    void prefixesEqualsSign() {
        assertThat(CsvWriter.escape("=HYPERLINK(\"http://evil\")"))
                .isEqualTo("\"'=HYPERLINK(\"\"http://evil\"\")\"");
    }

    @Test
    void prefixesPlusMinusAt() {
        assertThat(CsvWriter.escape("+1")).isEqualTo("'+1");
        assertThat(CsvWriter.escape("-1")).isEqualTo("'-1");
        assertThat(CsvWriter.escape("@SUM(A1)")).isEqualTo("'@SUM(A1)");
    }

    @Test
    void prefixesLeadingTabAndCarriageReturn() {
        // A leading TAB triggers neutralization; the value itself has no RFC-4180
        // trigger char so only the prefix is added.
        assertThat(CsvWriter.escape("\tcmd")).isEqualTo("'\tcmd");
        // A leading CR is both a neutralization trigger AND an RFC-4180 quote trigger,
        // so the prefixed value is additionally wrapped in quotes.
        assertThat(CsvWriter.escape("\rcmd")).isEqualTo("\"'\rcmd\"");
    }

    // ── ordinary values unchanged ────────────────────────────────────────────

    @Test
    void leavesOrdinaryValuesUnchanged() {
        assertThat(CsvWriter.escape("Campaign 2024")).isEqualTo("Campaign 2024");
        assertThat(CsvWriter.escape("hello")).isEqualTo("hello");
    }

    @Test
    void leavesNumbersAsTextUnchanged() {
        // A plain number does not start with a formula char.
        assertThat(CsvWriter.escape("12345")).isEqualTo("12345");
        assertThat(CsvWriter.escape("3.14")).isEqualTo("3.14");
    }

    @Test
    void doesNotPrefixWhenFormulaCharIsNotFirst() {
        // Only the FIRST character matters — an internal '=' or '+' is untouched.
        assertThat(CsvWriter.escape("a=b")).isEqualTo("a=b");
        assertThat(CsvWriter.escape("1+1")).isEqualTo("1+1");
    }

    // ── null and empty ───────────────────────────────────────────────────────

    @Test
    void nullBecomesEmptyString() {
        assertThat(CsvWriter.escape(null)).isEmpty();
    }

    @Test
    void emptyStringStaysEmpty() {
        assertThat(CsvWriter.escape("")).isEmpty();
    }

    // ── still applies RFC-4180 quoting after neutralization ──────────────────

    @Test
    void quotesValuesWithCommaAfterNeutralization() {
        // Starts with '=' (neutralized) and contains a comma (must quote).
        assertThat(CsvWriter.escape("=A,B")).isEqualTo("\"'=A,B\"");
    }

    @Test
    void ordinaryValueWithCommaStillQuoted() {
        assertThat(CsvWriter.escape("a,b")).isEqualTo("\"a,b\"");
    }
}
