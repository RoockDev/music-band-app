package com.banda;

import com.banda.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;

class BandaApplicationTests extends IntegrationTestBase {

    @Test
    void contextLoads() {
        // Proves the Spring context wires up (web, security, data-jpa, validation, mail)
        // against a real Postgres instance provided by Testcontainers.
    }

}
