package brut.androlib.res.xml;

import brut.androlib.BaseTest;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ResXmlEncodersTest extends BaseTest {
    @Test
    public void escapeXmlCharsEscapeExpected() {
        assertEquals("foo", ResXmlEncoders.escapeXmlChars("foo"));
        assertEquals("foo&amp;bar", ResXmlEncoders.escapeXmlChars("foo&bar"));
        assertEquals("&lt;foo>", ResXmlEncoders.escapeXmlChars("<foo>"));
        assertEquals("&lt;![CDATA[foo]]&gt;", ResXmlEncoders.escapeXmlChars("<![CDATA[foo]]>"));
    }
}
