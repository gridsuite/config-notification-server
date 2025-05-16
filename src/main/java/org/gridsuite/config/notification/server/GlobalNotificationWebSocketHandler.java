/**
 * Copyright (c) 2024, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.gridsuite.config.notification.server;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.ConnectableFlux;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * A WebSocketHandler that sends messages from a broker to websockets opened by clients, interleaving with pings to keep connections open.
 *
 * Spring Cloud Stream gets the consumeMessage bean and calls it with the
 * flux from the broker. We call publish and connect to subscribe immediately to the flux
 * and multicast the messages to all connected websockets and to discard the messages when
 * no websockets are connected.
 *
 * @author Abdelsalem Hedhili <abdelsalem.hedhili at rte-france.com>
 */
@Component
public class GlobalNotificationWebSocketHandler implements WebSocketHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalNotificationWebSocketHandler.class);
    private static final String CATEGORY_BROKER_INPUT = GlobalNotificationWebSocketHandler.class.getName() + ".messages.input-broker";
    private static final String CATEGORY_WS_OUTPUT = GlobalNotificationWebSocketHandler.class.getName() + ".messages.output-websocket";
    public static final String HEADER_MESSAGE_TYPE = "messageType";
    public static final String HEADER_ANNOUNCEMENT_ID = "announcementId";
    public static final String HEADER_DURATION = "duration";
    public static final String HEADER_SEVERITY = "severity";

    private ObjectMapper jacksonObjectMapper;

    private int heartbeatInterval;

    public GlobalNotificationWebSocketHandler(ObjectMapper jacksonObjectMapper, @Value("${notification.websocket.heartbeat.interval:30}") int heartbeatInterval) {
        this.jacksonObjectMapper = jacksonObjectMapper;
        this.heartbeatInterval = heartbeatInterval;
    }

    Flux<Message<String>> flux;

    @Bean
    public Consumer<Flux<Message<String>>> consumeMessage() {
        return f -> {
            ConnectableFlux<Message<String>> c = f.log(CATEGORY_BROKER_INPUT, Level.FINE).publish();
            this.flux = c;
            c.connect();
            // Force connect 1 fake subscriber to consumme messages as they come.
            // Otherwise, reactorcore buffers some messages (not until the connectable flux had
            // at least one subscriber. Is there a better way ?
            c.subscribe();
        };
    }

    /**
     * map from the broker flux to the filtered flux for one websocket client, extracting only relevant fields.
     */
    private Flux<WebSocketMessage> notificationFlux(WebSocketSession webSocketSession) {
        return flux.map(m -> {
            try {
                Map<String, Object> headers = new HashMap<>();

                putHeaderIfExists(m, headers, HEADER_MESSAGE_TYPE);
                putHeaderIfExists(m, headers, HEADER_DURATION);
                putHeaderIfExists(m, headers, HEADER_SEVERITY);
                putHeaderIfExists(m, headers, HEADER_ANNOUNCEMENT_ID);

                return jacksonObjectMapper.writeValueAsString(Map.of("payload", m.getPayload(), "headers", headers));
            } catch (JsonProcessingException e) {
                throw new UncheckedIOException(e);
            }
        }).log(CATEGORY_WS_OUTPUT, Level.FINE).map(webSocketSession::textMessage);
    }

    /**
     * A heartbeat flux sending websockets pings
     */
    private Flux<WebSocketMessage> heartbeatFlux(WebSocketSession webSocketSession) {
        return Flux.interval(Duration.ofSeconds(heartbeatInterval)).map(n -> webSocketSession
                .pingMessage(dbf -> dbf.wrap((webSocketSession.getId() + "-" + n).getBytes(StandardCharsets.UTF_8))));
    }

    @NotNull
    @Override
    public Mono<Void> handle(WebSocketSession webSocketSession) {
        URI uri = webSocketSession.getHandshakeInfo().getUri();
        LOGGER.debug("New websocket connection for {}", uri);
        return webSocketSession
                .send(
                        notificationFlux(webSocketSession)
                                .mergeWith(heartbeatFlux(webSocketSession)))
                .and(webSocketSession.receive());
    }

    private static void putHeaderIfExists(Message<String> message, Map<String, Object> headers, String headerName) {
        if (message.getHeaders().get(headerName) != null) {
            headers.put(headerName, message.getHeaders().get(headerName));
        }
    }
}
