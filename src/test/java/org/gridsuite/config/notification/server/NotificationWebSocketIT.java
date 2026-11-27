/**
 * Copyright (c) 2020, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.gridsuite.config.notification.server;

import com.powsybl.ws.commons.security.AllowAllAuthTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.client.StandardWebSocketClient;
import org.springframework.web.reactive.socket.client.WebSocketClient;

import java.net.URI;

import static com.powsybl.ws.commons.security.AuthorizationSystemConstants.HEADER_USER_ID;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * @author Abdelsalem Hedhili <abdelsalem.hedhili at rte-france.com>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = {NotificationApplication.class})
@Import(AllowAllAuthTestConfiguration.class)
@DirtiesContext
class NotificationWebSocketIT {

    @LocalServerPort
    private String port;

    @Test
    void echo() {
        WebSocketClient client = new StandardWebSocketClient();
        assertNotNull(client);
        HttpHeaders headers = new HttpHeaders();
        headers.add(HEADER_USER_ID, "userId");
        client.execute(getUrl("/notify"), headers, WebSocketSession::close).block();
    }

    @Test
    void echo2() {
        WebSocketClient client = new StandardWebSocketClient();
        assertNotNull(client);
        HttpHeaders headers = new HttpHeaders();
        headers.add(HEADER_USER_ID, "userId");
        client.execute(getUrl("/global"), headers, WebSocketSession::close).block();
    }

    protected URI getUrl(String path) {
        return URI.create("ws://localhost:" + this.port + path);
    }
}
