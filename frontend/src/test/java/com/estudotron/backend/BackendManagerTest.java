package com.estudotron.backend;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class BackendManagerTest {

    @Test
    void freePortIsUsable() throws IOException {
        int port = BackendManager.freePort();
        assertTrue(port > 0 && port < 65536);
    }

    @Test
    void tokensAreLongAndUnique() {
        String a = BackendManager.newToken();
        String b = BackendManager.newToken();
        assertEquals(64, a.length());
        assertNotEquals(a, b);
    }

    @Test
    void viewerUrlCarriesDocAndToken() {
        ApiClient client = new ApiClient(8123, "tok");
        assertEquals("http://127.0.0.1:8123/viewer/index.html?doc=abc&token=tok", client.viewerUrl("abc"));
    }
}
