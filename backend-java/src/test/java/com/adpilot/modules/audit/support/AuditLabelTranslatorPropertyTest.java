package com.adpilot.modules.audit.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property 11: Audit code-to-label mapping is total.
 *
 * <p>For any action/entity-type code — including {@code null}, blank, and
 * unrecognized values — {@link AuditLabelTranslator} returns a non-empty label
 * and never renders a recognized code raw.
 *
 * <p>Tag: {@code Feature: app-functionality-completion, Property 11: Audit
 * code-to-label mapping is total}
 *
 * <p>Validates: Requirements 11.1, 11.2
 */
@Label("Feature: app-functionality-completion, Property 11: Audit code-to-label mapping is total")
class AuditLabelTranslatorPropertyTest {

    private static final int MIN_ITERATIONS = 100;

    // ----- Totality: any input yields a non-empty action label -----

    @Property(tries = MIN_ITERATIONS)
    @Label("actionLabel returns a non-empty label for any input (including null/blank/unrecognized)")
    void actionLabelIsTotal(@ForAll("anyCode") String code) {
        String label = AuditLabelTranslator.actionLabel(code);
        assertThat(label).isNotNull();
        assertThat(label.trim()).isNotEmpty();
    }

    @Property(tries = MIN_ITERATIONS)
    @Label("entityTypeLabel returns a non-empty label for any input (including null/blank/unrecognized)")
    void entityTypeLabelIsTotal(@ForAll("anyCode") String code) {
        String label = AuditLabelTranslator.entityTypeLabel(code);
        assertThat(label).isNotNull();
        assertThat(label.trim()).isNotEmpty();
    }

    // ----- Recognized codes are never rendered raw -----

    @Property(tries = MIN_ITERATIONS)
    @Label("actionLabel never returns a recognized action code raw, in any letter case")
    void recognizedActionIsNeverRaw(@ForAll("recognizedActionCode") String code) {
        String label = AuditLabelTranslator.actionLabel(code);
        assertThat(label).isNotNull();
        assertThat(label.trim()).isNotEmpty();
        // The label must differ from the raw code (case-insensitively) so the
        // audit log never surfaces an opaque machine code for a known action.
        assertThat(label).isNotEqualToIgnoringCase(code);
        // It must resolve to the canonical recognized label.
        assertThat(label).isEqualTo(
                AuditLabelTranslator.actionLabel(code.toUpperCase(Locale.ROOT)));
    }

    @Property(tries = MIN_ITERATIONS)
    @Label("entityTypeLabel never returns a recognized entity-type code raw, in any letter case")
    void recognizedEntityTypeIsNeverRaw(@ForAll("recognizedEntityTypeCode") String code) {
        String label = AuditLabelTranslator.entityTypeLabel(code);
        assertThat(label).isNotNull();
        assertThat(label.trim()).isNotEmpty();
        assertThat(label).isNotEqualToIgnoringCase(code);
        assertThat(label).isEqualTo(
                AuditLabelTranslator.entityTypeLabel(code.toUpperCase(Locale.ROOT)));
    }

    // ----- Generators -----

    /**
     * Any code: a healthy mix of null, blank/whitespace, recognized codes (in
     * mixed case), and arbitrary unrecognized strings — covering the whole
     * input space the totality guarantee must hold over.
     */
    @Provide
    Arbitrary<String> anyCode() {
        Arbitrary<String> recognized = recognizedCodes();
        Arbitrary<String> arbitrary = Arbitraries.strings().ofMaxLength(40);
        Arbitrary<String> blanks = Arbitraries.of("", " ", "   ", "\t", "\n", "_", "-", "._-");
        Arbitrary<String> nullValue = Arbitraries.just(null);
        return Arbitraries.oneOf(recognized, arbitrary, blanks, nullValue);
    }

    private Arbitrary<String> recognizedCodes() {
        List<String> all = new ArrayList<>();
        all.addAll(AuditLabelTranslator.recognizedActionCodes());
        all.addAll(AuditLabelTranslator.recognizedEntityTypeCodes());
        return Arbitraries.of(all).map(this::randomizeCase);
    }

    @Provide
    Arbitrary<String> recognizedActionCode() {
        return Arbitraries.of(new ArrayList<>(AuditLabelTranslator.recognizedActionCodes()))
                .map(this::randomizeCase);
    }

    @Provide
    Arbitrary<String> recognizedEntityTypeCode() {
        return Arbitraries.of(new ArrayList<>(AuditLabelTranslator.recognizedEntityTypeCodes()))
                .map(this::randomizeCase);
    }

    /** Flip the case of a code to exercise case-insensitive recognition. */
    private String randomizeCase(String code) {
        StringBuilder sb = new StringBuilder(code.length());
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            sb.append((i % 2 == 0) ? Character.toLowerCase(c) : Character.toUpperCase(c));
        }
        return sb.toString();
    }
}
