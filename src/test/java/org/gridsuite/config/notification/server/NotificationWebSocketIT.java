/**
 * Copyright (c) 2020, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.gridsuite.config.notification.server;

import java.net.URI;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.client.StandardWebSocketClient;
import org.springframework.web.reactive.socket.client.WebSocketClient;

import static org.junit.Assert.assertNotNull;

/**
 * @author Abdelsalem Hedhili <abdelsalem.hedhili at rte-france.com>
 */
@RunWith(SpringRunner.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(classes = { NotificationApplication.class })
@DirtiesContext
public class NotificationWebSocketIT {

    @LocalServerPort
    private String port;

    @Test
    public void echo() {
        WebSocketClient client = new StandardWebSocketClient();
        assertNotNull(client);
        HttpHeaders headers = new HttpHeaders();
        headers.add("userId", "userId");
        client.execute(getUrl("/notify"), headers, WebSocketSession::close).block();
    }

    @Test
    public void echo2() {
        WebSocketClient client = new StandardWebSocketClient();
        assertNotNull(client);
        HttpHeaders headers = new HttpHeaders();
        headers.add("userId", "userId");
        client.execute(getUrl("/global"), headers, WebSocketSession::close).block();
    }

    protected URI getUrl(String path) {
        return URI.create("ws://localhost:" + this.port + path);
    }
}
