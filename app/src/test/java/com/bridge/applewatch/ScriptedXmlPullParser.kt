package com.bridge.applewatch

import org.xmlpull.v1.XmlPullParser

/**
 * A scripted [XmlPullParser] for unit tests.
 *
 * The importer's scan logic is worth testing directly, but the JVM unit-test runtime
 * replaces both ndroid.util.Xml.newPullParser() and
 * org.xmlpull.v1.XmlPullParserFactory.newInstance() with non-functional stubs (they
 * return null / defaults). Tests that went through them observed zero records and would
 * have passed for the wrong reason.
 *
 * This fake yields a fixed event sequence instead, so the scan rules - which tags are
 * read, when a record is skipped, and what happens when a document ends early - are
 * asserted deterministically. Real XML parsing happens at runtime and is not what these
 * tests are for.
 *
 * Only the four methods the importer calls are implemented; the rest fail loudly if a
 * test ever starts depending on them.
 */
internal class ScriptedXmlPullParser(
    private val events: List<Event>,
    /**
     * When set, the parser throws once this many `next()` calls have happened, which is
     * how a half-copied `export.xml` behaves: the scan must keep what it already read.
     */
    private val truncateAfterNextCalls: Int? = null
) : XmlPullParser {

    /** One parser position: an event type, a tag name and its attributes. */
    data class Event(
        val type: Int,
        val name: String? = null,
        val attributes: Map<String, String> = emptyMap()
    )

    private var index = 0
    private var nextCalls = 0

    override fun getEventType(): Int =
        events.getOrElse(index) { Event(XmlPullParser.END_DOCUMENT) }.type

    override fun getName(): String? = events.getOrNull(index)?.name

    override fun getAttributeValue(namespace: String?, name: String): String? =
        events.getOrNull(index)?.attributes?.get(name)

    override fun getAttributeValue(index: Int): String? =
        events.getOrNull(this.index)?.attributes?.values?.toList()?.getOrNull(index)

    /** Advances one event. Throws past the end, which is how a truncated file behaves. */
    override fun next(): Int {
        if (truncateAfterNextCalls != null && nextCalls >= truncateAfterNextCalls) {
            throw org.xmlpull.v1.XmlPullParserException("Document truncated")
        }
        nextCalls += 1
        index += 1
        if (index >= events.size) {
            throw org.xmlpull.v1.XmlPullParserException("Unexpected end of document")
        }
        return getEventType()
    }

    override fun getTextCharacters(p0: IntArray?): CharArray =
        fail("getTextCharacters")

    private fun fail(what: String): Nothing =
        throw UnsupportedOperationException("ScriptedXmlPullParser. is not implemented")

    @Throws(Exception::class)
    override fun defineEntityReplacementText(p0: String, p1: String) {}
    @Throws(Exception::class)
    override fun getAttributeCount(): Int = fail("getAttributeCount")
    @Throws(Exception::class)
    override fun getAttributeName(p0: Int): String = fail("getAttributeName")
    @Throws(Exception::class)
    override fun getAttributeNamespace(p0: Int): String = fail("getAttributeNamespace")
    @Throws(Exception::class)
    override fun getAttributePrefix(p0: Int): String = fail("getAttributePrefix")
    @Throws(Exception::class)
    override fun getAttributeType(p0: Int): String = fail("getAttributeType")
    @Throws(Exception::class)
    override fun getColumnNumber(): Int = fail("getColumnNumber")
    @Throws(Exception::class)
    override fun getDepth(): Int = fail("getDepth")
    @Throws(Exception::class)
    override fun getFeature(p0: String): Boolean = fail("getFeature")
    @Throws(Exception::class)
    override fun getInputEncoding(): String = fail("getInputEncoding")
    @Throws(Exception::class)
    override fun getLineNumber(): Int = fail("getLineNumber")
    @Throws(Exception::class)
    override fun getNamespace(): String = fail("getNamespace")
    @Throws(Exception::class)
    override fun getNamespace(p0: String): String = fail("getNamespace")
    @Throws(Exception::class)
    override fun getNamespaceCount(p0: Int): Int = fail("getNamespaceCount")
    @Throws(Exception::class)
    override fun getNamespacePrefix(p0: Int): String = fail("getNamespacePrefix")
    @Throws(Exception::class)
    override fun getNamespaceUri(p0: Int): String = fail("getNamespaceUri")
    @Throws(Exception::class)
    override fun getPositionDescription(): String = fail("getPositionDescription")
    @Throws(Exception::class)
    override fun getPrefix(): String = fail("getPrefix")
    @Throws(Exception::class)
    override fun getProperty(p0: String): Any? = fail("getProperty")
    @Throws(Exception::class)
    override fun getText(): String = fail("getText")
    @Throws(Exception::class)
    override fun isAttributeDefault(p0: Int): Boolean = fail("isAttributeDefault")
    @Throws(Exception::class)
    override fun isEmptyElementTag(): Boolean = fail("isEmptyElementTag")
    @Throws(Exception::class)
    override fun isWhitespace(): Boolean = fail("isWhitespace")
    @Throws(Exception::class)
    override fun nextTag(): Int = fail("nextTag")
    @Throws(Exception::class)
    override fun nextText(): String = fail("nextText")
    @Throws(Exception::class)
    override fun nextToken(): Int = fail("nextToken")
    @Throws(Exception::class)
    override fun require(p0: Int, p1: String, p2: String) {}
    @Throws(Exception::class)
    override fun setFeature(p0: String, p1: Boolean) {}
    @Throws(Exception::class)
    override fun setInput(p0: java.io.InputStream, p1: String) {}
    @Throws(Exception::class)
    override fun setInput(p0: java.io.Reader) {}
    @Throws(Exception::class)
    override fun setProperty(p0: String, p1: Any?) {}
    companion object {
        /** Convenience for the common <Record .../> shape. */
        fun record(attributes: Map<String, String>): Event =
            Event(XmlPullParser.START_TAG, "Record", attributes)

        val endDocument = Event(XmlPullParser.END_DOCUMENT)

        fun document(vararg records: Event): List<Event> =
            listOf(Event(XmlPullParser.START_TAG, "HealthData")) + records.toList() + endDocument
    }
}
