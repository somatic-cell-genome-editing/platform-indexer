package edu.mcw.scge.platform.embedding;

import edu.mcw.scge.platform.index.ClinicalTrialIndexObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds the one block of text that represents a trial in vector space.
 *
 * WHY PROSE AND NOT "Label: value" LINES
 *
 * The first version of this class emitted a labeled block ("Condition: Sickle Cell
 * Disease\nTherapeutic modality: Gene Therapy\n..."). Measured against three trials and
 * six real-world phrasings, that format separated them badly: bge-m3 got 5/6 right with a
 * mean top-to-second margin of 0.055, and ranked a hemophilia trial above a sickle cell
 * trial for "haemoglobinopathy treatment".
 *
 * The cause is that every trial in this corpus carries the same scaffolding and many of
 * the same values - every record repeats "Therapeutic modality: Gene Therapy", every
 * record repeats the label words themselves. Shared tokens pull all the vectors toward
 * each other, so the disease and the modality, which are what actually distinguish one
 * trial from another, end up as a small part of the signal.
 *
 * Writing the same facts as a sentence, with the condition stated first, scored 6/6 with
 * a mean margin of 0.068 on bge-m3 and 0.099 on qwen3-embedding:8b. The content is
 * identical - only the framing changed.
 *
 * WHAT GOES IN
 *
 * - The scientific description: what disease, what therapy, what vector, what gene, what
 *   route. This is the part users phrase differently from the record ("SCD" against
 *   "sickle cell disease", "AAV5" against "adeno-associated virus"), and it is the whole
 *   reason for putting vectors next to the keyword query.
 *
 * - Phase, recruitment status, sponsor and funder type stay out. They are exact,
 *   closed-vocabulary values the facets already answer precisely; blending them in would
 *   pull unrelated trials together for merely sharing a phase, and a vector is the wrong
 *   instrument for a question with an exact answer.
 *
 * - Eligibility criteria stay out. They are long, highly templated and nearly identical
 *   across trials, so they would dominate the token budget while adding almost nothing
 *   that distinguishes one trial from another - the same crowding problem as the labels,
 *   only larger.
 */
public class TrialEmbeddingText {

    /**
     * Roughly 6000 characters is near 1500 tokens, comfortably inside bge-m3's 8192-token
     * window with room for records longer than any seen so far. The cap exists so that one
     * unusually long description cannot push the identifying facts, which are written
     * first, out of the model's window.
     */
    private static final int MAX_CHARS = Integer.parseInt(System.getProperty("scge.embedding.maxChars", "6000"));

    public static String build(ClinicalTrialIndexObject trial) {
        StringBuilder sb = new StringBuilder();

        sb.append(openingSentence(trial));
        appendSentence(sb, titleSentence(trial));
        appendSentence(sb, modalitySentence(trial));
        appendSentence(sb, interventionSentence(trial));

        appendSentence(sb, clean(trial.getDescription()));
        appendSentence(sb, clean(trial.getInterventionDescription()));
        appendSentence(sb, clean(trial.getCompoundDescription()));

        String text = sb.toString().trim();
        if (text.length() > MAX_CHARS) {
            text = text.substring(0, MAX_CHARS);
        }
        return text;
    }

    /** "A gene therapy trial in sickle cell disease, also described as hemoglobinopathies." */
    private static String openingSentence(ClinicalTrialIndexObject trial) {
        String modality = clean(trial.getTherapyType());
        String condition = join(trial.getIndications());
        String related = clean(trial.getBrowseConditionTerms());

        StringBuilder s = new StringBuilder();
        s.append("A ");
        s.append(modality != null ? modality.toLowerCase() : "clinical");
        s.append(" trial");
        if (condition != null) {
            s.append(" in ").append(condition);
        }
        if (related != null && !related.equalsIgnoreCase(condition)) {
            s.append(", also described as ").append(related);
        }
        s.append(".");
        return s.toString();
    }

    private static String titleSentence(ClinicalTrialIndexObject trial) {
        String brief = clean(trial.getBriefTitle());
        String official = clean(trial.getOfficialTitle());
        if (brief == null) {
            return official;
        }
        if (official == null || official.equalsIgnoreCase(brief)) {
            return brief;
        }
        return brief + ". " + official;
    }

    /**
     * "It uses gene addition targeting the HBB gene in hematopoietic stem cells, delivered
     * by lentiviral vector (lentivirus), given intravenously."
     */
    private static String modalitySentence(ClinicalTrialIndexObject trial) {
        List<String> parts = new ArrayList<>();

        String mechanism = clean(trial.getMechanismOfAction());
        String gene = clean(trial.getTargetGeneOrVariant());
        String tissue = clean(trial.getTargetTissueOrCell());
        String editor = clean(trial.getEditorType());
        String delivery = clean(trial.getDeliverySystem());
        String vector = clean(trial.getVectorType());
        String viralSubType = clean(trial.getViralVectorSubType());
        String nonViralType = clean(trial.getNonViralVectorType());
        String regulatory = clean(trial.getRegulatoryElement());
        String route = clean(trial.getRouteOfAdministration());
        String therapyRoute = clean(trial.getTherapyRoute());
        String productType = clean(trial.getDrugProductType());

        if (mechanism != null) {
            parts.add("It uses " + mechanism.toLowerCase());
        }
        if (editor != null) {
            parts.add("with " + editor + " editing");
        }
        if (gene != null) {
            parts.add("targeting the " + gene + " gene");
        }
        if (regulatory != null) {
            parts.add("under a " + regulatory + " regulatory element");
        }
        if (tissue != null) {
            parts.add("in " + tissue.toLowerCase());
        }
        if (delivery != null) {
            parts.add("delivered by " + delivery.toLowerCase());
        }
        if (vector != null && !overlaps(delivery, vector)) {
            parts.add("using a " + vector + " vector");
        }
        if (viralSubType != null && !overlaps(vector, viralSubType) && !overlaps(delivery, viralSubType)) {
            parts.add("of subtype " + viralSubType);
        }
        if (nonViralType != null && !overlaps(delivery, nonViralType)) {
            parts.add("with non-viral delivery by " + nonViralType.toLowerCase());
        }
        if (productType != null) {
            parts.add("as a " + productType.toLowerCase());
        }
        if (route != null) {
            parts.add("administered via the " + route.toLowerCase() + " route");
        }
        if (therapyRoute != null && !therapyRoute.equalsIgnoreCase(route)) {
            parts.add("given " + therapyRoute.toLowerCase());
        }

        return parts.isEmpty() ? null : String.join(", ", parts) + ".";
    }

    /** "The intervention is LentiGlobin BB305, also known as SCD, bluebird bio BB305." */
    private static String interventionSentence(ClinicalTrialIndexObject trial) {
        String intervention = clean(trial.getInterventionName());
        String compound = clean(trial.getCompoundName());
        String aliases = join(trial.getAliases());

        Set<String> names = new LinkedHashSet<>();
        if (intervention != null) {
            names.add(intervention);
        }
        if (compound != null) {
            names.add(compound);
        }
        if (names.isEmpty() && aliases == null) {
            return null;
        }

        StringBuilder s = new StringBuilder();
        if (!names.isEmpty()) {
            s.append("The intervention is ").append(String.join(", ", names));
        }
        if (aliases != null) {
            s.append(s.length() == 0 ? "Also known as " : ", also known as ").append(aliases);
        }
        s.append(".");
        return s.toString();
    }

    private static void appendSentence(StringBuilder sb, String sentence) {
        if (sentence == null) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(' ');
        }
        sb.append(sentence);
        if (!sentence.endsWith(".")) {
            sb.append('.');
        }
    }

    /**
     * Whether two field values name the same thing closely enough that writing both would
     * just repeat it - "Lentiviral vector" against "Lentivirus", "AAV5 vector" against
     * "Adeno-associated virus" after the first token. A plain contains() check misses
     * these because the words differ past a shared stem, so compare the stems.
     */
    private static boolean overlaps(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        String x = a.toLowerCase();
        String y = b.toLowerCase();
        if (x.contains(y) || y.contains(x)) {
            return true;
        }
        String stem = y.length() >= 7 ? y.substring(0, 7) : y;
        return stem.length() >= 4 && x.contains(stem);
    }

    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || "null".equalsIgnoreCase(trimmed)) {
            return null;
        }
        return trimmed;
    }

    private static String join(Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        Set<String> cleaned = new LinkedHashSet<>();
        for (String v : values) {
            String c = clean(v);
            if (c != null) {
                cleaned.add(c);
            }
        }
        return cleaned.isEmpty() ? null : String.join(", ", cleaned);
    }
}
