/**
 * Copyright (c) 2024, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.gridsuite.config.notification.server;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.GenericMessage;
import org.springframework.web.reactive.socket.HandshakeInfo;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.gridsuite.config.notification.server.GlobalNotificationWebSocketHandler.HEADER_DURATION;
import static org.gridsuite.config.notification.server.GlobalNotificationWebSocketHandler.HEADER_MESSAGE_TYPE;
import static org.gridsuite.config.notification.server.NotificationWebSocketHandler.HEADER_APP_NAME;
import static org.gridsuite.config.notification.server.NotificationWebSocketHandler.HEADER_USER_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * @author Abdelsalem Hedhili <abdelsalem.hedhili at rte-france.com>
 */
class GlobalNotificationWebSocketHandlerTest {

    private ObjectMapper objectMapper;
    private WebSocketSession ws;
    private HandshakeInfo handshakeinfo;

    @BeforeEach
    void setup() {
        objectMapper = new ObjectMapper();
        var dataBufferFactory = new DefaultDataBufferFactory();

        ws = Mockito.mock(WebSocketSession.class);
        handshakeinfo = Mockito.mock(HandshakeInfo.class);

        when(ws.getHandshakeInfo()).thenReturn(handshakeinfo);
        when(ws.receive()).thenReturn(Flux.empty());
        when(ws.send(any())).thenReturn(Mono.empty());
        when(ws.textMessage(any())).thenAnswer(invocation -> {
            Object[] args = invocation.getArguments();
            String str = (String) args[0];
            return new WebSocketMessage(WebSocketMessage.Type.TEXT, dataBufferFactory.wrap(str.getBytes()));
        });
        when(ws.pingMessage(any())).thenAnswer(invocation -> {
            Object[] args = invocation.getArguments();
            Function<DataBufferFactory, DataBuffer> f = (Function<DataBufferFactory, DataBuffer>) args[0];
            return new WebSocketMessage(WebSocketMessage.Type.PING, f.apply(dataBufferFactory));
        });
        when(ws.getId()).thenReturn("testsession");

    }

    @Test
    void test() {
        var notificationWebSocketHandler = new GlobalNotificationWebSocketHandler(objectMapper, Integer.MAX_VALUE);

        UriComponentsBuilder uriComponentBuilder = UriComponentsBuilder.fromUriString("http://localhost:1234/global");

        when(handshakeinfo.getUri()).thenReturn(uriComponentBuilder.build().toUri());

        HttpHeaders httpHeaders = new HttpHeaders();

        when(handshakeinfo.getHeaders()).thenReturn(httpHeaders);

        var atomicRef = new AtomicReference<FluxSink<Message<String>>>();
        var flux = Flux.create(atomicRef::set);
        notificationWebSocketHandler.consumeMessage().accept(flux);
        var sink = atomicRef.get();

        notificationWebSocketHandler.handle(ws);

        List<Map<String, Object>> refMessages = Arrays.asList(

                Map.of(HEADER_MESSAGE_TYPE, "msgType"),
                Map.of(HEADER_MESSAGE_TYPE, "msgType", HEADER_DURATION, "100"),
                Map.of(HEADER_MESSAGE_TYPE, "foo bar/bar", HEADER_DURATION, "200"),     // test encoding message type
                Map.of(HEADER_DURATION, "200"),     //test without message type
                Map.of("random", "thing")
        );

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Flux<WebSocketMessage>> argument = ArgumentCaptor.forClass(Flux.class);
        verify(ws).send(argument.capture());
        List<String> messages = new ArrayList<>();
        argument.getValue().map(WebSocketMessage::getPayloadAsText).subscribe(messages::add);
        refMessages.stream().map(headers -> new GenericMessage<>("", headers)).forEach(sink::next);
        sink.complete();

        List<Map<String, Object>> expected = refMessages.stream().map(GlobalNotificationWebSocketHandlerTest::toResultHeader)
                .collect(Collectors.toList());

        List<Map<String, Object>> actual = messages.stream()
                .map(t -> {
                    try {
                        return toResultHeader(((Map<String, Map<String, Object>>) objectMapper.readValue(t, Map.class)).get("headers"));
                    } catch (JsonProcessingException e) {
                        throw new RuntimeException(e);
                    }
                }).collect(Collectors.toList());

        assertEquals(expected, actual);
    }

    private static Map<String, Object> toResultHeader(Map<String, Object> messageHeader) {
        var resHeader = new HashMap<String, Object>();
        if (messageHeader.get(HEADER_MESSAGE_TYPE) != null) {
            resHeader.put(HEADER_MESSAGE_TYPE, messageHeader.get(HEADER_MESSAGE_TYPE));
        }
        if (messageHeader.get(HEADER_DURATION) != null) {
            resHeader.put(HEADER_DURATION, messageHeader.get(HEADER_DURATION));
        }
        return resHeader;
    }

    @Test
    void testHeartbeat() {
        var notificationWebSocketHandler = new GlobalNotificationWebSocketHandler(null, 1);

        UriComponentsBuilder uriComponentBuilder = UriComponentsBuilder.fromUriString("http://localhost:1234/global");

        when(handshakeinfo.getUri()).thenReturn(uriComponentBuilder.build().toUri());
        ArrayList<String> values = new ArrayList<>();
        values.add("userId");
        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.put("userId", values);
        when(handshakeinfo.getHeaders()).thenReturn(httpHeaders);

        var flux = Flux.<Message<String>>empty();
        notificationWebSocketHandler.consumeMessage().accept(flux);
        notificationWebSocketHandler.handle(ws);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Flux<WebSocketMessage>> argument = ArgumentCaptor.forClass(Flux.class);
        verify(ws).send(argument.capture());
        assertEquals("testsession-0", argument.getValue().blockFirst(Duration.ofSeconds(10)).getPayloadAsText());
    }

    @Test
    void testDiscard() {
        var notificationWebSocketHandler = new GlobalNotificationWebSocketHandler(objectMapper, Integer.MAX_VALUE);

        UriComponentsBuilder uriComponentBuilder = UriComponentsBuilder.fromUriString("http://localhost:1234/global");

        when(handshakeinfo.getUri()).thenReturn(uriComponentBuilder.build().toUri());
        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.add(HEADER_USER_ID, "userId1");
        when(handshakeinfo.getHeaders()).thenReturn(httpHeaders);

        var atomicRef = new AtomicReference<FluxSink<Message<String>>>();
        var flux = Flux.create(atomicRef::set);
        notificationWebSocketHandler.consumeMessage().accept(flux);
        var sink = atomicRef.get();
        Map<String, Object> headers = Map.of(HEADER_USER_ID, "userId", HEADER_APP_NAME, "appName");

        sink.next(new GenericMessage<>("", headers)); // should be discarded, no client connected

        notificationWebSocketHandler.handle(ws);

        ArgumentCaptor<Flux<WebSocketMessage>> argument1 = ArgumentCaptor.forClass(Flux.class);
        verify(ws).send(argument1.capture());
        List<String> messages1 = new ArrayList<>();
        Flux<WebSocketMessage> out1 = argument1.getValue();
        Disposable d1 = out1.map(WebSocketMessage::getPayloadAsText).subscribe(messages1::add);
        d1.dispose();

        sink.next(new GenericMessage<>("", headers)); // should be discarded, first client disconnected

        notificationWebSocketHandler.handle(ws);

        ArgumentCaptor<Flux<WebSocketMessage>> argument2 = ArgumentCaptor.forClass(Flux.class);
        verify(ws, times(2)).send(argument2.capture());
        List<String> messages2 = new ArrayList<>();
        Flux<WebSocketMessage> out2 = argument2.getValue();
        Disposable d2 = out2.map(WebSocketMessage::getPayloadAsText).subscribe(messages2::add);
        d2.dispose();

        sink.complete();
        assertEquals(0, messages1.size());
        assertEquals(0, messages2.size());
    }
}
