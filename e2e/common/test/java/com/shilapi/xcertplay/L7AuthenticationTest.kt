package com.shilapi.xcertplay

import org.junit.Assert.assertThrows
import org.junit.Test

class L7AuthenticationTest {
    @Test fun acceptsLanHttpAndHttpsServicePathsAndOptionalTokens() {
        L7Authentication.validateRemote("http://192.168.1.2:8080", "")
        L7Authentication.validateRemote(" https://mfi.example.test/api/ ", "test-token")
    }

    @Test fun rejectsAddressesThatCannotBeUsedAsAnMfiServiceBaseUrl() {
        for (address in listOf("", "192.168.1.2", "file:///tmp/mfi", "http://", "https://user:password@mfi.example.test", "https://mfi.example.test?token=secret", "http://mfi.example.test:99999", "https://mfi.example.test/#fragment", "https://mfi.example.test\u0000")) {
            assertThrows(Exception::class.java) { L7Authentication.validateRemote(address, "") }
        }
        assertThrows(Exception::class.java) { L7Authentication.validateRemote("https://mfi.example.test", "test\u0000token") }
    }
}
