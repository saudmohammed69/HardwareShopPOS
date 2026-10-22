package com.hardwareshop.pos

import com.hardwareshop.pos.util.ProductSearch
import org.junit.Assert.*
import org.junit.Test

class ProductSearchTest {
    @Test fun hardwareSynonymsWorkWithoutOwnerAliases() {
        for ((query, product) in listOf("bend" to "PVC Elbow", "wrench" to "Spanner",
            "thread seal tape" to "PTFE Tape", "hex key" to "Allen Key",
            "insulation tape" to "Electrical Tape", "pechkas" to "Screwdriver",
            "galvanized iron elbow" to "GI Elbow")) assertNotNull(query, score(query, product))
        assertTrue(score("bend", "Bend")!! < score("bend", "Elbow")!!)
        assertNull(score("spanner", "PVC Elbow"))
    }
    @Test fun equivalentSizesMatchWithoutMixingDifferentSizes() {
        for (query in listOf("half inch elbow", "0.5 inch elbow", "½ inch elbow", "1/2\" elbow")) {
            assertNotNull(query, score(query, "Elbow 1/2 inch"))
            assertNull(query, score(query, "Elbow 3/4 inch"))
        }
        assertNotNull(score("1.5 inch", "Valve 1 1/2 inch"))
        assertNotNull(score("2.5 inch", "Valve 2½ inch"))
        assertNotNull(score("20 millimetres", "Elbow 20mm"))
        assertNull(score("20 millimetres", "Elbow 25mm"))
        assertNull(score("half inch", "Half Round File"))
    }
    @Test fun automaticAliasesKeepMaterialPriority() {
        assertNotNull(score("pvc bend", "PVC Elbow")); assertNull(score("pvc bend", "CPVC Elbow"))
        assertNotNull(score("cpvc bend", "CPVC Elbow")); assertNull(score("cpvc bend", "PVC Elbow"))
    }
    private fun score(q: String, name: String, aliases: String = "") =
        ProductSearch.score(ProductSearch.tokens(q), name, aliases)
    @Test fun missingAndSwappedLettersMatch() {
        for (q in listOf("ebow", "elbo", "elobw", "ellbow", "elbow"))
            assertNotNull(q, score(q, "Elbow"))
    }
    @Test fun exactMaterialsRankFirstInBothDirections() {
        assertNotNull(score("pvc", "PVC Elbow")); assertNull(score("pvc", "CPVC Elbow"))
        assertNotNull(score("cpvc", "CPVC Elbow")); assertNull(score("cpvc", "PVC Elbow"))
    }
    @Test fun multiwordTyposKeepCorrectMaterialFirst() {
        assertNotNull(score("pvc ebow", "PVC Elbow")); assertNull(score("pvc ebow", "CPVC Elbow"))
        assertNotNull(score("cpvc ebow", "CPVC Elbow")); assertNull(score("cpvc ebow", "PVC Elbow"))
        assertNull(score("pvc ebow", "PVC Pipe"))
    }
    @Test fun exactBeatsPrefixSubstringAndTypo() {
        val exact = score("clip", "Clip")!!
        assertTrue(exact < score("clip", "Clips")!!)
        assertTrue(exact < score("clip", "Circlip")!!)
        assertTrue(exact < score("clip", "Clap")!!)
    }
    @Test fun aliasesSupportLocalNamesAndTheirTypos() {
        assertNotNull(score("bend", "PVC Elbow", "bend, corner"))
        assertNotNull(score("bned", "PVC Elbow", "bend, corner"))
    }
    @Test fun caseSpacingPunctuationAndOrderAreIgnored() {
        assertEquals(score("pvc elbow", "PVC Elbow"), score(" ELBOW,  PvC! ", "PVC Elbow"))
        assertNull(score("%%%", "PVC Elbow"))
    }
    @Test fun numbersAreNeverSpellCorrected() {
        assertNotNull(score("elbow 20", "Elbow 20 mm"))
        assertNull(score("elbow 20", "Elbow 25 mm"))
        assertNull(score("20", "200"))
    }
    @Test fun unrelatedWordsAndShortTyposDoNotMatch() {
        assertNull(score("fan", "Elbow"))
        assertNull(score("xy", "Elbow"))
        assertNotNull(score("el", "Elbow"))
    }
    @Test fun commonSeparatedNamesMatchJoinedNamesBothWays() {
        assertNotNull(score("alen key", "Allen Key"))
        assertNotNull(score("scrw driver", "Screwdriver"))
        for ((a, b) in listOf("screw driver" to "Screwdriver", "hack saw" to "Hacksaw",
            "rain coat" to "Raincoat", "allen key" to "Allenkey", "c clip" to "C-Clip",
            "ballvalve" to "Ball Valve", "washbasin" to "Wash Basin")) {
            assertNotNull(a, score(a, b))
            assertNotNull(b, score(b, a))
        }
    }
    @Test fun fractionsDecimalsAndCodesAreNotInterchanged() {
        assertNotNull(score("1/2", "Valve 1/2 inch"))
        assertNull(score("1/2", "Valve 1/4 inch"))
        assertNull(score("1.5", "Cable 5.1 mm"))
        assertNull(score("1 1/2", "Valve 1/2 inch"))
        assertNull(score("b22", "Bulb B23"))
        assertNotNull(score("20mm", "Elbow 20 mm"))
    }
    @Test fun identifierPrioritySurvivesOtherWordsBeingMisspelled() {
        assertNotNull(score("pvc ebow", "PVC Elbow")); assertNull(score("pvc ebow", "CPVC Ebow"))
        assertNotNull(score("cpvc ebow", "CPVC Elbow")); assertNull(score("cpvc ebow", "PVC Ebow"))
    }
    @Test fun mistakesAcrossNonPipeProductGroups() {
        for ((q, name) in listOf("hamer" to "Hammer", "swich" to "Switch",
            "scrwdriver" to "Screw Driver", "umbella" to "Umbrella",
            "buket" to "Bucket", "gront" to "Grout", "bulbb" to "Bulb",
            "spnner" to "Spanner", "wirre" to "Wire", "ceilling fan" to "Ceiling Fan"))
            assertNotNull(q, score(q, name))
    }
    @Test fun matcherCacheDoesNotChangeRanking() {
        val query = ProductSearch.tokens("pvc ebow")
        val matcher = ProductSearch.Matcher(query)
        for (name in listOf("PVC Elbow", "CPVC Elbow", "PVC Pipe", "PVC Elbow"))
            assertEquals(ProductSearch.score(query, name), matcher.score(name))
    }
}
