package ccsuite.webclient;

import org.junit.Test;

import static org.junit.Assert.*;

public class WebHttpTest {
    @Test
    public void theCurlTotalTimeoutLeavesRoomForALongStreamedAnswer() {
        assertTrue("the library default of 60 s cut answers that take minutes while the account was still charged", WebHttp.curlTimeoutSeconds() >= 600);
        System.setProperty("ccsuite.curlTimeoutSeconds", "123");
        try {
            assertEquals(123, WebHttp.curlTimeoutSeconds());
        } finally {
            System.clearProperty("ccsuite.curlTimeoutSeconds");
        }
    }
}
