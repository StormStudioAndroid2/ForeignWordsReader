package com.example.myapplication.shared.processing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConlluParserTest {
    private val parser = ConlluParser()

    @Test
    fun parsesFormLemmaAndUpos() {
        val tokens = parser.parse(
            sectionId = "section-1",
            conllu = """
                # sent_id = 1
                1	The	the	DET	_	_	2	det	_	_
                2	children	child	NOUN	_	_	3	nsubj	_	_
                3	were	be	AUX	_	_	4	aux	_	_
                4	reading	read	VERB	_	_	0	root	_	_
                5	books	book	NOUN	_	_	4	obj	_	_
                6	.	.	PUNCT	_	_	4	punct	_	_
            """.trimIndent(),
        )

        assertEquals("The", tokens.first().surface)
        assertEquals("child", tokens[1].lemma)
        assertEquals("AUX", tokens[2].upos)
        assertEquals(TokenType.Punctuation, tokens.last().tokenType)
    }

    @Test
    fun ignoresMultiwordTokenRangesAndEmptyNodes() {
        val tokens = parser.parse(
            sectionId = "section-1",
            conllu = """
                1-2	can't	_	_	_	_	_	_	_	_
                1	ca	can	AUX	_	_	0	root	_	_
                2	n't	not	PART	_	_	1	advmod	_	_
                2.1	ghost	ghost	NOUN	_	_	1	dep	_	_
            """.trimIndent(),
        )

        assertEquals(listOf("ca", "n't"), tokens.map { it.surface })
        assertFalse(tokens.any { it.surface == "can't" })
        assertFalse(tokens.any { it.surface == "ghost" })
    }

    @Test
    fun englishSmokeFixtureContainsExpectedLemmas() {
        val tokens = parser.parse(
            sectionId = "section-1",
            conllu = """
                1	The	the	DET	_	_	2	det	_	_
                2	children	child	NOUN	_	_	3	nsubj	_	_
                3	were	be	AUX	_	_	4	aux	_	_
                4	reading	read	VERB	_	_	0	root	_	_
                5	books	book	NOUN	_	_	4	obj	_	_
                6	.	.	PUNCT	_	_	4	punct	_	_
            """.trimIndent(),
        )
        val lemmas = tokens.map { it.lemma }.toSet()

        assertTrue("child" in lemmas)
        assertTrue("be" in lemmas)
        assertTrue("read" in lemmas)
        assertTrue("book" in lemmas)
    }
}
